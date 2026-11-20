package com.friday.app.ui.settings

import androidx.activity.result.IntentSenderRequest
import android.Manifest
import android.location.Location
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.friday.app.BuildConfig
import com.friday.app.data.AppSettings
import com.friday.app.ui.components.SwitchRow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.friday.app.R
import com.friday.app.container
import com.friday.app.ui.LocalToaster
import com.friday.app.ui.components.BarIcon
import com.friday.app.ui.components.EmptyState
import com.friday.app.ui.components.FridaySheet
import com.friday.app.ui.components.LargeTitleScreen
import com.friday.app.ui.components.ListRow
import com.friday.app.ui.components.PrimaryButton
import com.friday.app.ui.components.RowDivider
import com.friday.app.ui.components.Section
import com.friday.app.ui.components.TextAction
import com.friday.app.ui.theme.Friday
import kotlinx.coroutines.launch

@Composable
fun PlacesScreen(nav: NavController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val colors = Friday.colors
    val places by c.db.places().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var resume by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }
    val hasLocation = remember(resume) { c.places.hasLocation() }
    val hasBackground = remember(resume) { c.places.hasBackground() }
    var naming by remember { mutableStateOf<Location?>(null) }
    var locating by remember { mutableStateOf(false) }
    var askBackground by remember { mutableStateOf(false) }
    val available by c.places.available.collectAsStateWithLifecycle()
    val fixAccuracy = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        scope.launch { c.places.registerAll() }
    }

    val locate: () -> Unit = {
        locating = true
        scope.launch {
            val here = c.places.here()
            locating = false
            if (here == null) toaster.show(ctx.getString(R.string.places_no_fix)) else naming = here
        }
    }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
    }
    // Place awareness: location, then physical activity (API 29+), then location all the time. Any "no" leaves it off.
    val settings by c.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    var awareDenied by remember { mutableStateOf(false) }
    var wantAware by remember { mutableStateOf(false) }
    lateinit var nextAwareStep: () -> Unit
    val awareLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        resume++
        if (r.values.any { it }) nextAwareStep() else awareDenied = true
    }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) nextAwareStep() else awareDenied = true
    }
    nextAwareStep = {
        when {
            !c.places.hasLocation() -> awareLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED ->
                activity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            !c.places.hasBackground() -> { wantAware = true; askBackground = true }
            else -> { awareDenied = false; scope.launch { c.awareness.enable() } }
        }
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resume++
        scope.launch { c.places.registerAll() }
        if (wantAware) { wantAware = false; if (c.places.hasBackground()) nextAwareStep() else awareDenied = true }
    }
    val skipBackground = {
        askBackground = false
        if (wantAware) { wantAware = false; awareDenied = true }
    }

    LargeTitleScreen(title = stringResource(R.string.places_title), onBack = { nav.popBackStack() }) {
        item(key = "intro") {
            Text(stringResource(R.string.places_intro), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp))
        }
        item(key = "here") {
            Section {
                ListRow(
                    stringResource(R.string.places_here), icon = R.drawable.ic_location_on, iconBackground = colors.accent,
                    value = if (locating) stringResource(R.string.places_locating) else null,
                    onClick = {
                        if (locating) return@ListRow
                        if (hasLocation) locate()
                        else foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                )
            }
        }
        item(key = "aware") {
            Section(footer = stringResource(if (awareDenied) R.string.place_aware_denied else R.string.place_aware_sub), modifier = Modifier.padding(top = 22.dp)) {
                SwitchRow(stringResource(R.string.place_aware), settings.placeAwareness, { on ->
                    awareDenied = false
                    if (on) nextAwareStep() else scope.launch { c.awareness.disable() }
                }, icon = R.drawable.ic_location_on, iconBackground = colors.accent)
                if (BuildConfig.DEBUG) {
                    RowDivider(60.dp)
                    ListRow(stringResource(R.string.place_check_now), icon = R.drawable.ic_location_on, iconBackground = colors.textTertiary, onClick = {
                        scope.launch { c.awareness.stillFor3Min() }
                    })
                }
            }
        }
        if (hasLocation && !hasBackground && places.isNotEmpty()) {
            item(key = "background") {
                Section(footer = stringResource(R.string.places_bg_footer), modifier = Modifier.padding(top = 22.dp)) {
                    ListRow(stringResource(R.string.places_bg), icon = R.drawable.ic_warning, iconBackground = colors.amber, onClick = { askBackground = true })
                }
            }
        }
        if (!available && places.isNotEmpty()) {
            item(key = "accuracy") {
                Section(footer = stringResource(R.string.places_accuracy_footer), modifier = Modifier.padding(top = 22.dp)) {
                    ListRow(stringResource(R.string.places_accuracy), icon = R.drawable.ic_warning, iconBackground = colors.amber, onClick = {
                        scope.launch {
                            val sender = c.places.accuracyFix()
                            if (sender != null) fixAccuracy.launch(IntentSenderRequest.Builder(sender).build()) else c.places.registerAll()
                        }
                    })
                }
            }
        }
        item(key = "list") {
            if (places.isEmpty()) {
                EmptyState(R.drawable.ic_location_on, stringResource(R.string.places_empty_title), stringResource(R.string.places_empty_body))
            } else {
                Section(header = stringResource(R.string.places_saved_header), modifier = Modifier.padding(top = 22.dp)) {
                    places.forEachIndexed { i, p ->
                        if (i > 0) RowDivider(60.dp)
                        ListRow(
                            p.name, subtitle = "${p.radiusM.toInt()} m", icon = R.drawable.ic_location_on, iconBackground = colors.textTertiary, chevron = false,
                            trailing = { BarIcon(R.drawable.ic_close, stringResource(R.string.remove), { scope.launch { c.places.delete(p) } }, tint = colors.textTertiary) },
                        )
                    }
                }
            }
        }
    }

    naming?.let { here ->
        TextDialog(stringResource(R.string.places_name), "", { naming = null }, capitalization = KeyboardCapitalization.Words) { name ->
            naming = null
            if (name.isNotBlank()) scope.launch {
                c.places.save(name, here)
                c.places.registerAll()
                toaster.show(ctx.getString(R.string.places_saved, name))
                if (!c.places.hasBackground()) askBackground = true
            }
        }
    }
    if (askBackground) {
        FridaySheet(onDismiss = skipBackground) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Text(stringResource(R.string.places_bg_title), style = MaterialTheme.typography.headlineSmall, color = colors.text)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.places_bg_body), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                Spacer(Modifier.height(24.dp))
                PrimaryButton(stringResource(R.string.places_bg), {
                    askBackground = false
                    if (Build.VERSION.SDK_INT >= 29) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }, Modifier.fillMaxWidth(), icon = R.drawable.ic_location_on)
                TextAction(stringResource(R.string.not_now), skipBackground, color = colors.textSecondary)
            }
        }
    }
}
