package com.friday.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Transaction
    @Query("SELECT * FROM tasks WHERE completedAt IS NULL ORDER BY dueDate IS NULL, dueDate, dueTime IS NULL, dueTime, createdAt")
    fun observeOpen(): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE completedAt IS NOT NULL ORDER BY completedAt DESC LIMIT 200")
    fun observeCompleted(): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): Flow<TaskWithDetails?>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getDetails(id: Long): TaskWithDetails?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Transaction
    @Query("SELECT * FROM tasks WHERE completedAt IS NULL")
    suspend fun openTasks(): List<TaskWithDetails>

    /** Most recent other task with the same title that has custom reminders, for "use the same reminders as last time". */
    @Transaction
    @Query(
        """SELECT * FROM tasks WHERE id != :excludeId AND LOWER(TRIM(title)) = LOWER(TRIM(:title))
           AND id IN (SELECT taskId FROM cues WHERE kind = 'CUSTOM') ORDER BY createdAt DESC LIMIT 1""",
    )
    suspend fun lastWithSameTitle(title: String, excludeId: Long): TaskWithDetails?

    @Query("SELECT DISTINCT category FROM tasks WHERE category IS NOT NULL AND category != '' ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT * FROM tasks WHERE lastActiveAt IS NOT NULL ORDER BY lastActiveAt DESC LIMIT 1")
    suspend fun lastActive(): TaskEntity?

    @Insert suspend fun insert(task: TaskEntity): Long
    @Update suspend fun update(task: TaskEntity)
    @Delete suspend fun delete(task: TaskEntity)

    @Query("UPDATE tasks SET lastActiveAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long = System.currentTimeMillis())

    @Insert suspend fun insertSubtask(s: SubtaskEntity): Long
    @Insert suspend fun insertSubtasks(s: List<SubtaskEntity>): List<Long>

    @Query("DELETE FROM subtasks WHERE id IN (:ids)")
    suspend fun deleteSubtasks(ids: List<Long>)
    @Update suspend fun updateSubtask(s: SubtaskEntity)
    @Delete suspend fun deleteSubtask(s: SubtaskEntity)

    @Query("UPDATE subtasks SET done = 0 WHERE taskId = :taskId")
    suspend fun resetSubtasks(taskId: Long)

    @Insert suspend fun insertCue(c: CueEntity): Long
    @Insert suspend fun insertCues(c: List<CueEntity>): List<Long>
    @Update suspend fun updateCue(c: CueEntity)
    @Delete suspend fun deleteCue(c: CueEntity)

    @Query("SELECT * FROM cues WHERE id = :id")
    suspend fun getCue(id: Long): CueEntity?

    @Query("SELECT * FROM cues WHERE taskId = :taskId")
    suspend fun cuesFor(taskId: Long): List<CueEntity>

    @Query("SELECT * FROM cues")
    suspend fun allCues(): List<CueEntity>

    @Query("UPDATE cues SET firedKey = NULL, ackKey = NULL, snoozedUntil = NULL, firedAt = NULL WHERE taskId = :taskId")
    suspend fun resetCueState(taskId: Long)

    @Query("SELECT * FROM cues WHERE firedAt >= :since AND taskId != :exceptTaskId ORDER BY firedAt")
    suspend fun firedSince(since: Long, exceptTaskId: Long): List<CueEntity>

    @Insert suspend fun logSnooze(e: SnoozeLogEntity)

    @Query("SELECT * FROM snooze_log WHERE taskId = :taskId AND at >= :since ORDER BY at DESC")
    suspend fun snoozesFor(taskId: Long, since: Long): List<SnoozeLogEntity>

    @Query("DELETE FROM snooze_log WHERE taskId = :taskId")
    suspend fun clearSnoozes(taskId: Long)

    @Query("SELECT * FROM cues WHERE held = 1")
    suspend fun heldCues(): List<CueEntity>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    suspend fun all(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: Long): MemoryEntity?

    @Insert suspend fun insert(m: MemoryEntity): Long
    @Update suspend fun update(m: MemoryEntity)
    @Delete suspend fun delete(m: MemoryEntity)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY inbox DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE inbox = 1")
    fun observeInboxCount(): Flow<Int>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?

    @Insert suspend fun insert(note: NoteEntity): Long
    @Update suspend fun update(note: NoteEntity)
    @Delete suspend fun delete(note: NoteEntity)
}

@Dao
interface FocusDao {
    @Query("SELECT * FROM focus_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun latest(): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun get(id: Long): FocusSessionEntity?

    @Insert suspend fun insert(s: FocusSessionEntity): Long
    @Update suspend fun update(s: FocusSessionEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM (SELECT * FROM messages ORDER BY createdAt DESC LIMIT 200) ORDER BY createdAt")
    fun observeRecent(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :n")
    suspend fun lastN(n: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Insert suspend fun insert(m: MessageEntity): Long
    @Update suspend fun update(m: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY name")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places")
    suspend fun all(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun get(id: Long): PlaceEntity?

    @Insert suspend fun insert(p: PlaceEntity): Long
    @Update suspend fun update(p: PlaceEntity)
    @Delete suspend fun delete(p: PlaceEntity)
}
