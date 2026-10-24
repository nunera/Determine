package org.determine.android.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.determine.android.service.DetermineService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DetermineTheme { DetermineNav() }
        }
    }
}

private object Routes {
    const val MAIN = "main"
    const val JUDGE = "judge"
    const val CALIBRATION = "calibration"
    const val SETTINGS = "settings"
}

@Composable
private fun DetermineNav() {
    val nav = rememberNavController()
    val context = LocalContext.current
    // The service runs without the notification permission too (its notification is then hidden), so the
    // loop starts whatever the answer is; refusing must never stop the warnings.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        DetermineService.start(context)
    }
    val start = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DetermineService.start(context)
        }
    }
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onStart = start,
                onStop = { DetermineService.stop(context) },
                onJudge = { nav.navigate(Routes.JUDGE) },
                onCalibrate = { nav.navigate(Routes.CALIBRATION) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.JUDGE) { JudgeScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CALIBRATION) { CalibrationScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
