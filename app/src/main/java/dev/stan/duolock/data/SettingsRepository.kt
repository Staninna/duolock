package dev.stan.duolock.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "duogate")

data class Settings(
    val blockedPackages: Set<String> = emptySet(),
    val sessionMinutes: Int = 30,
    val fallbackLessonMinutes: Int = 5,
    val jwt: String = "",
    val userId: Long = 0L,
    // User override for the refill rate; null means "use the rate observed
    // from Duolingo's energy drawer, or the default".
    val refillMinutesOverride: Int? = null,
    // A lesson costs roughly 1 energy per question; below this many units the
    // user can't realistically finish one, so the gate lets them through.
    val minEnergyForLesson: Int = 10,
    val onboardingDone: Boolean = false,
    // Streak Saver: from this hour until today's lesson is done, every app is
    // blocked except the whitelist (plus phone, SMS, launcher and system UI).
    val streakSaverEnabled: Boolean = false,
    val streakSaverStartHour: Int = 21,
    val streakSaverWhitelist: Set<String> = emptySet(),
    // Debug builds only: whether the Debug tab is shown. Release builds have
    // no debug screen at all, so this flag is meaningless there.
    val showDebugTab: Boolean = true,
    // How chatty the gate is. Defaults match the old hardcoded behavior.
    /** Notification confirming the gate ran when entering a blocked app with time left. */
    val notifyGateOpen: Boolean = true,
    /** "You can reset the clock" reminder at half the allowance. */
    val notifyHalfway: Boolean = true,
    /** A low-energy reading older than this must be re-verified before it buys passes. */
    val staleReadingMinutes: Int = 150,
    /** From this hour, warn once per evening while today's XP is zero. */
    val streakWarnHour: Int = 21,
) {
    val hasAuth: Boolean get() = jwt.isNotBlank() && userId > 0

    companion object {
        // Duolingo publishes no official rate. The in-app energy screen shows a
        // full 25 recharge takes "1D 0H" -> 57.6 min/unit; rates vary per account.
        const val DEFAULT_REFILL_MINUTES_PER_UNIT = 58
    }
}

/** A single observed value of Duolingo's energy meter. */
data class EnergyReading(val units: Int, val atMs: Long)

enum class GrantSource { LESSON, ENERGY, DEBUG }

data class SessionState(
    val remainingAllowanceMs: Long = 0L,
    val grantedAllowanceMs: Long = 0L,
    /** totalXp at the moment blocking started; null = no block pending. */
    val pendingXpSnapshot: Long? = null,
    /** Duolingo foreground time accumulated toward the fallback unlock. */
    val fallbackAccumulatedMs: Long = 0L,
    val reminderSentForSession: Boolean = false,
    val energy: EnergyReading? = null,
    /** Refill rate derived from Duolingo's energy drawer, if ever seen. */
    val observedRefillMinutesPerUnit: Int? = null,
    val grantSource: GrantSource? = null,
)

