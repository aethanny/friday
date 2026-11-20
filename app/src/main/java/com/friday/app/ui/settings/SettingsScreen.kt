package com.friday.app.ui.settings

import com.friday.app.data.SecretStore
import androidx.compose.foundation.clickable
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.friday.app.AppContainer
import com.friday.app.BuildConfig
import com.friday.app.R
import com.friday.app.data.AppSettings
import com.friday.app.data.SettingsStore
import com.friday.app.data.ThemeMode
import com.friday.app.focus.Dnd
import com.friday.app.focus.ShieldPermissions
import com.friday.app.ui.Fmt
import com.friday.app.ui.components.EmptyState
import com.friday.app.ui.components.FridaySwitch
import com.friday.app.ui.components.Ic
import com.friday.app.ui.components.LargeTitleScreen
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.Section
import com.friday.app.ui.components.Segmented
import com.friday.app.ui.components.SwitchRow
import com.friday.app.ui.screens.OptionSheet
import com.friday.app.ui.screens.TimeDialog
import com.friday.app.ui.screens.fridayViewModel
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

class SettingsViewModel(val c: AppContainer) : ViewModel() {
    val s: StateFlow<AppSettings?> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun set(reschedule: Boolean = false, resyncCues: Boolean = false, block: suspend SettingsStore.() -> Unit) = viewModelScope.launch {
        c.settings.block()
        if (reschedule) c.briefing.schedule()
        if (resyncCues) c.tasks.openTasks().forEach { c.tasks.save(it.task) }
    }

    fun clearChat() = viewModelScope.launch { c.db.messages().clear() }

    /** Deletes everything FRIDAY stored on this phone and returns to the welcome screen. */
    fun clearAll(ctx: Context) = c.scope.launch {
        c.db.tasks().allCues().forEach { c.scheduler.cancel(it.id) }
        c.focus.current()?.let { c.focus.end(it.id, null, null) }
        withContext(Dispatchers.IO) { c.db.clearAllTables() }
        c.secrets.clear()
        NotificationManagerCompat.from(ctx).cancelAll()
        c.settings.clear()
        c.briefing.schedule()
    }
}

fun appLanguage(ctx: Context): String =
    if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else ""

fun setAppLanguage(ctx: Context, tag: String) {
    if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
}

