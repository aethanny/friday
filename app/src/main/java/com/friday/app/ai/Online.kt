package com.friday.app.ai

import android.content.Context
import com.friday.app.R
import com.friday.app.data.MemoryRepository
import com.friday.app.data.db.MemorySource
import com.friday.app.data.NoteRepository
import com.friday.app.data.SecretStore
import com.friday.app.data.SettingsStore
import com.friday.app.data.TaskRepository
import com.friday.app.data.TaskSnapshot
import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.FridayDatabase
import com.friday.app.data.db.Repeat
import com.friday.app.data.db.TaskEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.FocusMath
import com.friday.app.domain.Matrix
import com.friday.app.reminders.ReminderEngine
import com.friday.app.ui.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class AiException(val reason: Reason, message: String? = null) : Exception(message) {
    enum class Reason { NO_KEY, OFFLINE, AUTH, RATE_LIMIT, MODEL, SERVER, BAD_REPLY }
}

/** Minimal OpenAI-compatible client: chat completions and the model list. Works with Gemini, OpenAI, OpenRouter, Groq, Ollama… */
class OpenAiCompatClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun chat(base: String, key: String, model: String, messages: List<Pair<String, String>>, jsonMode: Boolean = true): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") { messages.forEach { (role, content) -> addJsonObject { put("role", role); put("content", content) } } }
            put("temperature", 0.4)
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
            // Gemini thinks before answering by default; a light setting keeps replies quick.
            if (base.contains("generativelanguage.googleapis.com")) put("reasoning_effort", "low")
        }
        val text = call(
            Request.Builder().url(url(base, "chat/completions")).header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody(JSON)).build(),
        )
        return runCatching {
            Json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull!!
        }.getOrElse { throw AiException(AiException.Reason.BAD_REPLY) }
    }

    suspend fun models(base: String, key: String): List<String> {
        val text = call(Request.Builder().url(url(base, "models")).header("Authorization", "Bearer $key").get().build())
        return runCatching {
            Json.parseToJsonElement(text).jsonObject["data"]!!.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }
        }.getOrElse { throw AiException(AiException.Reason.BAD_REPLY) }.sorted()
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        val resp = try { http.newCall(req).execute() } catch (e: IOException) { throw AiException(AiException.Reason.OFFLINE, e.message) }
        resp.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw AiException(reasonFor(it.code, text), "HTTP ${it.code}")
            text
        }
    }

    private fun url(base: String, path: String) = base.trim().trimEnd('/') + "/" + path

    companion object {
        private val JSON = "application/json".toMediaType()
        fun reasonFor(code: Int, body: String) = when {
            // Gemini answers a bad key with 400 "API key not valid" rather than 401.
            code == 401 || code == 403 || (code == 400 && body.contains("api key", ignoreCase = true)) -> AiException.Reason.AUTH
            code == 429 -> AiException.Reason.RATE_LIMIT
            code == 404 || (code == 400 && body.contains("model", ignoreCase = true)) -> AiException.Reason.MODEL
            else -> AiException.Reason.SERVER
        }
    }
}

@Serializable
data class AiAction(
    val type: String = "",
    val taskId: Long? = null,
    val title: String? = null,
    val text: String? = null,
    val date: String? = null,
    val time: String? = null,
    val durationMin: Int? = null,
    val important: Boolean? = null,
    val repeat: String? = null,
    val minutes: Int? = null,
    val steps: List<String>? = null,
)

@Serializable
data class AiReply(val think: String = "", val reply: String = "", val actions: List<AiAction> = emptyList())

@Serializable
data class AiCue(val `when`: String = "", val minutes: Int = 0, val time: String? = null, val message: String = "")

@Serializable
private data class AiCues(val cues: List<AiCue> = emptyList())

@Serializable
private data class AiSteps(val steps: List<String> = emptyList())

/** The only changes the AI may ask for, after validation. Anything else is dropped. */
sealed interface Action {
    data class Create(val title: String, val date: LocalDate?, val time: LocalTime?, val durationMin: Int?, val important: Boolean, val repeat: Repeat) : Action
    data class Note(val text: String) : Action
    data class Steps(val taskId: Long, val steps: List<String>) : Action
    data class Update(val taskId: Long, val title: String?, val date: LocalDate?, val time: LocalTime?, val important: Boolean?) : Action
    data class Complete(val taskId: Long) : Action
    data class Snooze(val taskId: Long, val minutes: Int) : Action
    data class Focus(val taskId: Long?, val minutes: Int) : Action
    data class Remember(val text: String) : Action
}

