package com.friday.app.ai

import com.friday.app.location.PlaceManager
import android.content.Context
import android.content.res.Configuration
import com.friday.app.R
import com.friday.app.data.MemoryRepository
import com.friday.app.data.NoteRepository
import com.friday.app.data.SettingsStore
import com.friday.app.data.TaskRepository
import com.friday.app.data.TaskSnapshot
import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.Repeat
import com.friday.app.domain.Prep
import com.friday.app.domain.PrepPlan
import com.friday.app.domain.Schedule
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
    data class DeleteMemory(val id: Long) : Undo
    data class Restore(val snapshot: TaskSnapshot) : Undo
    data class DeleteSteps(val ids: List<Long>) : Undo
    data class Many(val items: List<Undo>) : Undo
}

/** Something FRIDAY wants to change in existing data; the user confirms with one tap. */
data class Proposal(val kind: ProposalKind, val taskId: Long, val label: String, val minutes: Int = 0, val payload: String = "")
enum class ProposalKind { COMPLETE_TASK, UPDATE_TASK, SNOOZE_REMINDER }

data class EngineResult(
    val text: String,
    val chips: List<String> = emptyList(),
    val undo: Undo? = null,
    val proposal: Proposal? = null,
    val taskId: Long? = null,
    val remote: Boolean = false,
    val lang: Lang = Lang.EN,
    /** The online AI could not answer; [text] says why. */
    val failed: Boolean = false,
)

fun Context.inLang(lang: Lang): Context {
    val cfg = Configuration(resources.configuration)
    cfg.setLocale(Locale.forLanguageTag(if (lang == Lang.MS) "ms" else "en"))
    return createConfigurationContext(cfg)
}

