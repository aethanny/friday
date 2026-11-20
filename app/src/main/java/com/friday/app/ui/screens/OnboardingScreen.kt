package com.friday.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.friday.app.R
import com.friday.app.container
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.TextAction
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.launch

/** FRIDAY's mark: a quiet ring with a core. */
@Composable
fun FridayMark(size: Int = 88) {
    val c = Friday.colors
    Canvas(Modifier.size(size.dp)) {
        val r = this.size.minDimension / 2
        drawCircle(c.accent.copy(alpha = 0.12f), r)
        drawCircle(c.accent, r * 0.62f, style = Stroke(r * 0.12f))
        drawCircle(c.accent, r * 0.22f, center = Offset(this.size.width / 2, this.size.height / 2))
    }
}

@Composable
fun OnboardingScreen() {
    val ctx = LocalContext.current
    val app = ctx.container
    val c = Friday.colors
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    val finish = {
        scope.launch {
            app.settings.setName(name)
            app.settings.setOnboarded()
        }
        Unit
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    Box(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding().imePadding().padding(28.dp)) {
        AnimatedContent(step, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "onboarding") { s ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                when (s) {
                    0 -> {
                        FridayMark()
                        Spacer(Modifier.height(28.dp))
                        Text(stringResource(R.string.onb_hi), style = MaterialTheme.typography.displaySmall, color = c.text, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.onb_body), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, textAlign = TextAlign.Center)
                    }
                    1 -> {
                        Text(stringResource(R.string.onb_name_q), style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        BasicTextField(
                            name, { name = it.take(40) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(18.dp),
                            textStyle = MaterialTheme.typography.titleLarge.copy(color = c.text, textAlign = TextAlign.Center),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { step = 2 }),
                            decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (name.isEmpty()) Text(stringResource(R.string.onb_name_hint), style = MaterialTheme.typography.titleLarge, color = c.textTertiary); inner() } },
                        )
                    }
                    else -> {
                        FridayMark(64)
                        Spacer(Modifier.height(24.dp))
                        Text(stringResource(R.string.onb_notif_title), style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.onb_notif_body), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.weight(1f))
                when (s) {
                    0 -> PrimaryButton(stringResource(R.string.onb_start), { step = 1 }, Modifier.fillMaxWidth())
                    1 -> {
                        PrimaryButton(stringResource(R.string.continue_), { step = 2 }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
                        TextAction(stringResource(R.string.skip), { name = ""; step = 2 }, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
                    }
                    else -> {
                        PrimaryButton(stringResource(R.string.onb_allow), {
                            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else finish()
                        }, Modifier.fillMaxWidth())
                        TextAction(stringResource(R.string.not_now), { finish() }, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
