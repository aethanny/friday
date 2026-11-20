package com.friday.app.data

import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.FridayDatabase
import com.friday.app.data.db.MemoryEntity
import com.friday.app.data.db.MemorySource
import com.friday.app.data.db.NoteEntity
import com.friday.app.data.db.Repeat
import com.friday.app.data.db.SubtaskEntity
import com.friday.app.data.db.TaskEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.Schedule
import com.friday.app.reminders.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Everything needed to put a task back exactly as it was. */
data class TaskSnapshot(val details: TaskWithDetails, val historyId: Long? = null)

class TaskRepository(
    private val db: FridayDatabase,
    private val settings: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val dao = db.tasks()

    fun observeOpen() = dao.observeOpen()
    fun observeCompleted() = dao.observeCompleted()
    fun observe(id: Long): Flow<TaskWithDetails?> = dao.observe(id)
    fun observeCategories() = dao.observeCategories()
    suspend fun get(id: Long) = dao.get(id)
    suspend fun details(id: Long) = dao.getDetails(id)
    suspend fun openTasks() = dao.openTasks()

    /** Creates or updates a task, keeps its automatic reminders in step with its schedule and re-arms alarms. */
    suspend fun save(task: TaskEntity, keepMonthlyAnchor: Boolean = false): Long {
        val s = settings.current()
        var t = task.copy(title = task.title.trim().take(200).ifBlank { "Untitled" }, category = task.category?.trim()?.ifBlank { null })
        if (t.repeat != Repeat.NONE && t.dueDate == null) t = t.copy(dueDate = today())
        if (t.dueDate == null) t = t.copy(dueTime = null)
        val old = if (t.id != 0L) dao.get(t.id) else null
        if (t.repeat == Repeat.MONTHLY) {
            // anchor day follows the due date, except when postponing (keeps the original day, e.g. the 31st)
            val unchanged = old != null && old.repeat == Repeat.MONTHLY && old.dueDate == t.dueDate && old.repeatDays in 1..31
            val keep = keepMonthlyAnchor && t.repeatDays in 1..31
            t = when {
                unchanged -> t.copy(repeatDays = old!!.repeatDays) // editor may send 0; the stored anchor wins
                keep -> t
                else -> t.copy(repeatDays = t.dueDate!!.dayOfMonth)
            }
        }
        val id = if (old == null) {
            dao.insert(t.copy(id = 0))
        } else {
            val postponed = old.dueDate != null && t.dueDate != null && t.dueDate.isAfter(old.dueDate)
            dao.update(t.copy(postponeCount = old.postponeCount + if (postponed) 1 else 0))
            t.id
        }
        val saved = dao.get(id)!!
        val scheduleChanged = old == null || old.dueDate != saved.dueDate || old.dueTime != saved.dueTime || old.durationMin != saved.durationMin
        syncAutoCues(saved, s, scheduleChanged)
        scheduler.syncTask(id)
        return id
    }

    private suspend fun syncAutoCues(t: TaskEntity, s: AppSettings, scheduleChanged: Boolean) {
        val existing = dao.cuesFor(t.id)
        val wanted = Schedule.autoCues(t, s.prepLeadMin, s.dayReminderMin).associateBy { it.kind }
        for (kind in listOf(CueKind.PREP, CueKind.START, CueKind.END, CueKind.DAY)) {
            val have = existing.firstOrNull { it.kind == kind }
            val want = wanted[kind]
            when {
                want == null && have != null -> { scheduler.cancel(have.id); dao.deleteCue(have) }
                want != null && have == null -> dao.insertCue(want)
                want != null && have != null && (have.offsetMin != want.offsetMin || have.anchor != want.anchor || scheduleChanged) ->
                    dao.updateCue(have.copy(anchor = want.anchor, offsetMin = want.offsetMin, firedKey = null, ackKey = null, snoozedUntil = null, held = false))
            }
        }
        if (scheduleChanged) existing.filter { it.kind == CueKind.CUSTOM }.forEach {
            dao.updateCue(it.copy(firedKey = null, ackKey = null, snoozedUntil = null, held = false))
        }
    }

    /** Completes a task. Repeating tasks keep a completed copy for history and move to their next date. */
    suspend fun complete(id: Long): TaskSnapshot? {
        val d = dao.getDetails(id) ?: return null
        val t = d.task
        if (t.completedAt != null) return null
        val now = System.currentTimeMillis()
        val next = Schedule.nextOccurrence(t, today())
        var historyId: Long? = null
        if (next != null) {
            historyId = dao.insert(t.copy(id = 0, completedAt = now, repeat = Repeat.NONE, repeatDays = 0, autoPrep = false, lastActiveAt = null))
            dao.update(t.copy(dueDate = next, lastActiveAt = now, postponeCount = 0))
            dao.resetSubtasks(id)
            dao.resetCueState(id)
        } else {
            dao.update(t.copy(completedAt = now, lastActiveAt = now))
        }
        scheduler.syncTask(id)
        return TaskSnapshot(d, historyId)
    }

    suspend fun reopen(id: Long) {
        val t = dao.get(id) ?: return
        dao.update(t.copy(completedAt = null))
        dao.resetCueState(id)
        scheduler.syncTask(id)
    }

    suspend fun delete(id: Long): TaskSnapshot? {
        val d = dao.getDetails(id) ?: return null
        d.cues.forEach { scheduler.cancel(it.id) }
        dao.delete(d.task)
        if (d.cues.any { it.placeId != null }) scheduler.syncPlaces()
        return TaskSnapshot(d)
    }

    /** Undo for [complete] and [delete]: puts the task, its steps and reminders back. */
    suspend fun restore(s: TaskSnapshot) {
        s.historyId?.let { h -> dao.get(h)?.let { dao.delete(it) } }
        val t = s.details.task
        if (dao.get(t.id) != null) {
            dao.update(t)
            s.details.subtasks.forEach { dao.updateSubtask(it) }
        } else {
            dao.insert(t)
            dao.insertSubtasks(s.details.subtasks)
            dao.insertCues(s.details.cues)
        }
        scheduler.syncTask(t.id)
    }

    suspend fun setImportant(id: Long, important: Boolean) {
        dao.get(id)?.let { dao.update(it.copy(important = important)) }
    }

    /** Moves a task's due date by [days]; counts as a postponement. */
    suspend fun postpone(id: Long, days: Long = 1) {
        val t = dao.get(id) ?: return
        save(t.copy(dueDate = (t.dueDate ?: today()).plusDays(days)), keepMonthlyAnchor = true)
    }

    suspend fun touch(id: Long) = dao.touch(id)

    // Steps
    suspend fun addSubtask(taskId: Long, title: String) {
        val pos = dao.getDetails(taskId)?.subtasks?.size ?: 0
        dao.insertSubtask(SubtaskEntity(taskId = taskId, title = title.trim().take(200), position = pos))
        dao.touch(taskId)
    }

    suspend fun addSubtasks(taskId: Long, titles: List<String>): List<Long> {
        val pos = dao.getDetails(taskId)?.subtasks?.size ?: 0
        return dao.insertSubtasks(titles.filter { it.isNotBlank() }.mapIndexed { i, s -> SubtaskEntity(taskId = taskId, title = s.trim().take(200), position = pos + i) })
    }

    suspend fun setSubtaskDone(s: SubtaskEntity, done: Boolean) {
        dao.updateSubtask(s.copy(done = done))
        dao.touch(s.taskId)
    }

    suspend fun renameSubtask(s: SubtaskEntity, title: String) = dao.updateSubtask(s.copy(title = title.trim().take(200)))
    suspend fun deleteSubtask(s: SubtaskEntity) = dao.deleteSubtask(s)

    // Reminders
    suspend fun addCue(cue: CueEntity): Long {
        val id = dao.insertCue(cue.copy(id = 0, kind = CueKind.CUSTOM, message = cue.message.trim().take(300)))
        scheduler.syncTask(cue.taskId)
        return id
    }

    suspend fun updateCue(cue: CueEntity) {
        dao.updateCue(cue.copy(message = cue.message.trim().take(300), firedKey = null, ackKey = null, snoozedUntil = null, held = false))
        scheduler.syncTask(cue.taskId)
    }

    suspend fun deleteCue(cue: CueEntity) {
        scheduler.cancel(cue.id)
        if (cue.kind == CueKind.PREP) {
            dao.get(cue.taskId)?.let { dao.update(it.copy(autoPrep = false)) }
        }
        dao.deleteCue(cue)
    }

    suspend fun setAutoPrep(taskId: Long, enabled: Boolean) {
        dao.get(taskId)?.let { save(it.copy(autoPrep = enabled)) }
    }

    /** Custom reminders of the latest other task with the same name, for "use the same reminders as last time". */
    suspend fun reusableCues(taskId: Long, title: String): List<CueEntity> =
        dao.lastWithSameTitle(title, taskId)?.cues?.filter { it.kind == CueKind.CUSTOM }.orEmpty()

    suspend fun copyCues(taskId: Long, cues: List<CueEntity>) {
        dao.insertCues(cues.map { it.copy(id = 0, taskId = taskId, firedKey = null, ackKey = null, snoozedUntil = null, held = false, firedAt = null) })
        scheduler.syncTask(taskId)
    }

    suspend fun addPlaceCue(taskId: Long, placeId: Long, leave: Boolean, message: String = ""): Long {
        val id = dao.insertCue(CueEntity(taskId = taskId, kind = CueKind.CUSTOM, anchor = if (leave) CueAnchor.LEAVE else CueAnchor.ARRIVE, placeId = placeId, message = message))
        scheduler.syncTask(taskId)
        return id
    }
}

