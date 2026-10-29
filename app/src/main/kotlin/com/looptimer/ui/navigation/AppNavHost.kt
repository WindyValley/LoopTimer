package com.looptimer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.looptimer.data.PresetsRepository
import com.looptimer.domain.TimerState
import com.looptimer.service.TimerService
import com.looptimer.ui.home.HomeScreen
import com.looptimer.ui.home.TimerConnection
import com.looptimer.ui.presets.PresetEditScreen
import com.looptimer.ui.presets.PresetListScreen
import com.looptimer.ui.theme.LoopTimerTheme
import kotlinx.coroutines.delay

/** Cadence for redrawing the on-screen seconds; only runs while the UI is visible. */
private const val UI_REFRESH_INTERVAL_MS = 500L

/** Route constants and the argument that selects which preset the editor works on. */
private object Route {
    const val HOME = "home"
    const val PRESETS = "presets"
    const val PRESET_EDIT = "preset_edit"
}

private const val ARG_PRESET_ID = "presetId"
private const val ARG_NEW = "new"

/**
 * The whole navigation graph of the single-activity app.
 *
 * Three destinations: the timer home, the preset list and the preset editor. The editor
 * receives the preset id (or [ARG_NEW]) as a route argument instead of an Intent extra. The
 * theme choice lives here so every destination shares one source, rather than each former
 * activity reading and wrapping the theme on its own.
 */
@Composable
fun AppNavHost(connection: TimerConnection, presets: PresetsRepository) {
    val navController = rememberNavController()
    // The repository flow is the single source of the theme choice for every destination.
    val darkTheme by presets.darkTheme.collectAsState()

    LoopTimerTheme(darkTheme = darkTheme) {
        NavHost(navController = navController, startDestination = Route.HOME) {
            composable(Route.HOME) {
                HomeRoute(
                    connection = connection,
                    presets = presets,
                    onToggleTheme = { presets.setDarkTheme(!darkTheme) },
                    onOpenPresets = { navController.navigate(Route.PRESETS) }
                )
            }

            composable(Route.PRESETS) {
                val list by presets.presets.collectAsState()
                val selectedId by presets.selectedPresetId.collectAsState()
                PresetListScreen(
                    presets = list,
                    selectedId = selectedId,
                    // Selecting a preset returns to the timer, as the old activity did.
                    onSelect = { preset ->
                        presets.selectPreset(preset)
                        navController.popBackStack()
                    },
                    onEdit = { preset ->
                        navController.navigate("${Route.PRESET_EDIT}/${preset.id}")
                    },
                    onDelete = { presets.deletePreset(it.id) },
                    onCreate = { navController.navigate("${Route.PRESET_EDIT}/$ARG_NEW") },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "${Route.PRESET_EDIT}/{$ARG_PRESET_ID}",
                arguments = listOf(
                    navArgument(ARG_PRESET_ID) {
                        type = NavType.StringType
                        defaultValue = ARG_NEW
                    }
                )
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString(ARG_PRESET_ID)
                val editing = remember(id) {
                    id?.takeIf { it != ARG_NEW }?.let(presets::getPreset)
                }
                PresetEditScreen(
                    editing = editing,
                    onSave = { name, config ->
                        if (editing != null) {
                            presets.updatePreset(editing.copy(name = name, config = config))
                        } else {
                            presets.addPreset(name, config)
                        }
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() }
                )
            }
        }
    }
}

/** Wires the home screen to the service-bound controller and the configuration repository. */
@Composable
private fun HomeRoute(
    connection: TimerConnection,
    presets: PresetsRepository,
    onToggleTheme: () -> Unit,
    onOpenPresets: () -> Unit
) {
    val context = LocalContext.current
    val controller by connection.controller.collectAsState()
    val config by presets.activeConfig.collectAsState()

    // The repository is the source of truth for configuration, so selecting a preset in
    // another destination is picked up here without polling a timestamp on resume.
    LaunchedEffect(controller, config) {
        controller?.updateConfig(config)
    }

    val timer = controller?.state?.collectAsState()?.value ?: TimerState.initial(config)
    val ringing = controller?.alarmRinging?.collectAsState()?.value ?: false

    // The service does no per-second work; this drives the on-screen seconds and exists only
    // while the screen is composed, so nothing ticks once the UI is gone.
    LaunchedEffect(controller, timer.isRunning) {
        if (timer.isRunning) {
            while (true) {
                delay(UI_REFRESH_INTERVAL_MS)
                controller?.refresh()
            }
        }
    }

    HomeScreen(
        config = config,
        timer = timer,
        alarmRinging = ringing,
        onToggleTheme = onToggleTheme,
        onConfigChange = presets::saveCurrentConfig,
        onToggleRun = { TimerService.send(context, TimerService.ACTION_TOGGLE) },
        onReset = { TimerService.send(context, TimerService.ACTION_RESET) },
        onSkip = { TimerService.send(context, TimerService.ACTION_SKIP) },
        onStopAlarm = { TimerService.send(context, TimerService.ACTION_STOP_ALARM) },
        onOpenPresets = onOpenPresets
    )
}
