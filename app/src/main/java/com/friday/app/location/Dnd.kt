package com.friday.app.location

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.friday.app.domain.DndReasons

/** Do Not Disturb with a reason, so focus ending never un-silences a mosque (and the other way round). Faked in tests. */
interface DndControl {
    fun enable(reason: String)
    fun restore(reason: String)
}

class SystemDnd(private val ctx: Context) : DndControl {
    override fun enable(reason: String) = Dnd.enable(ctx, reason)
    override fun restore(reason: String) = Dnd.restore(ctx, reason)
}

/**
 * Turns on Do Not Disturb (priority only) at a quiet place and puts the user's own setting back
 * afterwards. Reminders still come through so FRIDAY can reach the user for the focused task.
 */
object Dnd {
    private const val PREFS = "focus_dnd"
    private const val REASONS = "reasons"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun hasAccess(ctx: Context) = nm(ctx).isNotificationPolicyAccessGranted

    /** Adds [reason] to the stored set and returns it. Prefs only, never the system policy, so tests can use their own file. */
    fun addReason(prefs: SharedPreferences, reason: String): Set<String> =
        DndReasons.add(prefs.getStringSet(REASONS, null).orEmpty(), reason).also { prefs.edit().putStringSet(REASONS, it).apply() }

    fun removeReason(prefs: SharedPreferences, reason: String): Set<String> =
        DndReasons.remove(prefs.getStringSet(REASONS, null).orEmpty(), reason).also { prefs.edit().putStringSet(REASONS, it).apply() }

    fun enable(ctx: Context, reason: String) {
        val nm = nm(ctx)
        if (!nm.isNotificationPolicyAccessGranted) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        addReason(prefs, reason)
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

    fun restore(ctx: Context, reason: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val after = removeReason(prefs, reason)
        if (!DndReasons.shouldRestore(after) || !prefs.getBoolean("active", false)) return
        val nm = nm(ctx)
        if (nm.isNotificationPolicyAccessGranted) {
            nm.notificationPolicy = NotificationManager.Policy(
                prefs.getInt("cats", 0), prefs.getInt("calls", 0), prefs.getInt("msgs", 0), prefs.getInt("visual", 0),
            )
            // Leave it alone if the user changed Do Not Disturb themselves while it was on.
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                nm.setInterruptionFilter(prefs.getInt("filter", NotificationManager.INTERRUPTION_FILTER_ALL))
            }
        }
        prefs.edit().clear().apply()
    }
}
