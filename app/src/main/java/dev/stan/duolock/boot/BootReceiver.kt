package dev.stan.duolock.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.stan.duolock.blocking.AppMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppMonitorService.start(context)
        }
    }
}
