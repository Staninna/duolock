package dev.stan.duolock

import dev.stan.duolock.duolingo.EnergyEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyEstimatorTest {

    @Test
    fun `no reading yields null`() {
        assertNull(EnergyEstimator.estimate(-1, 0, 1000, 45))
    }

    @Test
    fun `fresh zero stays zero`() {
        assertEquals(0, EnergyEstimator.estimate(0, 1_000_000, 1_000_000, 45))
    }

    @Test
    fun `regenerates one unit per interval`() {
        val start = 1_000L
        val now = start + 46 * 60_000L
        assertEquals(1, EnergyEstimator.estimate(0, start, now, 45))
    }

    @Test
    fun `caps at max`() {
        val start = 1_000L
        val now = start + 10_000L * 60_000L
        assertEquals(EnergyEstimator.MAX_ENERGY, EnergyEstimator.estimate(20, start, now, 45))
    }

    @Test
    fun `minutes until target energy counts partial progress`() {
        val start = 1_000L
        // 10 min after a reading of 2, rate 58: next unit in 48 min,
        // then 7 more full units to reach 10 -> 48 + 7*58 = 454.
        val now = start + 10 * 60_000L
        assertEquals(454L, EnergyEstimator.minutesUntilEnergy(10, 2, start, now, 58))
    }

    @Test
    fun `minutes until target is zero when already there`() {
        assertEquals(0L, EnergyEstimator.minutesUntilEnergy(10, 12, 1_000L, 2_000L, 58))
    }

    @Test
    fun `minutes until next unit counts down`() {
        val start = 1_000L
        assertEquals(35, EnergyEstimator.minutesUntilNextUnit(start, start + 10 * 60_000L, 45))
    }
}
