package dev.stan.duolock

import dev.stan.duolock.duolingo.LessonVerifier
import dev.stan.duolock.duolingo.XpGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LessonVerifierTest {

    @Test
    fun `xp increase means lesson done`() {
        assertTrue(LessonVerifier.lessonCompleted(100, 110))
    }

    @Test
    fun `no increase means not done`() {
        assertFalse(LessonVerifier.lessonCompleted(100, 100))
    }

    @Test
    fun `negative snapshot means no pending block`() {
        assertFalse(LessonVerifier.lessonCompleted(-1, 500))
    }

    @Test
    fun `xpToday sums only today's gains`() {
        val zone = ZoneId.of("UTC")
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
        val now = (midnight + 3600) * 1000
        val gains = listOf(
            XpGain(xp = 10, time = midnight + 100),
            XpGain(xp = 20, time = midnight + 200),
            XpGain(xp = 99, time = midnight - 100), // yesterday
        )
        assertEquals(30, LessonVerifier.xpToday(gains, zone, now))
    }
}
