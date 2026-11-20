package com.friday.app.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.friday.app.R
import com.friday.app.container
import com.friday.app.data.db.MemoryEntity
import com.friday.app.data.db.MemorySource
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.BarIcon
import com.friday.app.ui.components.EmptyState
import com.friday.app.ui.components.LargeTitleScreen
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.Section
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = ctx.container.memory
    val colors = Friday.colors
    val items by repo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }

    LargeTitleScreen(title = stringResource(R.string.memory_title), onBack = { nav.popBackStack() }) {
        item(key = "intro") {
            Text(stringResource(R.string.memory_intro), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp))
        }
        item(key = "add") {
            Section { ListRow(stringResource(R.string.memory_add_hint), icon = R.drawable.ic_add, iconBackground = colors.accent, onClick = { adding = true }) }
        }
        item(key = "list") {
            if (items.isEmpty()) {
                EmptyState(R.drawable.ic_info, stringResource(R.string.memory_empty_title), stringResource(R.string.memory_empty_body))
            } else {
                Section(modifier = Modifier.padding(top = 22.dp)) {
                    items.forEachIndexed { i, m ->
                        if (i > 0) RowDivider(16.dp)
                        ListRow(
                            m.text, subtitle = stringResource(when (m.source) {
                                MemorySource.USER -> R.string.memory_source_user
                                MemorySource.CHAT -> R.string.memory_source_chat
                                MemorySource.HABIT -> R.string.memory_source_habit
                            }),
                            chevron = false, onClick = { editing = m },
                            trailing = {
                                BarIcon(R.drawable.ic_delete, stringResource(R.string.delete), {
                                    scope.launch { repo.delete(m); toaster.show(ctx.getString(R.string.memory_deleted)) { scope.launch { repo.restore(m) } } }
                                }, tint = colors.textTertiary)
                            },
                        )
                    }
                }
            }
        }
    }

    if (adding) TextDialog(stringResource(R.string.memory_add_hint), "", { adding = false }) { t -> adding = false; scope.launch { repo.add(t, MemorySource.USER) } }
    editing?.let { m -> TextDialog(stringResource(R.string.memory_edit), m.text, { editing = null }) { t -> editing = null; scope.launch { repo.edit(m, t) } } }
}
