package com.friday.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.friday.app.R
import com.friday.app.ai.AiException
import com.friday.app.ai.Lang
import com.friday.app.container
import com.friday.app.data.AppSettings
import com.friday.app.data.SecretStore
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.LargeTitleScreen
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.Section
import com.friday.app.ui.components.SwitchRow
import com.friday.app.ui.screens.OptionSheet
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.launch

private fun masked(key: String?) = key?.let { "•••• " + it.takeLast(4) }

private fun serviceName(url: String): String = when (val host = Uri.parse(url).host.orEmpty()) {
    "generativelanguage.googleapis.com" -> "Google Gemini"
    "api.openai.com" -> "OpenAI"
    "openrouter.ai" -> "OpenRouter"
    "api.groq.com" -> "Groq"
    else -> host.ifBlank { url }
}

@Composable
fun AiSettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val colors = Friday.colors
    val s by c.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val keyVersion by c.secrets.version.collectAsStateWithLifecycle()
    val key = remember(keyVersion) { c.secrets.get(SecretStore.AI_KEY) }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LargeTitleScreen(title = stringResource(R.string.ai_title), onBack = { nav.popBackStack() }) {
        item(key = "intro") {
            Text(stringResource(R.string.ai_intro), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp))
        }
        item(key = "switch") {
            Section {
                SwitchRow(stringResource(R.string.ai_use), s.aiEnabled, { v -> scope.launch { c.settings.setAiEnabled(v) } }, icon = R.drawable.ic_auto_awesome, iconBackground = colors.accent)
            }
        }
        item(key = "provider") {
            Section(header = stringResource(R.string.ai_provider), footer = stringResource(R.string.ai_provider_footer), modifier = Modifier.padding(top = 22.dp)) {
                ListRow(stringResource(R.string.ai_key), icon = R.drawable.ic_key, iconBackground = colors.amber, value = masked(key) ?: stringResource(R.string.not_set), onClick = { dialog = "key" })
                RowDivider(60.dp)
                ListRow(stringResource(R.string.ai_get_key), icon = R.drawable.ic_cloud, iconBackground = colors.green, onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
                RowDivider(60.dp)
                ListRow(stringResource(R.string.ai_server), icon = R.drawable.ic_language, iconBackground = colors.textTertiary, value = serviceName(s.aiBaseUrl), onClick = { dialog = "url" })
                RowDivider(60.dp)
                ListRow(stringResource(R.string.ai_model), icon = R.drawable.ic_tune, iconBackground = colors.textTertiary, value = s.aiModel, onClick = { dialog = "model" })
                if (key != null) {
                    RowDivider(60.dp)
                    ListRow(stringResource(R.string.ai_pick_model), titleColor = colors.accent, onClick = {
                        scope.launch {
                            runCatching { c.ai.models() }
                                .onSuccess { models = it }
                                .onFailure { e -> toaster.show((e as? AiException)?.let { c.ai.errorText(ctx, it) } ?: ctx.getString(R.string.ai_err_server)) }
                        }
                    }, chevron = false)
                }
            }
        }
        item(key = "test") {
            Section(footer = status, modifier = Modifier.padding(top = 22.dp)) {
                ListRow(
                    stringResource(R.string.ai_test), titleColor = colors.accent, chevron = false,
                    value = if (testing) stringResource(R.string.thinking) else null,
                    onClick = {
                        if (testing) return@ListRow
                        testing = true
                        scope.launch {
                            status = runCatching { c.ai.test() }.fold(
                                { ctx.getString(R.string.ai_test_ok, it) },
                                { e -> (e as? AiException)?.let { c.ai.errorText(ctx, it) } ?: ctx.getString(R.string.ai_err_server) },
                            )
                            testing = false
                        }
                    },
                )
            }
        }
        item(key = "privacy") {
            Text(stringResource(R.string.ai_privacy), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 22.dp))
        }
    }

    when (dialog) {
        "key" -> TextDialog(stringResource(R.string.ai_key), "", { dialog = null }, secret = true) { v -> dialog = null; c.secrets.put(SecretStore.AI_KEY, v); status = null }
        "url" -> TextDialog(stringResource(R.string.ai_server), s.aiBaseUrl, { dialog = null }, capitalization = KeyboardCapitalization.None) { v ->
            dialog = null
            if (v.startsWith("https://") || v.startsWith("http://")) scope.launch { c.settings.setAiUrl(v) } else toaster.show(ctx.getString(R.string.ai_bad_url))
        }
        "model" -> TextDialog(stringResource(R.string.ai_model), s.aiModel, { dialog = null }, capitalization = KeyboardCapitalization.None) { v -> dialog = null; if (v.isNotBlank()) scope.launch { c.settings.setAiModel(v) } }
    }
    models?.let { list ->
        OptionSheet(stringResource(R.string.ai_model), list.map { it to it }, s.aiModel, { models = null }) { m -> models = null; scope.launch { c.settings.setAiModel(m) } }
    }
}

