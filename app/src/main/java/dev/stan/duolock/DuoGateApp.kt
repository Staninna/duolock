package dev.stan.duolock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class DuoGateApp : Application() {

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_EVENTS = "events"
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Monitoring", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Unlocks & reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
