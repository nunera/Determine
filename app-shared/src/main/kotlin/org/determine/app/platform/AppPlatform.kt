package org.determine.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import org.determine.app.describe.DescribeEndpoint
import org.determine.core.FrameSourceKind

/**
 * What the shared screens need from the app they run in (Android app or desktop test app). Provided once at the
 * root through [LocalPlatform]; the screens contain no platform code.
 */
interface AppPlatform {
    val settings: SettingsStore

    /** False where there is no phone camera (desktop): the Tier 0 option is shown disabled. */
    val phoneCameraSupported: Boolean

    /** Start the hazard loop (Android: foreground service, after the notification-permission prompt). */
    @Composable
    fun rememberStartAction(): () -> Unit

    fun stop()

    /** Returns a function that asks for camera access and calls [onGranted] once it is granted. */
    @Composable
    fun rememberCameraRequest(onGranted: () -> Unit): () -> Unit

    /** System back gesture/button (Android). No-op where the platform has none; screens still have a back button. */
    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)
}

val LocalPlatform = staticCompositionLocalOf<AppPlatform> { error("AppPlatform not provided") }

/**
 * Persisted user choices.
 *  - Spearcons default ON (Arch §8.2: on by default during training, opt-in once the wearer is fluent).
 *  - Describe (Arch §7.3) is an explicit opt-in, default OFF; only used on a user long-press, never read by the
 *    hazard loop. The API key is stored encrypted where the platform offers it.
 *  - Changing the camera source restarts the engine session; the board's haptic cap applies live.
 */
interface SettingsStore {
    val spearcons: StateFlow<Boolean>
    val describeEnabled: StateFlow<Boolean>
    val describeUrl: StateFlow<String>
    val describeKeySet: StateFlow<Boolean>
    val frameSource: StateFlow<FrameSourceKind>
    val hapticMax: StateFlow<Int>

    fun setSpearcons(enabled: Boolean)
    fun setDescribeEnabled(enabled: Boolean)
    fun setDescribeUrl(url: String)
    fun setDescribeKey(key: String)
    fun setFrameSource(source: FrameSourceKind)
    fun setHapticMax(value: Int)

    /** Current endpoint, or null if URL or key is missing. Read on the Describe HTTP thread. */
    fun describeEndpoint(): DescribeEndpoint?
}
