package dev.stan.duolock.duolingo

/**
 * Debug-only substitute for the Duolingo API user, so Streak Saver and the
 * streak warning can be exercised without touching the real account. The
 * monitor consults this only in debug builds; in release builds nothing sets
 * it and the branch is dead.
 */
object DebugUserOverride {

    enum class Mode {
        /** Use the real API data. */
        OFF,

        /** A 5-day streak with zero XP today: Streak Saver's trigger state. */
        STREAK_AT_RISK,

        /** The same streak with a lesson already done today: Saver stands down. */
        LESSON_DONE,
    }

    @Volatile
    var mode: Mode = Mode.OFF

    /**
     * The synthetic user for the current [mode], stamped fresh at [nowMs] so
     * age checks always pass. Null when the override is off.
     */
    fun user(nowMs: Long): UserResponse? = when (mode) {
        Mode.OFF -> null
        Mode.STREAK_AT_RISK -> UserResponse(
            totalXp = 10_000L, xpGains = emptyList(), streak = 5,
        )
        Mode.LESSON_DONE -> UserResponse(
            totalXp = 10_030L,
            xpGains = listOf(XpGain(xp = 30, time = nowMs / 1000)),
            streak = 5,
        )
    }
}
