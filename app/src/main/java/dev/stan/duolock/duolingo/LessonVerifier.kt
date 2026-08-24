package dev.stan.duolock.duolingo

import java.time.LocalDate
import java.time.ZoneId

object LessonVerifier {

    /** Primary signal: total XP grew since the snapshot taken when blocking started. */
    fun lessonCompleted(snapshotXp: Long, currentXp: Long): Boolean =
        snapshotXp in 0 until currentXp

    /** For status display: XP earned since local midnight. */
    fun xpToday(gains: List<XpGain>, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): Long {
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
        return gains.filter { it.time in midnight..(now / 1000) }.sumOf { it.xp }
    }
}