object AiActions {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val TIME = Regex("^(\\d{1,2}):(\\d{2})")

    /** Models sometimes wrap JSON in prose or code fences; take the outermost object. */
    fun parse(raw: String): AiReply? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(AiReply.serializer(), raw.substring(start, end + 1)) }.getOrNull()
    }

    fun parseCues(raw: String): List<AiCue> = runCatching {
        json.decodeFromString(AiCues.serializer(), raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).cues
    }.getOrDefault(emptyList())

    fun parseSteps(raw: String): List<String> = runCatching {
        json.decodeFromString(AiSteps.serializer(), raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).steps
    }.getOrDefault(emptyList())

    fun date(s: String?, today: LocalDate): LocalDate? = s?.let { runCatching { LocalDate.parse(it.trim().take(10)) }.getOrNull() }
        ?.takeIf { !it.isBefore(today.minusDays(1)) && it.isBefore(today.plusYears(5)) }

    fun time(s: String?): LocalTime? = s?.let { TIME.find(it.trim()) }?.let { m ->
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private fun clean(s: String?, max: Int) = s?.trim()?.replace(Regex("\\s+"), " ")?.take(max)?.takeIf { it.isNotBlank() }

    fun validate(a: AiAction, openIds: Set<Long>, today: LocalDate): Action? {
        val id = a.taskId?.takeIf { it in openIds }
        val type = a.type.trim().uppercase(Locale.ROOT)
        return when (type) {
            "CREATE_TASK", "CREATE_REMINDER" -> {
                val title = clean(a.title, 200) ?: return null
                val time = time(a.time)
                val date = date(a.date, today) ?: if (time != null) today else null
                if (type == "CREATE_REMINDER" && time == null) return null
                Action.Create(
                    title, date, time,
                    a.durationMin?.takeIf { it in 1..720 },
                    a.important == true,
                    runCatching { Repeat.valueOf(a.repeat?.uppercase(Locale.ROOT) ?: "NONE") }.getOrDefault(Repeat.NONE),
                )
            }
            "CREATE_NOTE" -> clean(a.text ?: a.title, 4000)?.let { Action.Note(it) }
            "CREATE_SUBTASKS" -> {
                val steps = a.steps.orEmpty().mapNotNull { clean(it, 200) }.take(10)
                if (id == null || steps.isEmpty()) null else Action.Steps(id, steps)
            }
            "UPDATE_TASK" -> {
                if (id == null) return null
                val u = Action.Update(id, clean(a.title, 200), date(a.date, today), time(a.time), a.important)
                if (u.title == null && u.date == null && u.time == null && u.important == null) null else u
            }
            "COMPLETE_TASK" -> id?.let { Action.Complete(it) }
            "SNOOZE_REMINDER" -> if (id == null) null else a.minutes?.takeIf { it in 1..1440 }?.let { Action.Snooze(id, it) }
            "REMEMBER" -> clean(a.text, 200)?.let { Action.Remember(it) }
            "START_FOCUS" -> Action.Focus(id, (a.minutes ?: 25).coerceIn(1, 180))
            else -> null
        }
    }

    fun encode(a: AiAction): String = json.encodeToString(AiAction.serializer(), a)
    fun decode(s: String): AiAction? = runCatching { json.decodeFromString(AiAction.serializer(), s) }.getOrNull()
}

/**
 * FRIDAY's optional online brain. It only sees the message, recent chat and the titles/times of open tasks
 * (never notes), and every change it asks for passes [AiActions.validate] first.
 */
