package com.friday.app

import com.friday.app.data.db.SnoozeLogEntity
import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.SubtaskEntity
import com.friday.app.data.db.TaskEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.Clip
import com.friday.app.domain.DndReasons
import com.friday.app.domain.Habits
import com.friday.app.domain.PlaceKind
import com.friday.app.domain.PlaceKinds
import com.friday.app.domain.Prep
import com.friday.app.voice.spoken
import com.friday.app.domain.ReminderWhy
import com.friday.app.domain.Why
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // --- Place awareness ---

    @Test fun classifyMosqueIsHold() =
        assertEquals(PlaceKind.HOLD, PlaceKinds.classify(listOf(mapOf("amenity" to "place_of_worship", "religion" to "muslim"))))

    @Test fun classifyPharmacyBeatsShop() =
        assertEquals(PlaceKind.PHARMACY, PlaceKinds.classify(listOf(mapOf("shop" to "convenience"), mapOf("amenity" to "pharmacy"))))

    @Test fun classifyEmptyIsNone() = assertEquals(PlaceKind.NONE, PlaceKinds.classify(emptyList()))

    @Test fun storeMatchesPharmacy() = assertTrue(PlaceKinds.matches("STORE", PlaceKind.PHARMACY))

    @Test fun pharmacyDoesNotMatchStore() = assertFalse(PlaceKinds.matches("PHARMACY", PlaceKind.STORE))

    @Test fun dndReasonsRestoreOnlyWhenLastReasonGoes() {
        val both = DndReasons.add(DndReasons.add(emptySet(), "focus"), "place")
        val afterFocus = DndReasons.remove(both, "focus")
        assertFalse(DndReasons.shouldRestore(afterFocus))
        assertTrue(DndReasons.shouldRestore(DndReasons.remove(afterFocus, "place")))
    }

    @Test fun placeNotesStayWithin140() {
        val long = "x".repeat(300)
        val study = Clip.note("At the library. Focus on ${Clip.title(long)}?")
        assertTrue(study.length <= 140)
        assertTrue(Clip.title(long).endsWith("…"))
        val home = Clip.note("Left today: " + List(3) { Clip.title(long) }.joinToString(", "))
        assertTrue(home.length <= 140 && home.endsWith("…"))
        assertEquals("Left today: Essay", Clip.note("Left today: " + Clip.title("Essay")))
    }

    @Test fun undoCountsPerKind() {
        val (once, n1) = PlaceKinds.bumpUndo(setOf("STUDY:5"), PlaceKind.HOLD)
        assertEquals(1, n1)
        val (twice, n2) = PlaceKinds.bumpUndo(once, PlaceKind.HOLD)
        assertEquals(2, n2)
        assertEquals(setOf("STUDY:5", "HOLD:2"), twice)
    }

    @Test fun prepThinksAboutWhatKindOfEventItIs() {
        val lesson = Prep.think("License lesson", ms = false, userListed = false)!!
        assertEquals(60, lesson.leadMin)
        assertEquals("Bring your IC and learner's licence", lesson.steps.first())
        assertTrue(lesson.steps.any { it.contains("switches") })
        assertEquals(180, Prep.think("Flight to Penang", ms = false, userListed = false)!!.leadMin)
        assertEquals(90, Prep.think("Job interview", ms = false, userListed = false)!!.leadMin)
        assertTrue(Prep.think("Temujanji klinik", ms = true, userListed = false)!!.steps.contains("Periksa suis dan kunci pintu"))
        assertEquals(null, Prep.think("Call mum", ms = false, userListed = false))
        assertEquals(null, Prep.think("Submit assignment", ms = false, userListed = false))
    }

    @Test fun prepKeepsTheUsersOwnThingsAndSkipsRepeats() {
        val plan = Prep.think("License lesson", ms = false, userListed = true)!!
        assertEquals(listOf("Take your phone, wallet and keys", "Check the switches and lock the door"), plan.steps)
        val merged = Prep.merge(listOf("Bring my IC", "Check all the switches in my house"), plan.steps)
        assertEquals(listOf("Bring my IC", "Check all the switches in my house", "Take your phone, wallet and keys"), merged)
    }

    @Test fun repliesReadNaturallyAloud() {
        assertEquals(
            "Reminder set: License lesson, Sat 28 Nov, 8:00 AM. Before you go: Bring my IC, Check the switches. How early should I alarm you? I’d say 1 hour, OK?",
            spoken("Reminder set: License lesson · Sat 28 Nov, 8:00 AM\nBefore you go: Bring my IC · Check the switches\n\nHow early should I alarm you? I’d say 1 h, OK?"),
        )
        assertEquals("Got it, 2 hours and 15 minutes", spoken("Got it, 2 h and 15 min"))
    }
}
