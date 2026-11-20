package com.friday.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.friday.app.ai.Source
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.FocusOutcome
import com.friday.app.data.db.FocusSessionEntity
import com.friday.app.data.db.FridayDatabase
import com.friday.app.data.db.Repeat
import com.friday.app.data.db.SubtaskEntity
import com.friday.app.data.db.TaskEntity
import com.friday.app.domain.FocusMath
import com.friday.app.focus.FocusController
import com.friday.app.reminders.Extras
import com.friday.app.reminders.NotificationIds
import com.friday.app.reminders.ReminderReceiver
import com.friday.app.ui.theme.DarkColors
import com.friday.app.ui.theme.Friday
import com.friday.app.ui.theme.FridayColors
import com.friday.app.ui.theme.FridayTheme
import com.friday.app.ui.theme.LightColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

/**
 * Runs on a device or emulator against an in-memory database, so the app's real data is untouched.
 * Ids start at 1,000,000 so test alarms can never replace the app's real reminder alarms.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTests {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var c: AppContainer

    @get:Rule val compose = createComposeRule()

    @Before fun setUp() = runBlocking<Unit> {
        c = AppContainer(ctx, inMemoryDb = true)
        val seed = c.db.tasks().insert(TaskEntity(id = 1_000_000, title = "seed"))
        c.db.tasks().insertCue(com.friday.app.data.db.CueEntity(id = 1_000_000, taskId = seed, kind = CueKind.CUSTOM, anchor = com.friday.app.data.db.CueAnchor.START))
        c.db.tasks().delete(c.db.tasks().get(seed)!!)
    }

    @After fun tearDown() = runBlocking<Unit> {
        c.db.tasks().allCues().forEach { c.scheduler.cancel(it.id); c.notifier.cancelReminder(it.id) }
        c.db.close()
    }

    private val today get() = LocalDate.now()

    @Test fun taskCreateEditCompleteDeleteAndUndo() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "  Finish poster  ", dueDate = today.plusDays(1), dueTime = LocalTime.of(21, 0), durationMin = 60))
        val created = c.tasks.details(id)!!
        assertEquals("Finish poster", created.task.title)
        assertEquals(setOf(CueKind.PREP, CueKind.START, CueKind.END), created.cues.map { it.kind }.toSet())

        // Editing the time keeps the same reminders but moves them; moving the date later counts as postponing.
        c.tasks.save(created.task.copy(dueDate = today.plusDays(2), dueTime = LocalTime.of(20, 0)))
        val edited = c.tasks.details(id)!!
        assertEquals(created.cues.map { it.id }.toSet(), edited.cues.map { it.id }.toSet())
        assertEquals(1, edited.task.postponeCount)

        c.tasks.addSubtask(id, "Pick colours")
        val snap = c.tasks.complete(id)!!
        assertNotNull(c.tasks.get(id)!!.completedAt)
        c.tasks.restore(snap)
        assertNull(c.tasks.get(id)!!.completedAt)

        val deleted = c.tasks.delete(id)!!
        assertNull(c.tasks.get(id))
        c.tasks.restore(deleted)
        val back = c.tasks.details(id)!!
        assertEquals(1, back.subtasks.size)
        assertEquals(3, back.cues.size)
    }

    @Test fun repeatingTaskKeepsHistoryAndMovesOn() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Clean room", dueDate = today, repeat = Repeat.DAILY))
        c.tasks.addSubtask(id, "Desk")
        c.tasks.setSubtaskDone(c.tasks.details(id)!!.subtasks.first(), true)
        c.tasks.complete(id)
        val t = c.tasks.details(id)!!
        assertNull(t.task.completedAt)
        assertEquals(today.plusDays(1), t.task.dueDate)
        assertFalse(t.subtasks.first().done)
        assertEquals(1, c.db.tasks().observeCompleted().first().count { it.task.title == "Clean room" })
    }

    @Test fun dataSurvivesClosingAndReopeningTheDatabase() = runBlocking<Unit> {
        val name = "friday-persistence-test.db"
        ctx.deleteDatabase(name)
        val first = Room.databaseBuilder(ctx, FridayDatabase::class.java, name).build()
        first.tasks().insert(TaskEntity(title = "Persist me", dueDate = today))
        first.close()
        val second = Room.databaseBuilder(ctx, FridayDatabase::class.java, name).build()
        assertEquals("Persist me", second.tasks().openTasks().single().task.title)
        second.close()
        ctx.deleteDatabase(name)
    }

    @Test fun reminderFiresOnceSnoozesAndCompletes() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Call lecturer", dueDate = today, dueTime = LocalTime.now().plusMinutes(1).withSecond(0).withNano(0)))
        val start = c.db.tasks().cuesFor(id).first { it.kind == CueKind.START }

        c.reminders.fire(start.id)
        val fired = c.db.tasks().getCue(start.id)!!
        assertNotNull(fired.firedKey)
        c.reminders.fire(start.id) // same occurrence again: ignored
        assertEquals(fired.firedAt, c.db.tasks().getCue(start.id)!!.firedAt)

        c.reminders.snooze(start.id, 5)
        val snoozed = c.db.tasks().getCue(start.id)!!
        assertTrue(snoozed.snoozedUntil!! > System.currentTimeMillis() + 4 * 60_000)
        assertEquals(snoozed.firedKey, snoozed.ackKey)
        assertNotNull(alarm(start.id))

        c.reminders.done(start.id) // "Mark done" on a reminder without duration finishes the task
        assertNotNull(c.tasks.get(id)!!.completedAt)
        assertNull(alarm(start.id))
    }

    @Test fun followUpNudgesOnlyOnce() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Pay bill", dueDate = today, dueTime = LocalTime.now().plusMinutes(1).withSecond(0).withNano(0)))
        val cue = c.db.tasks().cuesFor(id).first { it.kind == CueKind.START }
        c.reminders.fire(cue.id)
        assertNull(c.db.tasks().getCue(cue.id)!!.ackKey)
        c.reminders.followUp(cue.id)
        val after = c.db.tasks().getCue(cue.id)!!
        assertEquals(after.firedKey, after.ackKey) // marked as nudged: no further follow-ups
        c.reminders.followUp(cue.id)
        assertEquals(after, c.db.tasks().getCue(cue.id)!!)
        // Dismissing a notification is not completing: the task is still open.
        assertNull(c.tasks.get(id)!!.completedAt)
    }

    @Test fun focusCountdownIsRightAfterReopening() = runBlocking<Unit> {
        var clock = 10_000_000L
        val focus = FocusController(ctx, c.db, c.notifier, c.scheduler) { clock }
        val sessionId = focus.start(null, "Poster", 25, dnd = false, shield = false)
        clock += 10 * 60_000
        // A fresh controller (as after the app was closed) reads the same timestamps.
        val reopened = FocusController(ctx, c.db, c.notifier, c.scheduler) { clock }
        val s = reopened.current()!!
        assertEquals(15 * 60_000L, FocusMath.remainingMs(s, clock))
        reopened.pause(sessionId)
        clock += 30 * 60_000
        assertEquals(15 * 60_000L, FocusMath.remainingMs(reopened.current()!!, clock))
        reopened.resume(sessionId)
        val ended = reopened.end(sessionId, FocusOutcome.PROGRESS, "Title done")!!
        assertEquals("Title done", ended.stopNote)
        assertNull(reopened.current())
    }

    @Test fun briefingIsSkippedOnEmptyDays() = runBlocking<Unit> {
        assertNull(c.briefing.compose())
        c.tasks.save(TaskEntity(title = "Submit report", important = true, dueDate = today))
        val (_, text) = c.briefing.compose()!!
        assertTrue(text.contains("Submit report"))
    }

    @Test fun assistantWorksWithoutOnlineAi() = runBlocking<Unit> {
        // No key: the online AI is unavailable, commands still work and unknown chat is saved, not lost.
        if (c.secrets.has(com.friday.app.data.SecretStore.AI_KEY)) return@runBlocking
        assertFalse(c.ai.available())
        val created = c.assistant.handle("Remind me tomorrow at 9am to call the bank", Source.CHAT)
        assertNotNull(created.taskId)
        val chat = c.assistant.handle("tell me a joke about penguins", Source.CHAT)
        assertFalse(chat.remote)
        assertEquals(1, chat.chips.size) // "Save to Inbox"
    }

    @Test fun notificationSnoozeButtonReachesTheReceiver() = runBlocking<Unit> {
        // Uses the app's real container, as the notification button does; cleaned up afterwards.
        val real = ctx.container
        val id = real.tasks.save(TaskEntity(title = "[test] snooze button", dueDate = today, dueTime = LocalTime.now().plusMinutes(1).withSecond(0).withNano(0)))
        try {
            val cue = real.db.tasks().cuesFor(id).first { it.kind == CueKind.START }
            real.reminders.fire(cue.id)
            ctx.sendBroadcast(Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SNOOZE).putExtra(Extras.CUE_ID, cue.id).putExtra(Extras.MINUTES, 15))
            var snoozed: Long? = null
            repeat(50) { if (snoozed == null) { delay(100); snoozed = real.db.tasks().getCue(cue.id)?.snoozedUntil } }
            assertNotNull(snoozed)
            assertTrue(snoozed!! > System.currentTimeMillis() + 14 * 60_000)
        } finally {
            real.tasks.delete(id)
            real.db.tasks().allCues().filter { it.taskId == id }.forEach { real.notifier.cancelReminder(it.id) }
        }
    }

    @Test fun themeSwitchesBetweenLightAndDark() {
        var dark by mutableStateOf(false)
        var seen: FridayColors? = null
        compose.setContent { FridayTheme(dark) { seen = Friday.colors } }
        compose.waitForIdle()
        assertEquals(LightColors, seen)
        dark = true
        compose.waitForIdle()
        assertEquals(DarkColors, seen)
    }

    @Test fun snoozedPlaceReminderSurvivesResync() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Buy milk"))
        val place = c.db.places().insert(com.friday.app.data.db.PlaceEntity(name = "Shop", lat = 1.0, lng = 1.0))
        val cue = c.tasks.addPlaceCue(id, place, leave = false)
        c.reminders.snooze(cue, 10)
        c.scheduler.cancel(cue) // simulate the alarm being gone, then resync must re-arm it
        c.db.tasks().updateCue(c.db.tasks().getCue(cue)!!.copy(snoozedUntil = System.currentTimeMillis() + 10 * 60_000))
        c.scheduler.syncAll(false)
        assertNotNull(alarm(cue))
    }

    @Test fun completingRepeatingTaskResetsPostpones() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Water plants", dueDate = today, repeat = Repeat.DAILY))
        c.tasks.postpone(id, 1); c.tasks.postpone(id, 1)
        assertEquals(2, c.tasks.get(id)!!.postponeCount)
        c.tasks.complete(id)
        assertEquals(0, c.tasks.get(id)!!.postponeCount)
    }

    @Test fun monthlySaveStoresAnchorDay() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Rent", dueDate = LocalDate.of(2027, 1, 31), repeat = Repeat.MONTHLY))
        assertEquals(31, c.tasks.get(id)!!.repeatDays)
    }

    @Test fun editingMonthlyDueDateMovesAnchor() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Rent", dueDate = LocalDate.of(2027, 1, 31), repeat = Repeat.MONTHLY))
        c.tasks.save(c.tasks.get(id)!!.copy(dueDate = LocalDate.of(2027, 2, 15)))
        assertEquals(15, c.tasks.get(id)!!.repeatDays)
    }

    @Test fun weeklyToMonthlyUsesDueDateDay() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Gym", dueDate = LocalDate.of(2027, 3, 9), repeat = Repeat.WEEKLY, repeatDays = 0b0010101))
        c.tasks.save(c.tasks.get(id)!!.copy(repeat = Repeat.MONTHLY))
        assertEquals(9, c.tasks.get(id)!!.repeatDays)
    }

    @Test fun postponingMonthlyKeepsAnchor() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Rent", dueDate = LocalDate.of(2027, 1, 31), repeat = Repeat.MONTHLY))
        c.tasks.postpone(id, 1)
        val t = c.tasks.get(id)!!
        assertEquals(LocalDate.of(2027, 2, 1), t.dueDate)
        assertEquals(31, t.repeatDays)
    }

    @Test fun titleEditKeepsMonthlyAnchorEvenWithZeroRepeatDays() = runBlocking<Unit> {
        val id = c.tasks.save(TaskEntity(title = "Rent", dueDate = LocalDate.of(2027, 1, 31), repeat = Repeat.MONTHLY))
        c.tasks.postpone(id, 28) // Feb 28, anchor stays 31
        c.tasks.save(c.tasks.get(id)!!.copy(title = "x", repeatDays = 0))
        assertEquals(31, c.tasks.get(id)!!.repeatDays)
    }

    @Test fun memoryDedupesAndTrims() = runBlocking<Unit> {
        assertNotNull(c.memory.add("Hates long messages", com.friday.app.data.db.MemorySource.USER))
        assertNull(c.memory.add("  hates LONG messages ", com.friday.app.data.db.MemorySource.CHAT))
        val id = c.memory.add("x".repeat(250), com.friday.app.data.db.MemorySource.USER)
        assertNotNull(id)
        assertEquals(200, c.memory.all().first { it.id == id }.text.length)
    }

    @Test fun memoryCap() = runBlocking<Unit> {
        repeat(100) { assertNotNull(c.memory.add("fact $it", com.friday.app.data.db.MemorySource.USER)) }
        assertNull(c.memory.add("one too many", com.friday.app.data.db.MemorySource.USER))
    }

    @Test fun twoRememberActionsAddTwoMemoriesAndShowBoth() = runBlocking<Unit> {
        val reply = com.friday.app.ai.AiReply(
            reply = "Got it.",
            actions = listOf(
                com.friday.app.ai.AiAction(type = "REMEMBER", text = "Gym after 7 PM"),
                com.friday.app.ai.AiAction(type = "REMEMBER", text = "Prefers short messages"),
            ),
        )
        val res = c.ai.apply(reply, emptyList(), ctx, com.friday.app.ai.Lang.EN)
        assertEquals(setOf("Gym after 7 PM", "Prefers short messages"), c.memory.all().map { it.text }.toSet())
        assertTrue(res.text.contains("Remembered: Gym after 7 PM"))
        assertTrue(res.text.contains("Remembered: Prefers short messages"))
    }

    // --- Smart timing: hold during focus, skip if started, bundle ---

    private suspend fun timedTask(title: String, important: Boolean = false, due: LocalDate = today.plusDays(3)) =
        c.tasks.save(TaskEntity(title = title, important = important, dueDate = due, dueTime = LocalTime.of(12, 0), durationMin = 30))

    private suspend fun cueOf(taskId: Long, kind: CueKind) = c.db.tasks().cuesFor(taskId).first { it.kind == kind }

    private fun showing(id: Int) = ctx.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id }

    /** A focus session in the test database only: no notification, end alarm or DND, so a real session is never disturbed. */
    private suspend fun focusOn(taskId: Long, now: Long, minutes: Int = 25) =
        c.db.focus().insert(FocusSessionEntity(taskId = taskId, taskTitle = "[test] focus", plannedMin = minutes, startedAt = now))

    private suspend fun endFocus(session: Long, at: Long) = c.db.focus().update(c.db.focus().get(session)!!.copy(endedAt = at))

    @Test fun reminderForOtherTaskIsHeldDuringFocusAndReleasedOnEnd() = runBlocking<Unit> {
        // Drives the real FocusController (shared end alarm, focus notification): only when the app has no session of its own.
        assumeTrue("real focus session running", ctx.container.focus.current() == null)
        val a = timedTask("[test] A"); val b = cueOf(timedTask("[test] B"), CueKind.START)
        val session = c.focus.start(a, "[test] focus", 25, dnd = false, shield = false)
        try {
            c.reminders.fire(b.id, System.currentTimeMillis())
            val held = c.db.tasks().getCue(b.id)!!
            assertTrue(held.held)
            assertNull(held.firedKey)
            c.focus.end(session, null, null)
            val released = c.db.tasks().getCue(b.id)!!
            assertFalse(released.held)
            assertNotNull(released.firedKey)
        } finally {
            if (c.focus.current() != null) c.focus.end(session, null, null)
        }
    }

    @Test fun heldReminderIsReleasedAfterFocus() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A"); val b = cueOf(timedTask("[test] B"), CueKind.START)
        val session = focusOn(a, now)
        c.reminders.fire(b.id, now)
        val held = c.db.tasks().getCue(b.id)!!
        assertTrue(held.held)
        assertNull(held.firedKey)
        assertEquals(now + 25 * 60_000, held.snoozedUntil)
        assertNotNull(alarm(b.id)) // backup: the alarm at the session's end delivers it even if release never runs
        endFocus(session, now + 60_000)
        c.reminders.releaseHeld(now + 60_000)
        val released = c.db.tasks().getCue(b.id)!!
        assertFalse(released.held)
        assertNotNull(released.firedKey)
        assertNull(released.snoozedUntil)
        assertEquals(now + 60_000, released.firedAt)
    }

    @Test fun heldReminderReHoldsWhenFocusExtended() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A"); val b = cueOf(timedTask("[test] B"), CueKind.START)
        val session = focusOn(a, now)
        c.reminders.fire(b.id, now)
        val oldEnd = c.db.tasks().getCue(b.id)!!.snoozedUntil!!
        c.db.focus().update(c.db.focus().get(session)!!.copy(plannedMin = 35)) // "+10 min"
        c.reminders.fire(b.id, oldEnd) // the hold alarm goes off at the old end
        val again = c.db.tasks().getCue(b.id)!!
        assertTrue(again.held)
        assertNull(again.firedKey)
        assertEquals(oldEnd + 10 * 60_000, again.snoozedUntil)
    }

    @Test fun importantUrgentPassesThroughFocus() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A"); val b = cueOf(timedTask("[test] B", important = true, due = today), CueKind.START)
        focusOn(a, now)
        c.reminders.fire(b.id, now)
        val cue = c.db.tasks().getCue(b.id)!!
        assertFalse(cue.held)
        assertNotNull(cue.firedKey)
    }

    @Test fun snoozedReminderHeldDuringFocusStillArrivesAfter() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A"); val b = cueOf(timedTask("[test] B"), CueKind.START)
        c.reminders.fire(b.id, now - 60_000)
        c.reminders.snooze(b.id, 5, now - 60_000)
        val session = focusOn(a, now)
        c.reminders.fire(b.id, now + 4 * 60_000) // snooze ends mid-focus: held
        assertTrue(c.db.tasks().getCue(b.id)!!.held)
        endFocus(session, now + 10 * 60_000)
        c.reminders.releaseHeld(now + 10 * 60_000)
        val released = c.db.tasks().getCue(b.id)!!
        assertFalse(released.held)
        assertNull(released.snoozedUntil)
        assertEquals(now + 10 * 60_000, released.firedAt) // posted again, not dropped as a duplicate of the first alert
    }

    @Test fun startReminderSkippedWhenFocusingOnThatTask() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A")
        val start = cueOf(a, CueKind.START)
        focusOn(a, now)
        c.reminders.fire(start.id, now)
        val cue = c.db.tasks().getCue(start.id)!!
        assertNotNull(cue.firedKey)
        assertFalse(cue.held)
        assertFalse(showing(NotificationIds.reminder(start.id)))
    }

    @Test fun prepSkippedWhenFocusOnTaskEnded10MinAgo() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A")
        val prep = cueOf(a, CueKind.PREP)
        endFocus(focusOn(a, now - 35 * 60_000), now - 10 * 60_000)
        c.reminders.fire(prep.id, now)
        assertNotNull(c.db.tasks().getCue(prep.id)!!.firedKey)
        assertFalse(showing(NotificationIds.reminder(prep.id)))
    }

    @Test fun prepNotSkippedWhenTaskMerelyTouched() = runBlocking<Unit> {
        assumeTrue("notifications off", c.notifier.enabled())
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A")
        val prep = cueOf(a, CueKind.PREP)
        c.db.tasks().touch(a, now - 5 * 60_000) // e.g. opened in the editor: not "started"
        c.reminders.fire(prep.id, now)
        repeat(50) { if (!showing(NotificationIds.reminder(prep.id))) delay(100) }
        assertTrue(showing(NotificationIds.reminder(prep.id)))
    }

    @Test fun prepPostedAfterUncheckedFocusSession() = runBlocking<Unit> {
        assumeTrue("notifications off", c.notifier.enabled())
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A")
        val prep = cueOf(a, CueKind.PREP)
        focusOn(a, now - 2 * 60 * 60_000) // time ran out 95 min ago, never checked in: still open
        c.reminders.fire(prep.id, now)
        repeat(50) { if (!showing(NotificationIds.reminder(prep.id))) delay(100) }
        assertTrue(showing(NotificationIds.reminder(prep.id)))
    }

    @Test fun prepPostedAfterFocusPausedLongAgo() = runBlocking<Unit> {
        assumeTrue("notifications off", c.notifier.enabled())
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A")
        val prep = cueOf(a, CueKind.PREP)
        c.db.focus().insert(
            FocusSessionEntity(taskId = a, taskTitle = "[test] focus", plannedMin = 25, startedAt = now - 2 * 60 * 60_000 - 5 * 60_000, pausedAt = now - 2 * 60 * 60_000),
        )
        c.reminders.fire(prep.id, now)
        repeat(50) { if (!showing(NotificationIds.reminder(prep.id))) delay(100) }
        assertTrue(showing(NotificationIds.reminder(prep.id)))
    }

    @Test fun postponedHeldReminderIsNotFiredEarly() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val a = timedTask("[test] A"); val bId = timedTask("[test] B"); val b = cueOf(bId, CueKind.START)
        val session = focusOn(a, now)
        c.reminders.fire(b.id, now)
        assertTrue(c.db.tasks().getCue(b.id)!!.held)
        c.tasks.postpone(bId)
        assertFalse(c.db.tasks().getCue(b.id)!!.held)
        endFocus(session, now + 60_000)
        c.reminders.releaseHeld(now + 60_000)
        val after = c.db.tasks().getCue(b.id)!!
        assertNull(after.firedKey)
        assertFalse(showing(NotificationIds.reminder(b.id)))
        assertNotNull(alarm(b.id)) // still armed for the new date
    }

    @Test fun burstOfRemindersIsBundled() = runBlocking<Unit> {
        assumeTrue("notifications off", c.notifier.enabled())
        val x = cueOf(timedTask("[test] X"), CueKind.START)
        val y = cueOf(timedTask("[test] Y"), CueKind.START)
        c.reminders.fire(x.id)
        repeat(50) { if (!showing(NotificationIds.reminder(x.id))) delay(100) }
        c.reminders.fire(y.id)
        repeat(50) { if (!showing(NotificationIds.BUNDLE)) delay(100) }
        assertTrue(showing(NotificationIds.BUNDLE))
        c.notifier.cancelReminder(y.id)
        repeat(50) { if (showing(NotificationIds.BUNDLE)) delay(100) }
        assertFalse(showing(NotificationIds.BUNDLE))
    }

    private fun alarm(cueId: Long): PendingIntent? = PendingIntent.getBroadcast(
        ctx, cueId.toInt(),
        Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(Extras.CUE_ID, cueId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    )
}
