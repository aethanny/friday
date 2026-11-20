package com.friday.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.friday.app.AppContainer
import com.friday.app.R
import com.friday.app.ai.Proposal
import com.friday.app.ai.ProposalKind
import com.friday.app.ai.Source
import com.friday.app.ai.Undo
import com.friday.app.data.db.ActionState
import com.friday.app.data.db.MessageEntity
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.CaptureBar
import com.friday.app.ui.components.LocalTabBarHeight
import com.friday.app.ui.components.Pill
import com.friday.app.ui.components.TextAction
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a FRIDAY message can still do: start focus, confirm a change or undo what it did. */
@Serializable
data class MsgMeta(
    val taskId: Long? = null,
    val focusChip: String? = null,
    val focusMinutes: Int? = null,
    val proposal: String? = null,
    val proposalTask: Long? = null,
    val proposalLabel: String? = null,
    val undoTask: Long? = null,
    val undoNote: Long? = null,
)

private val json = Json { ignoreUnknownKeys = true }

class AssistantViewModel(private val c: AppContainer) : ViewModel() {
    val messages: StateFlow<List<MessageEntity>> = c.db.messages().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val thinking = MutableStateFlow(false)
    val online: StateFlow<Boolean> = c.settings.flow.map { c.remote != null && it.aiEnabled }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val sessionUndo = mutableMapOf<Long, Undo>()

    fun meta(m: MessageEntity): MsgMeta? = m.actionJson?.let { runCatching { json.decodeFromString(MsgMeta.serializer(), it) }.getOrNull() }

    fun send(text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        val dao = c.db.messages()
        dao.insert(MessageEntity(fromUser = true, text = text.trim()))
        thinking.value = true
        val r = try { c.assistant.handle(text, Source.CHAT) } finally { thinking.value = false }
        val meta = MsgMeta(
            taskId = r.taskId,
            focusChip = r.chips.singleOrNull()?.takeIf { r.taskId != null || r.focusMinutes != null },
            focusMinutes = r.focusMinutes,
            proposal = r.proposal?.kind?.name,
            proposalTask = r.proposal?.taskId,
            proposalLabel = r.proposal?.label,
            undoTask = (r.undo as? Undo.DeleteTask)?.id,
            undoNote = (r.undo as? Undo.DeleteNote)?.id,
        )
        val id = dao.insert(
            MessageEntity(
                fromUser = false,
                text = r.text,
                chips = r.chips.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                actionJson = json.encodeToString(MsgMeta.serializer(), meta),
                actionState = when { r.proposal != null -> ActionState.PENDING; r.undo != null -> ActionState.APPLIED; else -> null },
                remote = r.remote,
            ),
        )
        r.undo?.let { sessionUndo[id] = it }
    }

    fun undo(m: MessageEntity) = viewModelScope.launch {
        val meta = meta(m)
        val u = sessionUndo.remove(m.id) ?: meta?.undoTask?.let { Undo.DeleteTask(it) } ?: meta?.undoNote?.let { Undo.DeleteNote(it) } ?: return@launch
        c.assistant.undo(u)
        c.db.messages().update(m.copy(actionState = ActionState.UNDONE))
    }

    fun confirm(m: MessageEntity) = viewModelScope.launch {
        val meta = meta(m) ?: return@launch
        val kind = meta.proposal?.let { runCatching { ProposalKind.valueOf(it) }.getOrNull() } ?: return@launch
        val undo = c.assistant.confirm(Proposal(kind, meta.proposalTask ?: return@launch, meta.proposalLabel.orEmpty()))
        undo?.let { sessionUndo[m.id] = it }
        c.db.messages().update(m.copy(actionState = ActionState.APPLIED))
    }

    fun decline(m: MessageEntity) = viewModelScope.launch { c.db.messages().update(m.copy(actionState = ActionState.DECLINED)) }

    fun canUndo(m: MessageEntity) = m.actionState == ActionState.APPLIED && (sessionUndo.containsKey(m.id) || meta(m)?.let { it.undoTask != null || it.undoNote != null } == true)

