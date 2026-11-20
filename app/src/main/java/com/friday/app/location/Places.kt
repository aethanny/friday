package com.friday.app.location

import kotlinx.coroutines.flow.MutableStateFlow
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.api.ApiException
import android.content.IntentSender
import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.app.container
import com.friday.app.data.db.CueAnchor
import com.friday.app.data.db.FridayDatabase
import com.friday.app.data.db.PlaceEntity
import com.friday.app.reminders.launchAsync
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/**
 * Saved places and the geofences behind "when I leave/arrive" reminders. Only places with an open
 * reminder are watched, so nothing is monitored without a reason.
 */
class PlaceManager(private val ctx: Context, private val db: FridayDatabase) {
    private val geofencing by lazy { LocationServices.getGeofencingClient(ctx) }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(ctx) }

    /** False when Google Play services can't watch places, usually because Google Location Accuracy is off. */
    val available = MutableStateFlow(true)

    fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackground() = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // Geofencing fills in the event extras, so this PendingIntent has to be mutable.
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    @SuppressLint("MissingPermission")
    suspend fun here(): Location? {
        if (!hasLocation()) return null
        return runCatching {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).awaitResult()
        }.getOrNull() ?: runCatching { fused.lastLocation.awaitResult() }.getOrNull()
    }

    suspend fun save(name: String, at: Location): Long =
        db.places().insert(PlaceEntity(name = name.trim().take(40), lat = at.latitude, lng = at.longitude, radiusM = maxOf(150f, at.accuracy * 2)))

    /** Deleting a place removes the reminders that point at it (their tasks stay). */
    suspend fun delete(p: PlaceEntity) {
        db.tasks().allCues().filter { it.placeId == p.id }.forEach { db.tasks().deleteCue(it) }
        db.places().delete(p)
        registerAll()
    }

    /** Re-creates FRIDAY's geofences from the open place reminders. Safe to call any time. */
    @SuppressLint("MissingPermission")
    suspend fun registerAll() {
        if (!hasLocation() || !hasBackground()) return
        runCatching { geofencing.removeGeofences(pendingIntent()).awaitResult() }
        val cues = db.tasks().openTasks().flatMap { it.cues }.filter { it.placeId != null }
        val fences = db.places().all().mapNotNull { p ->
            val anchors = cues.filter { it.placeId == p.id }.map { it.anchor }.toSet()
            var types = 0
            if (CueAnchor.ARRIVE in anchors) types = types or Geofence.GEOFENCE_TRANSITION_ENTER
            if (CueAnchor.LEAVE in anchors) types = types or Geofence.GEOFENCE_TRANSITION_EXIT
            if (types == 0) null
            else Geofence.Builder()
                .setRequestId("place-${p.id}")
                .setCircularRegion(p.lat, p.lng, p.radiusM)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(types)
                .build()
        }
        if (fences.isEmpty()) return
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
        runCatching { geofencing.addGeofences(request, pendingIntent()).awaitResult() }
            .onSuccess { available.value = true; Log.i(TAG, "watching ${fences.size} place(s)") } // counts only, never locations
            .onFailure {
                if ((it as? ApiException)?.statusCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) available.value = false
                Log.w(TAG, "could not watch places: ${it.message}")
            }
    }

    /** Google's own "turn on location accuracy" dialog, or null when nothing needs fixing. */
    suspend fun accuracyFix(): IntentSender? = runCatching {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000).build())
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(ctx).checkLocationSettings(request).awaitResult()
        null
    }.getOrElse { (it as? ResolvableApiException)?.resolution?.intentSender }


    companion object {
        private const val REQUEST = 2_000_000_003
        private const val TAG = "FRIDAY"
        /** GPS can wobble at the edge of a place; one reminder per half hour is enough. */
        const val COOLDOWN_MS = 30 * 60 * 1000L
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val anchor = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> CueAnchor.ARRIVE
            Geofence.GEOFENCE_TRANSITION_EXIT -> CueAnchor.LEAVE
            else -> return
        }
        val placeIds = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.removePrefix("place-").toLongOrNull() }.toSet()
        val c = context.container
        launchAsync {
            val now = System.currentTimeMillis()
            c.db.tasks().openTasks().flatMap { it.cues }
                .filter { it.placeId in placeIds && it.anchor == anchor && now - (it.firedAt ?: 0) > PlaceManager.COOLDOWN_MS }
                .forEach { c.reminders.fire(it.id, now) }
        }
    }
}
