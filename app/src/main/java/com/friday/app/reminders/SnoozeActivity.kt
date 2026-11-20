package com.friday.app.reminders

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.friday.app.R
import com.friday.app.container
import com.friday.app.ui.Fmt
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.theme.Friday
import com.friday.app.ui.theme.FridayTheme
import kotlinx.coroutines.launch

/** "Snooze…" from a reminder notification: 5 min, 15 min, 1 hour or tomorrow. */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cueId = intent.getLongExtra(Extras.CUE_ID, -1)
        if (cueId < 0) { finish(); return }
        val engine = container.reminders
        fun pick(block: suspend () -> Unit) {
            lifecycleScope.launch { block(); finish() }
        }
        setContent {
            FridayTheme(isSystemInDarkTheme()) {
                val c = Friday.colors
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .padding(24.dp)
                            .widthIn(max = 360.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.surface)
                            .clickable(enabled = false) {},
                    ) {
                        Text(
                            stringResource(R.string.snooze_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = c.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
                        )
                        for (m in listOf(5, 15, 60)) {
                            RowDivider(0.dp)
                            ListRow(Fmt.duration(this@SnoozeActivity, m), onClick = { pick { engine.snooze(cueId, m) } }, chevron = false)
                        }
                        RowDivider(0.dp)
                        ListRow(stringResource(R.string.tomorrow), onClick = { pick { engine.postponeToTomorrow(cueId) } }, chevron = false)
                        RowDivider(0.dp)
                        ListRow(stringResource(R.string.cancel), titleColor = c.accent, onClick = { finish() }, chevron = false)
                    }
                }
            }
        }
    }
}