class NoteRepository(private val db: FridayDatabase) {
    private val dao = db.notes()
    fun observeAll() = dao.observeAll()
    fun observeInboxCount() = dao.observeInboxCount()
    suspend fun add(text: String, inbox: Boolean = true): Long = dao.insert(NoteEntity(text = text.trim().take(4000), inbox = inbox))
    suspend fun update(note: NoteEntity, text: String) = dao.update(note.copy(text = text.trim().take(4000), updatedAt = System.currentTimeMillis()))
    suspend fun keep(note: NoteEntity) = dao.update(note.copy(inbox = false, updatedAt = System.currentTimeMillis()))
    suspend fun delete(note: NoteEntity) = dao.delete(note)
    suspend fun restore(note: NoteEntity) = dao.insert(note)
}

class MemoryRepository(db: FridayDatabase) {
    private val dao = db.memories()
    fun observeAll() = dao.observeAll()
    suspend fun all() = dao.all()

    /** Trims to 200 chars; returns null if blank, a case-insensitive duplicate, or 100 memories already exist. */
    suspend fun add(text: String, source: MemorySource): Long? {
        val t = text.trim().take(200)
        val existing = dao.all()
        if (t.isBlank() || existing.size >= 100 || existing.any { it.text.trim().equals(t, ignoreCase = true) }) return null
        return dao.insert(MemoryEntity(text = t, source = source))
    }

    suspend fun edit(m: MemoryEntity, text: String) {
        val t = text.trim().take(200)
        if (t.isNotBlank()) dao.update(m.copy(text = t))
    }

    suspend fun delete(m: MemoryEntity) = dao.delete(m)
    suspend fun restore(m: MemoryEntity) = dao.insert(m)
}
