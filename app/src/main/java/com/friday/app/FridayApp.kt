package com.friday.app

import android.app.Application
import android.content.Context
import com.friday.app.ai.AssistantEngine
import com.friday.app.ai.CommandParser
import com.friday.app.ai.RemoteAssistant
import com.friday.app.data.NoteRepository
import com.friday.app.data.SecretStore
import com.friday.app.data.SettingsStore
import com.friday.app.data.TaskRepository
import com.friday.app.data.db.FridayDatabase
import com.friday.app.focus.FocusController
import com.friday.app.reminders.Briefing
import com.friday.app.reminders.Notifier
import com.friday.app.reminders.ReminderEngine
import com.friday.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-written dependency container: one instance of each service for the whole app. */
class AppContainer(val context: Context, inMemoryDb: Boolean = false) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = FridayDatabase.build(context, inMemoryDb)
    val settings = SettingsStore(context)
    val secrets = SecretStore(context)
    val notifier = Notifier(context)
    val scheduler = ReminderScheduler(context, db)
    val tasks = TaskRepository(db, settings, scheduler)
    val notes = NoteRepository(db)
    val reminders = ReminderEngine(db, settings, scheduler, notifier, tasks)
    val briefing = Briefing(context, settings, tasks, notifier)
    val focus = FocusController(context, db, notifier, scheduler)
    val parser = CommandParser()
    var remote: RemoteAssistant? = null
    val assistant by lazy { AssistantEngine(context, db, tasks, notes, parser, RemoteProxy()) }

    /** Lets the remote AI be switched on and off at runtime without rebuilding the engine. */
    private inner class RemoteProxy : RemoteAssistant {
        override suspend fun respond(text: String, lang: com.friday.app.ai.Lang) = remote?.respond(text, lang)
        override suspend fun applyConfirmed(p: com.friday.app.ai.Proposal) = remote?.applyConfirmed(p)
    }
}

class FridayApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.scope.launch {
            container.scheduler.syncAll(fireMissed = false)
            container.briefing.schedule()
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as FridayApp).container