@Composable
fun SettingsScreen(nav: NavController) {
    val vm = fridayViewModel { SettingsViewModel(it) }
    val s by vm.s.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = Friday.colors
    var resume by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }
    val notificationsOn = remember(resume) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val exact = remember(resume) { vm.c.scheduler.canExact() }
    val dndAccess = remember(resume) { Dnd.hasAccess(ctx) }
    val shieldReady = remember(resume) { ShieldPermissions.ready(ctx) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val st = s ?: return

    LargeTitleScreen(title = stringResource(R.string.settings), onBack = { nav.popBackStack() }) {
        item(key = "profile") {
            Section(header = stringResource(R.string.set_profile)) {
                ListRow(stringResource(R.string.set_name), icon = R.drawable.ic_account_circle, iconBackground = c.accent, value = st.name.ifBlank { stringResource(R.string.not_set) }, onClick = { dialog = "name" })
            }
        }
        item(key = "look") {
            Section(header = stringResource(R.string.set_appearance), modifier = Modifier.padding(top = 22.dp)) {
                Box(Modifier.padding(12.dp)) {
                    Segmented(
                        listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                        st.theme.ordinal,
                        { i -> vm.set { setTheme(ThemeMode.entries[i]) } },
                    )
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    RowDivider(0.dp)
                    val tag = remember(resume) { appLanguage(ctx) }
                    ListRow(
                        stringResource(R.string.set_language), icon = R.drawable.ic_language, iconBackground = c.accent,
                        value = when { tag.startsWith("ms") -> "Bahasa Melayu"; tag.startsWith("en") -> "English"; else -> stringResource(R.string.theme_system) },
                        onClick = { dialog = "language" },
                    )
                }
            }
        }
        item(key = "reminders") {
            Section(header = stringResource(R.string.set_reminders), footer = stringResource(R.string.set_reminders_footer), modifier = Modifier.padding(top = 22.dp)) {
                if (!notificationsOn) {
                    ListRow(stringResource(R.string.notifications_off), subtitle = stringResource(R.string.notifications_off_sub), icon = R.drawable.ic_warning, iconBackground = c.red, titleColor = c.red, onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                    RowDivider(60.dp)
                }
                if (!exact && Build.VERSION.SDK_INT >= 31) {
                    ListRow(stringResource(R.string.exact_off), subtitle = stringResource(R.string.exact_off_sub), icon = R.drawable.ic_alarm, iconBackground = c.amber, onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                    RowDivider(60.dp)
                }
                ListRow(stringResource(R.string.set_get_ready), icon = R.drawable.ic_alarm, iconBackground = c.red, value = if (st.prepLeadMin == 0) stringResource(R.string.off) else stringResource(R.string.n_before, Fmt.duration(ctx, st.prepLeadMin)), onClick = { dialog = "prep" })
                RowDivider(60.dp)
                ListRow(stringResource(R.string.set_day_reminder), icon = R.drawable.ic_calendar_today, iconBackground = c.red, value = Fmt.minuteOfDay(ctx, st.dayReminderMin), onClick = { dialog = "day" })
                RowDivider(60.dp)
                ListRow(stringResource(R.string.set_snooze), icon = R.drawable.ic_snooze, iconBackground = c.red, value = Fmt.duration(ctx, st.defaultSnoozeMin), onClick = { dialog = "snooze" })
                RowDivider(60.dp)
                SwitchRow(stringResource(R.string.set_follow_up), st.followUp, { v -> vm.set { setFollowUp(v) } }, subtitle = stringResource(R.string.set_follow_up_sub), icon = R.drawable.ic_notifications, iconBackground = c.red)
            }
        }
        item(key = "quiet") {
            Section(header = stringResource(R.string.set_quiet), footer = stringResource(R.string.set_quiet_footer), modifier = Modifier.padding(top = 22.dp)) {
                SwitchRow(stringResource(R.string.set_quiet_on), st.quietEnabled, { v -> vm.set(reschedule = true) { setQuiet(v) } }, icon = R.drawable.ic_dark_mode, iconBackground = Friday.colors.accent)
                if (st.quietEnabled) {
                    RowDivider(60.dp)
                    ListRow(stringResource(R.string.from), value = Fmt.minuteOfDay(ctx, st.quietStartMin), onClick = { dialog = "quietStart" })
                    RowDivider(16.dp)
                    ListRow(stringResource(R.string.until), value = Fmt.minuteOfDay(ctx, st.quietEndMin), onClick = { dialog = "quietEnd" })
                }
            }
        }
        item(key = "briefing") {
            Section(header = stringResource(R.string.set_briefing), footer = stringResource(R.string.set_briefing_footer), modifier = Modifier.padding(top = 22.dp)) {
                SwitchRow(stringResource(R.string.set_briefing_on), st.briefingEnabled, { v -> vm.set(reschedule = true) { setBriefing(v) } }, icon = R.drawable.ic_light_mode, iconBackground = c.amber)
                if (st.briefingEnabled) {
                    RowDivider(60.dp)
                    ListRow(stringResource(R.string.time), value = Fmt.minuteOfDay(ctx, st.briefingMin), onClick = { dialog = "briefing" })
                }
            }
        }
        item(key = "focus") {
            Section(header = stringResource(R.string.focus), modifier = Modifier.padding(top = 22.dp)) {
                ListRow(stringResource(R.string.set_focus_length), icon = R.drawable.ic_timer, iconBackground = c.accent, value = Fmt.duration(ctx, st.focusDefaultMin), onClick = { dialog = "focus" })
                RowDivider(60.dp)
                if (dndAccess) {
                    SwitchRow(stringResource(R.string.dnd_during_focus), st.focusDnd, { v -> vm.set { setFocusDnd(v) } }, icon = R.drawable.ic_do_not_disturb_on, iconBackground = c.accent)
                } else {
                    ListRow(stringResource(R.string.allow_dnd), subtitle = stringResource(R.string.allow_dnd_sub), icon = R.drawable.ic_do_not_disturb_on, iconBackground = c.accent, onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                }
                RowDivider(60.dp)
                ListRow(
                    stringResource(R.string.distraction_shield), icon = R.drawable.ic_shield, iconBackground = c.green,
                    value = if (shieldReady && st.shieldApps.isNotEmpty()) stringResource(R.string.shield_apps_count, st.shieldApps.size) else stringResource(R.string.off),
                    onClick = { nav.navigate("settings/shield") },
                )
            }
        }
        item(key = "assistant") {
            val keys by vm.c.secrets.version.collectAsStateWithLifecycle()
            val aiOn = remember(keys) { vm.c.secrets.has(SecretStore.AI_KEY) } && st.aiEnabled
            val eleven = remember(keys) { vm.c.secrets.has(SecretStore.ELEVEN_KEY) }
            Section(header = stringResource(R.string.set_assistant), modifier = Modifier.padding(top = 22.dp)) {
                ListRow(stringResource(R.string.ai_title), icon = R.drawable.ic_auto_awesome, iconBackground = c.accent, value = stringResource(if (aiOn) R.string.on else R.string.off), onClick = { nav.navigate("settings/ai") })
                RowDivider(60.dp)
                ListRow(stringResource(R.string.voice_title), icon = R.drawable.ic_graphic_eq, iconBackground = c.accent, value = if (eleven) "ElevenLabs" else stringResource(R.string.voice_phone), onClick = { nav.navigate("settings/voice") })
            }
        }
        item(key = "privacy") {
            Section(header = stringResource(R.string.set_privacy), footer = stringResource(R.string.privacy_text), modifier = Modifier.padding(top = 22.dp)) {
                ListRow(stringResource(R.string.clear_chat), icon = R.drawable.ic_delete, iconBackground = c.textTertiary, onClick = { dialog = "clearChat" }, chevron = false)
                RowDivider(60.dp)
                ListRow(stringResource(R.string.clear_all), icon = R.drawable.ic_delete, iconBackground = c.red, titleColor = c.red, onClick = { dialog = "clearAll" }, chevron = false)
            }
        }
        item(key = "about") {
            Section(header = stringResource(R.string.set_about), footer = stringResource(R.string.about_text), modifier = Modifier.padding(top = 22.dp)) {
                ListRow("FRIDAY", icon = R.drawable.ic_info, iconBackground = c.textTertiary, value = BuildConfig.VERSION_NAME)
            }
        }
    }

    when (dialog) {
        "name" -> TextDialog(stringResource(R.string.set_name), st.name, { dialog = null }, capitalization = KeyboardCapitalization.Words) { v -> dialog = null; vm.set { setName(v) } }
        "language" -> OptionSheet(
            stringResource(R.string.set_language),
            listOf(stringResource(R.string.theme_system) to "", "English" to "en", "Bahasa Melayu" to "ms"),
            appLanguage(ctx).take(2),
            { dialog = null },
        ) { tag -> dialog = null; setAppLanguage(ctx, tag) }
        "prep" -> OptionSheet(stringResource(R.string.set_get_ready), listOf(0, 5, 10, 15, 30, 60).map { (if (it == 0) ctx.getString(R.string.off) else ctx.getString(R.string.n_before, Fmt.duration(ctx, it))) to it }, st.prepLeadMin, { dialog = null }) { v ->
            dialog = null; vm.set(resyncCues = true) { setPrepLead(v) }
        }
        "snooze" -> OptionSheet(stringResource(R.string.set_snooze), listOf(5, 10, 15, 30, 60).map { Fmt.duration(ctx, it) to it }, st.defaultSnoozeMin, { dialog = null }) { v -> dialog = null; vm.set { setSnooze(v) } }
        "focus" -> OptionSheet(stringResource(R.string.set_focus_length), listOf(5, 15, 25, 45, 60).map { Fmt.duration(ctx, it) to it }, st.focusDefaultMin, { dialog = null }) { v -> dialog = null; vm.set { setFocusMin(v) } }
        "day" -> MinuteDialog(st.dayReminderMin, { dialog = null }) { v -> dialog = null; vm.set(resyncCues = true) { setDayReminder(v) } }
        "quietStart" -> MinuteDialog(st.quietStartMin, { dialog = null }) { v -> dialog = null; vm.set(reschedule = true) { setQuietStart(v) } }
        "quietEnd" -> MinuteDialog(st.quietEndMin, { dialog = null }) { v -> dialog = null; vm.set(reschedule = true) { setQuietEnd(v) } }
        "briefing" -> MinuteDialog(st.briefingMin, { dialog = null }) { v -> dialog = null; vm.set(reschedule = true) { setBriefingTime(v) } }
        "clearChat" -> ConfirmDialog(stringResource(R.string.clear_chat_q), stringResource(R.string.clear_chat_body), stringResource(R.string.clear), { dialog = null }) { dialog = null; vm.clearChat() }
        "clearAll" -> ConfirmDialog(stringResource(R.string.clear_all_q), stringResource(R.string.clear_all_body), stringResource(R.string.delete_everything), { dialog = null }) { dialog = null; vm.clearAll(ctx.applicationContext) }
    }
}

@Composable
private fun MinuteDialog(minute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) =
    TimeDialog(LocalTime.of(minute / 60, minute % 60), onDismiss, { onPick(it.hour * 60 + it.minute) })

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = Friday.colors.red) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    secret: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    onSave: (String) -> Unit,
) {
    val c = Friday.colors
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            BasicTextField(
                v, { v = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = if (secret) KeyboardCapitalization.None else capitalization),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        },
        confirmButton = { TextButton({ onSave(v.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

class ShieldViewModel(private val c: AppContainer, ctx: Context) : ViewModel() {
    val s: StateFlow<AppSettings?> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val apps = MutableStateFlow<List<AppInfo>?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = ctx.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            apps.value = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it }
                .distinctBy { it.first }
                .filter { it.first != ctx.packageName }
                .map { (pkg, ri) -> AppInfo(pkg, ri.loadLabel(pm).toString(), runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun toggle(pkg: String, on: Boolean) = viewModelScope.launch {
        val cur = c.settings.current().shieldApps
        c.settings.setShieldApps(if (on) cur + pkg else cur - pkg)
    }
}

@Composable
fun ShieldSettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val vm = fridayViewModel { ShieldViewModel(it, ctx.applicationContext) }
    val s by vm.s.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val c = Friday.colors
    var resume by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }
    val usage = remember(resume) { ShieldPermissions.usageAccess(ctx) }
    val overlay = remember(resume) { ShieldPermissions.overlay(ctx) }
    val selected = s?.shieldApps.orEmpty()

    LargeTitleScreen(title = stringResource(R.string.distraction_shield), onBack = { nav.popBackStack() }) {
        item(key = "intro") {
            Text(stringResource(R.string.shield_intro), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp))
        }
        item(key = "perms") {
            Section(header = stringResource(R.string.shield_permissions), footer = stringResource(R.string.shield_permissions_footer)) {
                ListRow(
                    stringResource(R.string.usage_access), subtitle = stringResource(R.string.usage_access_sub), icon = R.drawable.ic_visibility_off, iconBackground = if (usage) c.green else c.textTertiary,
                    value = stringResource(if (usage) R.string.allowed else R.string.not_allowed),
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
                RowDivider(60.dp)
                ListRow(
                    stringResource(R.string.overlay_access), subtitle = stringResource(R.string.overlay_access_sub), icon = R.drawable.ic_shield, iconBackground = if (overlay) c.green else c.textTertiary,
                    value = stringResource(if (overlay) R.string.allowed else R.string.not_allowed),
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
            }
        }
        item(key = "apps") {
            val list = apps
            Section(header = stringResource(R.string.shield_apps), modifier = Modifier.padding(top = 22.dp)) {
                if (list == null) {
                    Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(16.dp))
                } else if (list.isEmpty()) {
                    EmptyState(R.drawable.ic_shield, stringResource(R.string.shield_apps), stringResource(R.string.no_apps))
                } else {
                    val ordered = list.sortedByDescending { it.pkg in selected }
                    ordered.forEachIndexed { i, app ->
                        if (i > 0) RowDivider(64.dp)
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggle(app.pkg, app.pkg !in selected) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            app.icon?.let { Image(it, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))) } ?: Ic(R.drawable.ic_grid_view, size = 32.dp)
                            Spacer(Modifier.width(16.dp))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f))
                            FridaySwitch(app.pkg in selected, { vm.toggle(app.pkg, it) })
                        }
                    }
                }
            }
        }
    }
}