data class Recovery(
    val task: TaskWithDetails?,
    val title: String,
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
    private val memory: MemoryRepository,
    private val parser: CommandParser,
    private val remote: RemoteAssistant?,
    private val places: PlaceManager? = null,
    private val settings: SettingsStore? = null,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private var pending: Parsed? = null
    /** Task waiting for "how early should I alarm you to get ready?". */
    private var pendingPrep: Long? = null
    private var suggestedPrep: Int? = null
    private var lastUnhandled: String? = null

    suspend fun handle(input: String, source: Source): EngineResult {
        val text = input.trim()
        val lang = CommandParser.detectLang(text)
        val r = ctx.inLang(lang)
        if (text.isBlank()) return EngineResult("", lang = lang)

        pendingPrep?.let { id ->
            pendingPrep = null
            // Anything that is not an answer is a new message; the usual get-ready reminder stays.
            val lead = if (CANCEL.containsMatchIn(text)) -1 else parser.parseLead(text, suggestedPrep)
            if (lead != null) return setPrep(id, lead, lang)
        }

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
                p.placeType != null -> savedPlaceNamed(p.placeWord)?.let { placeReminder(p.copy(placeType = null, placeEvent = PlaceEvent.ARRIVE, place = it), text) } ?: errandReminder(p)
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
                Source.CHAT -> {
                    val online = remote?.respond(text, lang)
                    if (online != null && !online.failed) online
                    else {
                        lastUnhandled = text
                        val fallback = r.getString(R.string.offline_fallback)
                        // When the online AI was tried and failed, say why before the offline answer.
                        EngineResult(online?.let { it.text + "\n\n" + fallback } ?: fallback, chips = listOf(r.getString(R.string.chip_save_inbox)), lang = lang)
                    }
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
        // Think first, like a person would: is this something to get ready for, and what should they take?
        val start = if (p.date != null && p.time != null) p.date.atTime(p.time) else null
        val eligible = source != Source.TASK_SHEET && p.repeat == Repeat.NONE && start != null && start.isAfter(now())
        val plan = if (!eligible) null
            else remote?.prepPlan(p.title, start!!, p.steps, p.lang) ?: Prep.think(p.title, p.lang == Lang.MS, userListed = p.steps.isNotEmpty())
        // Unrecognised offline: still ask for an event ("X at 8 am"), not for a plain "remind me at 5 pm".
        val askPrep = eligible && (plan?.let { it.leadMin > 0 } ?: (p.kind == CommandKind.TASK))
        val steps = if (askPrep) Prep.merge(p.steps, plan?.steps.orEmpty()) else p.steps
        val id = tasks.save(
            TaskEntity(
                title = (if (askPrep) plan?.title else null) ?: p.title,
                // Recognised as something to attend (not "submit X at 11 pm"): it only reminds, no to-do.
                event = askPrep && (plan?.leadMin ?: 0) > 0,
                important = p.important,
                dueDate = p.date,
                dueTime = p.time,
                durationMin = p.durationMin,
                repeat = p.repeat,
                repeatDays = p.repeatDays,
            ),
        )
        if (steps.isNotEmpty()) tasks.addSubtasks(id, steps)
        val t = tasks.get(id)!!
        val whenText = Fmt.whenText(r, t, now().toLocalDate())
        val repeat = Fmt.repeat(r, t)
        val detail = listOfNotNull(whenText, repeat).joinToString(" · ")
        val msg = when {
            t.dueTime != null -> r.getString(R.string.reminder_set, t.title, detail)
            detail.isNotEmpty() -> r.getString(R.string.task_added_when, t.title, detail)
            else -> r.getString(R.string.task_added, t.title)
        } + if (steps.isNotEmpty()) "\n" + r.getString(R.string.prep_steps, steps.joinToString(" · ")) else ""
        if (askPrep) {
            val suggested = plan?.leadMin?.takeIf { it > 0 }
            // The suggestion comes first so it is one tap away.
            val chips = (listOfNotNull(suggested) + PREP_LEADS).distinct().filter { start!!.minusMinutes(it.toLong()).isAfter(now()) }.map { Fmt.duration(r, it) }
            if (chips.isNotEmpty()) {
                pendingPrep = id
                suggestedPrep = suggested?.takeIf { chips.first() == Fmt.duration(r, it) }
                val ask = if (suggestedPrep != null) r.getString(R.string.ask_prep_suggest, t.title, chips.first()) else r.getString(R.string.ask_prep, t.title)
                return EngineResult(msg + "\n\n" + ask, chips = chips + r.getString(R.string.chip_no_alarm), undo = Undo.DeleteTask(id), taskId = id, lang = p.lang)
            }
        }
        return EngineResult(msg, undo = Undo.DeleteTask(id), taskId = id, lang = p.lang)
    }

    /** [lead] minutes before the start (0 = no alarm, -1 = keep the usual one). Steps go in the alarm text. */
    private suspend fun setPrep(id: Long, lead: Int, lang: Lang): EngineResult {
        val r = ctx.inLang(lang)
        val d = tasks.details(id)
        val start = d?.task?.let(Schedule::start)
        if (d == null || start == null || lead < 0) return EngineResult(r.getString(R.string.prep_kept), lang = lang)
        tasks.save(d.task.copy(autoPrep = false)) // drops the automatic one; the custom one below replaces it
        if (lead == 0) return EngineResult(r.getString(R.string.prep_none), taskId = id, lang = lang)
        val steps = d.subtasks.filter { !it.done }.sortedBy { it.position }.joinToString(" · ") { it.title }
        val text = r.getString(R.string.prep_msg, d.task.title, Fmt.duration(r, lead)) +
            if (steps.isNotEmpty()) "\n" + r.getString(R.string.prep_steps, steps) else ""
        tasks.addCue(CueEntity(taskId = id, kind = CueKind.CUSTOM, anchor = CueAnchor.START, offsetMin = lead, message = text))
        return EngineResult(r.getString(R.string.prep_set, Fmt.time(r, start.minusMinutes(lead.toLong()).toLocalTime())), taskId = id, lang = lang)
    }

    /** A saved place called exactly "shop" or "pharmacy" beats the generic errand. */
    private suspend fun savedPlaceNamed(word: String?): String? =
        word?.let { w -> db.places().all().firstOrNull { it.name.trim().equals(w, ignoreCase = true) }?.name }

    /** "Next time I'm at a pharmacy": no saved place, matched later by place type. */
    private suspend fun errandReminder(p: Parsed): EngineResult {
        val r = ctx.inLang(p.lang)
        val id = tasks.save(TaskEntity(title = p.title, important = p.important))
        tasks.addPlaceTypeCue(id, p.placeType!!)
        var msg = r.getString(R.string.errand_set, p.title, Fmt.placeTypeLabel(r, p.placeType))
        if (settings?.current()?.placeAwareness != true) msg += "\n\n" + r.getString(R.string.errand_needs_places)
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
        val msg = r.getString(if (p.placeEvent == PlaceEvent.LEAVE) R.string.place_set_leave else R.string.place_set_arrive, p.title, place.name) +
            if (places?.available?.value == false) "\n\n" + r.getString(R.string.places_accuracy_needed) else ""
        return EngineResult(msg, undo = Undo.DeleteTask(id), taskId = id, lang = p.lang)
    }

    private fun placeAlias(name: String) = when (name.lowercase()) {
        "rumah" -> "home"; "pejabat", "office" -> "work"; "kampus" -> "campus"; else -> name.lowercase()
    }

    suspend fun undo(u: Undo) {
        when (u) {
            is Undo.DeleteTask -> tasks.delete(u.id)
            is Undo.DeleteNote -> db.notes().get(u.id)?.let { notes.delete(it) }
            is Undo.DeleteMemory -> db.memories().get(u.id)?.let { memory.delete(it) }
            is Undo.Restore -> tasks.restore(u.snapshot)
            is Undo.DeleteSteps -> db.tasks().deleteSubtasks(u.ids)
            is Undo.Many -> u.items.asReversed().forEach { undo(it) }
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

    /** The task touched last (opened, edited, a step ticked) and its next step. */
    suspend fun recovery(): Recovery? {
        val active = db.tasks().lastActive() ?: return null
        val details = tasks.details(active.id) ?: return null
        val next = details.subtasks.sortedBy { it.position }.firstOrNull { !it.done }?.title
        return Recovery(details, details.task.title, next, active.lastActiveAt ?: 0L)
    }

    private suspend fun recoveryReply(r: Context, lang: Lang): EngineResult {
        val rec = recovery() ?: return EngineResult(r.getString(R.string.recovery_empty), lang = lang)
        return EngineResult(describeRecovery(r, rec), chips = listOf(r.getString(R.string.chip_open_task)), taskId = rec.task?.task?.id, lang = lang)
    }

    fun describeRecovery(r: Context, rec: Recovery): String {
        val parts = mutableListOf(r.getString(R.string.recovery_working_on, rec.title))
        rec.nextStep?.let { parts += r.getString(R.string.recovery_next_step, it) }
        parts += r.getString(R.string.recovery_continue_q)
        return parts.joinToString("\n\n")
    }

    private suspend fun todayReply(r: Context, lang: Lang, n: LocalDateTime): EngineResult {
        val today = n.toLocalDate()
        val open = tasks.openTasks().map { it.task }
        val ranked = Matrix.rankForNow(open.filter { it.dueDate != null && !it.dueDate.isAfter(today) }, n) +
            open.filter { it.event && it.dueDate == today && !Matrix.isOver(it, n) }.sortedBy { it.dueTime }
        if (ranked.isEmpty()) return EngineResult(r.getString(R.string.today_empty), lang = lang)
        val head = r.resources.getQuantityString(R.plurals.today_count, ranked.size, ranked.size)
        val list = ranked.take(5).joinToString("\n") { t -> (if (t.important) "★ " else "• ") + t.title + (t.dueTime?.let { " · " + Fmt.time(r, it) } ?: "") + if (Matrix.isOverdue(t, n)) " · " + r.getString(R.string.overdue).lowercase() else "" }
        return EngineResult("$head\n\n$list", lang = lang)
    }

    private suspend fun overwhelmedReply(r: Context, lang: Lang, n: LocalDateTime): EngineResult {
        val top = Matrix.rankForNow(tasks.openTasks().map { it.task }, n).firstOrNull()
            ?: return EngineResult(r.getString(R.string.overwhelmed_empty), lang = lang)
        return EngineResult(
            r.getString(R.string.overwhelmed_reply, top.title),
            chips = listOf(r.getString(R.string.chip_open_task)),
            taskId = top.id,
            lang = lang,
        )
    }

    private suspend fun freeTimeReply(r: Context, lang: Lang, n: LocalDateTime, minutes: Int): EngineResult {
        val ranked = Matrix.rankForNow(tasks.openTasks().map { it.task }, n)
        val fit = ranked.firstOrNull { (it.durationMin ?: Int.MAX_VALUE) <= minutes } ?: ranked.firstOrNull()
            ?: return EngineResult(r.getString(R.string.free_time_empty), lang = lang)
        val text = if ((fit.durationMin ?: Int.MAX_VALUE) <= minutes) r.getString(R.string.free_time_fits, fit.title, Fmt.duration(r, minutes))
        else r.getString(R.string.free_time_start, fit.title, Fmt.duration(r, minutes))
        return EngineResult(text, chips = listOf(r.getString(R.string.chip_open_task)), taskId = fit.id, lang = lang)
    }

    companion object {
        private val I = setOf(RegexOption.IGNORE_CASE)
        private val PREP_LEADS = listOf(15, 30, 60, 120)
        val CANCEL = Regex("^(cancel|never ?mind|stop|batal|tak jadi|tak payah)\\b", I)
        val SAVE_TO_INBOX = Regex("^(save (it )?to (my )?inbox|simpan dalam inbox|simpan ke inbox)", I)
        val FORGOT = Regex("(i forgot what i was doing|what was i doing|where was i|saya lupa apa saya buat|aku lupa apa aku buat|apa saya buat tadi|lupa apa (saya|aku) (tengah )?buat)", I)
        val TODAY = Regex("(what do i have( to do)? today|what('s| is) on today|what('s| is) my day|today'?s (tasks|plan)|what should i do today|apa (yang )?(saya|aku) (perlu|kena|nak) buat hari ini|apa plan hari ini|jadual hari ini)", I)
        val TOO_MANY = Regex("(too many things|so much to do|overwhelmed|i'?m stressed|banyak sangat|terlalu banyak|penat sangat|serabut)", I)
        val FREE_TIME = Regex("(?:i have|i've got|got|saya ada|aku ada|ada)\\s+(\\d+)\\s*(minutes?|mins?|hours?|hrs?|minit|jam)\\b", I)
        val DONE_WITH = Regex("^(?:i(?:'ve| have)? (?:finished|completed|done)|i'?m done with|mark (?:as )?done|done with|dah siap|sudah siap|siap)\\s+(.+)$", I)
        val HELLO = Regex("^(hi|hello|hey|hey friday|hi friday|hai|helo|assalamualaikum|salam)[!. ]*$")
        val THANKS = Regex("^(thanks|thank you|thx|terima kasih|tq)\\b", I)
        val HELP = Regex("^(help|what can you do|tolong|apa kau boleh buat|apa awak boleh buat)\\b", I)
    }
}

/** Optional online brain. Null when no AI is configured. */
interface RemoteAssistant {
    suspend fun respond(text: String, lang: Lang): EngineResult?
    suspend fun applyConfirmed(p: Proposal): Undo?
    /** How to get ready for an event; null when the AI is off or failed (FRIDAY then uses its offline rules). */
    suspend fun prepPlan(title: String, at: LocalDateTime, userSteps: List<String>, lang: Lang): PrepPlan?
}
