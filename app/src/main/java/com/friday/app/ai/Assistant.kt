package com.friday.app.ai

import android.content.Context
import android.content.res.Configuration
import com.friday.app.R
import com.friday.app.data.NoteRepository
import com.friday.app.data.TaskRepository
import com.friday.app.data.TaskSnapshot
import com.friday.app.data.db.FocusOutcome
import com.friday.app.data.db.FocusSessionEntity
import com.friday.app.data.db.FridayDatabase
import com.friday.app.data.db.TaskEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.Matrix
import com.friday.app.ui.Fmt
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

enum class Source { CAPTURE, CHAT, TASK_SHEET }

sealed interface Undo {
    data class DeleteTask(val id: Long) : Undo
    data class DeleteNote(val id: Long) : Undo
    data class Restore(val snapshot: TaskSnapshot) : Undo
}

/** Something FRIDAY wants to change in existing data; the user confirms with one tap. */
data class Proposal(val kind: ProposalKind, val taskId: Long, val label: String, val minutes: Int = 0)
enum class ProposalKind { COMPLETE_TASK, UPDATE_TASK, SNOOZE_REMINDER }

data class EngineResult(
    val text: String,
    val chips: List<String> = emptyList(),
    val undo: Undo? = null,
    val proposal: Proposal? = null,
    val taskId: Long? = null,
    /** Open the focus screen for this task (0 = no task). */
    val focusTaskId: Long? = null,
    val focusMinutes: Int? = null,
    val remote: Boolean = false,
    val lang: Lang = Lang.EN,
)

fun Context.inLang(lang: Lang): Context {
    val cfg = Configuration(resources.configuration)
    cfg.setLocale(Locale.forLanguageTag(if (lang == Lang.MS) "ms" else "en"))
    return createConfigurationContext(cfg)
}

data class Recovery(
    val task: TaskWithDetails?,
    val title: String,
    val session: FocusSessionEntity?,
    val nextStep: String?,
    val at: Long,
)

/**
 * The local assistant: deterministic parsing plus a few useful conversations. It never pretends to be a
 * language model; anything it cannot handle offline goes to the optional remote AI or is saved to the Inbox.
 */
