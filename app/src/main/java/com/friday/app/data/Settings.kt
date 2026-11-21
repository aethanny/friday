package com.friday.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val name: String = "",
    val onboarded: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val defaultSnoozeMin: Int = 15,
    val quietEnabled: Boolean = true,
    val quietStartMin: Int = 23 * 60,
    val quietEndMin: Int = 7 * 60,
    val followUp: Boolean = true,
    val prepLeadMin: Int = 15,
    val dayReminderMin: Int = 9 * 60,
    val briefingEnabled: Boolean = true,
    val briefingMin: Int = 8 * 60,
    val readAloud: Boolean = false,
    val voiceConversation: Boolean = true,
    val elevenVoiceId: String = DEFAULT_ELEVEN_VOICE,
    val aiBaseUrl: String = DEFAULT_AI_URL,
    val aiModel: String = DEFAULT_AI_MODEL,
    val aiEnabled: Boolean = true,
    val placeAwareness: Boolean = false,
    /** PlaceKind names the user turned off by undoing twice. */
    val placeOffKinds: Set<String> = emptySet(),
    /** Undo counts per kind, encoded "KIND:n". */
    val placeUndos: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_AI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
        /** Google's alias for its newest Flash model, so FRIDAY never points at a retired one. */
        const val DEFAULT_AI_MODEL = "gemini-flash-latest"
        /** Empty: the first voice on the user's ElevenLabs account is picked on first use. */
        const val DEFAULT_ELEVEN_VOICE = ""
    }
}

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val name = stringPreferencesKey("name")
        val onboarded = booleanPreferencesKey("onboarded")
        val theme = stringPreferencesKey("theme")
        val snooze = intPreferencesKey("snooze")
        val quiet = booleanPreferencesKey("quiet")
        val quietStart = intPreferencesKey("quiet_start")
        val quietEnd = intPreferencesKey("quiet_end")
        val followUp = booleanPreferencesKey("follow_up")
        val prepLead = intPreferencesKey("prep_lead")
        val dayReminder = intPreferencesKey("day_reminder")
        val briefing = booleanPreferencesKey("briefing")
        val briefingTime = intPreferencesKey("briefing_time")
        val readAloud = booleanPreferencesKey("read_aloud")
        val voiceConversation = booleanPreferencesKey("voice_conversation")
        val elevenVoice = stringPreferencesKey("eleven_voice")
        val aiUrl = stringPreferencesKey("ai_url")
        val aiModel = stringPreferencesKey("ai_model")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val placeAwareness = booleanPreferencesKey("place_awareness")
        val placeOffKinds = stringSetPreferencesKey("place_off_kinds")
        val placeUndos = stringSetPreferencesKey("place_undos")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            name = p[K.name] ?: d.name,
            onboarded = p[K.onboarded] ?: d.onboarded,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            defaultSnoozeMin = p[K.snooze] ?: d.defaultSnoozeMin,
            quietEnabled = p[K.quiet] ?: d.quietEnabled,
            quietStartMin = p[K.quietStart] ?: d.quietStartMin,
            quietEndMin = p[K.quietEnd] ?: d.quietEndMin,
            followUp = p[K.followUp] ?: d.followUp,
            prepLeadMin = p[K.prepLead] ?: d.prepLeadMin,
            dayReminderMin = p[K.dayReminder] ?: d.dayReminderMin,
            briefingEnabled = p[K.briefing] ?: d.briefingEnabled,
            briefingMin = p[K.briefingTime] ?: d.briefingMin,
            readAloud = p[K.readAloud] ?: d.readAloud,
            voiceConversation = p[K.voiceConversation] ?: d.voiceConversation,
            elevenVoiceId = p[K.elevenVoice] ?: d.elevenVoiceId,
            aiBaseUrl = p[K.aiUrl] ?: d.aiBaseUrl,
            aiModel = p[K.aiModel] ?: d.aiModel,
            aiEnabled = p[K.aiEnabled] ?: d.aiEnabled,
            placeAwareness = p[K.placeAwareness] ?: d.placeAwareness,
            placeOffKinds = p[K.placeOffKinds] ?: d.placeOffKinds,
            placeUndos = p[K.placeUndos] ?: d.placeUndos,
        )
    }

    suspend fun current(): AppSettings = flow.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.store.edit { it[key] = value }
    }

    suspend fun setName(v: String) = set(K.name, v.trim())
    suspend fun setOnboarded() = set(K.onboarded, true)
    suspend fun setTheme(v: ThemeMode) = set(K.theme, v.name)
    suspend fun setSnooze(v: Int) = set(K.snooze, v)
    suspend fun setQuiet(enabled: Boolean) = set(K.quiet, enabled)
    suspend fun setQuietStart(min: Int) = set(K.quietStart, min)
    suspend fun setQuietEnd(min: Int) = set(K.quietEnd, min)
    suspend fun setFollowUp(v: Boolean) = set(K.followUp, v)
    suspend fun setPrepLead(v: Int) = set(K.prepLead, v)
    suspend fun setDayReminder(min: Int) = set(K.dayReminder, min)
    suspend fun setBriefing(v: Boolean) = set(K.briefing, v)
    suspend fun setBriefingTime(min: Int) = set(K.briefingTime, min)
    suspend fun setReadAloud(v: Boolean) = set(K.readAloud, v)
    suspend fun setVoiceConversation(v: Boolean) = set(K.voiceConversation, v)
    suspend fun setElevenVoice(v: String) = set(K.elevenVoice, v.trim())
    suspend fun setAiUrl(v: String) = set(K.aiUrl, v.trim())
    suspend fun setAiModel(v: String) = set(K.aiModel, v.trim())
    suspend fun setAiEnabled(v: Boolean) = set(K.aiEnabled, v)
    suspend fun setPlaceAwareness(v: Boolean) = set(K.placeAwareness, v)
    suspend fun setPlaceOffKinds(v: Set<String>) = set(K.placeOffKinds, v)
    suspend fun setPlaceUndos(v: Set<String>) = set(K.placeUndos, v)

    suspend fun clear() {
        context.store.edit { it.clear() }
    }
}
