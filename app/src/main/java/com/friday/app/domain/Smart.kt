package com.friday.app.domain

import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.SnoozeLogEntity
import com.friday.app.data.db.TaskEntity
import com.friday.app.data.db.TaskWithDetails
import java.time.Duration
import java.time.LocalDateTime
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

sealed interface Why {
    data class Postponed(val times: Int) : Why
    data class FirstStep(val step: String) : Why
    data class Next(val title: String, val at: LocalTime) : Why
}

object ReminderWhy {
    /** First match wins. [others] = the other open tasks. Limitation: "later today" never looks past midnight. */
    fun pick(task: TaskWithDetails, cue: CueEntity, others: List<TaskEntity>, now: LocalDateTime): Why? {
        if (task.task.postponeCount >= 2) return Why.Postponed(task.task.postponeCount)
        if (cue.kind == CueKind.PREP || cue.kind == CueKind.START) {
            task.subtasks.filter { !it.done }.minByOrNull { it.position }?.let { return Why.FirstStep(it.title) }
        }
        if (cue.kind == CueKind.END) {
            others.filter { it.id != task.task.id && it.completedAt == null && it.dueDate == now.toLocalDate() && it.dueTime != null }
                .filter { val d = Duration.between(now, now.toLocalDate().atTime(it.dueTime)); !d.isNegative && !d.isZero && d <= Duration.ofHours(3) }
                .minByOrNull { it.dueTime!! }?.let { return Why.Next(it.title, it.dueTime!!) }
        }
        return null
    }
}

enum class PlaceKind { HOLD, STUDY, STORE, PHARMACY, SUPERMARKET, HOME, NONE }

object PlaceKinds {
    private val HOLD_AMENITIES = setOf("place_of_worship", "cinema", "theatre", "hospital", "clinic", "doctors")
    private val PRIORITY = listOf(PlaceKind.HOLD, PlaceKind.PHARMACY, PlaceKind.SUPERMARKET, PlaceKind.STUDY, PlaceKind.STORE, PlaceKind.NONE)

    private fun kindOf(tags: Map<String, String>): PlaceKind {
        val amenity = tags["amenity"]; val shop = tags["shop"]
        return when {
            amenity in HOLD_AMENITIES -> PlaceKind.HOLD
            amenity == "pharmacy" || shop == "chemist" -> PlaceKind.PHARMACY
            shop == "supermarket" || shop == "convenience" -> PlaceKind.SUPERMARKET
            amenity == "library" -> PlaceKind.STUDY
            shop != null -> PlaceKind.STORE
            else -> PlaceKind.NONE
        }
    }

    /** OSM tags of nearby elements → kind of place; with several, the highest priority wins (HOLD first). */
    fun classify(elements: List<Map<String, String>>): PlaceKind =
        elements.map(::kindOf).minByOrNull(PRIORITY::indexOf) ?: PlaceKind.NONE

    /** Errand placeType vs place: STORE takes any shop, PHARMACY/SUPERMARKET only themselves. */
    fun matches(placeType: String, kind: PlaceKind): Boolean =
        if (placeType == "STORE") kind == PlaceKind.STORE || kind == PlaceKind.PHARMACY || kind == PlaceKind.SUPERMARKET
        else placeType == kind.name

    /** Undo counts are stored as "KIND:n": the set with [kind] counted once more, and the new count. */
    fun bumpUndo(undos: Set<String>, kind: PlaceKind): Pair<Set<String>, Int> {
        val prefix = "${kind.name}:"
        val n = (undos.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() ?: 0) + 1
        return undos.filterNot { it.startsWith(prefix) }.toSet() + "$prefix$n" to n
    }
}

/** Keeps place notes inside the notification limit: titles shortened with "…", whole text at most 140 characters. */
object Clip {
    fun title(s: String, max: Int = 40): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
    fun note(s: String): String = if (s.length <= 140) s else s.take(139).trimEnd() + "…"
}

/** Why Do Not Disturb is on ("focus", "place"): the user's own setting comes back only when the last reason ends. */
object DndReasons {
    fun add(current: Set<String>, r: String): Set<String> = current + r
    fun remove(current: Set<String>, r: String): Set<String> = current - r
    fun shouldRestore(after: Set<String>): Boolean = after.isEmpty()
}
