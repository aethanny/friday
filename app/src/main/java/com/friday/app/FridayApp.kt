package com.friday.app

import android.app.Application
import android.content.Context
import com.friday.app.ai.AssistantEngine
import com.friday.app.ai.CommandParser
import com.friday.app.ai.OnlineAssistant
import com.friday.app.ai.OpenAiCompatClient
import com.friday.app.voice.Speaker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.friday.app.data.MemoryRepository
import com.friday.app.data.NoteRepository
import com.friday.app.data.SecretStore
import com.friday.app.data.SettingsStore
import com.friday.app.data.TaskRepository
import com.friday.app.data.db.FridayDatabase
import com.friday.app.focus.FocusController
import com.friday.app.location.PlaceManager
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
    val places = PlaceManager(context, db)
    val scheduler = ReminderScheduler(context, db, places)
    val tasks = TaskRepository(db, settings, scheduler)
    val notes = NoteRepository(db)
    val memory = MemoryRepository(db)
    val reminders = ReminderEngine(db, settings, scheduler, notifier, tasks)
    val briefing = Briefing(context, settings, tasks, notifier)
    val focus = FocusController(context, db, notifier, scheduler)
    val parser = CommandParser()
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val ai = OnlineAssistant(context, db, settings, secrets, tasks, notes, reminders, OpenAiCompatClient(http))
    val assistant = AssistantEngine(context, db, tasks, notes, parser, ai, places)
    val speaker = Speaker(context, settings, secrets, http)
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
