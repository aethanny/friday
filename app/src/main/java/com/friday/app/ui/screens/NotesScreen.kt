package com.friday.app.ui.screens

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.friday.app.AppContainer
import com.friday.app.R
import com.friday.app.ai.EngineResult
import com.friday.app.ai.Source
import com.friday.app.data.db.NoteEntity
import com.friday.app.data.db.TaskEntity
import com.friday.app.ui.Fmt
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.EmptyState
import com.friday.app.ui.components.FridaySheet
import com.friday.app.ui.components.Ic
import com.friday.app.ui.components.LargeTitleScreen
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.Section
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NotesViewModel(private val c: AppContainer) : ViewModel() {
    val notes: StateFlow<List<NoteEntity>?> = c.notes.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun add(text: String, inbox: Boolean) = viewModelScope.launch { if (text.isNotBlank()) c.notes.add(text, inbox) }
    fun update(n: NoteEntity, text: String) = viewModelScope.launch { c.notes.update(n, text) }
    fun keep(n: NoteEntity) = viewModelScope.launch { c.notes.keep(n) }
    suspend fun delete(n: NoteEntity) = c.notes.delete(n)
    fun restore(n: NoteEntity) = viewModelScope.launch { c.notes.restore(n) }
    /** The text keeps living in the task, so removing it from the Inbox loses nothing. */
    suspend fun toTask(n: NoteEntity): EngineResult {
        val r = c.assistant.handle(n.text, Source.TASK_SHEET)
        if (r.taskId != null) c.notes.delete(n)
        return r
    }
    suspend fun toReminder(n: NoteEntity, date: LocalDate, time: LocalTime): Long {
        val id = c.tasks.save(TaskEntity(title = n.text.lineSequence().first().take(200), notes = n.text.takeIf { it.length > 200 || it.contains('\n') }.orEmpty(), dueDate = date, dueTime = time))
        c.notes.delete(n)
        return id
    }
}

@Composable
fun NotesScreen(nav: NavController) {
    val vm = fridayViewModel { NotesViewModel(it) }
    val notes by vm.notes.collectAsStateWithLifecycle()
    val c = Friday.colors
    val ctx = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val draft = rememberTextFieldState()
    var open by remember { mutableStateOf<NoteEntity?>(null) }
    val inbox = notes.orEmpty().filter { it.inbox }
    val kept = notes.orEmpty().filter { !it.inbox }

    LargeTitleScreen(title = stringResource(R.string.inbox_and_notes), onBack = { nav.popBackStack() }) {
        item(key = "add") {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ic(R.drawable.ic_lightbulb, tint = c.amber, size = 22.dp)
                Spacer(Modifier.padding(start = 12.dp))
                BasicTextField(
                    state = draft,
                    modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    onKeyboardAction = { vm.add(draft.text.toString(), inbox = true); draft.clearText() },
                    decorator = { inner -> Box { if (draft.text.isEmpty()) Text(stringResource(R.string.note_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary); inner() } },
                )
            }
        }
        if (notes != null && inbox.isEmpty() && kept.isEmpty()) {
            item(key = "empty") { EmptyState(R.drawable.ic_inbox, stringResource(R.string.empty_notes_title), stringResource(R.string.empty_notes_body)) }
        }
        if (inbox.isNotEmpty()) {
            item(key = "inbox") {
                Section(header = stringResource(R.string.inbox), footer = stringResource(R.string.inbox_footer), modifier = Modifier.padding(top = 22.dp)) {
                    inbox.forEachIndexed { i, n -> if (i > 0) RowDivider(); NoteRow(n) { open = n } }
                }
            }
        }
        if (kept.isNotEmpty()) {
            item(key = "kept") {
                Section(header = stringResource(R.string.notes), modifier = Modifier.padding(top = 22.dp)) {
                    kept.forEachIndexed { i, n -> if (i > 0) RowDivider(); NoteRow(n) { open = n } }
                }
            }
        }
    }

    open?.let { n ->
        NoteSheet(
            note = n,
            onDismiss = { open = null },
            onSave = { vm.update(n, it); open = null },
            onKeep = { vm.keep(n); open = null; toaster.show(ctx.getString(R.string.kept_as_note)) },
            onTask = {
                open = null
                scope.launch { val r = vm.toTask(n); toaster.show(r.text); r.taskId?.let { nav.navigate("task/$it") } }
            },
            onRemind = { date, time ->
                open = null
                scope.launch {
                    vm.toReminder(n, date, time)
                    toaster.show(ctx.getString(R.string.reminder_set_short, Fmt.day(ctx, date) + " · " + Fmt.time(ctx, time)))
                }
            },
            onDelete = {
                open = null
                scope.launch { vm.delete(n); toaster.show(ctx.getString(R.string.note_deleted)) { vm.restore(n) } }
            },
        )
    }
}

@Composable
private fun NoteRow(n: NoteEntity, onClick: () -> Unit) {
    val c = Friday.colors
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(n.text, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val d = Instant.ofEpochMilli(n.updatedAt).atZone(ZoneId.systemDefault())
        Text(Fmt.day(ctx, d.toLocalDate()) + " · " + Fmt.time(ctx, d.toLocalTime()), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun NoteSheet(
    note: NoteEntity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onKeep: () -> Unit,
    onTask: () -> Unit,
    onRemind: (LocalDate, LocalTime) -> Unit,
    onDelete: () -> Unit,
) {
    val c = Friday.colors
    var text by remember { mutableStateOf(note.text) }
    var pickTime by remember { mutableStateOf(false) }
    FridaySheet(onDismiss) {
        Column(Modifier.padding(bottom = 28.dp)) {
            BasicTextField(
                text, { text = it.take(4000) },
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(16.dp).heightIn(min = 96.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (text != note.text) {
                PrimaryButton(stringResource(R.string.save), { onSave(text) }, Modifier.padding(16.dp).fillMaxWidth())
            }
            Section(modifier = Modifier.padding(top = 12.dp)) {
                ListRow(stringResource(R.string.make_task), icon = R.drawable.ic_checklist, onClick = onTask, chevron = false)
                RowDivider(52.dp)
                ListRow(stringResource(R.string.remind_me_about), icon = R.drawable.ic_alarm, onClick = { pickTime = true }, chevron = false)
                if (note.inbox) {
                    RowDivider(52.dp)
                    ListRow(stringResource(R.string.keep_as_note), icon = R.drawable.ic_sticky_note_2, onClick = onKeep, chevron = false)
                }
                RowDivider(52.dp)
                ListRow(stringResource(R.string.delete), icon = R.drawable.ic_delete, titleColor = c.red, onClick = onDelete, chevron = false)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (pickTime) {
        TimeDialog(LocalTime.now().plusHours(1).withMinute(0), { pickTime = false }, { t ->
            pickTime = false
            val today = LocalDate.now()
            onRemind(if (t.isAfter(LocalTime.now())) today else today.plusDays(1), t)
        })
    }
}

