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
import com.friday.app.focus.DndControl
import com.friday.app.focus.SystemDnd
import com.friday.app.location.AndroidPlaceSystem
import com.friday.app.location.OverpassClient
import com.friday.app.location.PlaceAwareness
import com.friday.app.location.PlaceManager
import com.friday.app.location.PlaceSystem
import com.friday.app.location.VisitStore
import com.friday.app.ui.Fmt
import com.friday.app.reminders.Briefing
import com.friday.app.reminders.Notifier
import com.friday.app.reminders.ReminderEngine
import com.friday.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-written dependency container: one instance of each service for the whole app. */
/** [dnd] is faked by device tests so they never touch the phone's real Do Not Disturb. */
class AppContainer(
    val context: Context,
    inMemoryDb: Boolean = false,
    val dnd: DndControl = SystemDnd(context),
    /** Device tests pass a fake so the real visit fence, cap alarm and place note are never touched. */
    placeSystem: PlaceSystem? = null,
) {
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
    val visits = VisitStore(context, if (inMemoryDb) "place_visit_test" else "place_visit")
    val reminders = ReminderEngine(context, db, settings, scheduler, notifier, tasks, memory, atQuietPlace = visits::holding) { t, time ->
        context.getString(R.string.habit_usual_time, t.title, Fmt.time(context, time))
    }
    val briefing = Briefing(context, settings, tasks, notifier)
    val focus = FocusController(context, db, notifier, scheduler, dnd, onEnded = { reminders.releaseHeld() })
    val parser = CommandParser()
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val ai = OnlineAssistant(context, db, settings, secrets, tasks, notes, memory, reminders, OpenAiCompatClient(http))
    val assistant = AssistantEngine(context, db, tasks, notes, memory, parser, ai, places, settings)
    val awareness = PlaceAwareness(
        context, db, settings, reminders, notifier, tasks, memory, visits, dnd,
        placeSystem ?: AndroidPlaceSystem(context, places, OverpassClient(http), scheduler, notifier),
    )
    val speaker = Speaker(context, settings, secrets, http)
}

class FridayApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.tasks.onSaved = { container.scope.launch { container.ai.prepareLines() } }
        container.scope.launch {
            container.scheduler.syncAll(fireMissed = false)
            container.briefing.schedule()
            container.ai.prepareLines()
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as FridayApp).container
