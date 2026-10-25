package org.determine.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import org.determine.android.DetermineApplication
import org.determine.android.service.DetermineService
import org.determine.app.platform.AppPlatform
import org.determine.app.platform.SettingsStore
import org.determine.app.ui.DetermineApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = AndroidAppPlatform(this)
        setContent { DetermineApp(platform) }
    }
}

/** The shared UI's view of Android: the foreground service, runtime permissions and the system back gesture. */
private class AndroidAppPlatform(private val context: Context) : AppPlatform {
    override val settings: SettingsStore = (context.applicationContext as DetermineApplication).settings
    override val phoneCameraSupported = true

    @Composable
    override fun rememberStartAction(): () -> Unit {
        // The service runs without the notification permission too (its notification is then hidden), so the
        // loop starts whatever the answer is; refusing must never stop the warnings.
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            DetermineService.start(context)
        }
        return {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                DetermineService.start(context)
            }
        }
    }

    override fun stop() = DetermineService.stop(context)

    @Composable
    override fun rememberCameraRequest(onGranted: () -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onGranted()
        }
        return {
            val has = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (has) onGranted() else launcher.launch(Manifest.permission.CAMERA)
        }
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
        androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
