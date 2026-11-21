package com.friday.app.reminders

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.friday.app.MainActivity
import com.friday.app.R
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.TaskEntity
import com.friday.app.ui.Fmt
import java.time.LocalTime

object Channels {
    const val REMINDERS = "reminders"
    const val REMINDERS_VIBRATE = "reminders_vibrate"
    const val QUIET = "quiet"
    const val BRIEFING = "briefing"
}

object NotificationIds {
    const val BRIEFING = 3
    const val PLACE = 5
    const val BUNDLE = 9_999
    fun reminder(cueId: Long) = 10_000 + cueId.toInt()
}

object Extras {
    const val TASK_ID = "friday.task_id"
    const val CUE_ID = "friday.cue_id"
    const val ROUTE = "friday.route"
    const val MINUTES = "friday.minutes"
}

/** The reminder's second button (the first is Mark done / Got it). */
sealed interface SecondAction {
    data object SnoozePicker : SecondAction
    data class MoveTo(val time: LocalTime) : SecondAction
}

class Notifier(private val ctx: Context) {
    private companion object {
        const val GROUP = "friday.reminders"
        const val BUNDLE_WINDOW_MS = 2 * 60_000L
    }

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val accent = 0xFF007AFF.toInt()

    fun createChannels() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(Channels.REMINDERS, ctx.getString(R.string.ch_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.ch_reminders_desc); enableVibration(true)
                },
                NotificationChannel(Channels.REMINDERS_VIBRATE, ctx.getString(R.string.ch_reminders_vibrate), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = ctx.getString(R.string.ch_reminders_vibrate_desc); setSound(null, null); enableVibration(true)
                },
                NotificationChannel(Channels.QUIET, ctx.getString(R.string.ch_quiet), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.ch_quiet_desc)
                },
                NotificationChannel(Channels.BRIEFING, ctx.getString(R.string.ch_briefing), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = ctx.getString(R.string.ch_briefing_desc)
                },
            ),
        )
        // Focus mode was removed in v1.3: its channels (and the shield's) should not linger in system settings.
        listOf("focus", "focus_done", "shield").forEach { nm.deleteNotificationChannel(it) }
    }

    fun enabled(): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun openAppIntent(requestCode: Int, route: String? = null, taskId: Long? = null, cueId: Long? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction("friday.open.$requestCode")
        route?.let { i.putExtra(Extras.ROUTE, it) }
        taskId?.let { i.putExtra(Extras.TASK_ID, it) }
        cueId?.let { i.putExtra(Extras.CUE_ID, it) }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun receiverIntent(action: String, cueId: Long, requestCode: Int, minutes: Int = 0): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(Extras.CUE_ID, cueId).putExtra(Extras.MINUTES, minutes)
        return PendingIntent.getBroadcast(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Reminders whose "Done" finishes the whole task (as opposed to one step inside it). */
    /** Events have nothing to complete: their reminders only say "Got it". */
    fun isPrimary(t: TaskEntity, c: CueEntity) = !t.event && (
        c.kind == CueKind.DAY || c.kind == CueKind.END || (c.kind == CueKind.START && t.durationMin == null) ||
            // "Remind me to bring my charger when I leave home": the place reminder is the whole task.
            ((c.placeId != null || c.placeType != null) && t.dueDate == null)
        )

    /** Other FRIDAY reminders on screen, newest first (the summary and this cue's own notification excluded). */
    private fun otherReminders(cueId: Long) = nm.activeNotifications
        .filter { it.id >= 10_000 && it.id != NotificationIds.reminder(cueId) }
        .sortedByDescending { it.postTime }

    /** True when another reminder arrived less than 2 minutes ago and is still showing: this one joins it quietly. */
    fun shouldBundle(cueId: Long): Boolean =
        otherReminders(cueId).any { System.currentTimeMillis() - it.postTime < BUNDLE_WINDOW_MS }

    @SuppressLint("MissingPermission")
    fun postReminder(t: TaskEntity, c: CueEntity, channel: String, followUp: Boolean, body: String, second: SecondAction, bundle: Boolean) {
        if (!enabled()) return
        val others = otherReminders(c.id) // read before notify(): the new one may not be listed yet
        val base = (c.id * 8).toInt()
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent)
            .setContentTitle(t.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(base, taskId = t.id, cueId = c.id))
            .setGroup(GROUP)
            .setSilent(bundle)
        if (followUp) b.setSubText(ctx.getString(R.string.follow_up_subtext))
        when {
            isPrimary(t, c) -> b.addAction(0, ctx.getString(R.string.mark_done), receiverIntent(ReminderReceiver.ACTION_DONE, c.id, base + 1))
            else -> b.addAction(0, ctx.getString(R.string.got_it), receiverIntent(ReminderReceiver.ACTION_ACK, c.id, base + 1))
        }
        when (second) {
            SecondAction.SnoozePicker -> {
                val pick = Intent(ctx, SnoozeActivity::class.java)
                    .putExtra(Extras.CUE_ID, c.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                b.addAction(0, ctx.getString(R.string.snooze_more), PendingIntent.getActivity(ctx, base + 3, pick, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            is SecondAction.MoveTo -> b.addAction(
                0, ctx.getString(R.string.move_to, Fmt.time(ctx, second.time)),
                receiverIntent(ReminderReceiver.ACTION_MOVE, c.id, base + 4, second.time.hour * 60 + second.time.minute),
            )
        }
        nm.notify(NotificationIds.reminder(c.id), b.build())
        val titles = listOf(t.title) + others.mapNotNull { it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE) }
        if (titles.size >= 2) postSummary(channel, titles)
    }

    @SuppressLint("MissingPermission")
    private fun postSummary(channel: String, titles: List<CharSequence>) {
        val title = ctx.resources.getQuantityString(R.plurals.bundle_title, titles.size, titles.size)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        titles.forEach { style.addLine(it) }
        nm.notify(
            NotificationIds.BUNDLE,
            NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(accent)
                .setContentTitle(title)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(903, route = "home"))
                .build(),
        )
    }

    fun cancelReminder(cueId: Long) {
        nm.cancel(NotificationIds.reminder(cueId))
        if (otherReminders(cueId).size < 2) nm.cancel(NotificationIds.BUNDLE)
    }


    /** One quiet note about the place the user stopped at, with at most one button. */
    @SuppressLint("MissingPermission")
    fun place(channel: String, text: String, actionLabel: String?, action: PendingIntent?, open: PendingIntent) {
        if (!enabled()) return
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (actionLabel != null && action != null) b.addAction(0, actionLabel, action)
        nm.notify(NotificationIds.PLACE, b.build())
    }

    fun cancelPlace() = nm.cancel(NotificationIds.PLACE)

    @SuppressLint("MissingPermission")
    fun briefing(title: String, text: String) {
        if (!enabled()) return
        nm.notify(
            NotificationIds.BRIEFING,
            NotificationCompat.Builder(ctx, Channels.BRIEFING)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(accent)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(902, route = "home"))
                .build(),
        )
    }
}