    fun clear() = viewModelScope.launch { c.db.messages().clear(); sessionUndo.clear() }
}

@Composable
fun AssistantScreen(nav: NavController, listen: Boolean) {
    val vm = fridayViewModel { AssistantViewModel(it) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val c = Friday.colors
    val tabBar = LocalTabBarHeight.current
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val dictate = rememberDictation { vm.send(it) }
    LaunchedEffect(listen) { if (listen) dictate?.invoke() }
    LaunchedEffect(messages.size, thinking) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size + 1) }

    val continueChip = stringResource(R.string.chip_continue_focus)
    fun onChip(m: MessageEntity, chip: String) {
        val meta = vm.meta(m)
        if (chip == meta?.focusChip || chip == continueChip) {
            val id = meta?.taskId ?: 0
            nav.navigate("focus/$id" + (meta?.focusMinutes?.let { "?min=$it" } ?: ""))
        } else vm.send(chip)
    }

    Column(Modifier.fillMaxSize().background(c.background).imePadding().padding(bottom = tabBar)) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("FRIDAY", style = MaterialTheme.typography.displaySmall, color = c.text)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) c.green else c.textTertiary))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (online) R.string.status_online else R.string.status_offline), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.separator)
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty()) {
                item(key = "intro") { Intro(onPick = vm::send) }
            }
            items(messages, key = { it.id }) { m ->
                val isLast = m.id == messages.lastOrNull()?.id
                Bubble(
                    m = m,
                    chips = if (isLast && !m.fromUser) m.chips?.split("\n").orEmpty() else emptyList(),
                    canUndo = vm.canUndo(m),
                    onChip = { onChip(m, it) },
                    onUndo = { vm.undo(m) },
                    onConfirm = { vm.confirm(m) },
                    onDecline = { vm.decline(m) },
                )
            }
            if (thinking) item(key = "typing") { Typing() }
            item(key = "end") { Spacer(Modifier.height(4.dp)) }
        }
        CaptureBar(
            value = input,
            onValueChange = { input = it },
            onSubmit = { vm.send(input); input = "" },
            placeholder = stringResource(R.string.ask_friday),
            onMic = dictate,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Intro(onPick: (String) -> Unit) {
    val c = Friday.colors
    val suggestions = listOf(R.string.suggest_today, R.string.suggest_forgot, R.string.suggest_free, R.string.suggest_too_many, R.string.suggest_remind)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        Text(stringResource(R.string.assistant_intro), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(16.dp))
        suggestions.forEach { s ->
            val text = stringResource(s)
            Pill(text, selected = false, onClick = { onPick(text) }, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun Bubble(
    m: MessageEntity,
    chips: List<String>,
    canUndo: Boolean,
    onChip: (String) -> Unit,
    onUndo: () -> Unit,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    val c = Friday.colors
    val shape = if (m.fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start) {
        Text(
            m.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (m.fromUser) androidx.compose.ui.graphics.Color.White else c.text,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(if (m.fromUser) c.accent else c.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .then(if (!m.fromUser) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        )
        if (!m.fromUser) {
            when (m.actionState) {
                ActionState.PENDING -> Row(Modifier.padding(top = 4.dp)) {
                    TextAction(stringResource(R.string.confirm), onConfirm, bold = true)
                    TextAction(stringResource(R.string.cancel), onDecline, color = c.textSecondary)
                }
                ActionState.APPLIED -> if (canUndo) TextAction(stringResource(R.string.undo), onUndo, color = c.textSecondary)
                ActionState.UNDONE -> Text(stringResource(R.string.undone), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(start = 10.dp, top = 4.dp))
                ActionState.DECLINED -> Text(stringResource(R.string.declined), style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(start = 10.dp, top = 4.dp))
                null -> Unit
            }
            if (chips.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { Pill(it, selected = false, onClick = { onChip(it) }) }
                }
            }
        }
    }
}

@Composable
private fun Typing() {
    val c = Friday.colors
    val t = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(7.dp).alpha(if (Friday.reduceMotion) 0.6f else a).clip(CircleShape).background(c.textSecondary))
        }
    }
}

