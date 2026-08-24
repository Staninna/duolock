package dev.stan.duolock.blocking

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.stan.duolock.data.SettingsRepository
import dev.stan.duolock.duolingo.EnergyEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Reads the energy counter from Duolingo's own UI whenever it is on screen.
 * Scoped to the com.duolingo package in the service config; it never sees
 * other apps. All observations funnel through one conflated channel into one
 * repository transaction, so concurrent events can't interleave writes.
 */
class EnergyReaderService : AccessibilityService() {

    companion object {
        private const val COUNTER_ID = "com.duolingo:id/energyCounter"
        private const val NUMBER_ID = "com.duolingo:id/countNumber"
        private const val INFINITY_ID = "com.duolingo:id/infinityImage"

        // Fullscreen energy drawer: progress "0 / 25" and time-to-full "1D 0H".
        private const val TIMER_ID = "com.duolingo:id/energyTimerText"
        private const val PROGRESS_TEXT_ID = "com.duolingo:id/energyProgressTextView"
        private const val PROGRESS_TEXT_BASE_ID = "com.duolingo:id/energyProgressTextBase"

        private val PROGRESS_RE = Regex("""(\d+)\s*/\s*(\d+)""")
        private val TIME_TOKEN_RE = Regex("""(\d+)\s*([DHM])""", RegexOption.IGNORE_CASE)

        /** "1D 0H" / "23H 59M" / "45M" -> total minutes, or null. */
        fun parseTimerMinutes(text: String): Long? {
            var minutes = 0L
            var matched = false
            for (m in TIME_TOKEN_RE.findAll(text)) {
                matched = true
                val n = m.groupValues[1].toLong()
                minutes += when (m.groupValues[2].uppercase()) {
                    "D" -> n * 1440
                    "H" -> n * 60
                    else -> n
                }
            }
            return if (matched) minutes else null
        }

        /** What the fullscreen energy drawer told us, if it parsed. */
        data class DrawerObservation(val energy: Int, val minutesPerUnit: Int)

        /**
         * Derive the per-unit refill rate from the drawer's "time until full"
         * timer and its "current / max" progress text.
         */
        fun parseDrawer(timerText: String, progressText: String): DrawerObservation? {
            val totalMinutes = parseTimerMinutes(timerText) ?: return null
            val progress = PROGRESS_RE.find(progressText) ?: return null
            val current = progress.groupValues[1].toIntOrNull() ?: return null
            val max = progress.groupValues[2].toIntOrNull() ?: return null
            val missing = max - current
            if (missing <= 0 || totalMinutes <= 0) return null
            val minutesPerUnit = (totalMinutes.toDouble() / missing).toInt().coerceIn(1, 720)
            return DrawerObservation(current, minutesPerUnit)
        }
    }

    private data class Observation(val energy: Int, val atMs: Long, val minutesPerUnit: Int?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observations = Channel<Observation>(Channel.CONFLATED)
    private var lastStored = Int.MIN_VALUE
    private var lastStoredAt = 0L
    private var lastTunedAt = 0L

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val repo = SettingsRepository.get(this@EnergyReaderService)
            for (obs in observations) {
                repo.recordEnergy(obs.energy, obs.atMs, obs.minutesPerUnit)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        try {
            val now = System.currentTimeMillis()
            val drawer = readDrawer(root, now)
            val counter = readCounterById(root) ?: readHomeTopBar(root)
            val energy = drawer?.energy ?: counter?.takeIf { it in 0..99 } ?: return

            if (drawer == null && energy == lastStored && now - lastStoredAt < 60_000) return
            lastStored = energy
            lastStoredAt = now
            android.util.Log.d("DuoGateEnergy", "stored energy=$energy rate=${drawer?.minutesPerUnit}")
            observations.trySend(Observation(energy, now, drawer?.minutesPerUnit))
        } catch (_: Exception) {
            // never crash the accessibility pipeline
        }
    }

    /** Older, id-based counter (still present on some screens like the drawer). */
    private fun readCounterById(root: AccessibilityNodeInfo): Int? {
        val counter = root.findAccessibilityNodeInfosByViewId(COUNTER_ID)
            ?.firstOrNull() ?: return null
        val unlimited = counter.findAccessibilityNodeInfosByViewId(INFINITY_ID)
            ?.any { it.isVisibleToUser } == true
        if (unlimited) return EnergyEstimator.MAX_ENERGY
        return counter.findAccessibilityNodeInfosByViewId(NUMBER_ID)
            ?.firstOrNull { it.isVisibleToUser }
            ?.text?.toString()?.trim()?.toIntOrNull()
    }

    /**
     * The home top bar is Compose with no view ids, only bare number texts in
     * order: course, streak, gems, energy. The energy counter is the RIGHTMOST
     * small number in the top strip of the screen.
     */
    private fun readHomeTopBar(root: AccessibilityNodeInfo): Int? {
        val rootBounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        if (rootBounds.height() <= 0) return null
        val topStrip = rootBounds.top + rootBounds.height() / 8
        val numbers = mutableListOf<Pair<Int, Int>>() // (right edge, value)
        val bounds = android.graphics.Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25) return
            val t = n.text?.toString()?.trim()
            if (n.isVisibleToUser && !t.isNullOrEmpty() && t.length <= 5 && t.all { it.isDigit() }) {
                n.getBoundsInScreen(bounds)
                if (bounds.bottom < topStrip) numbers.add(bounds.right to t.toInt())
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        // Only trust the strip when it looks like the home toolbar: at least
        // three separate counters (course/streak, gems, energy). A lone number
        // on some other screen never qualifies.
        if (numbers.size < 3) return null
        val energy = numbers.maxBy { it.first }.second
        return if (energy <= 30) energy else null
    }

    /** Fullscreen energy drawer, at most once a minute. */
    private fun readDrawer(root: AccessibilityNodeInfo, now: Long): DrawerObservation? {
        if (now - lastTunedAt < 60_000) return null
        val timerText = root.findAccessibilityNodeInfosByViewId(TIMER_ID)
            ?.firstOrNull { it.isVisibleToUser }?.text?.toString() ?: return null
        val progressText = (
            root.findAccessibilityNodeInfosByViewId(PROGRESS_TEXT_ID)
                ?.firstOrNull { it.isVisibleToUser }?.text?.toString()
                ?: root.findAccessibilityNodeInfosByViewId(PROGRESS_TEXT_BASE_ID)
                    ?.firstOrNull { it.isVisibleToUser }?.text?.toString()
            ) ?: return null
        val obs = parseDrawer(timerText, progressText)
        android.util.Log.d("DuoGateEnergy", "drawer: timer='$timerText' progress='$progressText' -> $obs")
        if (obs != null) lastTunedAt = now
        return obs
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
