package com.friday.app.domain

import com.friday.app.data.db.SnoozeLogEntity
import java.time.LocalTime
import kotlin.math.abs

object Habits {
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * For a repeating, timed task: the time it keeps getting snoozed to, or null.
     * Needs 3+ entries in the last 30 days, the newest 3 within 30 min of their median,
     * result rounded to the nearest 15 min, and 30+ min away from [current].
     * Limitation: minutes of day are compared on a plain line, so a habit straddling midnight is not detected.
     */
    fun learnedMove(log: List<SnoozeLogEntity>, current: LocalTime, now: Long): LocalTime? {
        val last = log.filter { it.at >= now - WINDOW_MS }.sortedByDescending { it.at }.take(3).map { it.targetMin }.sorted()
        if (last.size < 3) return null
        val median = last[1]
        if (last.any { abs(it - median) > 30 }) return null
        val rounded = ((median + 7) / 15 * 15).coerceAtMost(23 * 60 + 45)
        if (abs(rounded - (current.hour * 60 + current.minute)) < 30) return null
        return LocalTime.of(rounded / 60, rounded % 60)
    }
}
