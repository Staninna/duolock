package dev.stan.duolock.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.stan.duolock.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug hook: `adb shell am broadcast -n dev.stan.duolock/.blocking.ResetReceiver` clears the session. */
class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            SettingsRepository.get(context).lockNow()
            pending.finish()
        }
    }
}
