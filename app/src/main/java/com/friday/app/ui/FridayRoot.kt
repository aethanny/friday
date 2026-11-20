package com.friday.app.ui

import com.friday.app.ui.settings.PlacesScreen
import com.friday.app.ui.settings.VoiceSettingsScreen
import com.friday.app.ui.settings.AiSettingsScreen
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.friday.app.R
import com.friday.app.container
import com.friday.app.data.ThemeMode
import com.friday.app.ui.components.Ic
import com.friday.app.ui.components.LocalTabBarHeight
import com.friday.app.ui.components.TextAction
import com.friday.app.ui.screens.AssistantScreen
import com.friday.app.ui.screens.FocusScreen
import com.friday.app.ui.screens.HomeScreen
import com.friday.app.ui.screens.NotesScreen
import com.friday.app.ui.screens.OnboardingScreen
import com.friday.app.ui.screens.TaskEditorScreen
import com.friday.app.ui.screens.TasksScreen
import com.friday.app.ui.settings.SettingsScreen
import com.friday.app.ui.settings.ShieldSettingsScreen
import com.friday.app.ui.theme.Friday
import com.friday.app.ui.theme.FridayTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Short confirmations with an optional Undo, shown above the tab bar. */
class Toaster(private val scope: CoroutineScope, private val host: SnackbarHostState, private val undoLabel: String) {
    fun show(message: String, undo: (suspend () -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val r = host.showSnackbar(message, actionLabel = if (undo != null) undoLabel else null, duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("Toaster not provided") }

private val TABS = listOf("home", "tasks", "assistant")

@Composable
fun FridayRoot(pendingRoute: StateFlow<String?>, consumeRoute: () -> Unit) {
    val c = LocalContext.current.container
    val settings by c.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    val dark = when (s?.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
        )
    }
    FridayTheme(dark) {
        Box(Modifier.fillMaxSize().background(Friday.colors.background)) {
            when {
                s == null -> Unit
                !s.onboarded -> OnboardingScreen()
                else -> AppNav(pendingRoute, consumeRoute)
            }
        }
    }
}

@Composable
private fun AppNav(pendingRoute: StateFlow<String?>, consumeRoute: () -> Unit) {
    val nav = rememberNavController()
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undo = stringResource(R.string.undo)
    val toaster = remember(host) { Toaster(scope, host, undo) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore("?")
    val showTabs = route in TABS
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val reduce = Friday.reduceMotion

    val pending by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        consumeRoute()
        when {
            p.substringBefore("?") in TABS -> nav.navigateTab(p)
            // Coming back from a notification or the shield: one focus screen, directly above Home.
            p.startsWith("focus") -> nav.navigate(p) { popUpTo(nav.graph.startDestinationId); launchSingleTop = true }
            else -> nav.navigate(p) { launchSingleTop = true }
        }
    }

    CompositionLocalProvider(LocalToaster provides toaster, LocalTabBarHeight provides if (showTabs) 56.dp + navInset else 0.dp) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                nav,
                startDestination = "home",
                enterTransition = { if (reduce) EnterTransition.None else push() },
                exitTransition = { if (reduce) ExitTransition.None else pushBehind() },
                popEnterTransition = { if (reduce) EnterTransition.None else popBehind() },
                popExitTransition = { if (reduce) ExitTransition.None else pop() },
            ) {
                tab("home") { HomeScreen(nav) }
                tab("tasks") { TasksScreen(nav) }
                tab("assistant?listen={listen}", listOf(navArgument("listen") { type = NavType.BoolType; defaultValue = false })) {
                    AssistantScreen(nav, listen = it.arguments?.getBoolean("listen") == true)
                }
                composable("task/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                    TaskEditorScreen(nav, it.arguments?.getLong("id") ?: 0L)
                }
                composable("focus") { FocusScreen(nav, taskId = null, minutes = null) }
                composable(
                    "focus/{taskId}?min={min}",
                    listOf(navArgument("taskId") { type = NavType.LongType }, navArgument("min") { type = NavType.IntType; defaultValue = 0 }),
                ) {
                    FocusScreen(nav, taskId = it.arguments?.getLong("taskId"), minutes = it.arguments?.getInt("min")?.takeIf { m -> m > 0 })
                }
                composable("notes") { NotesScreen(nav) }
                composable("settings") { SettingsScreen(nav) }
                composable("settings/shield") { ShieldSettingsScreen(nav) }
                composable("settings/ai") { AiSettingsScreen(nav) }
                composable("settings/voice") { VoiceSettingsScreen(nav) }
                composable("settings/places") { PlacesScreen(nav) }
            }
            AnimatedVisibility(showTabs, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                TabBar(route) { nav.navigateTab(it) }
            }
            SnackbarHost(
                host,
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(bottom = (if (showTabs) 56.dp + navInset + 72.dp else navInset + 16.dp)),
            ) { data ->
                val c = Friday.colors
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (c.isDark) c.fill else c.text)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        data.visuals.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (c.isDark) c.text else c.surface,
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    data.visuals.actionLabel?.let { TextAction(it, { data.performAction() }, color = if (c.isDark) c.accent else Friday.colors.accent.copy(alpha = 1f), bold = true) }
                }
            }
        }
    }
}

fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun androidx.navigation.NavGraphBuilder.tab(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(
    route,
    arguments,
    enterTransition = { if (initialState.isTab()) fadeIn(tween(160)) else popBehind() },
    exitTransition = { if (targetState.isTab()) fadeOut(tween(120)) else pushBehind() },
    popEnterTransition = { if (initialState.isTab()) fadeIn(tween(160)) else popBehind() },
    popExitTransition = { fadeOut(tween(120)) },
) { content(it) }

private fun NavBackStackEntry.isTab() = destination.route?.substringBefore("?") in TABS

private fun AnimatedContentTransitionScope<NavBackStackEntry>.push() =
    slideInHorizontally(tween(320)) { it } + fadeIn(tween(200))
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushBehind() =
    slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(320), targetAlpha = 0.6f)
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popBehind() =
    slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(320), initialAlpha = 0.6f)
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pop() =
    slideOutHorizontally(tween(300)) { it }

@Composable
private fun TabBar(current: String?, onSelect: (String) -> Unit) {
    val c = Friday.colors
    val items = listOf(
        Triple("home", R.string.tab_home, R.drawable.ic_home to R.drawable.ic_home_fill),
        Triple("tasks", R.string.tab_tasks, R.drawable.ic_checklist to R.drawable.ic_checklist_fill),
        Triple("assistant", R.string.tab_assistant, R.drawable.ic_graphic_eq to R.drawable.ic_graphic_eq_fill),
    )
    Column(Modifier.fillMaxWidth().background(c.background)) {
        HorizontalDivider(thickness = 0.5.dp, color = c.separator)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            items.forEach { (route, label, icons) ->
                val isSelected = current == route
                val text = stringResource(label)
                Column(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .semantics { selected = isSelected }
                        .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(route) }
                        .padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Ic(if (isSelected) icons.second else icons.first, tint = if (isSelected) c.accent else c.textSecondary, size = 26.dp, description = null)
                    Spacer(Modifier.height(2.dp))
                    Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.1.sp), color = if (isSelected) c.accent else c.textSecondary)
                }
            }
        }
    }
}
