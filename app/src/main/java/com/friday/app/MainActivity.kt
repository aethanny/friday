package com.friday.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.friday.app.reminders.Extras
import com.friday.app.ui.FridayRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { FridayRoot(pendingRoute) { pendingRoute.value = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        val cueId = i.getLongExtra(Extras.CUE_ID, -1)
        if (cueId >= 0) {
            // Opening a reminder counts as handling it: no follow-up nudge.
            container.notifier.cancelReminder(cueId)
            lifecycleScope.launch { container.reminders.acknowledge(cueId) }
        }
        val route = i.getStringExtra(Extras.ROUTE)
        val taskId = i.getLongExtra(Extras.TASK_ID, -1)
        pendingRoute.value = when {
            i.action == Intent.ACTION_ASSIST || i.action == ACTION_TALK -> "assistant?listen=true"
            route != null -> route
            taskId > 0 -> "task/$taskId"
            else -> pendingRoute.value
        }
    }

    companion object {
        const val ACTION_TALK = "com.friday.app.TALK"
    }
}