class AssistantEngine(
    private val ctx: Context,
    private val db: FridayDatabase,
    private val tasks: TaskRepository,
    private val notes: NoteRepository,
    private val parser: CommandParser,
    private val remote: RemoteAssistant?,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private var pending: Parsed? = null
    private var lastUnhandled: String? = null

    suspend fun handle(input: String, source: Source): EngineResult {
        val text = input.trim()
        val lang = CommandParser.detectLang(text)
        val r = ctx.inLang(lang)
        if (text.isBlank()) return EngineResult("", lang = lang)

        pending?.let { p ->
            pending = null
            if (CANCEL.containsMatchIn(text)) return EngineResult(r.getString(R.string.ok_cancelled), lang = lang)
            val t = parser.parse(text)
            if (t.time != null && t.title.count { it.isLetter() } <= 3) {
                return create(p.copy(time = t.time, date = t.date?.takeIf { text.contains(Regex("\\d{1,2}[/-]|tomorrow|esok|today|hari ini", RegexOption.IGNORE_CASE)) } ?: p.date ?: t.date), source)
            }
        }

        if (source == Source.CHAT && SAVE_TO_INBOX.containsMatchIn(text)) {
            val last = lastUnhandled ?: return EngineResult(r.getString(R.string.nothing_to_save), lang = lang)
            lastUnhandled = null
            val id = notes.add(last)
            return EngineResult(r.getString(R.string.saved_inbox), undo = Undo.DeleteNote(id), lang = lang)
        }

        if (source != Source.TASK_SHEET) local(text, lang)?.let { return it }

        val p = parser.parse(text)
        return when (p.kind) {
            CommandKind.NOTE -> {
                val id = notes.add(text)
                EngineResult(r.getString(R.string.saved_inbox), undo = Undo.DeleteNote(id), lang = lang)
            }
            CommandKind.REMINDER, CommandKind.TASK -> when {
                p.title.isBlank() -> EngineResult(r.getString(R.string.ask_what), lang = lang)
                p.placeEvent != null -> placeReminder(p, text)
                p.needsTime -> {
                    pending = p
                    EngineResult(r.getString(R.string.ask_time, p.title), chips = timeChips(r), lang = lang)
                }
                else -> create(p, source)
            }
            CommandKind.UNKNOWN -> when (source) {
                Source.TASK_SHEET -> create(p.copy(kind = CommandKind.TASK, title = p.title.ifBlank { text }), source)
                Source.CAPTURE -> {
                    val id = notes.add(text)
                    EngineResult(r.getString(R.string.saved_inbox), undo = Undo.DeleteNote(id), lang = lang)
                }
                Source.CHAT -> remote?.respond(text, lang) ?: run {
                    lastUnhandled = text
                    EngineResult(r.getString(R.string.offline_fallback), chips = listOf(r.getString(R.string.chip_save_inbox)), lang = lang)
                }
            }
        }
    }

    private fun timeChips(r: Context) = listOf(
        r.getString(R.string.chip_morning, Fmt.time(r, LocalTime.of(9, 0))),
        r.getString(R.string.chip_afternoon, Fmt.time(r, LocalTime.of(13, 0))),
        r.getString(R.string.chip_evening, Fmt.time(r, LocalTime.of(19, 0))),
    )

    private suspend fun create(p: Parsed, source: Source): EngineResult {
        val r = ctx.inLang(p.lang)
        val id = tasks.save(
            TaskEntity(
                title = p.title,
                important = p.important,
                dueDate = p.date,
                dueTime = p.time,
                durationMin = p.durationMin,
                repeat = p.repeat,
                repeatDays = p.repeatDays,
            ),
        )
        val t = tasks.get(id)!!
        val whenText = Fmt.whenText(r, t, now().toLocalDate())
        val repeat = Fmt.repeat(r, t)
        val detail = listOfNotNull(whenText, repeat).joinToString(" · ")
        val msg = when {
            t.dueTime != null -> r.getString(R.string.reminder_set, t.title, detail)
            detail.isNotEmpty() -> r.getString(R.string.task_added_when, t.title, detail)
            else -> r.getString(R.string.task_added, t.title)
        }
        return EngineResult(msg, undo = Undo.DeleteTask(id), taskId = id, lang = p.lang)
    }

    /** Location reminders need a saved place; until it exists the thought is kept safe in the Inbox. */
    private suspend fun placeReminder(p: Parsed, original: String): EngineResult {
        val r = ctx.inLang(p.lang)
        val place = db.places().all().firstOrNull { it.name.equals(p.place, ignoreCase = true) || placeAlias(it.name) == p.place }
        if (place == null) {
            val id = notes.add(original)
            return EngineResult(r.getString(R.string.place_unknown, p.place ?: ""), undo = Undo.DeleteNote(id), lang = p.lang)
        }
        val id = tasks.save(TaskEntity(title = p.title, important = p.important))
        tasks.addPlaceCue(id, place.id, leave = p.placeEvent == PlaceEvent.LEAVE)
        val msg = r.getString(if (p.placeEvent == PlaceEvent.LEAVE) R.string.place_set_leave else R.string.place_set_arrive, p.title, place.name)
        return EngineResult(msg, undo = Undo.DeleteTask(id), taskId = id, lang = p.lang)
    }

    private fun placeAlias(name: String) = when (name.lowercase()) {
        "rumah" -> "home"; "pejabat", "office" -> "work"; "kampus" -> "campus"; else -> name.lowercase()
    }

    suspend fun undo(u: Undo) {
        when (u) {
            is Undo.DeleteTask -> tasks.delete(u.id)
            is Undo.DeleteNote -> db.notes().get(u.id)?.let { notes.delete(it) }
            is Undo.Restore -> tasks.restore(u.snapshot)
        }
    }

    suspend fun confirm(p: Proposal): Undo? = when (p.kind) {
        ProposalKind.COMPLETE_TASK -> tasks.complete(p.taskId)?.let { Undo.Restore(it) }
        ProposalKind.UPDATE_TASK, ProposalKind.SNOOZE_REMINDER -> remote?.applyConfirmed(p)
    }

    /** Conversations that need no AI at all. */
    private suspend fun local(text: String, lang: Lang): EngineResult? {
        val r = ctx.inLang(lang)
        val n = now()
        return when {
            FORGOT.containsMatchIn(text) -> recoveryReply(r, lang)
            TODAY.containsMatchIn(text) -> todayReply(r, lang, n)
            TOO_MANY.containsMatchIn(text) -> overwhelmedReply(r, lang, n)
            FREE_TIME.find(text) != null -> {
                val m = FREE_TIME.find(text)!!
                val num = m.groupValues[1].toIntOrNull() ?: 1
                val minutes = if (m.groupValues[2].lowercase().let { it.startsWith("h") || it.startsWith("j") }) num * 60 else num
                freeTimeReply(r, lang, n, minutes)
            }
            DONE_WITH.find(text) != null -> {
                val name = DONE_WITH.find(text)!!.groupValues.last().trim()
                val t = findTask(name) ?: return EngineResult(r.getString(R.string.cant_find_task, name), lang = lang)
                EngineResult(
                    r.getString(R.string.confirm_complete, t.title),
                    proposal = Proposal(ProposalKind.COMPLETE_TASK, t.id, r.getString(R.string.mark_done_label, t.title)),
                    lang = lang,
                )
            }
            START_FOCUS.find(text) != null -> {
                val m = START_FOCUS.find(text)!!
                val name = m.groupValues[1].trim()
                val t = if (name.isNotBlank()) findTask(name) else Matrix.rankForNow(tasks.openTasks().map { it.task }, n).firstOrNull()
                EngineResult(r.getString(R.string.opening_focus, t?.title ?: r.getString(R.string.focus)), focusTaskId = t?.id ?: 0, lang = lang)
            }
            HELLO.matches(text.lowercase().trim()) -> EngineResult(r.getString(R.string.hello_reply), lang = lang)
            THANKS.containsMatchIn(text) -> EngineResult(r.getString(R.string.thanks_reply), lang = lang)
            HELP.containsMatchIn(text) -> EngineResult(r.getString(R.string.help_reply), lang = lang)
            else -> null
        }
    }

    private suspend fun findTask(name: String): TaskEntity? {
        val q = name.lowercase().removePrefix("the ").removePrefix("my ").trim()
        if (q.isBlank()) return null
        val open = tasks.openTasks().map { it.task }
        return open.firstOrNull { it.title.lowercase() == q }
            ?: open.firstOrNull { it.title.lowercase().contains(q) }
            ?: open.firstOrNull { t -> q.split(" ").filter { it.length > 2 }.let { w -> w.isNotEmpty() && w.all { t.title.lowercase().contains(it) } } }
    }

    suspend fun recovery(): Recovery? {
        val session = db.focus().latest()
        val active = db.tasks().lastActive()
        val sessionAt = session?.let { it.endedAt ?: it.startedAt } ?: 0L
        val activeAt = active?.lastActiveAt ?: 0L
        if (session == null && active == null) return null
        val useSession = session != null && sessionAt >= activeAt - 60_000
        val taskId = if (useSession) session?.taskId else active?.id
        val details = taskId?.let { tasks.details(it) }
        val title = details?.task?.title ?: session?.taskTitle ?: return null
        val next = details?.subtasks?.sortedBy { it.position }?.firstOrNull { !it.done }?.title
        return Recovery(details, title, if (useSession || session?.taskId == taskId) session else null, next, maxOf(sessionAt, activeAt))
    }

    private suspend fun recoveryReply(r: Context, lang: Lang): EngineResult {
        val rec = recovery() ?: return EngineResult(r.getString(R.string.recovery_empty), lang = lang)
        return EngineResult(describeRecovery(r, rec), chips = listOf(r.getString(R.string.chip_continue_focus)), focusTaskId = null, taskId = rec.task?.task?.id, lang = lang)
    }

    fun describeRecovery(r: Context, rec: Recovery): String {
        val parts = mutableListOf(r.getString(R.string.recovery_working_on, rec.title))
        rec.session?.let { s ->
            val ago = ((System.currentTimeMillis() - (s.endedAt ?: s.startedAt)) / 60_000).toInt().coerceAtLeast(0)
            val how = when (s.outcome) {
                FocusOutcome.COMPLETED -> r.getString(R.string.outcome_completed_lc)
                FocusOutcome.PROGRESS -> r.getString(R.string.outcome_progress_lc)
                FocusOutcome.DISTRACTED -> r.getString(R.string.outcome_distracted_lc)
                null -> null
            }
            parts += if (s.endedAt == null) r.getString(R.string.recovery_session_running, Fmt.duration(r, s.plannedMin))
            else r.getString(R.string.recovery_session_ended, Fmt.duration(r, s.plannedMin), Fmt.duration(r, ago.coerceAtLeast(1))) +
                (how?.let { " " + r.getString(R.string.recovery_outcome, it) } ?: "")
            s.stopNote?.let { parts += r.getString(R.string.recovery_stopped_at, it) }
        }
        rec.nextStep?.let { parts += r.getString(R.string.recovery_next_step, it) }
        parts += r.getString(R.string.recovery_continue_q)
        return parts.joinToString("\n\n")
    }

    private suspend fun todayReply(r: Context, lang: Lang, n: LocalDateTime): EngineResult {
        val today = n.toLocalDate()
        val open = tasks.openTasks().map { it.task }
        val ranked = Matrix.rankForNow(open.filter { it.dueDate != null && !it.dueDate.isAfter(today) }, n)
        if (ranked.isEmpty()) return EngineResult(r.getString(R.string.today_empty), lang = lang)
        val important = ranked.filter { it.important }
        val head = if (important.isNotEmpty()) r.resources.getQuantityString(R.plurals.today_important, important.size, important.size)
        else r.resources.getQuantityString(R.plurals.today_count, ranked.size, ranked.size)
        val list = ranked.take(5).joinToString("\n") { t -> "• " + t.title + (t.dueTime?.let { " · " + Fmt.time(r, it) } ?: "") + if (Matrix.isOverdue(t, n)) " · " + r.getString(R.string.overdue).lowercase() else "" }
        return EngineResult("$head\n\n$list", lang = lang)
    }

    private suspend fun overwhelmedReply(r: Context, lang: Lang, n: LocalDateTime): EngineResult {
        val top = Matrix.rankForNow(tasks.openTasks().map { it.task }, n).firstOrNull()
            ?: return EngineResult(r.getString(R.string.overwhelmed_empty), lang = lang)
        return EngineResult(
            r.getString(R.string.overwhelmed_reply, top.title),
            chips = listOf(r.getString(R.string.chip_focus_5)),
            focusTaskId = null,
            taskId = top.id,
            focusMinutes = 5,
            lang = lang,
        )
    }

    private suspend fun freeTimeReply(r: Context, lang: Lang, n: LocalDateTime, minutes: Int): EngineResult {
        val ranked = Matrix.rankForNow(tasks.openTasks().map { it.task }, n)
        val fit = ranked.firstOrNull { (it.durationMin ?: Int.MAX_VALUE) <= minutes } ?: ranked.firstOrNull()
            ?: return EngineResult(r.getString(R.string.free_time_empty), lang = lang)
        val text = if ((fit.durationMin ?: Int.MAX_VALUE) <= minutes) r.getString(R.string.free_time_fits, fit.title, Fmt.duration(r, minutes))
        else r.getString(R.string.free_time_start, fit.title, Fmt.duration(r, minutes))
        return EngineResult(text, chips = listOf(r.getString(R.string.chip_focus_n, Fmt.duration(r, minutes))), taskId = fit.id, focusMinutes = minutes, lang = lang)
    }

    companion object {
        private val I = setOf(RegexOption.IGNORE_CASE)
        val CANCEL = Regex("^(cancel|never ?mind|stop|batal|tak jadi|tak payah)\\b", I)
        val SAVE_TO_INBOX = Regex("^(save (it )?to (my )?inbox|simpan dalam inbox|simpan ke inbox)", I)
        val FORGOT = Regex("(i forgot what i was doing|what was i doing|where was i|saya lupa apa saya buat|aku lupa apa aku buat|apa saya buat tadi|lupa apa (saya|aku) (tengah )?buat)", I)
        val TODAY = Regex("(what do i have( to do)? today|what('s| is) on today|what('s| is) my day|today'?s (tasks|plan)|what should i do today|apa (yang )?(saya|aku) (perlu|kena|nak) buat hari ini|apa plan hari ini|jadual hari ini)", I)
        val TOO_MANY = Regex("(too many things|so much to do|overwhelmed|i'?m stressed|banyak sangat|terlalu banyak|penat sangat|serabut)", I)
        val FREE_TIME = Regex("(?:i have|i've got|got|saya ada|aku ada|ada)\\s+(\\d+)\\s*(minutes?|mins?|hours?|hrs?|minit|jam)\\b", I)
        val DONE_WITH = Regex("^(?:i(?:'ve| have)? (?:finished|completed|done)|i'?m done with|mark (?:as )?done|done with|dah siap|sudah siap|siap)\\s+(.+)$", I)
        val START_FOCUS = Regex("^(?:start (?:a )?focus(?: session)?|let'?s focus|focus|mula fokus|nak fokus)(?:\\s+(?:on|pada|untuk)\\s+(.+))?$", I)
        val HELLO = Regex("^(hi|hello|hey|hey friday|hi friday|hai|helo|assalamualaikum|salam)[!. ]*$")
        val THANKS = Regex("^(thanks|thank you|thx|terima kasih|tq)\\b", I)
        val HELP = Regex("^(help|what can you do|tolong|apa kau boleh buat|apa awak boleh buat)\\b", I)
    }
}

/** Optional online brain. Null when no AI is configured. */
interface RemoteAssistant {
    suspend fun respond(text: String, lang: Lang): EngineResult?
    suspend fun applyConfirmed(p: Proposal): Undo?
}
