package com.friday.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.time.LocalDate
import java.time.LocalTime

enum class Repeat { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY }

/**
 * Dates and times are stored as local wall-clock values (epoch day / minute of day):
 * "9 PM" keeps meaning 9 PM wherever the phone is. Instants are derived at scheduling time.
 */
@Entity(
    tableName = "tasks",
    indices = [Index("completedAt"), Index("dueDate"), Index("title")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val category: String? = null,
    val important: Boolean = false,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val durationMin: Int? = null,
    val repeat: Repeat = Repeat.NONE,
    /** WEEKLY only: bit 0 = Monday … bit 6 = Sunday. */
    val repeatDays: Int = 0,
    /** Automatic "get ready" reminder before timed tasks. */
    val autoPrep: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val postponeCount: Int = 0,
    val lastActiveAt: Long? = null,
    /** Something to attend (a lesson, an appointment), not a to-do: it only reminds, is never overdue and ends by itself. */
    @ColumnInfo(defaultValue = "0") val event: Boolean = false,
)

@Entity(
    tableName = "subtasks",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class SubtaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val title: String,
    val done: Boolean = false,
    val position: Int = 0,
)

/** START/END: [CueEntity.offsetMin] minutes before. DAY: minute of day on the due date (negative = evening before). */
enum class CueAnchor { START, END, DAY, LEAVE, ARRIVE }

/** PREP/START/END/DAY are generated from the task's schedule; CUSTOM ones are the user's own. */
enum class CueKind { PREP, START, END, DAY, CUSTOM }

@Entity(
    tableName = "cues",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId"), Index("placeId")],
)
data class CueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val kind: CueKind,
    val anchor: CueAnchor,
    val offsetMin: Int = 0,
    val message: String = "",
    val sound: Boolean = true,
    val placeId: Long? = null,
    val snoozedUntil: Long? = null,
    /** Floating key of the occurrence already fired, so the same occurrence never fires twice. */
    val firedKey: Long? = null,
    /** Occurrence the user already handled (done, snoozed or opened): no follow-up nudge. */
    val ackKey: Long? = null,
    val firedAt: Long? = null,
    /** AI-prepared reminder line. */
    @ColumnInfo(defaultValue = "NULL") val smartText: String? = null,
    /** Occurrence key [smartText] was written for. */
    @ColumnInfo(defaultValue = "NULL") val smartKey: Long? = null,
    /** Waiting for focus end / leaving a hold place. */
    @ColumnInfo(defaultValue = "0") val held: Boolean = false,
    /** Errand reminders: "STORE" | "PHARMACY" | "SUPERMARKET". */
    @ColumnInfo(defaultValue = "NULL") val placeType: String? = null,
)

data class TaskWithDetails(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "id", entityColumn = "taskId") val subtasks: List<SubtaskEntity>,
    @Relation(parentColumn = "id", entityColumn = "taskId") val cues: List<CueEntity>,
)

@Entity(tableName = "notes", indices = [Index("inbox")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** True = still unsorted (Inbox). False = kept as a note. */
    val inbox: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class FocusOutcome { COMPLETED, PROGRESS, DISTRACTED }

@Entity(
    tableName = "focus_sessions",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("taskId"), Index("endedAt")],
)
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    /** Snapshot so Context Recovery still works if the task is deleted. */
    val taskTitle: String,
    val plannedMin: Int,
    val startedAt: Long,
    val pausedAt: Long? = null,
    val pausedTotalMs: Long = 0,
    val endedAt: Long? = null,
    val outcome: FocusOutcome? = null,
    val stopNote: String? = null,
    val shield: Boolean = false,
    val dnd: Boolean = false,
)

@Entity(tableName = "messages", indices = [Index("createdAt")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromUser: Boolean,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** JSON of proposed/applied actions (see ai.Actions). */
    val actionJson: String? = null,
    val actionState: ActionState? = null,
    /** Quick-reply chips offered with this message, joined by '\n'. */
    val chips: String? = null,
    val remote: Boolean = false,
)

enum class ActionState { PENDING, APPLIED, UNDONE, DECLINED }

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Float = 150f,
)

enum class MemorySource { USER, CHAT, HABIT }

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: MemorySource,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "snooze_log",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class SnoozeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val at: Long,
    /** Minute of day the reminder was snoozed to. */
    val targetMin: Int,
)
