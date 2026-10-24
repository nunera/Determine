package org.determine.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.determine.android.settings.UserSettings

class DetermineApplication : Application() {
    lateinit var settings: UserSettings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = UserSettings(this)
        // Low importance: the notification is a status line; failures are announced by sound and
        // vibration from the engine, not by notification alerts.
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "determine-running"
    }
}
