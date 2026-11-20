package com.friday.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.friday.app.AppContainer
import com.friday.app.R
import com.friday.app.ai.Undo
import com.friday.app.data.AppSettings
import com.friday.app.data.TaskSnapshot
import com.friday.app.data.db.FocusOutcome
import com.friday.app.data.db.FocusSessionEntity
import com.friday.app.data.db.SubtaskEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.FocusMath
import com.friday.app.domain.Matrix
import com.friday.app.focus.Dnd
import com.friday.app.focus.ShieldPermissions
import com.friday.app.ui.Fmt
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.BarIcon
import com.friday.app.ui.components.CheckCircle
import com.friday.app.ui.components.Ic
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.Pill
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.SecondaryButton
import com.friday.app.ui.components.Section
import com.friday.app.ui.components.SwitchRow
import com.friday.app.ui.components.TextAction
import com.friday.app.ui.components.rememberHaptics
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModel(private val c: AppContainer, taskId: Long?) : ViewModel() {
    val session: StateFlow<FocusSessionEntity?> = c.focus.active.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val loaded = MutableStateFlow(false)
    val selectedTask = MutableStateFlow(taskId?.takeIf { it > 0 })
    val task: StateFlow<TaskWithDetails?> = selectedTask
        .flatMapLatest { id -> session.map { it?.taskId ?: id } }
        .flatMapLatest { id -> if (id == null) flowOf(null) else c.tasks.observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val candidates: StateFlow<List<TaskWithDetails>> = c.tasks.observeOpen()
        .map { open -> Matrix.rankForNow(open.map { it.task }, LocalDateTime.now()).take(6).mapNotNull { r -> open.firstOrNull { it.task.id == r.id } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        viewModelScope.launch { c.focus.current(); loaded.value = true }
    }

    fun start(title: String, minutes: Int, dnd: Boolean, shield: Boolean) = viewModelScope.launch {
        c.focus.start(task.value?.task?.id, title, minutes, dnd, shield)
        c.settings.setFocusMin(minutes)
    }
    fun pause(id: Long) = viewModelScope.launch { c.focus.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { c.focus.resume(id) }
    fun addMinutes(id: Long) = viewModelScope.launch { c.focus.addMinutes(id, 5) }
    fun toggleStep(s: SubtaskEntity) = viewModelScope.launch { c.tasks.setSubtaskDone(s, !s.done) }
    suspend fun end(id: Long, outcome: FocusOutcome): FocusSessionEntity? = c.focus.end(id, outcome, null)
    fun saveNote(id: Long, note: String) = viewModelScope.launch { c.focus.saveNote(id, note) }
    suspend fun completeTask(id: Long): TaskSnapshot? = c.tasks.complete(id)
    suspend fun restore(s: TaskSnapshot) = c.tasks.restore(s.copy())
    suspend fun nextTask(exclude: Long?): TaskWithDetails? {
        val open = c.tasks.openTasks().filter { it.task.id != exclude }
        val first = Matrix.rankForNow(open.map { it.task }, LocalDateTime.now()).firstOrNull() ?: return null
        return open.firstOrNull { it.task.id == first.id }
    }
}

private enum class Stage { SETUP, RUNNING, CHECK_IN, NOTE, RESULT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(nav: NavController, taskId: Long?, minutes: Int?) {
    val vm = fridayViewModel(key = "focus$taskId") { FocusViewModel(it, taskId) }
    val session by vm.session.collectAsStateWithLifecycle()
    val task by vm.task.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val c = Friday.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val haptics = rememberHaptics()
    val now = rememberNowMillis()

    var ended by remember { mutableStateOf<FocusSessionEntity?>(null) }
    var outcome by rememberSaveable { mutableStateOf<FocusOutcome?>(null) }
    var checkInRequested by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var nextUp by remember { mutableStateOf<TaskWithDetails?>(null) }
    var noteDone by rememberSaveable { mutableStateOf(false) }

    val s = session
    val timeUp = s != null && FocusMath.remainingMs(s, now) == 0L
    val stage = when {
        !loaded -> null
        outcome != null && ended != null && (outcome == FocusOutcome.COMPLETED || noteDone) -> Stage.RESULT
        outcome != null && ended != null -> Stage.NOTE
        s != null && (timeUp || checkInRequested) -> Stage.CHECK_IN
        s != null -> Stage.RUNNING
        ended != null -> Stage.RESULT
        else -> Stage.SETUP
    }

    fun finish(o: FocusOutcome) {
        val active = session ?: return
        scope.launch {
            haptics.confirm()
            val e = vm.end(active.id, o) ?: return@launch
            ended = e
            outcome = o
            if (o == FocusOutcome.COMPLETED) {
                e.taskId?.let { id ->
                    vm.completeTask(id)?.let { snap -> toaster.show(ctx.getString(R.string.done_toast, snap.details.task.title)) { vm.restore(snap) } }
                }
                nextUp = vm.nextTask(e.taskId)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(R.drawable.ic_expand_more, stringResource(R.string.close), { nav.popBackStack() })
                Text(stringResource(R.string.focus), style = MaterialTheme.typography.titleMedium, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(44.dp))
            }
            AnimatedContent(stage, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "focus") { st ->
                when (st) {
                    null -> Spacer(Modifier.fillMaxSize())
                    Stage.SETUP -> Setup(vm, task, minutes, settings, nav)
                    Stage.RUNNING -> Running(s!!, task, now, vm, onEnd = { checkInRequested = true })
                    Stage.CHECK_IN -> CheckIn(s?.taskTitle.orEmpty(), timeUp, onPick = ::finish, onKeepGoing = { checkInRequested = false })
                    Stage.NOTE -> NoteStep(
                        note = note,
                        onNote = { note = it },
                        onSave = { ended?.let { vm.saveNote(it.id, note) }; noteDone = true },
                        onSkip = { noteDone = true },
                    )
                    Stage.RESULT -> Result(
                        outcome = outcome,
                        title = ended?.taskTitle.orEmpty(),
                        nextUp = nextUp,
                        onAgain = { min ->
                            val e = ended
                            ended = null; outcome = null; note = ""; noteDone = false; checkInRequested = false
                            vm.start(e?.taskTitle ?: ctx.getString(R.string.focus), min, settings.focusDnd && Dnd.hasAccess(ctx), e?.shield == true)
                        },
                        onNext = { id -> nav.popBackStack(); nav.navigate("focus/$id") },
                        onDone = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(vm: FocusViewModel, task: TaskWithDetails?, minutesArg: Int?, settings: AppSettings, nav: NavController) {
    val c = Friday.colors
    val ctx = LocalContext.current
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val defaultMin = minutesArg ?: task?.task?.durationMin?.coerceIn(5, 120) ?: settings.focusDefaultMin
    var minutes by rememberSaveable(task?.task?.id) { mutableIntStateOf(defaultMin) }
    var customOpen by remember { mutableStateOf(false) }
    var dnd by rememberSaveable { mutableStateOf(settings.focusDnd) }
    var shield by rememberSaveable { mutableStateOf(settings.shieldApps.isNotEmpty()) }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val dndAccess = remember(resumeTick) { Dnd.hasAccess(ctx) }
    val shieldReady = remember(resumeTick) { ShieldPermissions.ready(ctx) && settings.shieldApps.isNotEmpty() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            task?.task?.title ?: stringResource(R.string.focus_pick_title),
            style = MaterialTheme.typography.headlineSmall,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
        )
        if (task == null) {
            Section(header = stringResource(R.string.focus_on_what)) {
                candidates.forEachIndexed { i, d ->
                    if (i > 0) RowDivider()
                    ListRow(d.task.title, subtitle = Fmt.taskMeta(ctx, d.task), onClick = { vm.selectedTask.value = d.task.id }, chevron = false)
                }
                if (candidates.isNotEmpty()) RowDivider()
                ListRow(stringResource(R.string.just_focus), titleColor = c.textSecondary, onClick = { vm.selectedTask.value = null; vm.start(ctx.getString(R.string.focus), minutes, dnd && dndAccess, shield && shieldReady) }, chevron = false)
            }
        } else {
            task.subtasks.sortedBy { it.position }.firstOrNull { !it.done }?.let { step ->
                Text(
                    stringResource(R.string.next_step) + " " + step.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                )
            }
        }
        Text(stringResource(R.string.focus_length), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp))
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 15, 25, 45).forEach { m -> Pill(Fmt.duration(ctx, m), minutes == m, { minutes = m }) }
            Pill(if (minutes in listOf(5, 15, 25, 45)) stringResource(R.string.custom) else Fmt.duration(ctx, minutes), minutes !in listOf(5, 15, 25, 45), { customOpen = true })
        }
        Section(modifier = Modifier.padding(top = 22.dp)) {
            if (dndAccess) {
                SwitchRow(stringResource(R.string.dnd_during_focus), dnd, { dnd = it }, icon = R.drawable.ic_do_not_disturb_on, iconBackground = c.accent)
            } else {
                ListRow(
                    stringResource(R.string.allow_dnd), subtitle = stringResource(R.string.allow_dnd_sub), icon = R.drawable.ic_do_not_disturb_on, iconBackground = c.accent,
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
            }
            RowDivider(60.dp)
            if (shieldReady) {
                SwitchRow(stringResource(R.string.distraction_shield), shield, { shield = it }, subtitle = stringResource(R.string.shield_apps_count, settings.shieldApps.size), icon = R.drawable.ic_shield, iconBackground = c.green)
            } else {
                ListRow(stringResource(R.string.setup_shield), subtitle = stringResource(R.string.setup_shield_sub), icon = R.drawable.ic_shield, iconBackground = c.green, onClick = { nav.navigate("settings/shield") })
            }
        }
        Spacer(Modifier.height(28.dp))
        if (task != null) {
            PrimaryButton(
                stringResource(R.string.start_n, Fmt.duration(ctx, minutes)),
                { vm.start(task.task.title, minutes, dnd && dndAccess, shield && shieldReady) },
                Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                icon = R.drawable.ic_play_arrow,
            )
        }
    }
    if (customOpen) {
        OptionSheet(
            title = stringResource(R.string.focus_length),
            options = listOf(10, 20, 30, 40, 50, 60, 75, 90, 120).map { Fmt.duration(ctx, it) to it },
            selected = minutes,
            onDismiss = { customOpen = false },
            onPick = { minutes = it; customOpen = false },
        )
    }
}

@Composable
private fun Running(s: FocusSessionEntity, task: TaskWithDetails?, now: Long, vm: FocusViewModel, onEnd: () -> Unit) {
    val c = Friday.colors
    val ctx = LocalContext.current
    val reduce = Friday.reduceMotion
    val target = FocusMath.progress(s, now)
    val progress by animateFloatAsState(target, if (reduce) tween(0) else tween(950, easing = LinearEasing), label = "ring")
    val left = FocusMath.remainingMs(s, now)
    val paused = s.pausedAt != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.taskTitle, style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
        Box(Modifier.padding(vertical = 20.dp).size(270.dp).semantics { contentDescription = ctx.getString(R.string.time_left_a11y, countdown(left)) }, contentAlignment = Alignment.Center) {
            val track = c.fill
            val accent = if (paused) c.textTertiary else c.accent
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(accent, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(countdown(left), style = MaterialTheme.typography.displayLarge, color = if (paused) c.textSecondary else c.text)
                Text(
                    if (paused) stringResource(R.string.focus_paused) else stringResource(R.string.of_n, Fmt.duration(ctx, s.plannedMin)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            }
        }
        val step = task?.subtasks?.sortedBy { it.position }?.firstOrNull { !it.done }
        if (step != null) {
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckCircle(false, { vm.toggleStep(step) }, stringResource(R.string.complete_step))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(stringResource(R.string.next_step), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    Text(step.title, style = MaterialTheme.typography.bodyLarge, color = c.text)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.plus_5), { vm.addMinutes(s.id) }, height = 56.dp)
            if (paused) PrimaryButton(stringResource(R.string.resume), { vm.resume(s.id) }, Modifier.weight(1f).height(56.dp), icon = R.drawable.ic_play_arrow)
            else PrimaryButton(stringResource(R.string.pause), { vm.pause(s.id) }, Modifier.weight(1f).height(56.dp), icon = R.drawable.ic_pause, color = c.text, contentColor = c.surface)
        }
        Spacer(Modifier.height(8.dp))
        TextAction(stringResource(R.string.end_session), onEnd, color = c.red)
        Row(Modifier.padding(top = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (s.dnd) StatusChip(R.drawable.ic_do_not_disturb_on, stringResource(R.string.dnd_on))
            if (s.shield) StatusChip(R.drawable.ic_shield, stringResource(R.string.shield_on))
        }
    }
}

@Composable
private fun StatusChip(icon: Int, text: String) {
    val c = Friday.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Ic(icon, tint = c.textSecondary, size = 16.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
    }
}

@Composable
private fun CheckIn(title: String, timeUp: Boolean, onPick: (FocusOutcome) -> Unit, onKeepGoing: () -> Unit) {
    val c = Friday.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(if (timeUp) R.string.focus_time_up else R.string.ending_session), style = MaterialTheme.typography.labelSmall, color = c.accent)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.how_did_it_go), style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        Section(inset = 0.dp) {
            ListRow(stringResource(R.string.outcome_completed), icon = R.drawable.ic_check_circle, iconBackground = c.green, onClick = { onPick(FocusOutcome.COMPLETED) })
            RowDivider(60.dp)
            ListRow(stringResource(R.string.outcome_progress), icon = R.drawable.ic_bolt, iconBackground = c.accent, onClick = { onPick(FocusOutcome.PROGRESS) })
            RowDivider(60.dp)
            ListRow(stringResource(R.string.outcome_distracted), icon = R.drawable.ic_coffee, iconBackground = c.amber, onClick = { onPick(FocusOutcome.DISTRACTED) })
        }
        if (!timeUp) TextAction(stringResource(R.string.keep_going), onKeepGoing, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun NoteStep(note: String, onNote: (String) -> Unit, onSave: () -> Unit, onSkip: () -> Unit) {
    val c = Friday.colors
    val dictate = rememberDictation { onNote(if (note.isBlank()) it else "$note $it") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.where_did_you_stop), style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center)
        Text(stringResource(R.string.where_did_you_stop_sub), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            BasicTextField(
                note, { onNote(it.take(500)) },
                modifier = Modifier.weight(1f).padding(vertical = 14.dp).height(110.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner -> Box { if (note.isEmpty()) Text(stringResource(R.string.where_did_you_stop_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary); inner() } },
            )
            if (dictate != null) BarIcon(R.drawable.ic_mic, stringResource(R.string.speak), dictate)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.save), onSave, Modifier.fillMaxWidth(), enabled = note.isNotBlank())
        TextAction(stringResource(R.string.skip), onSkip, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Result(
    outcome: FocusOutcome?,
    title: String,
    nextUp: TaskWithDetails?,
    onAgain: (Int) -> Unit,
    onNext: (Long) -> Unit,
    onDone: () -> Unit,
) {
    val c = Friday.colors
    val reduce = Friday.reduceMotion
    var shown by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.6f, androidx.compose.animation.core.spring(dampingRatio = 0.45f), label = "celebrate")
    val (icon, tint, line) = when (outcome) {
        FocusOutcome.COMPLETED -> Triple(R.drawable.ic_check_circle_fill, c.green, stringResource(R.string.result_completed, title))
        FocusOutcome.PROGRESS -> Triple(R.drawable.ic_bolt, c.accent, stringResource(R.string.result_progress))
        FocusOutcome.DISTRACTED -> Triple(R.drawable.ic_coffee, c.amber, stringResource(R.string.result_distracted))
        null -> Triple(R.drawable.ic_check, c.accent, stringResource(R.string.result_ended))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier.size(88.dp).scale(scale).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Ic(icon, tint = tint, size = 44.dp) }
        Spacer(Modifier.height(24.dp))
        Text(line, style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        when (outcome) {
            FocusOutcome.COMPLETED -> {
                if (nextUp != null) {
                    PrimaryButton(stringResource(R.string.next_task, nextUp.task.title), { onNext(nextUp.task.id) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                SecondaryButton(stringResource(R.string.take_a_break), onDone, Modifier.fillMaxWidth())
            }
            FocusOutcome.PROGRESS -> {
                PrimaryButton(stringResource(R.string.another_round), { onAgain(25) }, Modifier.fillMaxWidth(), icon = R.drawable.ic_restart_alt)
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.take_a_break), onDone, Modifier.fillMaxWidth())
            }
            FocusOutcome.DISTRACTED -> {
                PrimaryButton(stringResource(R.string.try_5), { onAgain(5) }, Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow)
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.not_now), onDone, Modifier.fillMaxWidth())
            }
            null -> SecondaryButton(stringResource(R.string.done), onDone, Modifier.fillMaxWidth())
        }
    }
}
