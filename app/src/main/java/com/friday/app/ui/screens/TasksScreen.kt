package com.friday.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import com.friday.app.ai.CommandKind
import com.friday.app.ai.Parsed
import com.friday.app.ai.Source
import com.friday.app.ai.Undo
import com.friday.app.data.db.Repeat
import com.friday.app.data.db.TaskWithDetails
import com.friday.app.domain.Matrix
import com.friday.app.domain.Quadrant
import com.friday.app.ui.Fmt
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.BarIcon
import com.friday.app.ui.components.EmptyState
import com.friday.app.ui.components.FridaySheet
import com.friday.app.ui.components.Ic
import com.friday.app.ui.components.LargeTitleScreen
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.Pill
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.Section
import com.friday.app.ui.components.TaskRow
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

enum class TaskView(val label: Int) {
    TODAY(R.string.view_today), UPCOMING(R.string.view_upcoming), OVERDUE(R.string.view_overdue),
    ALL(R.string.view_all), DONE(R.string.view_done), MATRIX(R.string.view_matrix),
}

data class TaskGroup(val title: String?, val tasks: List<TaskWithDetails>)

data class TasksState(
    val view: TaskView = TaskView.TODAY,
    val query: String = "",
    val category: String? = null,
    val categories: List<String> = emptyList(),
    val groups: List<TaskGroup> = emptyList(),
    val matrix: Map<Quadrant, List<TaskWithDetails>> = emptyMap(),
    val now: LocalDateTime = LocalDateTime.now(),
    val inbox: Int = 0,
    val loaded: Boolean = false,
)

class TasksViewModel(private val c: AppContainer, private val ctx: Context) : ViewModel() {
    val view = MutableStateFlow(TaskView.TODAY)
    val query = MutableStateFlow("")
    val category = MutableStateFlow<String?>(null)

    val state: StateFlow<TasksState> = combine(
        combine(c.tasks.observeOpen(), c.tasks.observeCompleted(), clock(60_000)) { o, d, n -> Triple(o, d, n) },
        combine(view, query, category) { v, q, cat -> Triple(v, q, cat) },
        c.tasks.observeCategories(),
        c.notes.observeInboxCount(),
    ) { (open, done, now), (v, q, cat), cats, inbox ->
        val today = now.toLocalDate()
        fun match(d: TaskWithDetails) = (cat == null || d.task.category == cat) &&
            (q.isBlank() || d.task.title.contains(q, true) || d.task.notes.contains(q, true) || d.subtasks.any { it.title.contains(q, true) })
        val openF = open.filter { match(it) && !Matrix.isOver(it.task, now) }
        val ranked = { list: List<TaskWithDetails> ->
            val order = Matrix.rankForNow(list.map { it.task }, now).withIndex().associate { it.value.id to it.index }
            list.sortedBy { order[it.task.id] ?: Int.MAX_VALUE } // events are not ranked: after the to-dos
        }
        val groups = when {
            q.isNotBlank() -> listOf(TaskGroup(null, ranked(openF) + done.filter(::match)))
            v == TaskView.TODAY -> listOf(TaskGroup(null, ranked(openF.filter { it.task.dueDate != null && !it.task.dueDate.isAfter(today) })))
            v == TaskView.OVERDUE -> listOf(TaskGroup(null, openF.filter { Matrix.isOverdue(it.task, now) }))
            v == TaskView.UPCOMING -> openF.filter { it.task.dueDate?.isAfter(today) == true }
                .groupBy { it.task.dueDate!! }.toSortedMap()
                .map { (d, l) -> TaskGroup(Fmt.day(ctx, d, today), l.sortedBy { it.task.dueTime }) }
            v == TaskView.ALL -> listOf(
                TaskGroup(ctx.getString(R.string.group_scheduled), openF.filter { it.task.dueDate != null }),
                TaskGroup(ctx.getString(R.string.group_no_date), openF.filter { it.task.dueDate == null }),
            ).filter { it.tasks.isNotEmpty() }
            v == TaskView.DONE -> listOf(TaskGroup(null, done.filter(::match)))
            else -> emptyList()
        }
        TasksState(
            view = v, query = q, category = cat, categories = cats,
            groups = groups.filter { it.tasks.isNotEmpty() },
            matrix = ranked(openF).groupBy { Matrix.quadrant(it.task, today) },
            now = now, inbox = inbox, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksState())

    fun preview(text: String): Parsed = c.parser.parse(text)
    suspend fun add(text: String) = c.assistant.handle(text, Source.TASK_SHEET)
    suspend fun undo(u: Undo) = c.assistant.undo(u)
    suspend fun complete(id: Long) = c.tasks.complete(id)
    suspend fun restore(u: Undo.Restore) = c.tasks.restore(u.snapshot)
    fun reopen(id: Long) = viewModelScope.launch { c.tasks.reopen(id) }
    fun star(id: Long, v: Boolean) = viewModelScope.launch { c.tasks.setImportant(id, v) }
}

@Composable
fun TasksScreen(nav: NavController) {
    val ctx = LocalContext.current
    val vm = fridayViewModel { TasksViewModel(it, ctx.applicationContext) }
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Friday.colors
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }

    fun complete(d: TaskWithDetails) = scope.launch {
        val snap = vm.complete(d.task.id) ?: return@launch
        toaster.show(ctx.getString(R.string.done_toast, d.task.title)) { vm.restore(Undo.Restore(snap)) }
    }

    LargeTitleScreen(
        title = stringResource(R.string.tab_tasks),
        actions = { BarIcon(R.drawable.ic_add, stringResource(R.string.add_task), { adding = true }) },
    ) {
        item(key = "search") { SearchField(s.query, { vm.query.value = it }) }
        item(key = "views") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(TaskView.entries) { v ->
                    Pill(
                        stringResource(v.label),
                        selected = s.view == v && s.query.isBlank(),
                        onClick = { vm.view.value = v },
                        icon = if (v == TaskView.MATRIX) R.drawable.ic_grid_view else null,
                    )
                }
            }
        }
        if (s.categories.isNotEmpty()) {
            item(key = "cats") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    items(s.categories) { cat ->
                        Pill(cat, selected = s.category == cat, onClick = { vm.category.value = if (s.category == cat) null else cat }, icon = R.drawable.ic_label)
                    }
                }
            }
        }
        item(key = "inbox") {
            Section(modifier = Modifier.padding(top = 16.dp)) {
                ListRow(
                    stringResource(R.string.inbox_and_notes),
                    icon = R.drawable.ic_inbox,
                    value = if (s.inbox > 0) s.inbox.toString() else null,
                    onClick = { nav.navigate("notes") },
                )
            }
        }
        if (s.view == TaskView.MATRIX && s.query.isBlank()) {
            item(key = "matrix") { MatrixGrid(s, onOpen = { nav.navigate("task/$it") }, onStar = vm::star) }
        } else if (s.loaded && s.groups.isEmpty()) {
            item(key = "empty") {
                val (title, body) = when {
                    s.query.isNotBlank() -> R.string.empty_search_title to R.string.empty_search_body
                    s.view == TaskView.DONE -> R.string.empty_done_title to R.string.empty_done_body
                    s.view == TaskView.OVERDUE -> R.string.empty_overdue_title to R.string.empty_overdue_body
                    else -> R.string.empty_tasks_title to R.string.empty_tasks_body
                }
                EmptyState(R.drawable.ic_checklist, stringResource(title), stringResource(body), action = stringResource(R.string.add_task), onAction = { adding = true })
            }
        } else {
            s.groups.forEachIndexed { gi, g ->
                item(key = "g$gi-${g.title}") {
                    Section(header = g.title, modifier = Modifier.padding(top = 22.dp)) {
                        g.tasks.forEachIndexed { i, d ->
                            if (i > 0) RowDivider(52.dp)
                            val done = d.task.completedAt != null
                            TaskRow(
                                title = d.task.title,
                                done = done,
                                important = d.task.important,
                                meta = Fmt.taskMeta(ctx, d.task, s.now, d.subtasks.count { it.done }, d.subtasks.size),
                                metaColor = if (!done && Matrix.isOverdue(d.task, s.now)) c.red else c.textSecondary,
                                onClick = { nav.navigate("task/${d.task.id}") },
                                onToggle = { if (done) vm.reopen(d.task.id) else complete(d) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        QuickAddSheet(
            preview = vm::preview,
            onDismiss = { adding = false },
            onAdd = { text, details ->
                adding = false
                scope.launch {
                    val r = vm.add(text)
                    if (details && r.taskId != null) nav.navigate("task/${r.taskId}")
                    else toaster.show(r.text, r.undo?.let { u -> { vm.undo(u) } })
                }
            },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val c = Friday.colors
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).heightIn(min = 40.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ic(R.drawable.ic_search, tint = c.textSecondary, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value, onChange,
            modifier = Modifier.weight(1f).padding(vertical = 9.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(stringResource(R.string.search_tasks), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary); inner() }
            },
        )
        if (value.isNotEmpty()) BarIcon(R.drawable.ic_close, stringResource(R.string.clear), { onChange("") }, tint = c.textSecondary)
    }
}

@Composable
private fun MatrixGrid(s: TasksState, onOpen: (Long) -> Unit, onStar: (Long, Boolean) -> Unit) {
    val c = Friday.colors
    val cells = listOf(
        Triple(Quadrant.DO, R.string.q_do, R.string.q_do_hint) to c.red,
        Triple(Quadrant.PLAN, R.string.q_plan, R.string.q_plan_hint) to c.accent,
        Triple(Quadrant.QUICK, R.string.q_quick, R.string.q_quick_hint) to c.amber,
        Triple(Quadrant.LATER, R.string.q_later, R.string.q_later_hint) to c.textTertiary,
    )
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.urgent), style = MaterialTheme.typography.labelSmall, color = c.textSecondary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Text(stringResource(R.string.not_urgent), style = MaterialTheme.typography.labelSmall, color = c.textSecondary, modifier = Modifier.weight(1f).padding(start = 14.dp))
        }
        cells.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Visual order: DO | PLAN on top (important), QUICK | LATER below.
                row.forEach { (q, color) ->
                    val tasks = s.matrix[q.first].orEmpty()
                    Column(
                        Modifier.weight(1f).heightIn(min = 170.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(q.second), style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                            Text(tasks.size.toString(), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        }
                        Text(stringResource(q.third), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                        tasks.take(4).forEach { d ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpen(d.task.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(d.task.title, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape).clickable { onStar(d.task.id, !d.task.important) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Ic(
                                        if (d.task.important) R.drawable.ic_star_fill else R.drawable.ic_star,
                                        tint = if (d.task.important) c.amber else c.textTertiary,
                                        size = 18.dp,
                                        description = stringResource(R.string.important),
                                    )
                                }
                            }
                        }
                        if (tasks.size > 4) Text(stringResource(R.string.n_more, tasks.size - 4), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
                        if (tasks.isEmpty()) Text("—", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary)
                    }
                }
            }
        }
        Text(stringResource(R.string.matrix_footer), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

/** Fast task entry: type naturally, see how FRIDAY understood it, add. */
@Composable
private fun QuickAddSheet(preview: (String) -> Parsed, onDismiss: () -> Unit, onAdd: (String, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val c = Friday.colors
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val p = remember(text) { if (text.isBlank()) null else preview(text) }
    fun append(phrase: String) { text = (text.trimEnd() + " " + phrase).trimStart() }
    FridaySheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.new_task), style = MaterialTheme.typography.titleLarge, color = c.text)
            Spacer(Modifier.height(14.dp))
            BasicTextField(
                text, { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(14.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text, false) }),
                decorationBox = { inner -> Box { if (text.isEmpty()) Text(stringResource(R.string.quick_add_hint), style = MaterialTheme.typography.bodyLarge, color = c.textTertiary); inner() } },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.today), false, { append(ctx.getString(R.string.phrase_today)) })
                Pill(stringResource(R.string.tomorrow), false, { append(ctx.getString(R.string.phrase_tomorrow)) })
                Pill(stringResource(R.string.weekend), false, { append(ctx.getString(R.string.phrase_weekend)) })
                Pill("", false, { append(ctx.getString(R.string.phrase_important)) }, icon = R.drawable.ic_star)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                p?.let { describeParse(ctx, it) } ?: stringResource(R.string.quick_add_example),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.add), { onAdd(text, false) }, Modifier.weight(1f), enabled = text.isNotBlank())
                com.friday.app.ui.components.SecondaryButton(stringResource(R.string.add_details), { if (text.isNotBlank()) onAdd(text, true) }, height = 52.dp)
            }
        }
    }
}

private fun describeParse(ctx: Context, p: Parsed): String {
    val today = LocalDate.now()
    val bits = buildList {
        add(ctx.getString(if (p.kind == CommandKind.NOTE) R.string.parse_note else if (p.time != null || p.kind == CommandKind.REMINDER) R.string.parse_reminder else R.string.parse_task))
        if (p.title.isNotBlank() && p.kind != CommandKind.NOTE) add("“${p.title}”")
        p.date?.let { add(Fmt.day(ctx, it, today)) }
        p.time?.let { add(Fmt.time(ctx, it)) }
        p.durationMin?.let { add(Fmt.duration(ctx, it)) }
        if (p.repeat != Repeat.NONE) add(ctx.getString(R.string.parse_repeats))
        if (p.important) add("★")
        if (p.needsTime) add(ctx.getString(R.string.parse_asks_time))
        p.place?.let { add(ctx.getString(R.string.parse_place, it)) }
    }
    return bits.joinToString(" · ")
}

