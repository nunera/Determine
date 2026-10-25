package org.determine.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.determine.android.DetermineApplication
import org.determine.android.R
import org.determine.android.power.BatteryCriticalGate
import org.determine.android.runtime.DetermineRuntime
import org.determine.android.runtime.EngineHost
import org.determine.android.runtime.HostOptions
import org.determine.core.FrameSourceKind
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import android.Manifest
import android.content.pm.PackageManager
import org.determine.android.ui.MainActivity
import org.determine.android.ui.describe

/**
 * Foreground service that keeps the hazard loop alive with the screen off (Arch §4): it owns the
 * [EngineHost] (UDP :47000, engine, headset audio sink, 10 ms tick), a partial wake lock so the tick keeps running,
 * a Wi-Fi lock, and the battery watcher that feeds "phone battery critical" to the engine.
 *
 * Type connectedDevice: the service exists to talk to the two wearable boards.
 */
class DetermineService : Service() {
    private var host: EngineHost? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val batteryGate = BatteryCriticalGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (host != null) return START_STICKY

        val app = application as DetermineApplication
        val settings = app.settings
        val phoneCamera = settings.frameSource.value == FrameSourceKind.PHONE_CAMERA
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.state_starting)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    (if (phoneCamera && hasCameraPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
            } else 0,
        )
        acquireLocks()
        startHost()

        // Sticky battery broadcast delivers the current level immediately on registration.
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let(::onBattery)

        scope.launch {
            DetermineRuntime.state.collectLatest { info ->
                info ?: return@collectLatest
                updateNotification(describe(this@DetermineService, info.state))
            }
        }
        scope.launch {
            settings.spearcons.collectLatest { DetermineRuntime.setSpearcons(it) }
        }
        // Frame source and the Describe opt-in are fixed per engine session: a change restarts it (the restart
        // passes through STARTING, so the user hears the state change). The haptic cap is applied live.
        scope.launch {
            combine(settings.frameSource, settings.describeEnabled) { f, d -> f to d }
                .drop(1)
                .collectLatest { restartHost() }
        }
        scope.launch {
            settings.hapticMax.drop(1).collectLatest { DetermineRuntime.setHapticMax(it) }
        }
        // Restarted by the system after a kill: resume the loop, never stay silently off.
        return START_STICKY
    }

    private fun startHost() {
        val s = (application as DetermineApplication).settings
        val h = EngineHost(
            this,
            HostOptions(
                spearcons = s.spearcons.value,
                frameSource = s.frameSource.value,
                describeEnabled = s.describeEnabled.value,
                hapticMax = s.hapticMax.value,
                describeEndpoint = s::describeEndpoint,
            ),
        )
        host = h
        DetermineRuntime.host = h
        DetermineRuntime._running.value = true
        h.start()
        batteryGate.critical.takeIf { it }?.let { h.setBatteryCritical(true) }
    }

    private fun restartHost() {
        DetermineRuntime.host = null
        host?.stop()
        host = null
        startHost()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (batteryGate.update(pct, charging)) host?.setBatteryCritical(batteryGate.critical)
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "determine:hazard-loop").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        // The boards normally join this phone's hotspot (no station radio involved); the lock matters when the
        // phone is a client of an external access point instead, keeping its radio out of power save.
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "determine:udp").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DetermineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DetermineApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        try { unregisterReceiver(batteryReceiver) } catch (_: IllegalArgumentException) { }
        DetermineRuntime.host = null
        host?.stop()
        host = null
        DetermineRuntime.clear()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "org.determine.android.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DetermineService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DetermineService::class.java))
        }
    }
}
