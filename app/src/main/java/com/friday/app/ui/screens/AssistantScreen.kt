package com.friday.app.ui.screens

import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.FridaySheet
import com.friday.app.ui.components.BarIcon
import com.friday.app.voice.Listener
import com.friday.app.data.SecretStore
import com.friday.app.ai.EngineResult
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
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

/** What a FRIDAY message can still do: open a task, confirm a change or undo what it did. */
@Serializable
data class MsgMeta(
    val taskId: Long? = null,
    /** The chip that opens [taskId] ("Open it"). */
    val openChip: String? = null,
    val proposal: String? = null,
    val proposalTask: Long? = null,
    val proposalLabel: String? = null,
    val undoTask: Long? = null,
    val undoNote: Long? = null,
    val proposalMinutes: Int = 0,
    val proposalPayload: String = "",
)

enum class VoiceState { IDLE, LISTENING, SPEAKING }

private val json = Json { ignoreUnknownKeys = true }

class AssistantViewModel(private val c: AppContainer) : ViewModel() {
    val messages: StateFlow<List<MessageEntity>> = c.db.messages().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val thinking = MutableStateFlow(false)
    val online: StateFlow<Boolean> = combine(c.settings.flow, c.secrets.version) { s, _ -> s.aiEnabled && c.secrets.has(SecretStore.AI_KEY) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val navigate = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val voice = MutableStateFlow(VoiceState.IDLE)
    val heard = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    private val listener = Listener(c.context)
    private var conversation = false
    private var languageTag = "en-US"
    private val sessionUndo = mutableMapOf<Long, Undo>()

    fun canListen() = listener.available()

    fun startVoice(tag: String) {
        languageTag = tag
        conversation = true
        c.speaker.stop()
        listen()
    }

    private fun listen() {
        heard.value = ""
        voice.value = VoiceState.LISTENING
        listener.start(languageTag) { e ->
            when (e) {
                is Listener.Event.Partial -> heard.value = e.text
                is Listener.Event.Level -> level.value = e.rms
                is Listener.Event.Final -> { heard.value = e.text; voice.value = VoiceState.IDLE; send(e.text, spoken = true) }
                Listener.Event.Nothing, is Listener.Event.Failed -> { conversation = false; voice.value = VoiceState.IDLE }
            }
        }
    }

    fun stopVoice() {
        conversation = false
        listener.stop()
        c.speaker.stop()
        voice.value = VoiceState.IDLE
    }

    override fun onCleared() {
        listener.stop()
        c.speaker.stop()
    }

    private suspend fun afterReply(r: EngineResult, spoken: Boolean) {
        val s = c.settings.current()
        if (r.text.isNotBlank() && (spoken || s.readAloud)) {
            voice.value = VoiceState.SPEAKING
            c.speaker.speak(r.text, r.lang)
            if (voice.value == VoiceState.SPEAKING) voice.value = VoiceState.IDLE
        }
        // Like Siri: keep listening only when FRIDAY asked something back.
        if (spoken && conversation && s.voiceConversation && r.text.trimEnd().endsWith("?")) listen() else if (spoken) conversation = false
    }

    fun meta(m: MessageEntity): MsgMeta? = m.actionJson?.let { runCatching { json.decodeFromString(MsgMeta.serializer(), it) }.getOrNull() }

    fun send(text: String, spoken: Boolean = false) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        val dao = c.db.messages()
        dao.insert(MessageEntity(fromUser = true, text = text.trim()))
        thinking.value = true
        val r = try { c.assistant.handle(text, Source.CHAT) } finally { thinking.value = false }
        val meta = MsgMeta(
            taskId = r.taskId,
            openChip = r.chips.singleOrNull()?.takeIf { r.taskId != null },
            proposal = r.proposal?.kind?.name,
            proposalTask = r.proposal?.taskId,
            proposalLabel = r.proposal?.label,
            undoTask = (r.undo as? Undo.DeleteTask)?.id,
            undoNote = (r.undo as? Undo.DeleteNote)?.id,
            proposalMinutes = r.proposal?.minutes ?: 0,
            proposalPayload = r.proposal?.payload.orEmpty(),
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
        afterReply(r, spoken)
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
        val undo = c.assistant.confirm(Proposal(kind, meta.proposalTask ?: return@launch, meta.proposalLabel.orEmpty(), meta.proposalMinutes, meta.proposalPayload))
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
    val input = rememberTextFieldState()
    val list = rememberLazyListState()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val heard by vm.heard.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val dictate = rememberDictation { vm.send(it) }
    var askMic by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice(speechLocaleTag(ctx)) else dictate?.invoke()
    }
    val talk: () -> Unit = {
        when {
            !vm.canListen() -> dictate?.invoke()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.startVoice(speechLocaleTag(ctx))
            else -> askMic = true
        }
    }
    var listenHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(listen) { if (listen && !listenHandled) { listenHandled = true; talk() } }
    LaunchedEffect(Unit) { vm.navigate.collect { nav.navigate(it) } }
    LaunchedEffect(messages.size, thinking) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size + 1) }

    fun onChip(m: MessageEntity, chip: String) {
        val meta = vm.meta(m)
        val id = meta?.taskId
        if (chip == meta?.openChip && id != null) nav.navigate("task/$id") else vm.send(chip)
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
        if (voice == VoiceState.IDLE) {
            CaptureBar(
                state = input,
                onSubmit = { vm.send(input.text.toString()); input.clearText() },
                placeholder = stringResource(R.string.ask_friday),
                onMic = if (vm.canListen() || dictate != null) talk else null,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            VoicePanel(voice, heard, level, onStop = vm::stopVoice)
        }
    }

    if (askMic) {
        FridaySheet(onDismiss = { askMic = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                FridayMark(56)
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.mic_title), style = MaterialTheme.typography.headlineSmall, color = c.text)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mic_body), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                Spacer(Modifier.height(24.dp))
                PrimaryButton(stringResource(R.string.mic_allow), { askMic = false; micPermission.launch(Manifest.permission.RECORD_AUDIO) }, Modifier.fillMaxWidth(), icon = R.drawable.ic_mic)
                TextAction(stringResource(R.string.not_now), { askMic = false }, color = c.textSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
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


/** Replaces the text field while FRIDAY listens or speaks. The orb grows with your voice. */
@Composable
private fun VoicePanel(state: VoiceState, heard: String, level: Float, onStop: () -> Unit) {
    val c = Friday.colors
    val target = when (state) {
        VoiceState.LISTENING -> 1f + ((level + 2f) / 12f).coerceIn(0f, 0.45f)
        VoiceState.SPEAKING -> 1.15f
        VoiceState.IDLE -> 1f
    }
    val scale by animateFloatAsState(if (Friday.reduceMotion) 1f else target, spring(dampingRatio = 0.5f, stiffness = 300f), label = "orb")
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(c.surface)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(c.accentSoft))
            Box(Modifier.size(16.dp).clip(CircleShape).background(c.accent))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (state == VoiceState.SPEAKING) R.string.voice_speaking else R.string.voice_listening),
                style = MaterialTheme.typography.labelMedium,
                color = c.accent,
            )
            Text(
                heard.ifBlank { stringResource(R.string.voice_hint) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (heard.isBlank()) c.textTertiary else c.text,
                maxLines = 3,
            )
        }
        BarIcon(R.drawable.ic_stop_circle, stringResource(R.string.stop), onStop, tint = c.red)
    }
}
