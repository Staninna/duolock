package dev.stan.duolock.blocking

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.stan.duolock.Notifications
import dev.stan.duolock.data.SettingsRepository
import dev.stan.duolock.duolingo.DuolingoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the gate: once a second it snapshots persisted state, asks the pure
 * [GateEngine] what to do, and executes the returned effects. All Duolingo
 * API traffic happens in a separate refresher coroutine so the tick — and the
 * lock screen — never waits on the network.
 */
class AppMonitorService : LifecycleService() {

    companion object {
        const val DUOLINGO_PKG = "com.duolingo"
        private const val REFRESHER_IDLE_MS = 5_000L
        private const val REFRESH_WANTED_MIN_INTERVAL_MS = 30_000L
        private const val REFRESH_BACKGROUND_INTERVAL_MS = 5 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AppMonitorService::class.java))
        }
    }

    private lateinit var repo: SettingsRepository
    private lateinit var detector: ForegroundAppDetector
    private val duoRepo = DuolingoRepository.get()

    private var loopJob: Job? = null
    private var refresherJob: Job? = null

    private var tickState = GateEngine.TickState()
    private var lastFgsText: String? = null
    private var authErrorNotified = false
    private var watcherDeadSince = 0L
    private var watcherDeathNotified = false
    private var lastWatcherCheckAt = 0L
    @Volatile private var userWanted = false

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository.get(this)
        detector = ForegroundAppDetector(this)
        startForeground(Notifications.ID_FGS, Notifications.foregroundService(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (loopJob == null) {
            // Off the main thread: usage-stats queries and DataStore reads every
            // second would otherwise jank the whole app's UI.
            loopJob = lifecycleScope.launch(Dispatchers.IO) { monitorLoop() }
            refresherJob = lifecycleScope.launch(Dispatchers.IO) { refresherLoop() }
        }
        return START_STICKY
    }

    private suspend fun monitorLoop() {
        while (true) {
            try {
                tick()
            } catch (_: SecurityException) {
                Notifications.event(this, "DuoGate disabled", "Usage access was revoked. Tap to re-grant it.")
                delay(30_000)
            } catch (_: Exception) {
                // keep the loop alive no matter what
            }
            delay(GateEngine.POLL_MS)
        }
    }

    private suspend fun tick() {
        val snapshot = repo.currentSnapshot()
        val user = duoRepo.state.value.user?.let {
            GateEngine.User(it.totalXp, it.xpGains, it.streak, duoRepo.state.value.fetchedAtMs)
        }
        val nowT = java.time.LocalTime.now()
        val decision = GateEngine.decide(
            snapshot = snapshot,
            user = user,
            foreground = detector.currentForegroundPackage(),
            now = System.currentTimeMillis(),
            hour = nowT.hour,
            dayOfYear = java.time.LocalDate.now().dayOfYear,
            state = tickState,
            systemAllowedPackages = systemAllowedPackages(),
            ownPackage = packageName,
        )
        tickState = decision.state
        decision.effects.forEach { execute(it) }
        checkWatcherAlive()
    }

    /**
     * Settings saying "enabled" while the service isn't bound means the energy
     * reader crashed; Android won't rebind it until the user re-toggles it, so
     * every reading from here on would be stale. One notification per death,
     * with a minute of grace for boot / rebind races.
     */
    private fun checkWatcherAlive() {
        val now = System.currentTimeMillis()
        if (now - lastWatcherCheckAt < 30_000) return
        lastWatcherCheckAt = now
        val dead = dev.stan.duolock.permissions.SystemPermissions.isEnergyReaderEnabled(this) &&
            !EnergyReaderService.running
        if (!dead) {
            watcherDeadSince = 0L
            watcherDeathNotified = false
            return
        }
        if (watcherDeadSince == 0L) {
            watcherDeadSince = now
        } else if (!watcherDeathNotified && now - watcherDeadSince > 60_000) {
            watcherDeathNotified = true
            Notifications.event(
                this,
                "Nox fell asleep",
                "DuoGate can't see your Duolingo energy anymore, so the numbers will drift. " +
                    "Turn the energy reader off and on again in the Setup tab to wake it."
            )
        }
    }

    private suspend fun execute(effect: GateEngine.Effect) {
        when (effect) {
            is GateEngine.Effect.UpdateCountdown -> {
                if (effect.text != lastFgsText) {
                    lastFgsText = effect.text
                    Notifications.updateForegroundService(this, effect.text)
                }
            }
            is GateEngine.Effect.Notify ->
                Notifications.event(this, effect.title, effect.text)
            is GateEngine.Effect.Flush ->
                repo.consume(effect.consumedMs, effect.fallbackMs)
            is GateEngine.Effect.Grant -> {
                repo.grantAllowance(effect.ms, effect.source)
                sendBroadcast(Intent(BlockingActivity.ACTION_UNLOCKED).setPackage(packageName))
            }
            GateEngine.Effect.ClearAllowance -> repo.clearAllowance()
            GateEngine.Effect.MarkReminderSent -> repo.markReminderSent()
            is GateEngine.Effect.BeginBlock -> repo.beginBlock(effect.xpSnapshot)
            is GateEngine.Effect.LaunchBlocker -> {
                startActivity(
                    Intent(this, BlockingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(BlockingActivity.EXTRA_BLOCKED_PKG, effect.pkg)
                )
            }
            is GateEngine.Effect.LaunchApp -> {
                packageManager.getLaunchIntentForPackage(effect.pkg)
                    ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            GateEngine.Effect.WantFreshUser -> userWanted = true
        }
    }

    /**
     * The only place Duolingo is fetched from the monitor: a slow cadence in
     * the background, a faster one while the engine is waiting on XP.
     */
    private suspend fun refresherLoop() {
        while (true) {
            try {
                val settings = repo.currentSnapshot().settings
                if (settings.hasAuth) {
                    val minInterval =
                        if (userWanted) REFRESH_WANTED_MIN_INTERVAL_MS
                        else REFRESH_BACKGROUND_INTERVAL_MS
                    userWanted = false
                    val state = duoRepo.refresh(settings.jwt, settings.userId, minInterval)
                    if (state.authExpired && !authErrorNotified) {
                        authErrorNotified = true
                        Notifications.event(
                            this, "Duolingo login expired",
                            "Paste a fresh token in DuoGate settings. Time-based unlock still works."
                        )
                    }
                    if (!state.authExpired) authErrorNotified = false
                }
            } catch (_: Exception) {
                // the refresher, like the tick, never dies
            }
            delay(REFRESHER_IDLE_MS)
        }
    }

    /** Packages that must never be locked: home, phone, SMS, system UI, us, Duolingo. */
    private var allowedCache: Set<String>? = null
    private fun systemAllowedPackages(): Set<String> {
        allowedCache?.let { return it }
        val set = mutableSetOf(packageName, DUOLINGO_PKG, "com.android.systemui", "com.android.settings")
        try {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName?.let { set += it }
        } catch (_: Exception) {}
        try {
            (getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager)
                .defaultDialerPackage?.let { set += it }
        } catch (_: Exception) {}
        try {
            android.provider.Telephony.Sms.getDefaultSmsPackage(this)?.let { set += it }
        } catch (_: Exception) {}
        allowedCache = set
        return set
    }
}