/** Settings and session state read from the same store version: never torn. */
data class GateSnapshot(
    val settings: Settings = Settings(),
    val session: SessionState = SessionState(),
) {
    /** User override wins over the observed rate, which wins over the default. */
    val refillMinutesPerUnit: Int
        get() = settings.refillMinutesOverride
            ?: session.observedRefillMinutesPerUnit
            ?: Settings.DEFAULT_REFILL_MINUTES_PER_UNIT
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BLOCKED = stringSetPreferencesKey("blocked_packages")
        val SESSION_MIN = intPreferencesKey("session_minutes")
        val FALLBACK_MIN = intPreferencesKey("fallback_lesson_minutes")
        val JWT = stringPreferencesKey("jwt")
        val USER_ID = longPreferencesKey("user_id")
        val REMAINING = longPreferencesKey("remaining_allowance_ms")
        val GRANTED = longPreferencesKey("granted_allowance_ms")
        val XP_SNAPSHOT = longPreferencesKey("pending_xp_snapshot")
        val FALLBACK_ACCUM = longPreferencesKey("fallback_accumulated_ms")
        val REMINDER_SENT = booleanPreferencesKey("reminder_sent")
        val LAST_ENERGY = intPreferencesKey("last_energy")
        val LAST_ENERGY_AT = longPreferencesKey("last_energy_at_ms")
        val REFILL_OVERRIDE = intPreferencesKey("refill_minutes_per_unit")
        val OBSERVED_REFILL = intPreferencesKey("observed_refill_minutes_per_unit")
        val MIN_ENERGY = intPreferencesKey("min_energy_for_lesson")
        val GRANT_SOURCE = stringPreferencesKey("grant_source")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val STREAK_SAVER = booleanPreferencesKey("streak_saver_enabled")
        val STREAK_SAVER_HOUR = intPreferencesKey("streak_saver_start_hour")
        val STREAK_SAVER_ALLOWED = stringSetPreferencesKey("streak_saver_whitelist")
        val SHOW_DEBUG_TAB = booleanPreferencesKey("show_debug_tab")
        val NOTIFY_GATE_OPEN = booleanPreferencesKey("notify_gate_open")
        val NOTIFY_HALFWAY = booleanPreferencesKey("notify_halfway")
        val STALE_READING_MIN = intPreferencesKey("stale_reading_minutes")
        val STREAK_WARN_HOUR = intPreferencesKey("streak_warn_hour")
    }

    val snapshot: Flow<GateSnapshot> = context.dataStore.data.map { p ->
        val defaults = Settings()
        GateSnapshot(
            settings = Settings(
                blockedPackages = p[Keys.BLOCKED] ?: defaults.blockedPackages,
                sessionMinutes = p[Keys.SESSION_MIN] ?: defaults.sessionMinutes,
                fallbackLessonMinutes = p[Keys.FALLBACK_MIN] ?: defaults.fallbackLessonMinutes,
                jwt = p[Keys.JWT] ?: defaults.jwt,
                userId = p[Keys.USER_ID] ?: defaults.userId,
                refillMinutesOverride = p[Keys.REFILL_OVERRIDE],
                minEnergyForLesson = p[Keys.MIN_ENERGY] ?: defaults.minEnergyForLesson,
                onboardingDone = p[Keys.ONBOARDING_DONE] ?: defaults.onboardingDone,
                streakSaverEnabled = p[Keys.STREAK_SAVER] ?: defaults.streakSaverEnabled,
                streakSaverStartHour = p[Keys.STREAK_SAVER_HOUR] ?: defaults.streakSaverStartHour,
                streakSaverWhitelist = p[Keys.STREAK_SAVER_ALLOWED] ?: defaults.streakSaverWhitelist,
                showDebugTab = p[Keys.SHOW_DEBUG_TAB] ?: defaults.showDebugTab,
                notifyGateOpen = p[Keys.NOTIFY_GATE_OPEN] ?: defaults.notifyGateOpen,
                notifyHalfway = p[Keys.NOTIFY_HALFWAY] ?: defaults.notifyHalfway,
                staleReadingMinutes = p[Keys.STALE_READING_MIN] ?: defaults.staleReadingMinutes,
                streakWarnHour = p[Keys.STREAK_WARN_HOUR] ?: defaults.streakWarnHour,
            ),
            session = SessionState(
                remainingAllowanceMs = p[Keys.REMAINING] ?: 0L,
                grantedAllowanceMs = p[Keys.GRANTED] ?: 0L,
                pendingXpSnapshot = p[Keys.XP_SNAPSHOT]?.takeIf { it >= 0 },
                fallbackAccumulatedMs = p[Keys.FALLBACK_ACCUM] ?: 0L,
                reminderSentForSession = p[Keys.REMINDER_SENT] ?: false,
                energy = p[Keys.LAST_ENERGY]?.takeIf { it >= 0 }?.let { units ->
                    EnergyReading(units, p[Keys.LAST_ENERGY_AT] ?: 0L)
                },
                observedRefillMinutesPerUnit = p[Keys.OBSERVED_REFILL],
                grantSource = p[Keys.GRANT_SOURCE]?.let { name ->
                    GrantSource.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                },
            ),
        )
    }

    val settings: Flow<Settings> = snapshot.map { it.settings }
    val sessionState: Flow<SessionState> = snapshot.map { it.session }

    suspend fun currentSnapshot(): GateSnapshot = snapshot.first()

    suspend fun setBlockedPackages(pkgs: Set<String>) =
        context.dataStore.edit { it[Keys.BLOCKED] = pkgs }

    suspend fun setSessionMinutes(min: Int) =
        context.dataStore.edit { it[Keys.SESSION_MIN] = min.coerceIn(1, 240) }

    suspend fun setFallbackLessonMinutes(min: Int) =
        context.dataStore.edit { it[Keys.FALLBACK_MIN] = min.coerceIn(1, 60) }

    suspend fun setAuth(jwt: String, userId: Long) = context.dataStore.edit {
        it[Keys.JWT] = jwt
        it[Keys.USER_ID] = userId
    }

    suspend fun grantAllowance(ms: Long, source: GrantSource) = context.dataStore.edit {
        it[Keys.REMAINING] = ms
        it[Keys.GRANTED] = ms
        it[Keys.XP_SNAPSHOT] = -1L
        it[Keys.FALLBACK_ACCUM] = 0L
        it[Keys.REMINDER_SENT] = false
        it[Keys.GRANT_SOURCE] = source.name.lowercase()
    }

    /**
     * Burn down the allowance by [allowanceMs] of blocked-app foreground time
     * and credit [fallbackMs] of Duolingo foreground time, atomically.
     */
    suspend fun consume(allowanceMs: Long, fallbackMs: Long = 0L) = context.dataStore.edit {
        if (allowanceMs > 0) {
            val cur = it[Keys.REMAINING] ?: 0L
            it[Keys.REMAINING] = (cur - allowanceMs).coerceAtLeast(0L)
        }
        if (fallbackMs > 0) {
            val cur = it[Keys.FALLBACK_ACCUM] ?: 0L
            it[Keys.FALLBACK_ACCUM] = cur + fallbackMs
        }
    }

    /**
     * Store an energy observation, and (when the drawer supplied one) the
     * observed refill rate, in one transaction.
     */
    suspend fun recordEnergy(units: Int, atMs: Long, observedRefillMinutesPerUnit: Int? = null) =
        context.dataStore.edit {
            it[Keys.LAST_ENERGY] = units
            it[Keys.LAST_ENERGY_AT] = atMs
            observedRefillMinutesPerUnit?.let { rate ->
                it[Keys.OBSERVED_REFILL] = rate.coerceIn(1, 720)
            }
        }

    /** Snapshot the XP baseline and start a fresh block, atomically. */
    suspend fun beginBlock(xpSnapshot: Long) = context.dataStore.edit {
        it[Keys.XP_SNAPSHOT] = xpSnapshot
        it[Keys.FALLBACK_ACCUM] = 0L
    }

    /** End any allowance and any pending block, in one transaction. */
    suspend fun lockNow() = context.dataStore.edit {
        it[Keys.REMAINING] = 0L
        it[Keys.GRANTED] = 0L
        it[Keys.REMINDER_SENT] = false
        it[Keys.XP_SNAPSHOT] = -1L
        it[Keys.FALLBACK_ACCUM] = 0L
    }

    /** Allowance ran out; the pending block (if any) stays. */
    suspend fun clearAllowance() = context.dataStore.edit {
        it[Keys.REMAINING] = 0L
        it[Keys.GRANTED] = 0L
        it[Keys.REMINDER_SENT] = false
    }

    suspend fun markReminderSent() =
        context.dataStore.edit { it[Keys.REMINDER_SENT] = true }

    suspend fun setOnboardingDone(done: Boolean) =
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }

    suspend fun setMinEnergyForLesson(units: Int) =
        context.dataStore.edit { it[Keys.MIN_ENERGY] = units.coerceIn(1, 25) }

    suspend fun setStreakSaverEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.STREAK_SAVER] = enabled }

    suspend fun setStreakSaverStartHour(hour: Int) =
        context.dataStore.edit { it[Keys.STREAK_SAVER_HOUR] = hour.coerceIn(0, 23) }

    suspend fun setNotifyGateOpen(on: Boolean) =
        context.dataStore.edit { it[Keys.NOTIFY_GATE_OPEN] = on }

    suspend fun setNotifyHalfway(on: Boolean) =
        context.dataStore.edit { it[Keys.NOTIFY_HALFWAY] = on }

    suspend fun setStaleReadingMinutes(min: Int) =
        context.dataStore.edit { it[Keys.STALE_READING_MIN] = min.coerceIn(15, 1440) }

    suspend fun setStreakWarnHour(hour: Int) =
        context.dataStore.edit { it[Keys.STREAK_WARN_HOUR] = hour.coerceIn(0, 23) }

    suspend fun setShowDebugTab(show: Boolean) =
        context.dataStore.edit { it[Keys.SHOW_DEBUG_TAB] = show }

    suspend fun setStreakSaverWhitelist(pkgs: Set<String>) =
        context.dataStore.edit { it[Keys.STREAK_SAVER_ALLOWED] = pkgs }

    suspend fun setRefillMinutesOverride(min: Int?) = context.dataStore.edit {
        if (min == null) it.remove(Keys.REFILL_OVERRIDE)
        else it[Keys.REFILL_OVERRIDE] = min.coerceIn(1, 720)
    }

    companion object {
        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
