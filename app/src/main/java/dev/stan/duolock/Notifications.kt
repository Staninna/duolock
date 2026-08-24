package dev.stan.duolock

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** The one place that knows how a DuoGate notification is put together. */
object Notifications {

    const val ID_FGS = 1
    const val ID_EVENTS = 2

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    fun foregroundService(
        context: Context,
        text: String = "Blocked apps unlock after a Duolingo lesson.",
    ): Notification = NotificationCompat.Builder(context, DuoGateApp.CHANNEL_MONITOR)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle("DuoGate is watching")
        .setContentText(text)
        .setContentIntent(openAppIntent(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    fun updateForegroundService(context: Context, text: String) {
        manager(context).notify(ID_FGS, foregroundService(context, text))
    }

    fun event(context: Context, title: String, text: String, id: Int = ID_EVENTS) {
        val notif = NotificationCompat.Builder(context, DuoGateApp.CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        manager(context).notify(id, notif)
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
