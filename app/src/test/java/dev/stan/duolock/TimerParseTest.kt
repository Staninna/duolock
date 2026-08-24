package dev.stan.duolock

import dev.stan.duolock.blocking.EnergyReaderService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerParseTest {

    @Test
    fun `parses day hour format`() {
        assertEquals(1440L, EnergyReaderService.parseTimerMinutes("1D 0H"))
    }

    @Test
    fun `parses hour minute format`() {
        assertEquals(23 * 60 + 59L, EnergyReaderService.parseTimerMinutes("23H 59M"))
    }

    @Test
    fun `parses minutes only`() {
        assertEquals(45L, EnergyReaderService.parseTimerMinutes("45M"))
    }

    @Test
    fun `rejects text without time tokens`() {
        assertNull(EnergyReaderService.parseTimerMinutes("CHARGING"))
    }

    @Test
    fun `drawer with full timer and progress derives the rate`() {
        // 24h until full with 0/25 -> 57.6 -> 57 min/unit, energy 0
        val obs = EnergyReaderService.parseDrawer("1D 0H", "0 / 25")
        assertEquals(0, obs!!.energy)
        assertEquals(57, obs.minutesPerUnit)
    }

    @Test
    fun `drawer partial progress uses only the missing units`() {
        // 5h for the 20 missing units -> 15 min/unit, energy 5
        val obs = EnergyReaderService.parseDrawer("5H 0M", "5 / 25")
        assertEquals(5, obs!!.energy)
        assertEquals(15, obs.minutesPerUnit)
    }

    @Test
    fun `drawer with nothing missing or junk text is rejected`() {
        assertNull(EnergyReaderService.parseDrawer("1D 0H", "25 / 25"))
        assertNull(EnergyReaderService.parseDrawer("soon", "5 / 25"))
        assertNull(EnergyReaderService.parseDrawer("1D 0H", "full"))
    }
}
