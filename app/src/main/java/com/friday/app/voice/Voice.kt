package com.friday.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.friday.app.ai.Lang
import com.friday.app.data.SecretStore
import com.friday.app.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale

/** Text as it should be heard: no symbols, "1 h" → "1 hour", "·" lists read as pauses. */
fun spoken(text: String): String = text
    .replace(Regex("[•★“”\"*_#]"), "")
    .replace(Regex("(\\d+) h(?![\\p{L}])")) { if (it.groupValues[1] == "1") "1 hour" else "${it.groupValues[1]} hours" }
    .replace(Regex("(\\d+) min(?![\\p{L}])")) { if (it.groupValues[1] == "1") "1 minute" else "${it.groupValues[1]} minutes" }
    .replace(Regex("\\s*·\\s*"), ", ")
    .replace(Regex("\\s*\\n+\\s*"), ". ")
    .replace(Regex("([.?!:])\\. "), "$1 ")
    .trim()

/**
 * FRIDAY's voice: ElevenLabs when the user added a key (natural, Malay-capable), otherwise the phone's
 * own text-to-speech. Only the text FRIDAY is about to say is sent to ElevenLabs.
 */
class Speaker(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val http: OkHttpClient,
) {
    val speaking = MutableStateFlow(false)
    private var tts: TextToSpeech? = null
    private var ttsReady = CompletableDeferred<Boolean>()
    private var player: MediaPlayer? = null
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    /** Says [text] and returns once FRIDAY has finished talking (or could not talk at all). */
    suspend fun speak(text: String, lang: Lang) {
        stop()
        val clean = spoken(text)
        if (clean.isBlank()) return
        speaking.value = true
        audio.requestAudioFocus(focus)
        val key = secrets.get(SecretStore.ELEVEN_KEY)
        val played = key != null && runCatching { eleven(clean, lang, key) }.isSuccess
        if (!played) phone(clean, lang)
        withTimeoutOrNull(120_000) { speaking.first { !it } }
        audio.abandonAudioFocusRequest(focus)
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        tts?.stop()
        speaking.value = false
    }

    private suspend fun phone(text: String, lang: Lang) {
        val engine = tts ?: TextToSpeech(ctx.applicationContext) { ttsReady.complete(it == TextToSpeech.SUCCESS) }.also { t ->
            tts = t
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { speaking.value = false }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { speaking.value = false }
                override fun onError(id: String?, code: Int) { speaking.value = false }
            })
        }
        if (withTimeoutOrNull(5_000) { ttsReady.await() } != true) { speaking.value = false; return }
        val locale = if (lang == Lang.MS) Locale.forLanguageTag("ms-MY") else Locale.getDefault()
        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday") != TextToSpeech.SUCCESS) speaking.value = false
    }

    private suspend fun eleven(text: String, lang: Lang, key: String) {
        val voice = settings.current().elevenVoiceId.ifBlank { voices(key).firstOrNull()?.second?.also { settings.setElevenVoice(it) } }
            ?: throw IOException("no voice")
        val body = buildJsonObject {
            put("text", text.take(1200))
            put("model_id", ELEVEN_MODEL)
            put("language_code", if (lang == Lang.MS) "ms" else "en")
        }
        val file = File(ctx.cacheDir, "friday_voice.mp3")
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$ELEVEN/v1/text-to-speech/$voice?output_format=mp3_44100_128")
                .header("xi-api-key", key).header("Accept", "audio/mpeg")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("ElevenLabs HTTP ${r.code}")
                file.outputStream().use { r.body.byteStream().copyTo(it) }
            }
        }
        withContext(Dispatchers.Main) {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file.path)
                setOnCompletionListener { speaking.value = false }
                setOnErrorListener { _, _, _ -> speaking.value = false; true }
                prepare()
                start()
            }
        }
    }

    /** The voices on the user's ElevenLabs account as (name, id). */
    suspend fun voices(key: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$ELEVEN/v1/voices").header("xi-api-key", key).get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("ElevenLabs HTTP ${r.code}")
            Json.parseToJsonElement(r.body.string()).jsonObject["voices"]?.jsonArray.orEmpty().mapNotNull { v ->
                val o = v.jsonObject
                val id = o["voice_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                (o["name"]?.jsonPrimitive?.contentOrNull ?: id) to id
            }
        }
    }

    companion object {
        private const val ELEVEN = "https://api.elevenlabs.io"
        /** Fast, low-latency and supports Malay. */
        const val ELEVEN_MODEL = "eleven_flash_v2_5"
    }
}

/** In-app speech recognition for back-and-forth conversation. Call from the main thread. */
class Listener(private val ctx: Context) {
    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Level(val rms: Float) : Event
        data object Nothing : Event
        data class Failed(val code: Int) : Event
    }

    private var recognizer: SpeechRecognizer? = null

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(languageTag: String, onEvent: (Event) -> Unit) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onEvent(Event.Level(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = onEvent(
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Event.Nothing else Event.Failed(error),
            )
            override fun onResults(results: Bundle?) = onEvent(
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { Event.Final(it) } ?: Event.Nothing,
            )
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onEvent(Event.Partial(it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName),
        )
    }

    fun stop() {
        recognizer?.runCatching { cancel(); destroy() }
        recognizer = null
    }
}
