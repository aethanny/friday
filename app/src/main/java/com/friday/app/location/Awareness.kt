package com.friday.app.location

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.util.Log
import com.friday.app.R
import com.friday.app.container
import com.friday.app.data.MemoryRepository
import com.friday.app.data.SettingsStore
import com.friday.app.data.TaskRepository
import com.friday.app.data.db.FridayDatabase
import com.friday.app.data.db.MemorySource
import com.friday.app.domain.Matrix
import com.friday.app.domain.PlaceKind
import com.friday.app.domain.PlaceKinds
import com.friday.app.focus.DndControl
import com.friday.app.reminders.Channels
import com.friday.app.reminders.Notifier
import com.friday.app.reminders.ReminderEngine
import com.friday.app.reminders.ReminderScheduler
import com.friday.app.reminders.launchAsync
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Visit(val kind: PlaceKind, val lat: Double, val lng: Double, val startedAt: Long, val holding: Boolean)

/** The current stop, kept in SharedPreferences so it survives the process dying. Tests use their own [name]. */
class VisitStore(ctx: Context, name: String = "place_visit") {
    private val prefs = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun get(): Visit? {
        val kind = prefs.getString("kind", null)?.let { runCatching { PlaceKind.valueOf(it) }.getOrNull() } ?: return null
        return Visit(kind, Double.fromBits(prefs.getLong("lat", 0)), Double.fromBits(prefs.getLong("lng", 0)), prefs.getLong("at", 0), prefs.getBoolean("holding", false))
    }

    fun set(v: Visit?) {
        val e = prefs.edit()
        if (v == null) e.remove("kind").remove("lat").remove("lng").remove("at").remove("holding")
        else e.putString("kind", v.kind.name).putLong("lat", v.lat.toRawBits()).putLong("lng", v.lng.toRawBits()).putLong("at", v.startedAt).putBoolean("holding", v.holding)
        e.apply()
    }

    fun holding() = get()?.holding == true

    /** Epoch day the "Left today" note was last shown at home. */
    var homeShownDay: Long
        get() = prefs.getLong("home_day", -1)
        set(v) = prefs.edit().putLong("home_day", v).apply()
}

/**
 * Place awareness: when the phone stays still for 3 minutes, look up what kind of place this is on
 * OpenStreetMap (rough location only) and let it shape reminders. Nothing is stored beyond the current stop.
 */
