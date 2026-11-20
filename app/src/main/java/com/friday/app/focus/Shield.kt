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
import android.content.pm.PackageManager
import androidx.activity.SystemBarStyle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.friday.app.data.db.FocusSessionEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.ui.Fmt
import com.friday.app.ui.components.Eyebrow
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.ProgressRing
import com.friday.app.ui.components.TextAction
import com.friday.app.ui.components.rememberHaptics
import com.friday.app.ui.screens.countdown
import com.friday.app.ui.screens.rememberNowMillis
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.cos

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
        val appName = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            else @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
        val c = container
        setContent {
            val settings by c.settings.flow.collectAsState(initial = null)
            // null while loading, empty once we know no focus session is running.
            val sessions by remember { c.focus.active.map { listOfNotNull(it) } }.collectAsState(initial = null)
            val session = sessions?.firstOrNull()
            val task by remember(session?.taskId) { session?.taskId?.let { c.tasks.observe(it) } ?: flowOf<TaskWithDetails?>(null) }
                .collectAsState(initial = null)
            val dark = when (settings?.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(bars, bars)
            }
            LaunchedEffect(sessions) { if (sessions?.isEmpty() == true) finish() }
            FridayTheme(dark) {
                val backToTask = {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(Extras.ROUTE, "focus").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
                BackHandler { backToTask() }
                if (session == null) {
                    Box(Modifier.fillMaxSize().background(Friday.colors.background))
                } else {
                    ShieldScreen(
                        s = session,
                        appName = appName,
                        nextStep = task?.subtasks?.sortedBy { it.position }?.firstOrNull { !it.done }?.title,
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

    // Every new pull towards the app gets a fresh breath, so a shield left in the background is never reused.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
}

/** One full breath (in and out) before "let me in" unlocks. */
private const val BREATH_MS = 8_000L

/**
 * Calm, concrete and kind: what the app is pulling you away from, how close you are to done, and one
 * breath of pause. The pause is what breaks the automatic reach for the app; "Back to my task" is instant.
 */
@Composable
private fun ShieldScreen(s: FocusSessionEntity, appName: String?, nextStep: String?, onBack: () -> Unit, onPass: () -> Unit) {
    val c = Friday.colors
    val ctx = LocalContext.current
    val reduce = Friday.reduceMotion
    val haptics = rememberHaptics()
    val now = rememberNowMillis()
    val left = FocusMath.remainingMs(s, now)
    val minutesLeft = ((left + 59_999) / 60_000).toInt().coerceAtLeast(1)
    val focusedMin = (FocusMath.elapsedMs(s, now) / 60_000).toInt()

    var wait by rememberSaveable { mutableIntStateOf((BREATH_MS / 1_000).toInt()) }
    LaunchedEffect(Unit) {
        haptics.tick()
        while (wait > 0) { delay(1_000); wait-- }
    }
    val breath = produceState(0L) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { value = it - start }
    }
    val inhale by remember { derivedStateOf { (breath.value / (BREATH_MS / 2)) % 2 == 0L } }
    var shown by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(400), label = "appear")

    Box(Modifier.fillMaxSize().background(c.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .graphicsLayer { alpha = appear; scaleX = 0.96f + 0.04f * appear; scaleY = scaleX },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Eyebrow(stringResource(R.string.shield_title))
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.size(236.dp).semantics(mergeDescendants = true) { contentDescription = ctx.getString(R.string.time_left_a11y, countdown(left)) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Breathing halo: smallest at the start of an inhale, fullest at its end.
                            val k = if (reduce) 1f else ((1 - cos(2 * PI * breath.value / BREATH_MS)) / 2).toFloat()
                            scaleX = 0.86f + 0.14f * k
                            scaleY = scaleX
                        }
                        .clip(CircleShape)
                        .background(c.accentSoft),
                )
                ProgressRing(FocusMath.progress(s, now), c.accent, Modifier.size(192.dp), track = c.surface, stroke = 10.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(countdown(left), style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp, lineHeight = 48.sp), color = c.text)
                    Text(stringResource(R.string.shield_left), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                if (appName != null) stringResource(R.string.shield_can_wait, appName, Fmt.duration(ctx, minutesLeft))
                else stringResource(R.string.shield_can_wait_generic, Fmt.duration(ctx, minutesLeft)),
                style = MaterialTheme.typography.headlineSmall,
                color = c.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (focusedMin >= 1) stringResource(R.string.shield_progress, Fmt.duration(ctx, focusedMin), s.taskTitle)
                else stringResource(R.string.shield_progress_start, s.taskTitle),
                style = MaterialTheme.typography.bodyLarge,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (nextStep != null) {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.next_step), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text(nextStep, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(28.dp))
            PrimaryButton(stringResource(R.string.back_to_task), onBack, Modifier.fillMaxWidth(), icon = R.drawable.ic_timer)
            Spacer(Modifier.height(6.dp))
            if (wait > 0) {
                Text(
                    stringResource(if (inhale) R.string.breathe_in else R.string.breathe_out, wait),
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.textTertiary,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else {
                TextAction(stringResource(R.string.let_me_in_5), onPass, color = c.textSecondary)
                Text(stringResource(R.string.shield_check_again), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, textAlign = TextAlign.Center)
            }
        }
    }
}
