package org.determine.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import org.determine.app.platform.AppPlatform
import org.determine.app.platform.LocalPlatform

private object Routes {
    const val MAIN = "main"
    const val JUDGE = "judge"
    const val CALIBRATION = "calibration"
    const val SETTINGS = "settings"
}

/** The whole app UI, identical on Android and desktop. */
@Composable
fun DetermineApp(platform: AppPlatform) {
    CompositionLocalProvider(LocalPlatform provides platform) {
        DetermineTheme {
            var stack by rememberSaveable { mutableStateOf(listOf(Routes.MAIN)) }
            val push = { r: String -> stack = stack + r }
            val pop = { if (stack.size > 1) stack = stack.dropLast(1) }
            platform.BackHandler(enabled = stack.size > 1, onBack = pop)
            val start = platform.rememberStartAction()
            when (stack.last()) {
                Routes.MAIN -> MainScreen(
                    onStart = start,
                    onStop = platform::stop,
                    onJudge = { push(Routes.JUDGE) },
                    onCalibrate = { push(Routes.CALIBRATION) },
                    onSettings = { push(Routes.SETTINGS) },
                )
                Routes.JUDGE -> JudgeScreen(onBack = pop)
                Routes.CALIBRATION -> CalibrationScreen(onBack = pop)
                Routes.SETTINGS -> SettingsScreen(onBack = pop)
            }
        }
    }
}
