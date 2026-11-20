package com.friday.app.focus

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.friday.app.container
import com.friday.app.data.db.FocusOutcome
import com.friday.app.data.db.FocusSessionEntity
import com.friday.app.data.db.FridayDatabase
import com.friday.app.domain.FocusMath
import com.friday.app.reminders.Notifier
import com.friday.app.reminders.ReminderScheduler
import com.friday.app.reminders.launchAsync
import kotlinx.coroutines.flow.Flow

/**
 * Focus sessions are timestamps, not a running timer: the countdown is correct after the app is
 * closed, the process dies or the phone restarts.
 */
class FocusController(
    private val ctx: Context,
    private val db: FridayDatabase,
    private val notifier: Notifier,
    private val scheduler: ReminderScheduler,
    /** Runs once the session stops silencing reminders (ended or time up): releases the ones held back. */
    private val onEnded: suspend () -> Unit = {},
    // Last so callers can keep passing the clock as a trailing lambda.
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.focus()
    val active: Flow<FocusSessionEntity?> = dao.observeActive()
    val latest: Flow<FocusSessionEntity?> = dao.observeLatest()

    suspend fun current() = dao.active()

    suspend fun start(taskId: Long?, title: String, minutes: Int, dnd: Boolean, shield: Boolean): Long {
        dao.active()?.let { end(it.id, null, null) }
        val s = FocusSessionEntity(taskId = taskId, taskTitle = title, plannedMin = minutes.coerceIn(1, 600), startedAt = now(), dnd = dnd, shield = shield)
        val id = dao.insert(s)
        taskId?.let { db.tasks().touch(it) }
        val saved = s.copy(id = id)
        armEnd(saved)
        notifier.focusOngoing(saved)
        if (dnd) Dnd.enable(ctx)
        if (shield) ShieldService.start(ctx)
        return id
    }

    suspend fun pause(id: Long) {
        val s = dao.get(id) ?: return
        if (s.endedAt != null || s.pausedAt != null) return
        val p = s.copy(pausedAt = now())
        dao.update(p)
        cancelEnd()
        notifier.focusOngoing(p)
    }

    suspend fun resume(id: Long) {
        val s = dao.get(id) ?: return
        val pausedAt = s.pausedAt ?: return
        val r = s.copy(pausedAt = null, pausedTotalMs = s.pausedTotalMs + (now() - pausedAt))
        dao.update(r)
        armEnd(r)
        notifier.focusOngoing(r)
    }

    suspend fun addMinutes(id: Long, minutes: Int) {
        val s = dao.get(id) ?: return
        val u = s.copy(plannedMin = (s.plannedMin + minutes).coerceAtMost(600))
        dao.update(u)
        if (u.pausedAt == null) armEnd(u)
        notifier.cancelFocusDone()
        notifier.focusOngoing(u)
        if (u.dnd) Dnd.enable(ctx)
        if (u.shield) ShieldService.start(ctx)
    }

    suspend fun end(id: Long, outcome: FocusOutcome?, note: String?): FocusSessionEntity? {
        val s = dao.get(id) ?: return null
        val t = now()
        val endedAt = FocusMath.endsAt(s)?.let { minOf(it, t) } ?: t
        val e = s.copy(endedAt = endedAt, outcome = outcome, stopNote = note?.trim()?.take(500)?.ifBlank { null })
        dao.update(e)
        e.taskId?.let { db.tasks().touch(it) }
        stopSideEffects()
        notifier.cancelFocusDone()
        releaseHeld()
        return e
    }

    /** Saves the "where did you stop?" note after the session already ended. */
    suspend fun saveNote(id: Long, note: String) {
        val s = dao.get(id) ?: return
        dao.update(s.copy(stopNote = note.trim().take(500).ifBlank { null }))
    }

    suspend fun setOutcome(id: Long, outcome: FocusOutcome) {
        dao.get(id)?.let { dao.update(it.copy(outcome = outcome)) }
    }

    /** Called when the timer reaches zero. The session stays open until the user checks in. */
    suspend fun timeUp(id: Long) {
        val s = dao.get(id) ?: return
        if (s.endedAt != null || s.pausedAt != null) return
        if (FocusMath.remainingMs(s, now()) > 5_000) { armEnd(s); return }
        stopSideEffects()
        notifier.focusDone(s)
        releaseHeld()
    }

    suspend fun restoreAfterRestart() {
        val s = dao.active() ?: return
        if (s.pausedAt != null) { notifier.focusOngoing(s); return }
        if (FocusMath.remainingMs(s, now()) > 0) {
            armEnd(s)
            notifier.focusOngoing(s)
            if (s.shield) ShieldService.start(ctx) // allowed after boot/app update; silently skipped otherwise
        } else timeUp(s.id)
    }

    /** A reminder failure must never stop focus from ending. */
    private suspend fun releaseHeld() {
        runCatching { onEnded() }.onFailure { Log.w("FocusController", "releasing held reminders failed", it) }
    }

    private fun stopSideEffects() {
        cancelEnd()
        notifier.cancelFocus()
        Dnd.restore(ctx)
        ShieldService.stop(ctx)
    }

    private fun endIntent(sessionId: Long, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        ctx, END_REQUEST, Intent(ctx, FocusEndReceiver::class.java).putExtra(EXTRA_SESSION, sessionId), PendingIntent.FLAG_IMMUTABLE or flags,
    )

    private fun armEnd(s: FocusSessionEntity) {
        val at = FocusMath.endsAt(s) ?: return
        scheduler.setAt(at, endIntent(s.id, PendingIntent.FLAG_UPDATE_CURRENT)!!)
    }

    private fun cancelEnd() {
        endIntent(0, PendingIntent.FLAG_NO_CREATE)?.let { ctx.getSystemService(android.app.AlarmManager::class.java).cancel(it); it.cancel() }
    }

    companion object {
        const val EXTRA_SESSION = "friday.session"
        private const val END_REQUEST = 2_000_000_002
    }
}

class FocusEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(FocusController.EXTRA_SESSION, -1)
        if (id < 0) return
        launchAsync { context.container.focus.timeUp(id) }
    }
}

/**
 * Turns on Do Not Disturb (priority only) during focus and puts the user's own setting back
 * afterwards. Reminders still come through so FRIDAY can reach the user for the focused task.
 */
object Dnd {
    private const val PREFS = "focus_dnd"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun hasAccess(ctx: Context) = nm(ctx).isNotificationPolicyAccessGranted

    fun enable(ctx: Context) {
        val nm = nm(ctx)
        if (!nm.isNotificationPolicyAccessGranted) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("active", false)) return
        val p = nm.notificationPolicy
        prefs.edit()
            .putBoolean("active", true)
            .putInt("filter", nm.currentInterruptionFilter)
            .putInt("cats", p.priorityCategories)
            .putInt("calls", p.priorityCallSenders)
            .putInt("msgs", p.priorityMessageSenders)
            .putInt("visual", p.suppressedVisualEffects)
            .apply()
        var cats = p.priorityCategories or NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS
        if (Build.VERSION.SDK_INT >= 28) cats = cats or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        nm.notificationPolicy = NotificationManager.Policy(cats, p.priorityCallSenders, p.priorityMessageSenders, p.suppressedVisualEffects)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    fun restore(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return
        val nm = nm(ctx)
        if (nm.isNotificationPolicyAccessGranted) {
            nm.notificationPolicy = NotificationManager.Policy(
                prefs.getInt("cats", 0), prefs.getInt("calls", 0), prefs.getInt("msgs", 0), prefs.getInt("visual", 0),
            )
            // Leave it alone if the user changed Do Not Disturb themselves during the session.
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                nm.setInterruptionFilter(prefs.getInt("filter", NotificationManager.INTERRUPTION_FILTER_ALL))
            }
        }
        prefs.edit().clear().apply()
    }
}
