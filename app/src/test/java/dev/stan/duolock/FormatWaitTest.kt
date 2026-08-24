package dev.stan.duolock

import dev.stan.duolock.duolingo.EnergyEstimator
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatWaitTest {

    private fun fmt(minutes: Long, hour: Int = 12) = EnergyEstimator.formatWait(minutes, hour)

    @Test
    fun `short waits stay exact`() {
        assertEquals("5 min", fmt(5))
        assertEquals("24 min", fmt(24))
    }

    @Test
    fun `waits round up to half hours`() {
        assertEquals("half an hour", fmt(25))
        assertEquals("half an hour", fmt(30))
        assertEquals("an hour", fmt(31))
        assertEquals("an hour", fmt(60))
        assertEquals("1½ hours", fmt(61))
        assertEquals("1½ hours", fmt(90))
        assertEquals("2 hours", fmt(91))
        assertEquals("2 hours", fmt(120))
        assertEquals("8 hours", fmt(454))
        assertEquals("7½ hours", fmt(421))
    }

    @Test
    fun `late evening keeps exact minutes because midnight is real`() {
        assertEquals("454 min (midnight is coming)", fmt(454, hour = 22))
        assertEquals("90 min (midnight is coming)", fmt(90, hour = 23))
        // 21:59 still rounds
        assertEquals("1½ hours", fmt(90, hour = 21))
    }
}
