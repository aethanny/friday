package com.friday.app.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.friday.app.AppContainer
import com.friday.app.R
import com.friday.app.ai.EngineResult
import com.friday.app.ai.Recovery
import com.friday.app.ai.Source
import com.friday.app.ai.Undo
import com.friday.app.data.AppSettings
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.FocusSessionEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.FocusMath
import com.friday.app.domain.Matrix
import com.friday.app.domain.Quadrant
import com.friday.app.domain.Schedule
import com.friday.app.ui.Fmt
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.BarIcon
import com.friday.app.ui.components.CaptureBar
import com.friday.app.ui.components.Card
import com.friday.app.ui.components.EmptyState
import com.friday.app.ui.components.Eyebrow
import com.friday.app.ui.components.FridaySheet
import com.friday.app.ui.components.Ic
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.LocalTabBarHeight
import com.friday.app.ui.components.Pill
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.SecondaryButton
import com.friday.app.ui.components.Section
import com.friday.app.ui.components.TaskRow
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

data class Upcoming(val taskId: Long, val title: String, val at: LocalDateTime)

data class HomeState(
    val loaded: Boolean = false,
    val now: LocalDateTime = LocalDateTime.now(),
    val settings: AppSettings = AppSettings(),
    val nowTask: TaskWithDetails? = null,
    val next: List<TaskWithDetails> = emptyList(),
    val upcoming: List<Upcoming> = emptyList(),
    val left: Int = 0,
    val doneToday: Int = 0,
    val overdue: Int = 0,
    val important: Int = 0,
    val dueToday: Int = 0,
    val open: Int = 0,
    val focus: FocusSessionEntity? = null,
    val inbox: Int = 0,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<HomeState> = combine(
        combine(c.tasks.observeOpen(), c.tasks.observeCompleted(), clock()) { a, b, n -> Triple(a, b, n) },
        c.focus.active,
        c.settings.flow,
        c.notes.observeInboxCount(),
    ) { (open, completed, now), focus, settings, inbox ->
        val today = now.toLocalDate()
        val ranked = Matrix.rankForNow(open.map { it.task }, now)
        val byId = open.associateBy { it.task.id }
        val ordered = ranked.mapNotNull { byId[it.id] }
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val upcoming = open.mapNotNull { d ->
            d.cues.filter { it.kind != CueKind.PREP }
                .mapNotNull { cue -> cue.snoozedUntil?.let { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()) } ?: Schedule.trigger(d.task, cue) }
                .filter { it.isAfter(now) }
                .minOrNull()?.let { Upcoming(d.task.id, d.task.title, it) }
        }.sortedBy { it.at }.take(3)
        HomeState(
            loaded = true,
            now = now,
            settings = settings,
            nowTask = ordered.firstOrNull()?.takeIf { focus == null },
            next = ordered.drop(if (focus == null) 1 else 0).filter { it.task.id != focus?.taskId }.take(3),
            upcoming = upcoming,
            left = open.count { it.task.dueDate != null && !it.task.dueDate.isAfter(today) },
            doneToday = completed.count { (it.task.completedAt ?: 0) >= startOfDay },
            overdue = open.count { Matrix.isOverdue(it.task, now) },
            important = open.count { Matrix.quadrant(it.task, today) == Quadrant.DO },
            dueToday = open.count { it.task.dueDate == today },
            open = open.size,
            focus = focus,
            inbox = inbox,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    suspend fun capture(text: String): EngineResult = c.assistant.handle(text, Source.CAPTURE)
    suspend fun undo(u: Undo) = c.assistant.undo(u)
    suspend fun complete(id: Long) = c.tasks.complete(id)
    suspend fun restore(u: Undo.Restore) = c.tasks.restore(u.snapshot)
    suspend fun recovery(): Recovery? = c.assistant.recovery()
    fun describe(ctx: Context, r: Recovery) = c.assistant.describeRecovery(ctx, r)
    fun pause(id: Long) = viewModelScope.launch { c.focus.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { c.focus.resume(id) }
}

@Composable
fun HomeScreen(nav: NavController) {
    val vm = fridayViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = Friday.colors
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var chips by remember { mutableStateOf<List<String>>(emptyList()) }
    var prompt by remember { mutableStateOf<String?>(null) }
    var recovery by remember { mutableStateOf<Recovery?>(null) }
    var recoveryEmpty by remember { mutableStateOf(false) }

    fun submit(text: String) {
        if (text.isBlank()) return
        input = ""
        scope.launch {
            val r = vm.capture(text)
            chips = r.chips
            prompt = if (r.chips.isNotEmpty()) r.text else null
            if (r.chips.isEmpty() && r.text.isNotBlank()) toaster.show(r.text, r.undo?.let { u -> { vm.undo(u) } })
        }
    }
    val dictate = rememberDictation { submit(it) }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tabBar = LocalTabBarHeight.current
    Box(Modifier.fillMaxSize().background(c.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top + 8.dp, bottom = tabBar + 96.dp),
        ) {
            item(key = "header") { Header(state, onSettings = { nav.navigate("settings") }) }
            item(key = "main") {
                Box(Modifier.padding(horizontal = 16.dp).animateContentSize()) {
                    val focus = state.focus
                    when {
                        !state.loaded -> Spacer(Modifier.height(200.dp))
                        focus != null -> FocusCard(focus, state.now, onOpen = { nav.navigate("focus") }, onPause = { vm.pause(focus.id) }, onResume = { vm.resume(focus.id) })
                        state.nowTask != null -> NowCard(
                            state.nowTask!!, state.now,
                            onOpen = { nav.navigate("task/${state.nowTask!!.task.id}") },
                            onFocus = { nav.navigate("focus/${state.nowTask!!.task.id}") },
                            onDone = {
                                scope.launch {
                                    val snap = vm.complete(state.nowTask!!.task.id) ?: return@launch
                                    toaster.show(ctx.getString(R.string.done_toast, snap.details.task.title)) { vm.restore(Undo.Restore(snap)) }
                                }
                            },
                        )
                        else -> Card { EmptyState(R.drawable.ic_check_circle, stringResource(R.string.all_clear_title), stringResource(R.string.all_clear_body), Modifier.padding(0.dp)) }
                    }
                }
            }
            item(key = "recover") {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            scope.launch {
                                val r = vm.recovery()
                                if (r == null) recoveryEmpty = true else recovery = r
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ic(R.drawable.ic_history, tint = c.accent, size = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.forgot_button), style = MaterialTheme.typography.bodyLarge, color = c.accent, modifier = Modifier.weight(1f))
                    Ic(R.drawable.ic_chevron_right, tint = c.accent.copy(alpha = 0.6f), size = 18.dp)
                }
            }
            if (state.next.isNotEmpty()) {
                item(key = "next") {
                    Section(header = stringResource(R.string.next_up), modifier = Modifier.padding(top = 6.dp)) {
                        state.next.forEachIndexed { i, d ->
                            if (i > 0) RowDivider(52.dp)
                            TaskRow(
                                title = d.task.title,
                                done = false,
                                important = d.task.important,
                                meta = Fmt.taskMeta(ctx, d.task, state.now, d.subtasks.count { it.done }, d.subtasks.size),
                                metaColor = if (Matrix.isOverdue(d.task, state.now)) c.red else c.textSecondary,
                                onClick = { nav.navigate("task/${d.task.id}") },
                                onToggle = {
                                    scope.launch {
                                        val snap = vm.complete(d.task.id) ?: return@launch
                                        toaster.show(ctx.getString(R.string.done_toast, d.task.title)) { vm.restore(Undo.Restore(snap)) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (state.upcoming.isNotEmpty()) {
                item(key = "upcoming") {
                    Section(header = stringResource(R.string.coming_up), modifier = Modifier.padding(top = 22.dp)) {
                        state.upcoming.forEachIndexed { i, u ->
                            if (i > 0) RowDivider(84.dp)
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.navigate("task/${u.taskId}") }.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.width(68.dp)) {
                                    Text(Fmt.time(ctx, u.at.toLocalTime()), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = c.text)
                                    if (u.at.toLocalDate() != state.now.toLocalDate()) {
                                        Text(Fmt.day(ctx, u.at.toLocalDate(), state.now.toLocalDate()), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
                                    }
                                }
                                Text(u.title, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (state.inbox > 0) {
                item(key = "inbox") {
                    Section(modifier = Modifier.padding(top = 22.dp)) {
                        ListRow(
                            title = pluralStringResource(R.plurals.inbox_waiting, state.inbox, state.inbox),
                            icon = R.drawable.ic_inbox,
                            onClick = { nav.navigate("notes") },
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(bottom = tabBar)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            AnimatedVisibility(chips.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    prompt?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { chip -> Pill(chip, selected = false, onClick = { chips = emptyList(); submit(chip) }) }
                        Pill(stringResource(R.string.cancel), selected = false, onClick = { chips = emptyList(); scope.launch { vm.capture("cancel") } })
                    }
                }
            }
            CaptureBar(
                value = input,
                onValueChange = { input = it },
                onSubmit = { submit(input) },
                placeholder = stringResource(R.string.capture_placeholder),
                onMic = dictate,
            )
        }
    }

    recovery?.let { r ->
        FridaySheet(onDismiss = { recovery = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Eyebrow(stringResource(R.string.recovery_title))
                Spacer(Modifier.height(10.dp))
                Text(r.title, style = MaterialTheme.typography.headlineSmall, color = c.text)
                Spacer(Modifier.height(12.dp))
                Text(vm.describe(ctx, r), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                Spacer(Modifier.height(24.dp))
                PrimaryButton(stringResource(R.string.continue_focus), {
                    recovery = null
                    nav.navigate(r.task?.task?.id?.let { "focus/$it" } ?: "focus/0")
                }, Modifier.fillMaxWidth(), icon = R.drawable.ic_timer)
                Spacer(Modifier.height(8.dp))
                if (r.task != null) SecondaryButton(stringResource(R.string.open_task), { recovery = null; nav.navigate("task/${r.task.task.id}") }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.recovery_footer), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
        }
    }
    if (recoveryEmpty) {
        FridaySheet(onDismiss = { recoveryEmpty = false }) {
            EmptyState(R.drawable.ic_history, stringResource(R.string.recovery_title), stringResource(R.string.recovery_empty), Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun Header(s: HomeState, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val c = Friday.colors
    Column(Modifier.padding(start = 20.dp, end = 8.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.longDate(ctx, s.now.toLocalDate()).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textSecondary, modifier = Modifier.weight(1f))
            BarIcon(R.drawable.ic_account_circle, stringResource(R.string.settings), onSettings, tint = c.textSecondary)
        }
        Text(
            Fmt.greeting(ctx, s.now.hour, s.settings.name),
            style = MaterialTheme.typography.displaySmall,
            color = c.text,
            modifier = Modifier.padding(end = 12.dp).semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = 12.dp)) {
            Box(Modifier.padding(top = 2.dp).size(18.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
            }
            Spacer(Modifier.width(10.dp))
            Text(fridayLine(ctx, s), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
        }
        if (s.loaded && (s.left > 0 || s.doneToday > 0)) {
            Spacer(Modifier.height(10.dp))
            val bits = buildList {
                add(pluralStringResource(R.plurals.overview_left, s.left, s.left))
                if (s.overdue > 0) add(pluralStringResource(R.plurals.overview_overdue, s.overdue, s.overdue))
                if (s.doneToday > 0) add(pluralStringResource(R.plurals.overview_done, s.doneToday, s.doneToday))
            }
            Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = c.textTertiary, modifier = Modifier.padding(start = 28.dp))
        }
    }
}

/** FRIDAY's one-line status, chosen from the situation rather than at random. */
private fun fridayLine(ctx: Context, s: HomeState): String {
    val r = ctx.resources
    return when {
        !s.loaded -> ""
        s.focus != null -> ctx.getString(R.string.line_focusing, s.focus.taskTitle)
        s.overdue > 0 -> r.getQuantityString(R.plurals.line_overdue, s.overdue, s.overdue)
        s.important > 0 -> r.getQuantityString(R.plurals.line_important, s.important, s.important)
        s.dueToday > 0 -> r.getQuantityString(R.plurals.line_today, s.dueToday, s.dueToday)
        s.open == 0 -> ctx.getString(R.string.line_empty)
        else -> ctx.getString(R.string.line_calm)
    }
}

@Composable
private fun NowCard(d: TaskWithDetails, now: LocalDateTime, onOpen: () -> Unit, onFocus: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = Friday.colors
    val t = d.task
    val reason = Matrix.reason(t, now)
    val nextStep = d.subtasks.sortedBy { it.position }.firstOrNull { !it.done }
    Card(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(R.string.now))
            Spacer(Modifier.weight(1f))
            reasonLabel(reason)?.let { (label, warn) ->
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (warn) c.red else c.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (warn) c.red.copy(alpha = 0.1f) else c.fill).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(t.title, style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (t.important) Ic(R.drawable.ic_star_fill, tint = c.amber, size = 20.dp, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        }
        Fmt.taskMeta(ctx, t, now, d.subtasks.count { it.done }, d.subtasks.size)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (Matrix.isOverdue(t, now)) c.red else c.textSecondary)
        }
        if (nextStep != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.background).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.next_step), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                Spacer(Modifier.width(8.dp))
                Text(nextStep.title, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.start_focus), onFocus, Modifier.weight(1f), icon = R.drawable.ic_timer)
            SecondaryButton(stringResource(R.string.done), onDone, icon = R.drawable.ic_check, height = 52.dp)
        }
    }
}

private fun reasonLabel(r: Matrix.Reason): Pair<Int, Boolean>? = when (r) {
    Matrix.Reason.OVERDUE -> R.string.reason_overdue to true
    Matrix.Reason.STARTS_SOON -> R.string.reason_starts_soon to false
    Matrix.Reason.DUE_TODAY -> R.string.reason_due_today to false
    Matrix.Reason.DUE_TOMORROW -> R.string.reason_due_tomorrow to false
    Matrix.Reason.IMPORTANT -> R.string.reason_important to false
    Matrix.Reason.POSTPONED -> R.string.reason_postponed to false
    Matrix.Reason.NONE -> null
}

@Composable
private fun FocusCard(s: FocusSessionEntity, now: LocalDateTime, onOpen: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val c = Friday.colors
    val ms = rememberNowMillis()
    val left = FocusMath.remainingMs(s, ms)
    val timeUp = left == 0L
    Card(Modifier.clickable(onClick = onOpen)) {
        Eyebrow(stringResource(if (timeUp) R.string.focus_time_up else if (s.pausedAt != null) R.string.focus_paused else R.string.focusing))
        Spacer(Modifier.height(10.dp))
        Text(s.taskTitle, style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Text(
            countdown(left),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 52.sp, lineHeight = 56.sp),
            color = if (s.pausedAt != null) c.textSecondary else c.text,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { FocusMath.progress(s, ms) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = c.accent,
            trackColor = c.fill,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(16.dp))
        when {
            timeUp -> PrimaryButton(stringResource(R.string.how_did_it_go_btn), onOpen, Modifier.fillMaxWidth())
            s.pausedAt != null -> PrimaryButton(stringResource(R.string.resume), onResume, Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow)
            else -> SecondaryButton(stringResource(R.string.pause), onPause, Modifier.fillMaxWidth(), icon = R.drawable.ic_pause)
        }
    }
}

fun countdown(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
