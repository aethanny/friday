package com.friday.app

import com.friday.app.data.db.SnoozeLogEntity
import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.SubtaskEntity
import com.friday.app.data.db.TaskEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.Habits
import com.friday.app.domain.ReminderWhy
import com.friday.app.domain.Why
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class SmartTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun log(ageDays: Int, h: Int, m: Int) = SnoozeLogEntity(taskId = 1, at = now - ageDays * day, targetMin = h * 60 + m)
    private val t17 = LocalTime.of(17, 0)

    @Test fun learnsConsistentSnoozeTime() =
        assertEquals(LocalTime.of(19, 0), Habits.learnedMove(listOf(log(1, 19, 5), log(2, 18, 50), log(3, 19, 10)), t17, now))

    @Test fun ignoresScatteredSnoozes() =
        assertNull(Habits.learnedMove(listOf(log(1, 19, 0), log(2, 21, 30), log(3, 15, 0)), t17, now))

    @Test fun needsThree() = assertNull(Habits.learnedMove(listOf(log(1, 19, 0), log(2, 19, 0)), t17, now))

    @Test fun ignoresOldEntries() =
        assertNull(Habits.learnedMove(listOf(log(40, 19, 0), log(41, 19, 0), log(42, 19, 0)), t17, now))

    @Test fun ignoresIfAlreadyNearCurrent() =
        assertNull(Habits.learnedMove(listOf(log(1, 17, 10), log(2, 17, 5), log(3, 17, 15)), t17, now))

    private val at = LocalDateTime.of(2026, 11, 20, 14, 0)
    private fun task(pc: Int = 0, subs: List<SubtaskEntity> = emptyList()) =
        TaskWithDetails(TaskEntity(id = 1, title = "Essay", postponeCount = pc), subs, emptyList())
    private fun cue(k: CueKind) = CueEntity(taskId = 1, kind = k, anchor = CueAnchor.START)
    private val steps = listOf(SubtaskEntity(taskId = 1, title = "second", position = 2), SubtaskEntity(taskId = 1, title = "first", position = 1), SubtaskEntity(taskId = 1, title = "x", done = true, position = 0))

    @Test fun whyPostponedWins() = assertEquals(Why.Postponed(3), ReminderWhy.pick(task(3, steps), cue(CueKind.START), emptyList(), at))

    @Test fun whyFirstStepForStart() = assertEquals(Why.FirstStep("first"), ReminderWhy.pick(task(0, steps), cue(CueKind.START), emptyList(), at))

    @Test fun whyNextForEnd() {
        val d = LocalDate.of(2026, 11, 20)
        val others = listOf(
            TaskEntity(id = 2, title = "Gym", dueDate = d, dueTime = LocalTime.of(16, 0)),
            TaskEntity(id = 3, title = "Call", dueDate = d, dueTime = LocalTime.of(15, 0)),
            TaskEntity(id = 4, title = "Far", dueDate = d, dueTime = LocalTime.of(20, 0)),
        )
        assertEquals(Why.Next("Call", LocalTime.of(15, 0)), ReminderWhy.pick(task(), cue(CueKind.END), others, at))
    }

    @Test fun whyNullOtherwise() {
        assertNull(ReminderWhy.pick(task(1, steps), cue(CueKind.END), emptyList(), at))
        assertNull(ReminderWhy.pick(task(), cue(CueKind.DAY), emptyList(), at))
    }
}
