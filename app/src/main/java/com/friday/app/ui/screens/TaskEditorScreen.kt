package com.friday.app.ui.screens

import com.friday.app.data.db.PlaceEntity
import com.friday.app.ui.settings.TextDialog
import kotlinx.coroutines.flow.combine
import com.friday.app.data.SecretStore
import com.friday.app.ai.AiException
import android.content.Context
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.friday.app.AppContainer
import com.friday.app.R
import com.friday.app.ai.Undo
import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.CueEntity
import com.friday.app.data.db.CueKind
import com.friday.app.data.db.Repeat
import com.friday.app.data.db.SubtaskEntity
import com.friday.app.data.db.TaskEntity
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.Matrix
import com.friday.app.domain.Schedule
import com.friday.app.ui.Fmt
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.BarIcon
import com.friday.app.ui.components.CheckCircle
import com.friday.app.ui.components.FridaySheet
import com.friday.app.ui.components.FridaySwitch
import com.friday.app.ui.components.Ic
import com.friday.app.ui.components.LargeTitleScreen
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.Pill
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.Section
import com.friday.app.ui.components.Segmented
import com.friday.app.ui.components.SwitchRow
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class TaskEditorViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val details: StateFlow<TaskWithDetails?> = c.tasks.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val reusable = MutableStateFlow<List<CueEntity>>(emptyList())

    init {
        viewModelScope.launch {
            c.tasks.touch(id)
            val t = c.tasks.details(id) ?: return@launch
            if (t.cues.none { it.kind == CueKind.CUSTOM }) reusable.value = c.tasks.reusableCues(id, t.task.title)
        }
    }

    fun save(t: TaskEntity) = viewModelScope.launch { c.tasks.save(t) }
    /** Text edits are saved on the app scope so leaving the screen never loses them. */
    fun saveText(title: String, notes: String, category: String) = c.scope.launch {
        val t = c.tasks.get(id) ?: return@launch
        if (t.title != title.trim() || t.notes != notes || (t.category ?: "") != category.trim()) {
            c.tasks.save(t.copy(title = title.ifBlank { t.title }, notes = notes, category = category))
        }
    }
    fun addStep(title: String) = viewModelScope.launch { if (title.isNotBlank()) c.tasks.addSubtask(id, title) }
    fun toggleStep(s: SubtaskEntity) = viewModelScope.launch { c.tasks.setSubtaskDone(s, !s.done) }
    fun deleteStep(s: SubtaskEntity) = viewModelScope.launch { c.tasks.deleteSubtask(s) }
    fun addCue(cue: CueEntity) = viewModelScope.launch { c.tasks.addCue(cue) }
    fun updateCue(cue: CueEntity) = viewModelScope.launch { c.tasks.updateCue(cue) }
    fun deleteCue(cue: CueEntity) = viewModelScope.launch { c.tasks.deleteCue(cue) }
    fun setAutoPrep(v: Boolean) = viewModelScope.launch { c.tasks.setAutoPrep(id, v) }
    fun reuse() = viewModelScope.launch { c.tasks.copyCues(id, reusable.value); reusable.value = emptyList() }
    val aiReady: StateFlow<Boolean> = combine(c.settings.flow, c.secrets.version) { s, _ -> s.aiEnabled && c.secrets.has(SecretStore.AI_KEY) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val busy = MutableStateFlow(false)

    /** Runs an AI request with a busy flag; errors come back as user-facing text. */
    private suspend fun <T> withAi(ctx: Context, block: suspend () -> T): Result<T> {
        busy.value = true
        return try {
            Result.success(block())
        } catch (e: AiException) {
            Result.failure(Exception(c.ai.errorText(ctx, e)))
        } finally {
            busy.value = false
        }
    }

    suspend fun suggestReminders(ctx: Context) = withAi(ctx) { c.ai.suggestReminders(c.tasks.get(id)!!, appLang(ctx)) }
    suspend fun breakDown(ctx: Context) = withAi(ctx) { c.ai.breakDown(c.tasks.details(id)!!, appLang(ctx)) }
    fun addCues(cues: List<CueEntity>) = viewModelScope.launch { cues.forEach { c.tasks.addCue(it) } }
    fun addSteps(steps: List<String>) = viewModelScope.launch { c.tasks.addSubtasks(id, steps) }
    val places: StateFlow<List<PlaceEntity>> = c.db.places().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun addPlaceCue(placeId: Long, leave: Boolean, message: String) = viewModelScope.launch { c.tasks.addPlaceCue(id, placeId, leave, message.trim().take(300)) }
    suspend fun delete() = c.tasks.delete(id)
    suspend fun complete() = c.tasks.complete(id)
    fun reopen() = viewModelScope.launch { c.tasks.reopen(id) }
    suspend fun restore(u: Undo.Restore) = c.tasks.restore(u.snapshot)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(nav: NavController, id: Long) {
    val vm = fridayViewModel(key = "task$id") { TaskEditorViewModel(it, id) }
    val d by vm.details.collectAsStateWithLifecycle()
    val reusable by vm.reusable.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = Friday.colors
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    LaunchedEffect(d) {
        val t = d?.task ?: return@LaunchedEffect
        if (!loaded) { title = t.title; notes = t.notes; category = t.category.orEmpty(); loaded = true }
    }
    LaunchedEffect(title, notes, category) {
        if (!loaded) return@LaunchedEffect
        delay(600)
        vm.saveText(title, notes, category)
    }
    val latest = rememberUpdatedState(Triple(title, notes, category))
    DisposableEffect(Unit) { onDispose { if (loaded) latest.value.let { (t, n, cat) -> vm.saveText(t, n, cat) } } }

    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickDuration by remember { mutableStateOf(false) }
    var pickRepeat by remember { mutableStateOf(false) }
    var cueSheet by remember { mutableStateOf<CueEntity?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val newStep = rememberTextFieldState()
    val aiReady by vm.aiReady.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var suggestedCues by remember { mutableStateOf<List<CueEntity>?>(null) }
    var suggestedSteps by remember { mutableStateOf<List<String>?>(null) }
    var askFirstStep by remember { mutableStateOf(false) }
    val places by vm.places.collectAsStateWithLifecycle()
    var placeSheet by remember { mutableStateOf(false) }
    val suggest: () -> Unit = {
        scope.launch { vm.suggestReminders(ctx).onSuccess { suggestedCues = it }.onFailure { toaster.show(it.message.orEmpty()) } }
    }
    val breakDown: () -> Unit = {
        if (aiReady) scope.launch { vm.breakDown(ctx).onSuccess { suggestedSteps = it }.onFailure { toaster.show(it.message.orEmpty()) } }
        else askFirstStep = true
    }

    val details = d
    LargeTitleScreen(
        title = "",
        backLabel = stringResource(R.string.back),
        onBack = { nav.popBackStack() },
        actions = {
            if (details != null) {
                BarIcon(
                    if (details.task.important) R.drawable.ic_star_fill else R.drawable.ic_star,
                    stringResource(R.string.important),
                    { vm.save(details.task.copy(important = !details.task.important)) },
                    tint = if (details.task.important) c.amber else c.accent,
                )
            }
        },
    ) {
        if (details == null) return@LargeTitleScreen
        val t = details.task
        val done = t.completedAt != null
        item(key = "title") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                BasicTextField(
                    title, { title = it.take(200) },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> Box { if (title.isEmpty()) Text(stringResource(R.string.task_title_hint), style = MaterialTheme.typography.headlineSmall, color = c.textTertiary); inner() } },
                )
                val q = Matrix.quadrant(t, LocalDate.now())
                Text(
                    stringResource(
                        when (q) {
                            com.friday.app.domain.Quadrant.DO -> R.string.q_do
                            com.friday.app.domain.Quadrant.PLAN -> R.string.q_plan
                            com.friday.app.domain.Quadrant.QUICK -> R.string.q_quick
                            com.friday.app.domain.Quadrant.LATER -> R.string.q_later
                        },
                    ) + if (done) " · " + stringResource(R.string.completed) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item(key = "actions") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (done) {
                    PrimaryButton(stringResource(R.string.reopen), { vm.reopen() }, Modifier.weight(1f), icon = R.drawable.ic_restart_alt)
                } else {
                    PrimaryButton(stringResource(R.string.done), {
                        scope.launch {
                            val snap = vm.complete() ?: return@launch
                            toaster.show(ctx.getString(R.string.done_toast, t.title)) { vm.restore(Undo.Restore(snap)) }
                            if (t.repeat == Repeat.NONE) nav.popBackStack()
                        }
                    }, Modifier.weight(1f), icon = R.drawable.ic_check)
                }
            }
        }
        if (!done && t.postponeCount >= 3 && details.subtasks.isEmpty()) {
            item(key = "postponed") {
                Section(modifier = Modifier.padding(bottom = 22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ic(R.drawable.ic_account_tree, tint = c.amber, size = 24.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.postponed_nudge, t.postponeCount), style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                    }
                    RowDivider(0.dp)
                    ListRow(stringResource(R.string.break_it_down), titleColor = c.accent, onClick = { if (!busy) breakDown() }, chevron = false)
                }
            }
        }
        item(key = "when") {
            Section(header = stringResource(R.string.section_when)) {
                ListRow(stringResource(R.string.date), icon = R.drawable.ic_calendar_today, value = t.dueDate?.let { Fmt.day(ctx, it) } ?: stringResource(R.string.none), onClick = { pickDate = true })
                if (t.dueDate != null) {
                    RowDivider(52.dp)
                    ListRow(stringResource(R.string.time), icon = R.drawable.ic_schedule, value = t.dueTime?.let { Fmt.time(ctx, it) } ?: stringResource(R.string.any_time), onClick = { pickTime = true })
                    RowDivider(52.dp)
                    ListRow(stringResource(R.string.repeat), icon = R.drawable.ic_repeat, value = Fmt.repeat(ctx, t) ?: stringResource(R.string.never), onClick = { pickRepeat = true })
                }
                RowDivider(52.dp)
                ListRow(stringResource(R.string.duration), icon = R.drawable.ic_hourglass_empty, value = t.durationMin?.let { Fmt.duration(ctx, it) } ?: stringResource(R.string.none), onClick = { pickDuration = true })
                if (t.dueDate != null) {
                    RowDivider(52.dp)
                    SwitchRow(stringResource(R.string.event_toggle), t.event, { vm.save(t.copy(event = it)) }, subtitle = stringResource(R.string.event_toggle_sub))
                }
            }
        }
        item(key = "cues") {
            val timeCues = details.cues.filter { it.anchor != CueAnchor.LEAVE && it.anchor != CueAnchor.ARRIVE }.sortedBy { Schedule.trigger(t, it) ?: LocalDateTime.MAX }
            val placeCues = details.cues.filter { it.anchor == CueAnchor.LEAVE || it.anchor == CueAnchor.ARRIVE }
            Section(
                header = stringResource(R.string.section_reminders),
                footer = stringResource(if (t.dueDate == null && placeCues.isEmpty() && places.isEmpty()) R.string.reminders_need_date else R.string.reminders_footer),
                modifier = Modifier.padding(top = 22.dp),
            ) {
                (timeCues + placeCues).forEachIndexed { i, cue ->
                    if (i > 0) RowDivider(52.dp)
                    val at = Schedule.trigger(t, cue)
                    ListRow(
                        title = Fmt.cueWhen(ctx, cue, places.firstOrNull { it.id == cue.placeId }?.name) + (at?.let { " · " + Fmt.time(ctx, it.toLocalTime()) } ?: ""),
                        subtitle = Fmt.cueMessage(ctx, t, cue),
                        icon = if (cue.kind == CueKind.CUSTOM) R.drawable.ic_notifications else R.drawable.ic_alarm,
                        onClick = if (cue.kind == CueKind.CUSTOM) ({ cueSheet = cue }) else null,
                        trailing = if (cue.kind == CueKind.CUSTOM || cue.kind == CueKind.PREP) ({
                            BarIcon(R.drawable.ic_close, stringResource(R.string.remove), { vm.deleteCue(cue) }, tint = c.textTertiary)
                        }) else null,
                    )
                }
                if (t.dueTime != null && !t.autoPrep) {
                    if (timeCues.isNotEmpty()) RowDivider(52.dp)
                    ListRow(stringResource(R.string.add_get_ready), icon = R.drawable.ic_add, onClick = { vm.setAutoPrep(true) }, chevron = false)
                }
                if (t.dueDate != null) {
                    if (details.cues.isNotEmpty() || !t.autoPrep) RowDivider(52.dp)
                    ListRow(stringResource(R.string.add_reminder), icon = R.drawable.ic_add, titleColor = c.accent, onClick = {
                        cueSheet = CueEntity(taskId = t.id, kind = CueKind.CUSTOM, anchor = if (t.dueTime != null) CueAnchor.START else CueAnchor.DAY, offsetMin = if (t.dueTime != null) 10 else 20 * 60 - 24 * 60)
                    }, chevron = false)
                }
                if (places.isNotEmpty()) {
                    if (details.cues.isNotEmpty() || t.dueDate != null) RowDivider(52.dp)
                    ListRow(stringResource(R.string.place_reminder_add), icon = R.drawable.ic_location_on, titleColor = c.accent, onClick = { placeSheet = true }, chevron = false)
                }
                if (reusable.isNotEmpty()) {
                    RowDivider(52.dp)
                    ListRow(stringResource(R.string.reuse_reminders, reusable.size), icon = R.drawable.ic_history, titleColor = c.accent, onClick = { vm.reuse() }, chevron = false)
                }
                if (aiReady && t.dueDate != null) {
                    RowDivider(52.dp)
                    ListRow(
                        stringResource(R.string.suggest_reminders), icon = R.drawable.ic_auto_awesome, titleColor = c.accent,
                        value = if (busy) stringResource(R.string.thinking) else null, onClick = { if (!busy) suggest() }, chevron = false,
                    )
                }
            }
        }
        item(key = "steps") {
            Section(header = stringResource(R.string.section_steps), modifier = Modifier.padding(top = 22.dp)) {
                details.subtasks.sortedBy { it.position }.forEachIndexed { i, s ->
                    if (i > 0) RowDivider(52.dp)
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckCircle(s.done, { vm.toggleStep(s) }, stringResource(R.string.complete_step))
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, color = if (s.done) c.textTertiary else c.text, modifier = Modifier.weight(1f))
                        BarIcon(R.drawable.ic_close, stringResource(R.string.remove), { vm.deleteStep(s) }, tint = c.textTertiary)
                    }
                }
                if (details.subtasks.isNotEmpty()) RowDivider(52.dp)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ic(R.drawable.ic_add, tint = c.accent, size = 22.dp)
                    Spacer(Modifier.width(14.dp))
                    BasicTextField(
                        state = newStep,
                        modifier = Modifier.weight(1f).padding(vertical = 15.dp),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        onKeyboardAction = { vm.addStep(newStep.text.toString()); newStep.clearText() },
                        decorator = { inner -> Box { if (newStep.text.isEmpty()) Text(stringResource(R.string.add_step), style = MaterialTheme.typography.bodyLarge, color = c.accent); inner() } },
                    )
                }
                RowDivider(52.dp)
                ListRow(
                    stringResource(R.string.break_it_down), icon = R.drawable.ic_account_tree, titleColor = c.accent,
                    value = if (busy) stringResource(R.string.thinking) else null, onClick = { if (!busy) breakDown() }, chevron = false,
                )
            }
        }
        item(key = "notes") {
            Section(header = stringResource(R.string.section_notes), modifier = Modifier.padding(top = 22.dp)) {
                BasicTextField(
                    notes, { notes = it.take(4000) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner -> Box { if (notes.isEmpty()) Text(stringResource(R.string.notes_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary); inner() } },
                )
                RowDivider(16.dp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ic(R.drawable.ic_label, tint = c.textSecondary, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        category, { category = it.take(40) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(vertical = 15.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        decorationBox = { inner -> Box { if (category.isEmpty()) Text(stringResource(R.string.category_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary); inner() } },
                    )
                }
            }
        }
        item(key = "delete") {
            Section(modifier = Modifier.padding(top = 22.dp)) {
                ListRow(stringResource(R.string.delete_task), titleColor = c.red, onClick = { confirmDelete = true }, chevron = false)
            }
        }
    }

    val t = details?.task ?: return
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (t.dueDate ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    pickDate = false
                    state.selectedDateMillis?.let { ms -> vm.save(t.copy(dueDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())) }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                Row {
                    if (t.dueDate != null) TextButton({ pickDate = false; vm.save(t.copy(dueDate = null, dueTime = null, repeat = Repeat.NONE)) }) { Text(stringResource(R.string.clear)) }
                    TextButton({ pickDate = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        ) { DatePicker(state, showModeToggle = false) }
    }
    if (pickTime) {
        TimeDialog(
            initial = t.dueTime ?: LocalTime.of(9, 0),
            onDismiss = { pickTime = false },
            onClear = if (t.dueTime != null) ({ pickTime = false; vm.save(t.copy(dueTime = null)) }) else null,
            onPick = { pickTime = false; vm.save(t.copy(dueTime = it)) },
        )
    }
    if (pickDuration) {
        OptionSheet(
            title = stringResource(R.string.duration),
            options = listOf(null, 5, 15, 30, 45, 60, 90, 120, 180).map { m -> (m?.let { Fmt.duration(ctx, it) } ?: stringResource(R.string.none)) to m },
            selected = t.durationMin,
            onDismiss = { pickDuration = false },
            onPick = { pickDuration = false; vm.save(t.copy(durationMin = it)) },
        )
    }
    if (pickRepeat) RepeatSheet(t, onDismiss = { pickRepeat = false }) { r, days -> pickRepeat = false; vm.save(t.copy(repeat = r, repeatDays = days)) }
    cueSheet?.let { cue -> CueSheet(t, cue, onDismiss = { cueSheet = null }) { cueSheet = null; if (it.id == 0L) vm.addCue(it) else vm.updateCue(it) } }
    suggestedCues?.let { cues ->
        PickSheet(
            title = stringResource(R.string.suggest_reminders),
            items = cues.map { Fmt.cueWhen(ctx, it) + " · " + it.message },
            empty = stringResource(R.string.no_suggestions),
            onDismiss = { suggestedCues = null },
        ) { picked -> suggestedCues = null; vm.addCues(picked.map { cues[it] }) }
    }
    suggestedSteps?.let { steps ->
        PickSheet(
            title = stringResource(R.string.break_it_down),
            items = steps,
            empty = stringResource(R.string.no_suggestions),
            onDismiss = { suggestedSteps = null },
        ) { picked -> suggestedSteps = null; vm.addSteps(picked.map { steps[it] }) }
    }
    if (placeSheet) {
        PlaceCueSheet(places, onDismiss = { placeSheet = false }) { placeId, leave, message -> placeSheet = false; vm.addPlaceCue(placeId, leave, message) }
    }
    if (askFirstStep) {
        TextDialog(stringResource(R.string.first_tiny_step), "", { askFirstStep = false }) { s -> askFirstStep = false; if (s.isNotBlank()) vm.addStep(s) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_task_q)) },
            text = { Text(stringResource(R.string.delete_task_body, t.title)) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch {
                        val snap = vm.delete() ?: return@launch
                        nav.popBackStack()
                        toaster.show(ctx.getString(R.string.deleted_toast, t.title)) { vm.restore(Undo.Restore(snap)) }
                    }
                }) { Text(stringResource(R.string.delete), color = c.red) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit, onClear: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            Row {
                if (onClear != null) TextButton(onClear) { Text(stringResource(R.string.clear)) }
                TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
        text = { TimePicker(state) },
    )
}

@Composable
fun <T> OptionSheet(title: String, options: List<Pair<String, T>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    val c = Friday.colors
    FridaySheet(onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Section(modifier = Modifier.padding(top = 10.dp, bottom = 28.dp)) {
            options.forEachIndexed { i, (label, value) ->
                if (i > 0) RowDivider()
                ListRow(label, onClick = { onPick(value) }, chevron = false, trailing = if (value == selected) ({ Ic(R.drawable.ic_check, tint = c.accent, size = 20.dp) }) else null)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatSheet(t: TaskEntity, onDismiss: () -> Unit, onPick: (Repeat, Int) -> Unit) {
    val ctx = LocalContext.current
    val c = Friday.colors
    var repeat by remember { mutableStateOf(t.repeat) }
    var days by remember { mutableStateOf(if (t.repeatDays != 0) t.repeatDays else Schedule.dayBit((t.dueDate ?: LocalDate.now()).dayOfWeek)) }
    FridaySheet(onDismiss) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(stringResource(R.string.repeat), style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Section(modifier = Modifier.padding(top = 10.dp)) {
                val opts = listOf(Repeat.NONE to R.string.never, Repeat.DAILY to R.string.repeat_daily, Repeat.WEEKDAYS to R.string.repeat_weekdays, Repeat.WEEKLY to R.string.repeat_weekly, Repeat.MONTHLY to R.string.repeat_monthly)
                opts.forEachIndexed { i, (r, label) ->
                    if (i > 0) RowDivider()
                    ListRow(stringResource(label), onClick = { repeat = r }, chevron = false, trailing = if (repeat == r) ({ Ic(R.drawable.ic_check, tint = c.accent, size = 20.dp) }) else null)
                }
            }
            if (repeat == Repeat.WEEKLY) {
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        val bit = Schedule.dayBit(d)
                        Pill(d.getDisplayName(java.time.format.TextStyle.SHORT, Fmt.locale(ctx)), days and bit != 0, { days = if (days and bit != 0 && days != bit) days and bit.inv() else days or bit })
                    }
                }
            }
            PrimaryButton(stringResource(R.string.save), { onPick(repeat, if (repeat == Repeat.WEEKLY) days else 0) }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth())
        }
    }
}

/** Add or edit one of the user's own reminders inside a task. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CueSheet(t: TaskEntity, initial: CueEntity, onDismiss: () -> Unit, onSave: (CueEntity) -> Unit) {
    val ctx = LocalContext.current
    val c = Friday.colors
    var cue by remember { mutableStateOf(initial) }
    var message by remember { mutableStateOf(initial.message) }
    var pickDayTime by remember { mutableStateOf(false) }
    val timed = t.dueTime != null
    FridaySheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(if (initial.id == 0L) R.string.add_reminder else R.string.edit_reminder), style = MaterialTheme.typography.titleLarge, color = c.text)
            Spacer(Modifier.height(16.dp))
            if (timed) {
                val anchors = buildList { add(CueAnchor.START); if (t.durationMin != null) add(CueAnchor.END) }
                if (anchors.size > 1) {
                    Segmented(
                        anchors.map { stringResource(if (it == CueAnchor.START) R.string.relative_start else R.string.relative_end) },
                        anchors.indexOf(cue.anchor).coerceAtLeast(0),
                        { cue = cue.copy(anchor = anchors[it]) },
                    )
                    Spacer(Modifier.height(14.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 10, 15, 30, 60, 120).forEach { m ->
                        Pill(if (m == 0) stringResource(R.string.exactly) else stringResource(R.string.n_before, Fmt.duration(ctx, m)), cue.offsetMin == m, { cue = cue.copy(offsetMin = m) })
                    }
                }
            } else {
                Segmented(
                    listOf(stringResource(R.string.on_the_day), stringResource(R.string.day_before)),
                    if (cue.offsetMin < 0) 1 else 0,
                    { cue = cue.copy(offsetMin = ((cue.offsetMin + 24 * 60) % (24 * 60)) - if (it == 1) 24 * 60 else 0) },
                )
                Spacer(Modifier.height(10.dp))
                Section(inset = 0.dp) {
                    ListRow(stringResource(R.string.time), value = Fmt.minuteOfDay(ctx, (cue.offsetMin + 24 * 60) % (24 * 60)), onClick = { pickDayTime = true })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.cue_message_label), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            BasicTextField(
                message, { message = it.take(300) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(14.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner -> Box { if (message.isEmpty()) Text(stringResource(R.string.cue_message_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary); inner() } },
            )
            Spacer(Modifier.height(10.dp))
            Section(inset = 0.dp) {
                SwitchRow(stringResource(R.string.cue_sound), cue.sound, { cue = cue.copy(sound = it) }, subtitle = stringResource(R.string.cue_sound_sub))
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(stringResource(R.string.save), { onSave(cue.copy(message = message)) }, Modifier.fillMaxWidth())
        }
    }
    if (pickDayTime) {
        val m = (cue.offsetMin + 24 * 60) % (24 * 60)
        TimeDialog(LocalTime.of(m / 60, m % 60), { pickDayTime = false }, { lt ->
            pickDayTime = false
            val mins = lt.hour * 60 + lt.minute
            cue = cue.copy(offsetMin = if (cue.offsetMin < 0) mins - 24 * 60 else mins)
        })
    }
}


/** FRIDAY's language for AI suggestions: the app's current language. */
private fun appLang(ctx: Context) = if (Fmt.locale(ctx).language == "ms") com.friday.app.ai.Lang.MS else com.friday.app.ai.Lang.EN

/** Suggestions to accept or skip: everything starts ticked, one tap adds the rest. */
@Composable
private fun PickSheet(title: String, items: List<String>, empty: String, onDismiss: () -> Unit, onAdd: (List<Int>) -> Unit) {
    val c = Friday.colors
    var picked by remember(items) { mutableStateOf(items.indices.toSet()) }
    FridaySheet(onDismiss) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (items.isEmpty()) {
                Text(empty, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, modifier = Modifier.padding(20.dp))
            } else {
                Section(modifier = Modifier.padding(top = 10.dp)) {
                    items.forEachIndexed { i, item ->
                        if (i > 0) RowDivider(52.dp)
                        Row(Modifier.fillMaxWidth().clickable { picked = if (i in picked) picked - i else picked + i }.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CheckCircle(i in picked, { picked = if (i in picked) picked - i else picked + i }, item)
                            Text(item, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
                PrimaryButton(
                    stringResource(R.string.add_n, picked.size), { onAdd(picked.sorted()) },
                    Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth(), enabled = picked.isNotEmpty(),
                )
            }
        }
    }
}

/** "When I leave Home" / "when I arrive at Campus", with an optional message. */
@Composable
private fun PlaceCueSheet(places: List<PlaceEntity>, onDismiss: () -> Unit, onSave: (Long, Boolean, String) -> Unit) {
    val c = Friday.colors
    var leave by remember { mutableStateOf(true) }
    var placeId by remember { mutableStateOf(places.first().id) }
    var message by remember { mutableStateOf("") }
    FridaySheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.place_reminder_add), style = MaterialTheme.typography.titleLarge, color = c.text)
            Spacer(Modifier.height(16.dp))
            Segmented(listOf(stringResource(R.string.place_leave), stringResource(R.string.place_arrive)), if (leave) 0 else 1, { leave = it == 0 })
            Spacer(Modifier.height(14.dp))
            Section(inset = 0.dp) {
                places.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    ListRow(p.name, onClick = { placeId = p.id }, chevron = false, trailing = if (p.id == placeId) ({ Ic(R.drawable.ic_check, tint = c.accent, size = 20.dp) }) else null)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.cue_message_label), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            BasicTextField(
                message, { message = it.take(300) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(14.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner -> Box { if (message.isEmpty()) Text(stringResource(R.string.place_message_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary); inner() } },
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton(stringResource(R.string.save), { onSave(placeId, leave, message) }, Modifier.fillMaxWidth())
        }
    }
}