class OnlineAssistant(
    private val ctx: Context,
    private val db: FridayDatabase,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val tasks: TaskRepository,
    private val notes: NoteRepository,
    private val memory: MemoryRepository,
    private val reminders: ReminderEngine,
    private val client: OpenAiCompatClient = OpenAiCompatClient(),
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : RemoteAssistant {

    suspend fun available(): Boolean = settings.current().aiEnabled && secrets.has(SecretStore.AI_KEY)

    private suspend fun ask(messages: List<Pair<String, String>>, jsonMode: Boolean = true): String {
        val s = settings.current()
        val key = secrets.get(SecretStore.AI_KEY) ?: throw AiException(AiException.Reason.NO_KEY)
        return client.chat(s.aiBaseUrl, key, s.aiModel, messages, jsonMode)
    }

    /** A tiny round trip for the "Test connection" button. */
    suspend fun test(): String {
        val s = settings.current()
        ask(listOf("user" to "Reply with the single word OK."), jsonMode = false)
        return s.aiModel
    }

    suspend fun models(): List<String> {
        val s = settings.current()
        val key = secrets.get(SecretStore.AI_KEY) ?: throw AiException(AiException.Reason.NO_KEY)
        return client.models(s.aiBaseUrl, key)
    }

    override suspend fun respond(text: String, lang: Lang): EngineResult? {
        if (!available()) return null
        val r = ctx.inLang(lang)
        val open = tasks.openTasks()
        val history = db.messages().lastN(13).reversed().let { m -> if (m.lastOrNull()?.let { it.fromUser && it.text == text.trim() } == true) m.dropLast(1) else m }
        val messages = buildList {
            add("system" to systemPrompt(lang, open))
            history.takeLast(12).forEach { add((if (it.fromUser) "user" else "assistant") to it.text) }
            add("user" to text)
        }
        val raw = try { ask(messages) } catch (e: AiException) { return EngineResult(errorText(r, e), lang = lang, failed = true) }
        val reply = AiActions.parse(raw) ?: return EngineResult(r.getString(R.string.ai_bad_reply), lang = lang, failed = true)
        return apply(reply, open, r, lang)
    }

    private suspend fun apply(reply: AiReply, open: List<TaskWithDetails>, r: Context, lang: Lang): EngineResult {
        val byId = open.associateBy { it.task.id }
        val today = now().toLocalDate()
        val undos = mutableListOf<Undo>()
        var proposal: Proposal? = null
        var focus: Action.Focus? = null
        var created: Long? = null
        var remembered: String? = null
        for (raw in reply.actions.take(5)) {
            when (val a = AiActions.validate(raw, byId.keys, today)) {
                is Action.Create -> {
                    val id = tasks.save(TaskEntity(title = a.title, important = a.important, dueDate = a.date, dueTime = a.time, durationMin = a.durationMin, repeat = a.repeat))
                    undos += Undo.DeleteTask(id); created = id
                }
                is Action.Note -> undos += Undo.DeleteNote(notes.add(a.text))
                is Action.Steps -> undos += Undo.DeleteSteps(tasks.addSubtasks(a.taskId, a.steps))
                is Action.Update -> if (proposal == null) proposal = Proposal(ProposalKind.UPDATE_TASK, a.taskId, updateLabel(r, byId.getValue(a.taskId).task, a), payload = AiActions.encode(raw))
                is Action.Complete -> if (proposal == null) proposal = Proposal(ProposalKind.COMPLETE_TASK, a.taskId, r.getString(R.string.mark_done_label, byId.getValue(a.taskId).task.title))
                is Action.Snooze -> if (proposal == null) proposal = Proposal(ProposalKind.SNOOZE_REMINDER, a.taskId, r.getString(R.string.snooze_label, byId.getValue(a.taskId).task.title, Fmt.duration(r, a.minutes)), minutes = a.minutes)
                is Action.Focus -> focus = a
                is Action.Remember -> memory.add(a.text, MemorySource.CHAT)?.let { undos += Undo.DeleteMemory(it); remembered = r.getString(R.string.remembered_line, a.text) }
                null -> Unit
            }
        }
        return EngineResult(
            text = reply.reply.trim().ifBlank { r.getString(R.string.ai_done) }.take(1500) + remembered?.let { "\n\n$it" }.orEmpty(),
            undo = when (undos.size) { 0 -> null; 1 -> undos[0]; else -> Undo.Many(undos) },
            proposal = proposal,
            taskId = created,
            focusTaskId = focus?.let { it.taskId ?: 0L },
            focusMinutes = focus?.minutes,
            remote = true,
            lang = lang,
        )
    }

    private fun updateLabel(r: Context, t: TaskEntity, u: Action.Update): String {
        val changes = listOfNotNull(
            u.title?.let { "“$it”" },
            u.date?.let { Fmt.day(r, it) },
            u.time?.let { Fmt.time(r, it) },
            u.important?.let { if (it) "★" else r.getString(R.string.no_star) },
        ).joinToString(" · ")
        return r.getString(R.string.update_label, t.title, changes)
    }

    override suspend fun applyConfirmed(p: Proposal): Undo? {
        val before = tasks.details(p.taskId) ?: return null
        return when (p.kind) {
            ProposalKind.UPDATE_TASK -> {
                val a = AiActions.decode(p.payload)?.let { AiActions.validate(it, setOf(p.taskId), now().toLocalDate()) } as? Action.Update ?: return null
                val t = before.task
                tasks.save(t.copy(title = a.title ?: t.title, dueDate = a.date ?: t.dueDate, dueTime = a.time ?: t.dueTime, important = a.important ?: t.important))
                Undo.Restore(TaskSnapshot(before))
            }
            ProposalKind.SNOOZE_REMINDER -> {
                // Only a reminder that already rang can be snoozed; snoozing a future one would make it ring earlier.
                val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                before.cues.filter { (it.firedAt ?: 0) > since }.maxByOrNull { it.firedAt ?: 0 }?.let { reminders.snooze(it.id, p.minutes) }
                null
            }
            ProposalKind.COMPLETE_TASK -> tasks.complete(p.taskId)?.let { Undo.Restore(it) }
        }
    }

    /** 2–5 reminders that fit this task, mapped onto FRIDAY's own reminder anchors. */
    suspend fun suggestReminders(t: TaskEntity, lang: Lang): List<CueEntity> {
        val timed = t.dueTime != null
        val prompt = """
            Task: "${t.title}"${t.dueDate?.let { ", due $it" } ?: ""}${t.dueTime?.let { " at $it" } ?: ""}${t.durationMin?.let { ", lasts $it minutes" } ?: ""}.
            Suggest 2 to 5 short, practical reminders that would help someone with a busy, easily-distracted mind do this task well
            (preparation before it starts, key moments during it, wrapping up). Write each message in ${language(lang)} as a short instruction (max 12 words).
            Respond only with JSON: {"cues":[{"when": one of ${if (timed) "\"BEFORE_START\",\"AT_START\",\"BEFORE_END\",\"AT_END\"" else "\"ON_DAY\",\"DAY_BEFORE\""},
            "minutes": minutes before (for BEFORE_*), "time": "HH:MM" (for ON_DAY/DAY_BEFORE), "message": "..."}]}
        """.trimIndent()
        return AiActions.parseCues(ask(listOf("user" to prompt))).take(5).mapNotNull { c ->
            val msg = c.message.trim().take(120).ifBlank { return@mapNotNull null }
            val base = CueEntity(taskId = t.id, kind = CueKind.CUSTOM, anchor = CueAnchor.START, message = msg)
            when (c.`when`.uppercase(Locale.ROOT)) {
                "BEFORE_START" -> if (timed) base.copy(offsetMin = c.minutes.coerceIn(1, 24 * 60)) else null
                "AT_START" -> if (timed) base else null
                "BEFORE_END" -> if (timed && t.durationMin != null) base.copy(anchor = CueAnchor.END, offsetMin = c.minutes.coerceIn(1, t.durationMin)) else null
                "AT_END" -> if (timed && t.durationMin != null) base.copy(anchor = CueAnchor.END) else null
                "ON_DAY", "DAY_BEFORE" -> AiActions.time(c.time)?.let { tm ->
                    val m = tm.hour * 60 + tm.minute
                    if (t.dueDate == null) null else base.copy(anchor = CueAnchor.DAY, offsetMin = if (c.`when`.uppercase(Locale.ROOT) == "DAY_BEFORE") m - 24 * 60 else m)
                }
                else -> null
            }
        }
    }

    /** Small, concrete first steps for a task that feels too big. */
    suspend fun breakDown(t: TaskWithDetails, lang: Lang): List<String> {
        val prompt = """
            Break this task into 3 to 6 small, concrete steps, each doable in under 15 minutes and starting with a verb.
            Task: "${t.task.title}".
            Steps already listed: ${t.subtasks.joinToString("; ") { it.title }.ifBlank { "none" }}. Don't repeat them.
            Write the steps in ${language(lang)}, max 8 words each. Respond only with JSON: {"steps":["...", "..."]}
        """.trimIndent()
        return AiActions.parseSteps(ask(listOf("user" to prompt))).mapNotNull { it.trim().take(120).takeIf(String::isNotBlank) }.take(6)
    }

    private fun language(lang: Lang) = if (lang == Lang.MS) "Malay (Bahasa Melayu)" else "English"

    private suspend fun systemPrompt(lang: Lang, open: List<TaskWithDetails>): String {
        val n = now()
        val ranked = Matrix.rankForNow(open.map { it.task }, n).take(40)
        val tasksText = ranked.joinToString("\n") { t ->
            "${t.id} | ${t.title} | ${t.dueDate ?: "-"} | ${t.dueTime ?: "-"} | ${t.durationMin ?: "-"} | ${if (t.important) "yes" else "no"}"
        }.ifBlank { "(none)" }
        val focus = db.focus().active()?.let { "\nThe user is in a focus session on \"${it.taskTitle}\" with ${FocusMath.remainingMs(it, System.currentTimeMillis()) / 60_000} min left." }.orEmpty()
        val memories = memory.all().joinToString("\n") { "- ${it.text}" }.ifBlank { "(nothing yet)" }
        val postponed = open.map { it.task }.filter { it.postponeCount >= 2 }.take(10).joinToString("\n") { "- ${it.title} (moved ${it.postponeCount} times)" }
        val lastFocus = db.focus().latest()?.takeIf { (it.endedAt ?: it.startedAt) > System.currentTimeMillis() - 24 * 3_600_000L }
            ?.let { "\nLast focus: \"${it.taskTitle}\" — ${it.outcome?.name ?: "in progress"}" }.orEmpty()
        val name = settings.current().name.takeIf { it.isNotBlank() }?.let { " The user's name is $it." }.orEmpty()
        return """
            You are FRIDAY, a calm personal assistant inside the user's phone app. You help them remember things, focus and follow through.$name
            Be warm, brief and practical: one thing at a time, no lectures, never shame them, never diagnose. Reply in ${language(lang)}; mixing English and Malay is fine if they do.
            Now: ${n.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH))} (local time).$focus
            Open tasks (id | title | due date | time | minutes | important):
            $tasksText
            ${if (postponed.isBlank()) "" else "Tasks the user keeps putting off:\n$postponed"}$lastFocus

            What you know about the user:
            $memories

            Respond ONLY with a JSON object: {"think": string, "reply": string, "actions": [ ... ]}
            "think": think first, privately: what time it is, what they're doing, their energy, what they keep avoiding, what you know about them. Then reply with ONE recommendation, not a menu.
            "reply": what you say, at most 3 short sentences (a short list is fine when asked what is on).
            "actions": zero or more of:
            {"type":"CREATE_TASK","title":str,"date":"YYYY-MM-DD","time":"HH:MM","durationMin":int,"important":bool,"repeat":"NONE|DAILY|WEEKDAYS|WEEKLY|MONTHLY"} (only title is required)
            {"type":"CREATE_REMINDER", same fields, date and time required}
            {"type":"CREATE_NOTE","text":str}
            {"type":"CREATE_SUBTASKS","taskId":int,"steps":[str]} (2-6 small steps, each starts with a verb)
            {"type":"UPDATE_TASK","taskId":int, plus any of "title","date","time","important"}
            {"type":"COMPLETE_TASK","taskId":int}
            {"type":"SNOOZE_REMINDER","taskId":int,"minutes":int}
            {"type":"START_FOCUS","taskId":int,"minutes":int}
            {"type":"REMEMBER","text":str} (a lasting fact about the user's routine or preferences they told you. Never health, diagnosis, medication, religion or relationships unless they explicitly ask you to remember it. Don't remember one-off tasks.)
            Rules: only use taskIds from the list. If something needed is missing or unclear (for example a reminder without a time), ask one short question and return no actions.
            Never delete anything. Don't invent tasks the user didn't ask for. Times are 24-hour local time.
            Follow what you know about the user (e.g. if they hate long messages, be shorter).
        """.trimIndent()
    }

    fun errorText(r: Context, e: AiException): String = r.getString(
        when (e.reason) {
            AiException.Reason.NO_KEY -> R.string.ai_err_no_key
            AiException.Reason.OFFLINE -> R.string.ai_err_offline
            AiException.Reason.AUTH -> R.string.ai_err_auth
            AiException.Reason.RATE_LIMIT -> R.string.ai_err_rate
            AiException.Reason.MODEL -> R.string.ai_err_model
            AiException.Reason.SERVER, AiException.Reason.BAD_REPLY -> R.string.ai_err_server
        },
    )
}
