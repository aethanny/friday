package com.friday.app.focus

import androidx.activity.enableEdgeToEdge
import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.friday.app.MainActivity
import com.friday.app.R
import com.friday.app.container
import com.friday.app.data.ThemeMode
import com.friday.app.domain.FocusMath
import com.friday.app.reminders.Channels
import com.friday.app.reminders.Extras
import com.friday.app.reminders.NotificationIds
import com.friday.app.ui.components.Ic
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.TextAction
import com.friday.app.ui.theme.Friday
import com.friday.app.ui.theme.FridayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object ShieldPermissions {
    fun usageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun overlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun ready(ctx: Context) = usageAccess(ctx) && overlay(ctx)
}

/**
 * Runs only during a focus session with the shield on. It watches which app is in front and covers
 * apps from the user's distraction list with a calm "back to your task" screen. It is never a hard lock.
 */
class ShieldService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private val passes = ConcurrentHashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PASS) {
            intent.getStringExtra(EXTRA_PACKAGE)?.let { passes[it] = System.currentTimeMillis() + PASS_MS }
        }
        val n = NotificationCompat.Builder(this, Channels.FOCUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.shield_on))
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NotificationIds.SHIELD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NotificationIds.SHIELD, n)
        if (loop == null) loop = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        val usm = getSystemService(UsageStatsManager::class.java)
        var front: String? = null
        var since = System.currentTimeMillis() - 5_000
        var lastShown = 0L
        while (scope.isActive) {
            val s = container.focus.current()
            val now = System.currentTimeMillis()
            if (s == null || !s.shield || FocusMath.remainingMs(s, now) == 0L) break
            val events = usm.queryEvents(since, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == 1) front = e.packageName // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
            }
            since = now
            val pkg = front
            if (s.pausedAt == null && pkg != null && pkg != packageName && pkg in container.settings.current().shieldApps &&
                (passes[pkg] ?: 0L) < now && now - lastShown > 4_000
            ) {
                lastShown = now
                startActivity(
                    Intent(this, ShieldActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(EXTRA_PACKAGE, pkg),
                )
            }
            delay(1_000)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PASS = "com.friday.app.SHIELD_PASS"
        const val EXTRA_PACKAGE = "friday.package"
        const val PASS_MS = 5 * 60 * 1000L

        fun start(ctx: Context) {
            if (!ShieldPermissions.ready(ctx)) return
            runCatching { ctx.startForegroundService(Intent(ctx, ShieldService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ShieldService::class.java))
        }
    }
}

class ShieldActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pkg = intent.getStringExtra(ShieldService.EXTRA_PACKAGE).orEmpty()
        val c = container
        setContent {
            val settings by c.settings.flow.collectAsState(initial = null)
            val session by c.focus.active.collectAsState(initial = null)
            val dark = when (settings?.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            FridayTheme(dark) {
                val backToTask = {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(Extras.ROUTE, "focus").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
                BackHandler { backToTask() }
                val left = session?.let { ((FocusMath.remainingMs(it, System.currentTimeMillis()) + 59_999) / 60_000).toInt() } ?: 0
                ShieldScreen(
                    task = session?.taskTitle.orEmpty(),
                    minutesLeft = left,
                    onBack = backToTask,
                    onPass = {
                        startService(Intent(this, ShieldService::class.java).setAction(ShieldService.ACTION_PASS).putExtra(ShieldService.EXTRA_PACKAGE, pkg))
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun ShieldScreen(task: String, minutesLeft: Int, onBack: () -> Unit, onPass: () -> Unit) {
    val c = Friday.colors
    Box(Modifier.fillMaxSize().background(c.background).systemBarsPadding().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Ic(R.drawable.ic_shield, tint = c.accent, size = 34.dp)
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.shield_title), style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.shield_body, task, minutesLeft),
                style = MaterialTheme.typography.bodyLarge,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            PrimaryButton(stringResource(R.string.back_to_task), onBack, Modifier.fillMaxWidth(), icon = R.drawable.ic_timer)
            Spacer(Modifier.height(8.dp))
            TextAction(stringResource(R.string.let_me_in_5), onPass, color = c.textSecondary)
        }
    }
}
