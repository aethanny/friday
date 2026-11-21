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

/** How to get ready for an event. [leadMin] 0 = nothing to get ready for (a call, a chore at home). */
data class PrepPlan(val title: String? = null, val leadMin: Int, val steps: List<String> = emptyList())

/** Offline common sense for events: how early to get ready and what to take. The online AI does this when it is on. */
object Prep {
    private class Kind(words: String, val lead: Int, val en: List<String>, val ms: List<String>) {
        val regex = Regex("(?<![\\p{L}])($words)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    }

    // First match wins, so the specific kinds ("driving test") come before the general ones ("test", "lesson").
    private val KINDS = listOf(
        Kind("licen[cs]e|lesen|driving|memandu|jpj", 60, listOf("Bring your IC and learner's licence"), listOf("Bawa IC dan lesen L")),
        Kind("exams?|tests?|quiz|midterm|peperiksaan|ujian|kuiz", 60, listOf("Bring your IC or student card", "Pack pens, pencil and calculator"), listOf("Bawa IC atau kad pelajar", "Bawa pen, pensel dan kalkulator")),
        Kind("interview|temuduga|temu duga", 90, listOf("Bring your resume, IC and certificates", "Dress neatly"), listOf("Bawa resume, IC dan sijil", "Pakai kemas")),
        Kind("doctor|clinic|hospital|dentist|check-?up|appointment|klinik|doktor|temujanji|temu janji|pergigian", 60, listOf("Bring your IC and appointment card"), listOf("Bawa IC dan kad temujanji")),
        Kind("flight|airport|penerbangan|lapangan terbang|kapal terbang", 180, listOf("Bring your passport or IC and boarding pass", "Charge your phone"), listOf("Bawa pasport atau IC dan pas masuk", "Cas telefon")),
        Kind("bus|train|ktm|bas|kereta api|trip|travel|balik kampung", 60, listOf("Bring your ticket and IC", "Charge your phone"), listOf("Bawa tiket dan IC", "Cas telefon")),
        Kind("gym|workout|futsal|football|badminton|swim(?:ming)?|jogging|gim|renang|bola", 30, listOf("Pack sports clothes, water and a towel"), listOf("Bawa baju sukan, air dan tuala")),
        Kind("meeting|meet|presentation|mesyuarat|pembentangan|seminar|workshop|bengkel|jumpa", 30, listOf("Bring your laptop, charger and notes"), listOf("Bawa laptop, pengecas dan nota")),
        Kind("wedding|kenduri|majlis|party|birthday|dinner|parti|hari jadi", 60, listOf("Bring the gift", "Get dressed"), listOf("Bawa hadiah", "Bersiap pakaian")),
        Kind("class|lesson|lecture|tuition|course|school|kelas|kuliah|tuisyen|kursus|sekolah", 30, listOf("Pack your books and notes"), listOf("Bawa buku dan nota")),
    )
    private val LEAVE_EN = listOf("Take your phone, wallet and keys", "Check the switches and lock the door")
    private val LEAVE_MS = listOf("Bawa telefon, dompet dan kunci", "Periksa suis dan kunci pintu")

    /** null = not an event FRIDAY recognises. When the user listed their own things, only the leaving-home checks are added. */
    fun think(title: String, ms: Boolean, userListed: Boolean): PrepPlan? {
        val k = KINDS.firstOrNull { it.regex.containsMatchIn(title) } ?: return null
        val own = if (userListed) emptyList() else if (ms) k.ms else k.en
        return PrepPlan(leadMin = k.lead, steps = own + if (ms) LEAVE_MS else LEAVE_EN)
    }

    private val COMMON = setOf("and", "the", "all", "you", "dan", "yang", "bring","check", "take", "pack", "your", "with", "before", "bawa", "periksa", "semua", "dengan", "untuk", "house", "home", "rumah")
    private fun words(s: String) = Regex("\\p{L}{3,}").findAll(s.lowercase()).map { it.value.removeSuffix("es").removeSuffix("s") }.toSet() - COMMON

    /** The user's own steps first; a suggestion that talks about the same thing ("switches") is dropped. */
    fun merge(user: List<String>, suggested: List<String>): List<String> {
        val out = user.toMutableList()
        for (s in suggested) if (out.none { o -> (words(o) intersect words(s)).isNotEmpty() }) out += s
        return out
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
