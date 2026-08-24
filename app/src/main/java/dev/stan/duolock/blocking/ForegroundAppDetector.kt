package dev.stan.duolock.blocking

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

class ForegroundAppDetector(context: Context) {

    private val usm =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private var lastForeground: String? = null

    /**
     * Returns the package most recently brought to the foreground.
     * Remembers the last known value between polls so short query windows are fine.
     * Returns null until any event has ever been seen (e.g. permission missing).
     */
    fun currentForegroundPackage(windowMs: Long = 10_000): String? {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - windowMs, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForeground = event.packageName
            }
        }
        return lastForeground
    }
}
