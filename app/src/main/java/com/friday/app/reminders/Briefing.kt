package com.friday.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.friday.app.R
import com.friday.app.container
import com.friday.app.data.SettingsStore
import com.friday.app.data.TaskRepository
import com.friday.app.domain.Matrix
import com.friday.app.domain.QuietHours
import com.friday.app.domain.Quadrant
import com.friday.app.domain.Schedule
import com.friday.app.ui.Fmt
import java.time.LocalDateTime
import java.time.ZoneId

/** One short morning summary, skipped on days with nothing to report. */
class Briefing(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val tasks: TaskRepository,
    private val notifier: Notifier,
) {
    private val am = ctx.getSystemService(AlarmManager::class.java)

    private fun pi(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        ctx, REQUEST_CODE, Intent(ctx, BriefingReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or flags,
    )

    suspend fun schedule(from: LocalDateTime = LocalDateTime.now()) {
        pi(PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        val s = settings.current()
        if (!s.briefingEnabled) return
        var at = from.toLocalDate().atStartOfDay().plusMinutes(s.briefingMin.toLong())
        if (!at.isAfter(from)) at = at.plusDays(1)
        if (s.quietEnabled) at = QuietHours.nextAllowed(at, s.quietStartMin, s.quietEndMin)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setWindow(AlarmManager.RTC_WAKEUP, millis, WINDOW_MS, pi(PendingIntent.FLAG_UPDATE_CURRENT)!!)
    }

    /** The summary text, or null when there is nothing worth a notification. */
    suspend fun compose(now: LocalDateTime = LocalDateTime.now()): Pair<String, String>? {
        val today = now.toLocalDate()
        val open = tasks.openTasks().map { it.task }
        val ranked = Matrix.rankForNow(open, now)
        val important = ranked.filter { Matrix.quadrant(it, today) == Quadrant.DO }
        val dueToday = open.count { !it.event && it.dueDate == today }
        val overdue = open.count { Matrix.isOverdue(it, now) }
        if (important.isEmpty() && dueToday == 0 && overdue == 0) return null
        val lines = buildList {
            if (important.isNotEmpty()) add(
                ctx.resources.getQuantityString(R.plurals.briefing_important, important.size, important.size, important.take(3).joinToString(", ") { it.title }),
            )
            if (dueToday > 0) add(ctx.resources.getQuantityString(R.plurals.briefing_due_today, dueToday, dueToday))
            if (overdue > 0) add(ctx.resources.getQuantityString(R.plurals.briefing_overdue, overdue, overdue))
            open.mapNotNull { Schedule.start(it) }.filter { it.toLocalDate() == today && it.isAfter(now) }.minOrNull()?.let {
                add(ctx.getString(R.string.briefing_first_at, Fmt.time(ctx, it.toLocalTime())))
            }
        }
        return Fmt.greeting(ctx, now.hour, settings.current().name) to lines.joinToString(" ")
    }

    suspend fun post() {
        compose()?.let { (title, text) -> notifier.briefing(title, text) }
    }

    companion object {
        private const val REQUEST_CODE = 2_000_000_001
        private const val WINDOW_MS = 5 * 60 * 1000L
    }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val b = context.container.briefing
        launchAsync {
            b.post()
            b.schedule(LocalDateTime.now().plusMinutes(1))
            context.container.ai.prepareLines()
        }
    }
}
