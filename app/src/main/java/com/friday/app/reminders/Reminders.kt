package com.friday.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.friday.app.container
import com.friday.app.data.AppSettings
import com.friday.app.data.SettingsStore
import com.friday.app.data.TaskRepository
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.FridayDatabase
import com.friday.app.data.db.TaskEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.FocusMath
import com.friday.app.domain.Matrix
import com.friday.app.domain.QuietHours
import com.friday.app.domain.Schedule
import com.friday.app.location.PlaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Runs suspend work from a receiver without blocking the main thread. */
fun BroadcastReceiver.launchAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try { block() } finally { pending.finish() }
    }
}

/** Turns stored reminders into AlarmManager alarms. Alarm request codes are the cue ids. */
class ReminderScheduler(private val ctx: Context, private val db: FridayDatabase, private val places: PlaceManager) {
    private val am = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun pending(action: String, cueId: Long, requestCode: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(Extras.CUE_ID, cueId),
            PendingIntent.FLAG_IMMUTABLE or flags,
        )

    fun setAt(atMillis: Long, pi: PendingIntent) {
        if (canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) // inexact fallback, may be a few minutes late
    }

    fun set(cueId: Long, atMillis: Long) = setAt(atMillis, pending(ReminderReceiver.ACTION_FIRE, cueId, cueId.toInt())!!)

    fun setFollowUp(cueId: Long, atMillis: Long) = setAt(atMillis, pending(ReminderReceiver.ACTION_FOLLOW_UP, cueId, -cueId.toInt())!!)

