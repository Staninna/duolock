package dev.stan.duolock.duolingo

object EnergyEstimator {

    const val MAX_ENERGY = 25

    /**
     * Estimated current energy from the last seen value plus regeneration.
     * Returns null when there has never been a reading.
     */
    fun estimate(lastEnergy: Int, lastSeenAtMs: Long, nowMs: Long, minutesPerUnit: Int): Int? {
        if (lastEnergy < 0 || lastSeenAtMs <= 0) return null
        val elapsedMin = (nowMs - lastSeenAtMs) / 60_000
        val regenerated = (elapsedMin / minutesPerUnit.coerceAtLeast(1)).toInt()
        return (lastEnergy + regenerated).coerceAtMost(MAX_ENERGY)
    }

    /** Minutes until the next unit regenerates, given the same inputs. */
    fun minutesUntilNextUnit(lastSeenAtMs: Long, nowMs: Long, minutesPerUnit: Int): Long {
        val perUnit = minutesPerUnit.coerceAtLeast(1)
        val elapsedMin = (nowMs - lastSeenAtMs) / 60_000
        return perUnit - (elapsedMin % perUnit)
    }

    /**
     * Minutes until energy has regenerated up to [targetUnits], counting the
     * partial progress already made toward the next unit. 0 when already there;
     * null with no reading.
     */
    fun minutesUntilEnergy(
        targetUnits: Int, lastEnergy: Int, lastSeenAtMs: Long, nowMs: Long, minutesPerUnit: Int,
    ): Long? {
        val current = estimate(lastEnergy, lastSeenAtMs, nowMs, minutesPerUnit) ?: return null
        if (current >= targetUnits) return 0
        val unitsMissing = targetUnits - current
        return minutesUntilNextUnit(lastSeenAtMs, nowMs, minutesPerUnit) +
            (unitsMissing - 1).toLong() * minutesPerUnit.coerceAtLeast(1)
    }

    /**
     * Human wait time: rounded UP to the next half hour ("1½ hours"), because a
     * fake-precise "454 min" helps nobody. Exception: late evening (22:00 on),
     * when the streak deadline is real, it stays exact minutes.
     */
    fun formatWait(minutes: Long, hour: Int): String {
        if (hour >= 22) return "$minutes min (midnight is coming)"
        if (minutes < 25) return "$minutes min"
        val halves = (minutes + 29) / 30
        return when {
            halves == 1L -> "half an hour"
            halves == 2L -> "an hour"
            halves % 2 == 0L -> "${halves / 2} hours"
            else -> "${halves / 2}½ hours"
        }
    }
}
