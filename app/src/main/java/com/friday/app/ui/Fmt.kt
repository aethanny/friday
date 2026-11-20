package com.friday.app.ui

import android.content.Context
import android.text.format.DateFormat
import com.friday.app.R
import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.Repeat
import com.friday.app.data.db.TaskEntity
import com.friday.app.domain.Matrix
import com.friday.app.domain.Schedule
import com.friday.app.domain.Why
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Human-readable dates, times and task summaries in the app's current language. */
object Fmt {
    fun locale(ctx: Context): Locale = ctx.resources.configuration.locales[0]

    fun time(ctx: Context, t: LocalTime): String =
        t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a", locale(ctx)))

    fun minuteOfDay(ctx: Context, m: Int): String = time(ctx, LocalTime.of((m / 60) % 24, m % 60))

    fun day(ctx: Context, date: LocalDate, today: LocalDate = LocalDate.now()): String =
        when (val diff = ChronoUnit.DAYS.between(today, date)) {
            0L -> ctx.getString(R.string.today)
            1L -> ctx.getString(R.string.tomorrow)
            -1L -> ctx.getString(R.string.yesterday)
            in 2L..6L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale(ctx))
            else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year || diff in -300L..300L) "d MMM" else "d MMM yyyy", locale(ctx)))
        }

    fun longDate(ctx: Context, date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale(ctx)))

    fun duration(ctx: Context, min: Int): String = when {
        min < 60 -> ctx.getString(R.string.dur_min, min)
        min % 60 == 0 -> ctx.getString(R.string.dur_h, min / 60)
        else -> ctx.getString(R.string.dur_h_min, min / 60, min % 60)
    }

    fun weekdays(ctx: Context, bits: Int): String = DayOfWeek.entries
        .filter { bits and Schedule.dayBit(it) != 0 }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale(ctx)) }

    fun repeat(ctx: Context, t: TaskEntity): String? = when (t.repeat) {
        Repeat.NONE -> null
        Repeat.DAILY -> ctx.getString(R.string.repeat_daily)
        Repeat.WEEKDAYS -> ctx.getString(R.string.repeat_weekdays)
        Repeat.WEEKLY -> ctx.getString(R.string.repeat_weekly_on, weekdays(ctx, t.repeatDays))
        Repeat.MONTHLY -> ctx.getString(R.string.repeat_monthly)
    }

    fun whenText(ctx: Context, t: TaskEntity, today: LocalDate = LocalDate.now()): String? {
        val d = t.dueDate ?: return null
        val day = day(ctx, d, today)
        return if (t.dueTime != null) "$day · ${time(ctx, t.dueTime)}" else day
    }

    /** "Overdue · Yesterday · 3:00 PM · 1 h · Weekly on Mon · 2/5 steps" */
    fun taskMeta(ctx: Context, t: TaskEntity, now: LocalDateTime = LocalDateTime.now(), stepsDone: Int = 0, stepsTotal: Int = 0): String? {
        val parts = buildList {
            if (t.completedAt == null && Matrix.isOverdue(t, now)) add(ctx.getString(R.string.overdue))
            whenText(ctx, t, now.toLocalDate())?.let(::add)
            t.durationMin?.let { add(duration(ctx, it)) }
            repeat(ctx, t)?.let(::add)
            if (stepsTotal > 0) add(ctx.getString(R.string.steps_progress, stepsDone, stepsTotal))
            t.category?.takeIf { it.isNotBlank() }?.let(::add)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun placeTypeLabel(ctx: Context, type: String): String = ctx.getString(
        when (type) { "PHARMACY" -> R.string.place_type_pharmacy; "SUPERMARKET" -> R.string.place_type_supermarket; else -> R.string.place_type_store },
    )

    fun cueWhen(ctx: Context, c: CueEntity, placeName: String? = null): String = when (c.anchor) {
        CueAnchor.START -> if (c.offsetMin == 0) ctx.getString(R.string.cue_at_start) else ctx.getString(R.string.cue_before_start, duration(ctx, c.offsetMin))
        CueAnchor.END -> if (c.offsetMin == 0) ctx.getString(R.string.cue_at_end) else ctx.getString(R.string.cue_before_end, duration(ctx, c.offsetMin))
        CueAnchor.DAY -> if (c.offsetMin < 0) ctx.getString(R.string.cue_day_before, minuteOfDay(ctx, c.offsetMin + 24 * 60))
            else ctx.getString(R.string.cue_on_day, minuteOfDay(ctx, c.offsetMin))
        CueAnchor.LEAVE -> ctx.getString(R.string.cue_leave, placeName ?: "")
        CueAnchor.ARRIVE -> if (c.placeType != null) ctx.getString(R.string.cue_at_type, placeTypeLabel(ctx, c.placeType)) else ctx.getString(R.string.cue_arrive, placeName ?: "")
    }

    /** Text shown in the notification when a reminder fires. */
    fun cueMessage(ctx: Context, t: TaskEntity, c: CueEntity): String {
        if (c.message.isNotBlank()) return c.message
        return when (c.kind) {
            CueKind.PREP -> ctx.getString(R.string.cue_msg_prep, duration(ctx, c.offsetMin))
            CueKind.START -> if (t.durationMin != null) ctx.getString(R.string.cue_msg_start_for, duration(ctx, t.durationMin)) else ctx.getString(R.string.cue_msg_start)
            CueKind.END -> ctx.getString(R.string.cue_msg_end)
            CueKind.DAY -> ctx.getString(R.string.cue_msg_day)
            CueKind.CUSTOM -> when (c.anchor) {
                CueAnchor.LEAVE -> ctx.getString(R.string.cue_msg_leave)
                CueAnchor.ARRIVE -> ctx.getString(R.string.cue_msg_arrive)
                else -> ctx.getString(R.string.cue_msg_custom)
            }
        }
    }

    /** Notification body: the AI line if it was written for this very occurrence, else the offline text plus an optional why-line; max 140 chars. */
    fun reminderBody(ctx: Context, t: TaskEntity, c: CueEntity, why: Why?, occurrenceKey: Long?): String {
        if (occurrenceKey != null && c.smartKey == occurrenceKey && !c.smartText.isNullOrBlank()) return c.smartText.take(140)
        val base = cueMessage(ctx, t, c)
        val line = when (why) {
            is Why.Postponed -> ctx.getString(R.string.why_postponed, why.times)
            is Why.FirstStep -> ctx.getString(R.string.why_first_step, why.step)
            is Why.Next -> ctx.getString(R.string.why_next, why.title, time(ctx, why.at))
            null -> return base.take(140)
        }
        val room = 140 - base.length - 1
        if (room < 2) return base.take(140)
        return base + "\n" + if (line.length <= room) line else line.take(room - 1) + "…"
    }

    fun greeting(ctx: Context, hour: Int, name: String): String {
        val base = ctx.getString(
            when (hour) {
                in 5..11 -> R.string.good_morning
                in 12..17 -> R.string.good_afternoon
                else -> R.string.good_evening
            },
        )
        return if (name.isBlank()) base else "$base, $name"
    }
}
