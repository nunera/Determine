package org.determine.app.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.determine.app.platform.SettingsStore

/**
 * One running hazard loop and its settings wiring; owned by the Android foreground service or the desktop app.
 *
 * Frame source and Describe opt-in are fixed per engine session, so a change restarts [EngineHost] through
 * STARTING. Spearcons and the board's haptic cap apply live.
 */
class DetermineSession(
    private val platform: HostPlatform,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val port: Int = org.determine.app.net.BoardAddressBook.PHONE_PORT,
) {
    private var host: EngineHost? = null
    private val jobs = mutableListOf<Job>()
    private var batteryCritical = false

    val running: Boolean get() = host != null

    fun start() {
        if (host != null) return
        startHost()
        jobs += scope.launch { settings.spearcons.collectLatest { DetermineRuntime.setSpearcons(it) } }
        jobs += scope.launch { settings.hapticMax.collectLatest { DetermineRuntime.setHapticMax(it) } }
        jobs += scope.launch {
            combine(settings.frameSource, settings.describeEnabled) { f, d -> f to d }
                .drop(1)
                .collectLatest { restartHost() }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        DetermineRuntime.host = null
        host?.stop()
        host = null
        DetermineRuntime.clear()
    }

    /** Phone battery critical → UNAVAILABLE (Arch §4.2); remembered across restarts. */
    fun setBatteryCritical(critical: Boolean) {
        batteryCritical = critical
        host?.setBatteryCritical(critical)
    }

    private fun startHost() {
        val h = EngineHost(
            platform,
            HostOptions(
                spearcons = settings.spearcons.value,
                frameSource = settings.frameSource.value,
                hapticMax = settings.hapticMax.value,
                describeEnabled = settings.describeEnabled.value,
                describeEndpoint = settings::describeEndpoint,
                port = port,
            ),
        )
        host = h
        DetermineRuntime.host = h
        DetermineRuntime._running.value = true
        h.start()
        if (batteryCritical) h.setBatteryCritical(true)
    }

    private fun restartHost() {
        DetermineRuntime.host = null
        host?.stop()
        host = null
        startHost()
    }
}