@Composable
fun VoiceSettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val colors = Friday.colors
    val s by c.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val keyVersion by c.secrets.version.collectAsStateWithLifecycle()
    val key = remember(keyVersion) { c.secrets.get(SecretStore.ELEVEN_KEY) }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var keyDialog by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var voiceName by remember { mutableStateOf<String?>(null) }

    LargeTitleScreen(title = stringResource(R.string.voice_title), onBack = { nav.popBackStack() }) {
        item(key = "talk") {
            Section(header = stringResource(R.string.voice_talking), footer = stringResource(R.string.voice_assistant_footer)) {
                SwitchRow(stringResource(R.string.voice_read_aloud), s.readAloud, { v -> scope.launch { c.settings.setReadAloud(v) } }, subtitle = stringResource(R.string.voice_read_aloud_sub), icon = R.drawable.ic_volume_up, iconBackground = colors.accent)
                RowDivider(60.dp)
                SwitchRow(stringResource(R.string.voice_keep_listening), s.voiceConversation, { v -> scope.launch { c.settings.setVoiceConversation(v) } }, subtitle = stringResource(R.string.voice_keep_listening_sub), icon = R.drawable.ic_mic, iconBackground = colors.accent)
                RowDivider(60.dp)
                ListRow(stringResource(R.string.voice_default_assistant), icon = R.drawable.ic_graphic_eq, iconBackground = colors.accent, onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
        }
        item(key = "eleven") {
            Section(header = stringResource(R.string.voice_natural), footer = stringResource(R.string.voice_natural_footer), modifier = Modifier.padding(top = 22.dp)) {
                ListRow(stringResource(R.string.voice_eleven_key), icon = R.drawable.ic_key, iconBackground = colors.amber, value = masked(key) ?: stringResource(R.string.not_set), onClick = { keyDialog = true })
                RowDivider(60.dp)
                ListRow(stringResource(R.string.voice_get_key), icon = R.drawable.ic_cloud, iconBackground = colors.green, onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/app/settings/api-keys")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
                if (key != null) {
                    RowDivider(60.dp)
                    ListRow(
                        stringResource(R.string.voice_pick), icon = R.drawable.ic_graphic_eq, iconBackground = colors.textTertiary,
                        value = voiceName ?: s.elevenVoiceId.ifBlank { stringResource(R.string.voice_auto) },
                        onClick = {
                            scope.launch {
                                runCatching { c.speaker.voices(key) }
                                    .onSuccess { voices = it }
                                    .onFailure { toaster.show(ctx.getString(R.string.voice_err)) }
                            }
                        },
                    )
                }
            }
        }
        item(key = "test") {
            Section(modifier = Modifier.padding(top = 22.dp)) {
                ListRow(stringResource(R.string.voice_test), icon = R.drawable.ic_volume_up, titleColor = colors.accent, chevron = false, onClick = {
                    scope.launch {
                        val ms = com.friday.app.ui.Fmt.locale(ctx).language == "ms"
                        c.speaker.speak(ctx.getString(R.string.voice_test_line), if (ms) Lang.MS else Lang.EN)
                    }
                })
            }
        }
    }

    if (keyDialog) {
        TextDialog(stringResource(R.string.voice_eleven_key), "", { keyDialog = false }, secret = true) { v -> keyDialog = false; c.secrets.put(SecretStore.ELEVEN_KEY, v) }
    }
    voices?.let { list ->
        OptionSheet(stringResource(R.string.voice_pick), list, s.elevenVoiceId, { voices = null }) { id ->
            voices = null
            voiceName = list.firstOrNull { it.second == id }?.first
            scope.launch { c.settings.setElevenVoice(id) }
        }
    }
}
