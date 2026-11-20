package com.friday.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.friday.app.AppContainer
import com.friday.app.R
import com.friday.app.container
import com.friday.app.ui.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime

@Composable
inline fun <reified T : ViewModel> fridayViewModel(key: String? = null, noinline create: (AppContainer) -> T): T {
    val c = LocalContext.current.container
    return viewModel(key = key) { create(c) }
}

/** Emits the current time now and then every [periodMs], so "in 5 min" style text stays fresh. */
fun clock(periodMs: Long = 30_000): Flow<LocalDateTime> = flow {
    while (true) {
        emit(LocalDateTime.now())
        delay(periodMs)
    }
}

fun speechLocaleTag(ctx: android.content.Context): String {
    val l = Fmt.locale(ctx)
    return if (l.language == "ms") "ms-MY" else l.toLanguageTag()
}

/** One-shot dictation through the phone's own speech dialog (no microphone permission needed in FRIDAY). */
@Composable
fun rememberDictation(onText: (String) -> Unit): (() -> Unit)? {
    val ctx = LocalContext.current
    val callback = rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { callback.value(it) }
    }
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLocaleTag(ctx))
            .putExtra(RecognizerIntent.EXTRA_PROMPT, ctx.getString(R.string.capture_placeholder))
    }
    val available = remember { intent.resolveActivity(ctx.packageManager) != null }
    if (!available) return null
    return { try { launcher.launch(intent) } catch (_: ActivityNotFoundException) {} }
}

/** Current time in millis, refreshed every [periodMs] while on screen. */
@Composable
fun rememberNowMillis(periodMs: Long = 1_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}
