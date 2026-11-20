package com.friday.app

import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.FocusSessionEntity
import com.friday.app.data.db.Repeat
import com.friday.app.data.db.TaskEntity
import com.friday.app.domain.FocusMath
import com.friday.app.domain.Matrix
import com.friday.app.domain.Quadrant
import com.friday.app.domain.QuietHours
import com.friday.app.domain.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class LogicTest {
    private val today = LocalDate.of(2026, 11, 18) // Wednesday
    private val now = today.atTime(10, 0)

    private fun task(
        title: String = "t", important: Boolean = false, due: LocalDate? = null, time: LocalTime? = null,
        duration: Int? = null, repeat: Repeat = Repeat.NONE, days: Int = 0, id: Long = 1,
    ) = TaskEntity(id = id, title = title, important = important, dueDate = due, dueTime = time, durationMin = duration, repeat = repeat, repeatDays = days, createdAt = 0)

    @Test fun quadrants() {
        assertEquals(Quadrant.DO, Matrix.quadrant(task(important = true, due = today), today))
        assertEquals(Quadrant.DO, Matrix.quadrant(task(important = true, due = today.plusDays(2)), today))
        assertEquals(Quadrant.PLAN, Matrix.quadrant(task(important = true, due = today.plusDays(3)), today))
        assertEquals(Quadrant.PLAN, Matrix.quadrant(task(important = true), today))
        assertEquals(Quadrant.QUICK, Matrix.quadrant(task(due = today.minusDays(1)), today))
        assertEquals(Quadrant.LATER, Matrix.quadrant(task(), today))
    }

    @Test fun nowRankingPutsUrgentImportantFirstAndFutureTimedLast() {
        val bill = task("bill", due = today, id = 1)
        val poster = task("poster", important = true, due = today, id = 2)
        val guitar = task("guitar", important = true, id = 3)
        val live = task("live", important = true, due = today, time = LocalTime.of(21, 0), duration = 60, id = 4)
        val report = task("report", important = true, due = today, time = LocalTime.of(17, 0), id = 5)
        val ranked = Matrix.rankForNow(listOf(guitar, live, bill, report, poster), now).map { it.title }
        // Deadlines rank by quadrant; a scheduled activity later today waits until it is near.
        assertEquals(listOf("poster", "report", "bill", "guitar", "live"), ranked)
    }

    @Test fun overdueRules() {
        assertTrue(Matrix.isOverdue(task(due = today.minusDays(1)), now))
        assertTrue(Matrix.isOverdue(task(due = today, time = LocalTime.of(9, 0)), now))
        assertFalse(Matrix.isOverdue(task(due = today, time = LocalTime.of(9, 0), duration = 120), now))
        assertFalse(Matrix.isOverdue(task(due = today), now))
    }

    @Test fun autoCuesForTimedTaskWithDuration() {
        val cues = Schedule.autoCues(task(due = today, time = LocalTime.of(21, 0), duration = 60), prepLeadMin = 15, dayReminderMin = 540)
        assertEquals(listOf(CueKind.PREP, CueKind.START, CueKind.END), cues.map { it.kind })
        val t = task(due = today, time = LocalTime.of(21, 0), duration = 60)
        assertEquals(today.atTime(20, 45), Schedule.trigger(t, cues[0]))
        assertEquals(today.atTime(21, 0), Schedule.trigger(t, cues[1]))
        assertEquals(today.atTime(22, 0), Schedule.trigger(t, cues[2]))
    }

    @Test fun customCueBeforeEnd() {
        val t = task(due = today, time = LocalTime.of(21, 0), duration = 60)
        val cue = CueEntity(taskId = 1, kind = CueKind.CUSTOM, anchor = CueAnchor.END, offsetMin = 5, message = "Screenshot analytics")
        assertEquals(today.atTime(21, 55), Schedule.trigger(t, cue))
    }

    @Test fun dateOnlyTaskGetsMorningReminder() {
        val t = task(due = today)
        val cues = Schedule.autoCues(t, 15, 540)
        assertEquals(listOf(CueKind.DAY), cues.map { it.kind })
        assertEquals(today.atTime(9, 0), Schedule.trigger(t, cues[0]))
        assertTrue(Schedule.autoCues(task(), 15, 540).isEmpty())
    }

    @Test fun recurrence() {
        assertEquals(today.plusDays(1), Schedule.nextOccurrence(task(due = today, repeat = Repeat.DAILY), today))
        // Completed late: next occurrence is after today, not in the past.
        assertEquals(today.plusDays(1), Schedule.nextOccurrence(task(due = today.minusDays(5), repeat = Repeat.DAILY), today))
        val fri = LocalDate.of(2026, 11, 20)
        assertEquals(LocalDate.of(2026, 11, 23), Schedule.nextOccurrence(task(due = fri, repeat = Repeat.WEEKDAYS), fri))
        val monThu = Schedule.dayBit(DayOfWeek.MONDAY) or Schedule.dayBit(DayOfWeek.THURSDAY)
        assertEquals(LocalDate.of(2026, 11, 19), Schedule.nextOccurrence(task(due = LocalDate.of(2026, 11, 16), repeat = Repeat.WEEKLY, days = monThu), today))
        assertEquals(LocalDate.of(2026, 12, 18), Schedule.nextOccurrence(task(due = today, repeat = Repeat.MONTHLY), today))
        assertNull(Schedule.nextOccurrence(task(due = today), today))
    }

    @Test fun reminderKeepsWallClockAcrossTimeZones() {
        val t = task(due = today, time = LocalTime.of(9, 0))
        val start = Schedule.start(t)!!
        val kl = Schedule.instant(start, ZoneId.of("Asia/Kuala_Lumpur"))
        val tokyo = Schedule.instant(start, ZoneId.of("Asia/Tokyo"))
        assertNotEquals(kl, tokyo)
        assertEquals(60 * 60 * 1000L, kl - tokyo)
        // The occurrence key does not depend on the zone, so a fired reminder is not repeated after travel.
        assertEquals(Schedule.key(start), Schedule.key(LocalDateTime.of(today, LocalTime.of(9, 0))))
    }

    @Test fun quietHoursAcrossMidnight() {
        assertTrue(QuietHours.contains(23 * 60 + 30, 23 * 60, 7 * 60))
        assertTrue(QuietHours.contains(3 * 60, 23 * 60, 7 * 60))
        assertFalse(QuietHours.contains(7 * 60, 23 * 60, 7 * 60))
        assertEquals(today.plusDays(1).atTime(7, 0), QuietHours.nextAllowed(today.atTime(23, 30), 23 * 60, 7 * 60))
        assertEquals(today.atTime(7, 0), QuietHours.nextAllowed(today.atTime(2, 0), 23 * 60, 7 * 60))
        assertEquals(now, QuietHours.nextAllowed(now, 23 * 60, 7 * 60))
    }

    @Test fun focusCountdownSurvivesReopenAndPause() {
        val start = 1_000_000L
        val s = FocusSessionEntity(taskId = 1, taskTitle = "x", plannedMin = 25, startedAt = start)
        // App closed and reopened 10 minutes later: remaining time comes from timestamps.
        assertEquals(15 * 60_000L, FocusMath.remainingMs(s, start + 10 * 60_000L))
        val paused = s.copy(pausedAt = start + 10 * 60_000L)
        assertEquals(15 * 60_000L, FocusMath.remainingMs(paused, start + 30 * 60_000L))
        assertNull(FocusMath.endsAt(paused))
        val resumed = paused.copy(pausedAt = null, pausedTotalMs = 20 * 60_000L)
        assertEquals(start + 45 * 60_000L, FocusMath.endsAt(resumed))
        assertEquals(0L, FocusMath.remainingMs(s, start + 99 * 60_000L))
    }

    @Test fun onlyARunningSessionSilencesOtherReminders() {
        val start = 1_000_000L
        val s = FocusSessionEntity(taskId = 1, taskTitle = "x", plannedMin = 25, startedAt = start)
        assertTrue(FocusMath.silencesOthers(s, start + 60_000))
        // Paused, finished-but-not-checked-in, or no session: reminders ring normally.
        assertFalse(FocusMath.silencesOthers(s.copy(pausedAt = start + 60_000), start + 120_000))
        assertFalse(FocusMath.silencesOthers(s, start + 26 * 60_000L))
        assertFalse(FocusMath.silencesOthers(null, start))
    }

    @Test fun monthlyKeepsAnchorDay() {
        val t = task(due = LocalDate.of(2027, 1, 31), repeat = Repeat.MONTHLY, days = 31)
        val feb = Schedule.nextOccurrence(t, LocalDate.of(2027, 1, 31))
        assertEquals(LocalDate.of(2027, 2, 28), feb)
        assertEquals(LocalDate.of(2027, 3, 31), Schedule.nextOccurrence(t.copy(dueDate = feb), feb!!))
    }
}