    fun cancel(cueId: Long) {
        pending(ReminderReceiver.ACTION_FIRE, cueId, cueId.toInt(), PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        cancelFollowUp(cueId)
    }

    fun cancelFollowUp(cueId: Long) {
        pending(ReminderReceiver.ACTION_FOLLOW_UP, cueId, -cueId.toInt(), PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    suspend fun syncTask(taskId: Long) {
        val d = db.tasks().getDetails(taskId) ?: return
        if (d.task.completedAt != null) d.cues.forEach { cancel(it.id) } else sync(d, fireMissed = false)
        if (d.cues.any { it.placeId != null }) places.registerAll()
    }

    /** Re-arms every alarm. After a reboot or clock change, reminders missed in the last hour fire right away. */
    suspend fun syncAll(fireMissed: Boolean, onMissed: suspend (Long) -> Unit = {}) {
        db.tasks().openTasks().forEach { sync(it, fireMissed, onMissed) }
        places.registerAll()
    }

    private suspend fun sync(d: TaskWithDetails, fireMissed: Boolean, onMissed: suspend (Long) -> Unit = {}) {
        val now = System.currentTimeMillis()
        for (cue in d.cues) {
            val trigger = Schedule.trigger(d.task, cue)
            if (trigger == null) { cancel(cue.id); continue } // place reminders are handled by geofences
            val key = Schedule.key(trigger)
            val at = cue.snoozedUntil ?: Schedule.instant(trigger)
            when {
                cue.snoozedUntil == null && cue.firedKey == key -> cancel(cue.id)
                at > now -> set(cue.id, at)
                fireMissed && now - at < MISSED_WINDOW_MS -> onMissed(cue.id)
                else -> { cancel(cue.id); db.tasks().updateCue(cue.copy(firedKey = key, snoozedUntil = null)) }
            }
        }
    }

    companion object {
        const val MISSED_WINDOW_MS = 60 * 60 * 1000L
    }
}

/** What happens when a reminder fires or the user acts on its notification. */
class ReminderEngine(
    private val db: FridayDatabase,
    private val settings: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val notifier: Notifier,
    private val tasks: TaskRepository,
) {
    private val dao = db.tasks()

    suspend fun fire(cueId: Long, now: Long = System.currentTimeMillis()) {
        val cue = dao.getCue(cueId) ?: return
        val task = dao.get(cue.taskId) ?: return
        if (task.completedAt != null) return
        val trigger = Schedule.trigger(task, cue)
        val snoozed = cue.snoozedUntil
        if (snoozed != null && snoozed > now + 60_000) { scheduler.set(cue.id, snoozed); return } // woke up early
        val key = trigger?.let(Schedule::key) ?: now // place reminders: every arrival is its own occurrence
        if (snoozed == null && trigger != null && cue.firedKey == key) return // never fire the same occurrence twice
        val s = settings.current()
        notifier.postReminder(task, cue, channelFor(task, cue, s, now), followUp = false, snoozeMin = s.defaultSnoozeMin)
        dao.updateCue(cue.copy(firedKey = key, firedAt = now, snoozedUntil = null, ackKey = null))
        if (s.followUp) scheduler.setFollowUp(cue.id, followUpAt(now, s))
    }

    /** One extra nudge if the reminder was not handled. Never more than one. */
    suspend fun followUp(cueId: Long, now: Long = System.currentTimeMillis()) {
        val cue = dao.getCue(cueId) ?: return
        val task = dao.get(cue.taskId) ?: return
        if (task.completedAt != null || cue.snoozedUntil != null || cue.firedKey == null || cue.ackKey == cue.firedKey) return
        val s = settings.current()
        notifier.postReminder(task, cue, channelFor(task, cue, s, now), followUp = true, snoozeMin = s.defaultSnoozeMin)
        dao.updateCue(cue.copy(ackKey = cue.firedKey))
    }

    /** "Mark done" completes the task; for steps inside a task it just acknowledges. */
    suspend fun done(cueId: Long) {
        val cue = dao.getCue(cueId) ?: return
        val task = dao.get(cue.taskId) ?: return
        notifier.cancelReminder(cueId)
        scheduler.cancelFollowUp(cueId)
        if (notifier.isPrimary(task, cue)) tasks.complete(task.id) else acknowledge(cueId)
    }

    suspend fun acknowledge(cueId: Long) {
        val cue = dao.getCue(cueId) ?: return
        scheduler.cancelFollowUp(cueId)
        dao.updateCue(cue.copy(ackKey = cue.firedKey))
        dao.touch(cue.taskId)
    }

    suspend fun snooze(cueId: Long, minutes: Int, now: Long = System.currentTimeMillis()) {
        val cue = dao.getCue(cueId) ?: return
        val until = now + minutes * 60_000L
        notifier.cancelReminder(cueId)
        scheduler.cancelFollowUp(cueId)
        dao.updateCue(cue.copy(snoozedUntil = until, ackKey = cue.firedKey))
        scheduler.set(cueId, until)
    }

    /** "Tomorrow" from the snooze picker moves the task itself, counting as a postponement. */
    suspend fun postponeToTomorrow(cueId: Long) {
        val cue = dao.getCue(cueId) ?: return
        notifier.cancelReminder(cueId)
        scheduler.cancelFollowUp(cueId)
        tasks.postpone(cue.taskId, 1)
    }

    private suspend fun channelFor(task: TaskEntity, cue: CueEntity, s: AppSettings, now: Long): String {
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        if (s.quietEnabled && QuietHours.contains(t.hour * 60 + t.minute, s.quietStartMin, s.quietEndMin)) return Channels.QUIET
        val focus = db.focus().active()
        if (FocusMath.silencesOthers(focus, now) && focus?.taskId != task.id && !(task.important && Matrix.isUrgent(task, t.toLocalDate()))) return Channels.QUIET
        return if (!cue.sound) Channels.REMINDERS_VIBRATE else Channels.REMINDERS
    }

    private fun followUpAt(now: Long, s: AppSettings): Long {
        val at = now + FOLLOW_UP_MS
        if (!s.quietEnabled) return at
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
        return QuietHours.nextAllowed(t, s.quietStartMin, s.quietEndMin).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    companion object {
        const val FOLLOW_UP_MS = 10 * 60 * 1000L
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cueId = intent.getLongExtra(Extras.CUE_ID, -1)
        if (cueId < 0) return
        val engine = context.container.reminders
        launchAsync {
            when (intent.action) {
                ACTION_FIRE -> engine.fire(cueId)
                ACTION_FOLLOW_UP -> engine.followUp(cueId)
                ACTION_DONE -> engine.done(cueId)
                ACTION_ACK -> { context.container.notifier.cancelReminder(cueId); engine.acknowledge(cueId) }
                ACTION_SNOOZE -> engine.snooze(cueId, intent.getIntExtra(Extras.MINUTES, 15).coerceIn(1, 24 * 60))
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.friday.app.REMINDER_FIRE"
        const val ACTION_FOLLOW_UP = "com.friday.app.REMINDER_FOLLOW_UP"
        const val ACTION_DONE = "com.friday.app.REMINDER_DONE"
        const val ACTION_ACK = "com.friday.app.REMINDER_ACK"
        const val ACTION_SNOOZE = "com.friday.app.REMINDER_SNOOZE"
    }
}

/** Alarms do not survive reboots, and clock or time-zone changes move wall-clock reminders. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        launchAsync {
            c.scheduler.syncAll(fireMissed = true) { c.reminders.fire(it) }
            c.briefing.schedule()
            c.focus.restoreAfterRestart()
        }
    }
}
