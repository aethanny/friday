package com.friday.app.domain

import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.Repeat
import com.friday.app.data.db.TaskEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object Schedule {
    fun start(t: TaskEntity): LocalDateTime? =
        if (t.dueDate != null && t.dueTime != null) t.dueDate.atTime(t.dueTime) else null

    fun end(t: TaskEntity): LocalDateTime? = t.durationMin?.let { d -> start(t)?.plusMinutes(d.toLong()) }

    fun trigger(t: TaskEntity, c: CueEntity): LocalDateTime? = when (c.anchor) {
        CueAnchor.START -> start(t)?.minusMinutes(c.offsetMin.toLong())
        CueAnchor.END -> end(t)?.minusMinutes(c.offsetMin.toLong())
        CueAnchor.DAY -> t.dueDate?.atStartOfDay()?.plusMinutes(c.offsetMin.toLong())
        CueAnchor.LEAVE, CueAnchor.ARRIVE -> null
    }

    /** Zone-independent identity of a wall-clock occurrence. */
    fun key(t: LocalDateTime): Long = t.toEpochSecond(ZoneOffset.UTC)

    fun instant(t: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
        t.atZone(zone).toInstant().toEpochMilli()

    /** The reminders FRIDAY adds by itself, derived from the task's date, time and duration. */
    fun autoCues(t: TaskEntity, prepLeadMin: Int, dayReminderMin: Int): List<CueEntity> = buildList {
        when {
            t.dueTime != null && t.dueDate != null -> {
                if (t.autoPrep && prepLeadMin > 0) add(CueEntity(taskId = t.id, kind = CueKind.PREP, anchor = CueAnchor.START, offsetMin = prepLeadMin))
                add(CueEntity(taskId = t.id, kind = CueKind.START, anchor = CueAnchor.START))
                if (t.durationMin != null && t.durationMin > 0) add(CueEntity(taskId = t.id, kind = CueKind.END, anchor = CueAnchor.END))
            }
            t.dueDate != null -> add(CueEntity(taskId = t.id, kind = CueKind.DAY, anchor = CueAnchor.DAY, offsetMin = dayReminderMin))
        }
    }

    /** Next due date of a repeating task, always after both its current due date and today. */
    fun nextOccurrence(t: TaskEntity, today: LocalDate): LocalDate? {
        if (t.repeat == Repeat.NONE) return null
        var d = t.dueDate ?: today
        do {
            d = step(d, t.repeat, t.repeatDays)
        } while (!d.isAfter(today))
        return d
    }

    private fun step(d: LocalDate, r: Repeat, days: Int): LocalDate = when (r) {
        Repeat.NONE, Repeat.DAILY -> d.plusDays(1)
        Repeat.WEEKDAYS -> generateSequence(d.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek.value <= 5 }
        Repeat.WEEKLY -> if (days == 0) d.plusWeeks(1)
            else generateSequence(d.plusDays(1)) { it.plusDays(1) }.first { days and dayBit(it.dayOfWeek) != 0 }
        Repeat.MONTHLY -> {
            val m = d.plusMonths(1)
            val anchor = if (days in 1..31) days else d.dayOfMonth // days = anchor day-of-month; 0 on old tasks
            m.withDayOfMonth(minOf(anchor, m.lengthOfMonth()))
        }
    }

    fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)
}

enum class Quadrant { DO, PLAN, QUICK, LATER }

/** Eisenhower matrix: importance is the user's star, urgency comes from the due date. */
object Matrix {
    const val URGENT_DAYS = 2L

    fun isUrgent(t: TaskEntity, today: LocalDate): Boolean =
        t.dueDate != null && !t.dueDate.isAfter(today.plusDays(URGENT_DAYS))

    fun quadrant(t: TaskEntity, today: LocalDate): Quadrant {
        val urgent = isUrgent(t, today)
        return when {
            t.important && urgent -> Quadrant.DO
            t.important -> Quadrant.PLAN
            urgent -> Quadrant.QUICK
            else -> Quadrant.LATER
        }
    }

    /** An event whose time has passed: it leaves the lists by itself. Untimed events last their whole day. */
    fun isOver(t: TaskEntity, now: LocalDateTime): Boolean =
        t.event && (Schedule.end(t) ?: Schedule.start(t) ?: t.dueDate?.plusDays(1)?.atStartOfDay())?.isBefore(now) == true

    fun isOverdue(t: TaskEntity, now: LocalDateTime): Boolean {
        if (t.event) return false
        val due = t.dueDate ?: return false
        if (due.isBefore(now.toLocalDate())) return true
        val finish = Schedule.end(t) ?: Schedule.start(t) ?: return false
        return finish.isBefore(now)
    }

    /** Order for the Home "Now" card and its "Next" list. Lower tier wins. Events are not to-dos and are left out. */
    fun rankForNow(all: List<TaskEntity>, now: LocalDateTime): List<TaskEntity> {
        val tasks = all.filter { !it.event }
        val today = now.toLocalDate()
        fun tier(t: TaskEntity): Int {
            val start = Schedule.start(t)
            // Scheduled activities (with a duration) can't be done yet; a clock time without duration is a deadline.
            if (start != null && t.durationMin != null && start.isAfter(now.plusMinutes(60))) return 5
            if (start != null && !start.isAfter(now.plusMinutes(60)) && !isOverdue(t, now) && !start.toLocalDate().isBefore(today)) return 1
            return when (quadrant(t, today)) {
                Quadrant.DO -> 0
                Quadrant.QUICK -> 2
                Quadrant.PLAN -> 3
                Quadrant.LATER -> 4
            }
        }
        return tasks.sortedWith(
            compareBy<TaskEntity> { tier(it) }
                .thenBy { if (isOverdue(it, now)) 0 else 1 }
                .thenBy { it.dueDate == null }
                .thenBy { it.dueDate }
                .thenBy { it.dueTime }
                .thenByDescending { it.postponeCount }
                .thenBy { it.createdAt },
        )
    }

    enum class Reason { OVERDUE, STARTS_SOON, DUE_TODAY, DUE_TOMORROW, IMPORTANT, POSTPONED, NONE }

    /** Short, explainable reason a task is where it is. */
    fun reason(t: TaskEntity, now: LocalDateTime): Reason {
        val today = now.toLocalDate()
        val start = Schedule.start(t)
        return when {
            isOverdue(t, now) -> Reason.OVERDUE
            start != null && start.toLocalDate() == today && !start.isAfter(now.plusMinutes(60)) -> Reason.STARTS_SOON
            t.dueDate == today -> Reason.DUE_TODAY
            t.dueDate == today.plusDays(1) -> Reason.DUE_TOMORROW
            t.postponeCount >= 3 -> Reason.POSTPONED
            t.important -> Reason.IMPORTANT
            else -> Reason.NONE
        }
    }
}

object QuietHours {
    fun contains(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
    }

    /** First moment at or after [now] that is outside quiet hours. */
    fun nextAllowed(now: LocalDateTime, start: Int, end: Int): LocalDateTime {
        val m = now.hour * 60 + now.minute
        if (!contains(m, start, end)) return now
        val endToday = now.toLocalDate().atStartOfDay().plusMinutes(end.toLong())
        return if (endToday.isAfter(now)) endToday else endToday.plusDays(1)
    }
}
