package dev.stan.duolock

import dev.stan.duolock.data.EnergyReading
import dev.stan.duolock.data.GateSnapshot
import dev.stan.duolock.data.SessionState
import dev.stan.duolock.data.Settings
import dev.stan.duolock.duolingo.EnergyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyStatusTest {

    private val now = 1_700_000_000_000L

    private fun snap(
        energy: EnergyReading? = null,
        override: Int? = null,
        observed: Int? = null,
        threshold: Int = 10,
    ) = GateSnapshot(
        Settings(refillMinutesOverride = override, minEnergyForLesson = threshold),
        SessionState(energy = energy, observedRefillMinutesPerUnit = observed),
    )

    @Test
    fun `no reading means unknown everything`() {
        val s = EnergyStatus.of(snap(), now)
        assertTrue(s.noReading)
        assertNull(s.units)
        assertNull(s.minutesUntilLesson)
        assertFalse(s.lowForLesson)
        assertTrue(s.nextLessonSentence().contains("No energy reading"))
    }

    @Test
    fun `fresh reading below threshold is low with a wait`() {
        val s = EnergyStatus.of(snap(EnergyReading(3, now)), now)
        assertEquals(3, s.units)
        assertTrue(s.lowForLesson)
        assertTrue(s.minutesUntilLesson!! > 0)
        assertTrue(s.nextLessonSentence().startsWith("Next lesson in"))
    }

    @Test
    fun `reading at or above threshold is ready now`() {
        val s = EnergyStatus.of(snap(EnergyReading(10, now)), now)
        assertFalse(s.lowForLesson)
        assertEquals(0L, s.minutesUntilLesson)
        assertNull(s.waitText)
        assertTrue(s.nextLessonSentence().contains("enough energy"))
    }

    @Test
    fun `energy regenerates over time up to the cap`() {
        // 5 units read 10 refill periods ago at 58 min/unit
        val s = EnergyStatus.of(snap(EnergyReading(5, now - 10 * 58 * 60_000L)), now)
        assertEquals(15, s.units)
        val capped = EnergyStatus.of(snap(EnergyReading(24, now - 100 * 58 * 60_000L)), now)
        assertEquals(25, capped.units)
    }

    @Test
    fun `refill rate resolves override then observed then default`() {
        assertEquals(58, EnergyStatus.of(snap(), now).refillMinutesPerUnit)
        assertEquals(45, EnergyStatus.of(snap(observed = 45), now).refillMinutesPerUnit)
        assertEquals(90, EnergyStatus.of(snap(override = 90, observed = 45), now).refillMinutesPerUnit)
    }

    @Test
    fun `wait counts partial progress toward the next unit`() {
        // 9 of 10 units, half a refill period elapsed at 60 min/unit
        val s = EnergyStatus.of(snap(EnergyReading(9, now - 30 * 60_000L), override = 60), now)
        assertEquals(30L, s.minutesUntilLesson)
    }
}