class PlaceAwareness(
    private val ctx: Context,
    private val db: FridayDatabase,
    private val settings: SettingsStore,
    private val places: PlaceManager,
    private val reminders: ReminderEngine,
    private val scheduler: ReminderScheduler,
    private val notifier: Notifier,
    private val tasks: TaskRepository,
    private val memory: MemoryRepository,
    private val overpass: OverpassClient,
    val visits: VisitStore,
    private val dnd: DndControl,
) {
    private val am = ctx.getSystemService(AlarmManager::class.java)

    // Activity recognition fills in the result extras, so this one has to be mutable.
    private fun stillIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx, STILL_REQUEST, Intent(ctx, StillnessReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    private fun checkIntent(flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(ctx, CHECK_REQUEST, Intent(ctx, VisitCheckReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or flags)

    private fun capIntent(startedAt: Long, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(
        ctx, CAP_REQUEST,
        Intent(ctx, VisitCheckReceiver::class.java).setAction(VisitCheckReceiver.ACTION_CAP).putExtra(VisitCheckReceiver.EXTRA_STARTED, startedAt),
        PendingIntent.FLAG_IMMUTABLE or flags,
    )

    @SuppressLint("MissingPermission")
    suspend fun enable() {
        settings.setPlaceAwareness(true)
        val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(it).build()
        }
        runCatching { ActivityRecognition.getClient(ctx).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), stillIntent()).awaitResult() }
            .onFailure { Log.w(TAG, "could not watch stillness: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    suspend fun disable() {
        settings.setPlaceAwareness(false)
        runCatching { ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(stillIntent()).awaitResult() }
        stillEnded()
        left()
    }

    /** Still for a while: check in 3 minutes. Inexact on purpose; a few minutes late costs nothing. */
    fun stillStarted() = am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + STILL_MS, checkIntent()!!)

    /** Moving again: no check. Leaving a place is decided by the visit geofence, not by this. */
    fun stillEnded() {
        checkIntent(PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    suspend fun stillFor3Min(now: Long = System.currentTimeMillis()) {
        if (visits.get() != null) return
        val here = places.here() ?: return
        val saved = db.places().all().firstOrNull { p ->
            FloatArray(1).also { Location.distanceBetween(here.latitude, here.longitude, p.lat, p.lng, it) }[0] <= p.radiusM
        }
        val kind = when {
            saved != null -> if (saved.name.trim().lowercase() in HOME_NAMES) PlaceKind.HOME else PlaceKind.NONE // saved places have their own reminders
            else -> PlaceKinds.classify(overpass.tagsNear(here.latitude, here.longitude))
        }
        if (kind == PlaceKind.NONE) return
        visits.set(Visit(kind, here.latitude, here.longitude, now, holding = false))
        places.watchVisit(here.latitude, here.longitude)
        act(kind, now)
    }

    internal suspend fun act(kind: PlaceKind, now: Long = System.currentTimeMillis()) {
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        when (kind) {
            PlaceKind.HOLD -> {
                if (kind.name in settings.current().placeOffKinds) return
                val v = visits.get()
                v?.let { visits.set(it.copy(holding = true)) }
                dnd.enable(REASON)
                // Never silent for more than 3 h, even if leaving is never detected (calls must get through).
                val startedAt = v?.startedAt ?: now
                scheduler.setAt(startedAt + ReminderEngine.PLACE_HOLD_MS, capIntent(startedAt)!!)
                val undo = PendingIntent.getBroadcast(
                    ctx, UNDO_REQUEST, Intent(ctx, PlaceActionReceiver::class.java).setAction(PlaceActionReceiver.ACTION_UNDO), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                notifier.place(Channels.QUIET, ctx.getString(R.string.place_hold), ctx.getString(R.string.place_undo), undo, notifier.openAppIntent(904, route = "home"))
            }
            PlaceKind.STUDY -> {
                val t = Matrix.rankForNow(tasks.openTasks().map { it.task }, today).firstOrNull() ?: return
                val start = notifier.openAppIntent(905, route = "focus/${t.id}")
                notifier.place(Channels.BRIEFING, ctx.getString(R.string.place_study, t.title), ctx.getString(R.string.start_focus), start, start)
            }
            PlaceKind.STORE, PlaceKind.PHARMACY, PlaceKind.SUPERMARKET ->
                db.tasks().openTasks().flatMap { it.cues }
                    .filter { c -> c.placeType?.let { PlaceKinds.matches(it, kind) } == true && now - (c.firedAt ?: 0) > PlaceManager.COOLDOWN_MS }
                    .forEach { reminders.fire(it.id, now) }
            PlaceKind.HOME -> {
                val day = today.toLocalDate()
                if (today.hour < 17 || visits.homeShownDay == day.toEpochDay()) return
                val left = tasks.openTasks().map { it.task }.filter { it.dueDate == day }
                if (left.isEmpty()) return
                visits.homeShownDay = day.toEpochDay()
                notifier.place(Channels.BRIEFING, ctx.getString(R.string.place_home, left.take(3).joinToString(", ") { it.title }), null, null, notifier.openAppIntent(906, route = "home"))
            }
            PlaceKind.NONE -> Unit
        }
    }

    suspend fun left(now: Long = System.currentTimeMillis()) {
        val v = visits.get() ?: return
        visits.set(null)
        capIntent(v.startedAt, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        places.unwatchVisit()
        if (v.holding) { dnd.restore(REASON); notifier.cancelPlace() }
        reminders.releaseHeld(now) // the focus rule holds again whatever a running focus session still covers
    }

    fun holding(): Boolean = visits.holding()

    /** The 3 h cap of the visit that started at [startedAt]: end it. A visit that already ended (or a newer one) is left alone. */
    suspend fun capReached(startedAt: Long, now: Long = System.currentTimeMillis()) {
        if (visits.get()?.startedAt != startedAt) return
        left(now)
    }

    /** Alarms do not survive a restart: end a quiet-place visit past its cap, or arm the cap again. */
    suspend fun restoreAfterRestart(now: Long = System.currentTimeMillis()) {
        val v = visits.get() ?: return
        if (v.kind != PlaceKind.HOLD) return
        val cap = v.startedAt + ReminderEngine.PLACE_HOLD_MS
        if (now >= cap) left(now) else scheduler.setAt(cap, capIntent(v.startedAt)!!)
    }

    /** "Undo" on "Went silent here". Twice for the same kind of place, and FRIDAY stops going silent there. */
    suspend fun undo() {
        val v = visits.get() ?: return
        if (!v.holding) return
        visits.set(v.copy(holding = false))
        dnd.restore(REASON)
        notifier.cancelPlace()
        reminders.releaseHeld()
        val s = settings.current()
        val (undos, n) = PlaceKinds.bumpUndo(s.placeUndos, v.kind)
        settings.setPlaceUndos(undos)
        if (n >= 2 && v.kind.name !in s.placeOffKinds) {
            settings.setPlaceOffKinds(s.placeOffKinds + v.kind.name)
            memory.add(ctx.getString(R.string.place_memory_no_silence, ctx.getString(R.string.place_kind_hold)), MemorySource.HABIT)
        }
    }

    companion object {
        private const val TAG = "FRIDAY"
        private const val REASON = "place"
        private const val STILL_MS = 3 * 60_000L
        private const val STILL_REQUEST = 2_000_000_005
        private const val CHECK_REQUEST = 2_000_000_006
        private const val UNDO_REQUEST = 2_000_000_007
        private const val CAP_REQUEST = 2_000_000_008
        private val HOME_NAMES = setOf("home", "rumah")
    }
}

/** Asks OpenStreetMap what is around a point. Sends only the rounded coordinates (about 11 m), nothing about the user. */
class OverpassClient(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun tagsNear(lat: Double, lng: Double): List<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val at = String.format(Locale.US, "%.4f,%.4f", lat, lng)
            val query = "[out:json][timeout:10];(nwr(around:60,$at)[amenity];nwr(around:60,$at)[shop];);out tags 10;"
            val request = Request.Builder().url(URL).header("User-Agent", "FRIDAY-personal-assistant")
                .post(FormBody.Builder().add("data", query).build()).build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) emptyList()
                else Json.parseToJsonElement(r.body.string()).jsonObject["elements"]?.jsonArray.orEmpty().mapNotNull { e ->
                    e.jsonObject["tags"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
                }
            }
        }.getOrElse { emptyList() }
    }

    private companion object {
        const val URL = "https://overpass-api.de/api/interpreter"
    }
}

class StillnessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull { it.activityType == DetectedActivity.STILL } ?: return
        val a = context.container.awareness
        if (last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) a.stillStarted() else a.stillEnded()
    }
}

class VisitCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        launchAsync {
            if (intent.action == ACTION_CAP) c.awareness.capReached(intent.getLongExtra(EXTRA_STARTED, -1))
            else if (c.settings.current().placeAwareness) c.awareness.stillFor3Min()
        }
    }

    companion object {
        const val ACTION_CAP = "com.friday.app.VISIT_CAP"
        const val EXTRA_STARTED = "friday.visit_started"
    }
}

class PlaceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNDO) return
        launchAsync { context.container.awareness.undo() }
    }

    companion object {
        const val ACTION_UNDO = "com.friday.app.PLACE_UNDO"
    }
}
