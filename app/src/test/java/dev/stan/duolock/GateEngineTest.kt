package dev.stan.duolock

import dev.stan.duolock.blocking.GateEngine
import dev.stan.duolock.blocking.GateEngine.Effect
import dev.stan.duolock.data.EnergyReading
import dev.stan.duolock.data.GateSnapshot
import dev.stan.duolock.data.GrantSource
import dev.stan.duolock.data.SessionState
import dev.stan.duolock.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateEngineTest {

    private val now = 1_700_000_000_000L
    private val blocked = "com.example.scroll"

    private fun settings(vararg pkgs: String = arrayOf(blocked)) = Settings(
        blockedPackages = pkgs.toSet(),
        jwt = "token",
        userId = 1L,
    )

    private fun snap(
        settings: Settings = settings(),
        session: SessionState = SessionState(),
    ) = GateSnapshot(settings, session)

    /** A reading taken just now, so the estimate equals the raw units. */
    private fun energy(units: Int) = EnergyReading(units, now)

    private fun user(totalXp: Long = 1000L, streak: Int = 5) =
        GateEngine.User(totalXp, emptyList(), streak, fetchedAtMs = now)

    private fun decide(
        snapshot: GateSnapshot,
        user: GateEngine.User? = user(),
        fg: String? = blocked,
        state: GateEngine.TickState = GateEngine.TickState(),
        hour: Int = 12,
    ) = GateEngine.decide(
        snapshot = snapshot,
        user = user,
        foreground = fg,
        now = now,
        hour = hour,
        dayOfYear = 100,
        state = state,
        systemAllowedPackages = setOf("dev.stan.duolock", "com.duolingo", "com.android.systemui"),
        ownPackage = "dev.stan.duolock",
    )

    private inline fun <reified T : Effect> List<Effect>.only(): T =
        filterIsInstance<T>().single()

    @Test
    fun `nothing configured means nothing happens`() {
        val d = decide(snap(settings = Settings()), fg = blocked)
        assertTrue(d.effects.isEmpty())
    }

    @Test
    fun `blocked app with no allowance and no pending block starts a block`() {
        val d = decide(snap(session = SessionState(energy = energy(20))))
        assertEquals(1000L, d.effects.only<Effect.BeginBlock>().xpSnapshot)
        assertEquals(blocked, d.effects.only<Effect.LaunchBlocker>().pkg)
        assertEquals(blocked, d.state.lastBlockedPkg)
    }

    @Test
    fun `without user data the block still starts with a zero snapshot`() {
        val d = decide(snap(session = SessionState(energy = energy(20))), user = null)
        assertEquals(0L, d.effects.only<Effect.BeginBlock>().xpSnapshot)
    }

    @Test
    fun `low energy never blocks - grants an energy pass instead`() {
        val d = decide(snap(session = SessionState(energy = energy(3))))
        val grant = d.effects.only<Effect.Grant>()
        assertEquals(GrantSource.ENERGY, grant.source)
        assertTrue(grant.ms > 0)
        assertTrue(d.effects.none { it is Effect.LaunchBlocker })
        // and it says why
        assertTrue(d.effects.only<Effect.Notify>().title.contains("energy"))
    }

    @Test
    fun `energy pass is capped at the session length`() {
        val s = snap(
            settings = settings().copy(sessionMinutes = 30),
            // 0 units, 58 min/unit, threshold 10 -> hours of refill, capped at 30
            session = SessionState(energy = energy(0)),
        )
        val d = decide(s)
        assertEquals(30 * 60_000L, d.effects.only<Effect.Grant>().ms)
    }

    @Test
    fun `stale low reading blocks instead of granting a pass`() {
        // Reading of 3 units taken 3 hours ago: the estimate is still below
        // the threshold, but the age demands re-verification.
        val d = decide(
            snap(session = SessionState(energy = EnergyReading(3, now - 3 * 60 * 60_000L))),
        )
        assertEquals(blocked, d.effects.only<Effect.LaunchBlocker>().pkg)
        assertTrue(d.effects.filterIsInstance<Effect.Grant>().isEmpty())
    }

    @Test
    fun `fresh low reading still grants the pass without a lock screen`() {
        val d = decide(
            snap(session = SessionState(energy = EnergyReading(3, now - 60_000L))),
        )
        d.effects.only<Effect.Grant>()
        assertTrue(d.effects.filterIsInstance<Effect.LaunchBlocker>().isEmpty())
    }

    @Test
    fun `armed streak saver verifies even a recent low reading`() {
        // 21:30 with Streak Saver on: no grace at all — a 10-minute-old low
        // reading (older than the fresh window) must be re-read.
        val s = settings().copy(streakSaverEnabled = true, streakSaverStartHour = 21)
        val d = decide(
            snap(settings = s, session = SessionState(energy = EnergyReading(3, now - 10 * 60_000L))),
            hour = 22,
        )
        assertTrue(d.effects.filterIsInstance<Effect.Grant>().isEmpty())
        d.effects.only<Effect.LaunchBlocker>()
    }

    @Test
    fun `bounce-back from Duolingo needs a fresh reading, not the stale one`() {
        // In Duolingo with a pending block, but the low reading predates the
        // visit by hours: no pass yet — wait for the reader to store.
        val d = decide(
            snap(session = SessionState(
                pendingXpSnapshot = 1000L,
                energy = EnergyReading(3, now - 3 * 60 * 60_000L),
            )),
            fg = "com.duolingo",
        )
        assertTrue(d.effects.filterIsInstance<Effect.Grant>().isEmpty())
    }

    @Test
    fun `energy pass is revoked and the gate blocks once energy recovers`() {
        // A live ENERGY pass, but a fresh reading now shows plenty of energy.
        val d = decide(
            snap(session = SessionState(
                remainingAllowanceMs = 20 * 60_000L,
                grantedAllowanceMs = 30 * 60_000L,
                grantSource = GrantSource.ENERGY,
                energy = energy(25),
            )),
        )
        d.effects.only<Effect.ClearAllowance>()
        assertEquals(blocked, d.effects.only<Effect.LaunchBlocker>().pkg)
    }

    @Test
    fun `lesson allowance survives an energy recovery`() {
        // A pass earned by a real lesson must NOT be revoked by high energy.
        val d = decide(
            snap(session = SessionState(
                remainingAllowanceMs = 20 * 60_000L,
                grantedAllowanceMs = 30 * 60_000L,
                grantSource = GrantSource.LESSON,
                energy = energy(25),
            )),
        )
        assertTrue(d.effects.filterIsInstance<Effect.ClearAllowance>().isEmpty())
        assertTrue(d.effects.filterIsInstance<Effect.LaunchBlocker>().isEmpty())
    }

    @Test
    fun `energy pass with no fresh reading keeps running`() {
        // Still low: the pass stays.
        val d = decide(
            snap(session = SessionState(
                remainingAllowanceMs = 20 * 60_000L,
                grantedAllowanceMs = 30 * 60_000L,
                grantSource = GrantSource.ENERGY,
                energy = energy(4),
            )),
        )
        assertTrue(d.effects.filterIsInstance<Effect.ClearAllowance>().isEmpty())
        assertTrue(d.effects.filterIsInstance<Effect.LaunchBlocker>().isEmpty())
    }

    @Test
    fun `allowance burns only while a blocked app is on screen`() {
        val session = SessionState(remainingAllowanceMs = 60_000L, grantedAllowanceMs = 60_000L)
        val inBlocked = decide(snap(session = session))
        assertEquals(GateEngine.POLL_MS, inBlocked.state.unflushedConsumedMs)

        val elsewhere = decide(snap(session = session), fg = "com.other.app")
        assertEquals(0L, elsewhere.state.unflushedConsumedMs)
    }

    @Test
    fun `consumed time flushes after ten seconds`() {
        val session = SessionState(remainingAllowanceMs = 60_000L, grantedAllowanceMs = 60_000L)
        val st = GateEngine.TickState(lastForegroundPkg = blocked, unflushedConsumedMs = 9_000L)
        val d = decide(snap(session = session), state = st)
        assertEquals(10_000L, d.effects.only<Effect.Flush>().consumedMs)
        assertEquals(0L, d.state.unflushedConsumedMs)
    }

    @Test
    fun `stale unflushed time is dropped when no grant is live`() {
        // e.g. after "Lock now": the ledger must not survive into the next pass
        val st = GateEngine.TickState(unflushedConsumedMs = 8_000L)
        val d = decide(snap(session = SessionState(energy = energy(20))), state = st)
        assertEquals(0L, d.state.unflushedConsumedMs)
    }

    @Test
    fun `allowance expiry with enough energy clears the session`() {
        val session = SessionState(
            remainingAllowanceMs = 0L, grantedAllowanceMs = 60_000L, energy = energy(20),
        )
        val d = decide(snap(session = session))
        assertTrue(d.effects.any { it is Effect.ClearAllowance })
        assertTrue(d.effects.filterIsInstance<Effect.Notify>().any { it.title == "Time's up" })
    }

    @Test
    fun `allowance expiry with low energy extends silently instead of locking`() {
        val session = SessionState(
            remainingAllowanceMs = 0L, grantedAllowanceMs = 60_000L, energy = energy(3),
        )
        val d = decide(snap(session = session))
        val grant = d.effects.only<Effect.Grant>()
        assertEquals(GrantSource.ENERGY, grant.source)
        // silent: no notification, no lock
        assertTrue(d.effects.filterIsInstance<Effect.Notify>().none { it.title.contains("energy") })
        assertTrue(d.effects.none { it is Effect.LaunchBlocker })
    }

    @Test
    fun `entering a blocked app with allowance confirms the gate ran`() {
        val session = SessionState(remainingAllowanceMs = 60_000L, grantedAllowanceMs = 60_000L)
        val d = decide(
            snap(session = session),
            state = GateEngine.TickState(lastForegroundPkg = "com.other.app"),
        )
        assertTrue(d.effects.filterIsInstance<Effect.Notify>().any { it.title == "Gate is open" })
    }

    @Test
    fun `halfway reminder fires once when energy suffices`() {
        val session = SessionState(
            remainingAllowanceMs = 29_000L, grantedAllowanceMs = 60_000L, energy = energy(20),
        )
        val d = decide(snap(session = session), state = GateEngine.TickState(lastForegroundPkg = blocked))
        assertTrue(d.effects.any { it is Effect.MarkReminderSent })

        val alreadySent = decide(
            snap(session = session.copy(reminderSentForSession = true)),
            state = GateEngine.TickState(lastForegroundPkg = blocked),
        )
        assertFalse(alreadySent.effects.any { it is Effect.MarkReminderSent })
    }

    @Test
    fun `xp growth beyond the snapshot grants a session`() {
        val session = SessionState(pendingXpSnapshot = 900L, energy = energy(20))
        val d = decide(snap(session = session), user = user(totalXp = 950L), fg = "com.other.app")
        val grant = d.effects.only<Effect.Grant>()
        assertEquals(GrantSource.LESSON, grant.source)
        assertEquals(30 * 60_000L, grant.ms)
        assertTrue(d.effects.filterIsInstance<Effect.Notify>().any { it.title == "Unlocked" })
    }

    @Test
    fun `unchanged xp does not unlock`() {
        val session = SessionState(pendingXpSnapshot = 900L, energy = energy(20))
        val d = decide(snap(session = session), user = user(totalXp = 900L), fg = "com.other.app")
        assertTrue(d.effects.none { it is Effect.Grant })
    }

    @Test
    fun `enough duolingo foreground time unlocks without the api`() {
        val session = SessionState(
            pendingXpSnapshot = 900L,
            fallbackAccumulatedMs = 5 * 60_000L,
            energy = energy(20),
        )
        val d = decide(snap(session = session), user = null, fg = "com.duolingo")
        assertEquals(GrantSource.LESSON, d.effects.only<Effect.Grant>().source)
    }

    @Test
    fun `duolingo foreground time accrues and flushes during a pending block`() {
        val session = SessionState(pendingXpSnapshot = 900L, energy = energy(20))
        val st = GateEngine.TickState(unflushedFallbackMs = 9_000L)
        val d = decide(snap(session = session), user = user(totalXp = 900L), fg = "com.duolingo", state = st)
        assertEquals(10_000L, d.effects.only<Effect.Flush>().fallbackMs)
        assertEquals(0L, d.state.unflushedFallbackMs)
    }

    @Test
    fun `low energy seen inside duolingo bounces back to the blocked app`() {
        val session = SessionState(pendingXpSnapshot = 900L, energy = energy(3))
        val st = GateEngine.TickState(lastBlockedPkg = blocked)
        val d = decide(snap(session = session), fg = "com.duolingo", state = st)
        assertEquals(GrantSource.ENERGY, d.effects.only<Effect.Grant>().source)
        assertEquals(blocked, d.effects.only<Effect.LaunchApp>().pkg)
    }

    @Test
    fun `streak saver locks everything but the whitelist`() {
        val s = settings().copy(
            streakSaverEnabled = true,
            streakSaverStartHour = 21,
            streakSaverWhitelist = setOf("com.allowed.app"),
        )
        val zeroXpUser = user(streak = 5)
        val session = SessionState(energy = energy(20))

        val lockedApp = decide(snap(s, session), user = zeroXpUser, fg = "com.random.app", hour = 22)
        assertTrue(lockedApp.effects.any { it is Effect.LaunchBlocker })

        val allowed = decide(snap(s, session), user = zeroXpUser, fg = "com.allowed.app", hour = 22)
        assertTrue(allowed.effects.none { it is Effect.LaunchBlocker })

        val beforeHour = decide(snap(s, session), user = zeroXpUser, fg = "com.random.app", hour = 20)
        assertTrue(beforeHour.effects.none { it is Effect.LaunchBlocker })
    }

    @Test
    fun `streak saver stays off without api data`() {
        val s = settings().copy(streakSaverEnabled = true, streakSaverStartHour = 21)
        val d = decide(
            snap(s.copy(blockedPackages = emptySet()), SessionState(energy = energy(20))),
            user = null, fg = "com.random.app", hour = 22,
        )
        assertTrue(d.effects.none { it is Effect.LaunchBlocker })
        assertTrue(d.effects.any { it is Effect.WantFreshUser })
    }

    @Test
    fun `streak saver announces itself once on the rising edge`() {
        val s = settings().copy(streakSaverEnabled = true, streakSaverStartHour = 21)
        val session = SessionState(energy = energy(20))
        val first = decide(snap(s, session), fg = null, hour = 22)
        assertTrue(first.effects.filterIsInstance<Effect.Notify>().any { it.title == "Streak Saver active" })
        assertTrue(first.state.saverWasActive)

        val second = decide(snap(s, session), fg = null, hour = 22, state = first.state)
        assertTrue(second.effects.filterIsInstance<Effect.Notify>().none { it.title == "Streak Saver active" })
    }

    @Test
    fun `evening streak warning fires once per day with fresh data`() {
        val session = SessionState(energy = energy(20))
        val first = decide(snap(session = session), fg = null, hour = 22)
        assertTrue(first.effects.filterIsInstance<Effect.Notify>().any { it.title == "Streak at risk" })
        assertEquals(100, first.state.streakWarnedOnDay)

        val second = decide(snap(session = session), fg = null, hour = 22, state = first.state)
        assertTrue(second.effects.filterIsInstance<Effect.Notify>().none { it.title == "Streak at risk" })
    }

    @Test
    fun `evening streak warning waits for fresh data instead of guessing`() {
        val stale = user().copy(fetchedAtMs = now - 60 * 60_000L)
        val d = decide(snap(session = SessionState(energy = energy(20))), user = stale, fg = null, hour = 22)
        assertTrue(d.effects.any { it is Effect.WantFreshUser })
        assertEquals(-1, d.state.streakWarnedOnDay)
    }

    @Test
    fun `gate-open notification respects its setting`() {
        val session = SessionState(remainingAllowanceMs = 60_000L, grantedAllowanceMs = 60_000L)
        val quiet = decide(
            snap(settings().copy(notifyGateOpen = false), session),
            state = GateEngine.TickState(lastForegroundPkg = "com.other.app"),
        )
        assertTrue(quiet.effects.filterIsInstance<Effect.Notify>().none { it.title == "Gate is open" })
    }

    @Test
    fun `halfway reminder respects its setting`() {
        val session = SessionState(
            remainingAllowanceMs = 29_000L, grantedAllowanceMs = 60_000L, energy = energy(20),
        )
        val quiet = decide(
            snap(settings().copy(notifyHalfway = false), session),
            state = GateEngine.TickState(lastForegroundPkg = blocked),
        )
        assertFalse(quiet.effects.any { it is Effect.MarkReminderSent })
    }

    @Test
    fun `halfway reminder is suppressed while energy is too low for a lesson`() {
        val session = SessionState(
            remainingAllowanceMs = 29_000L, grantedAllowanceMs = 60_000L,
            grantSource = GrantSource.ENERGY, energy = energy(3),
        )
        val d = decide(snap(session = session), state = GateEngine.TickState(lastForegroundPkg = blocked))
        assertFalse(d.effects.any { it is Effect.MarkReminderSent })
    }

    @Test
    fun `no streak warning when a lesson is already done or there is no streak`() {
        val session = SessionState(energy = energy(20))
        val lessonDone = GateEngine.User(
            1000L, listOf(dev.stan.duolock.duolingo.XpGain(xp = 30, time = now / 1000)), 5, now,
        )
        val doneToday = decide(snap(session = session), user = lessonDone, fg = null, hour = 22)
        assertTrue(doneToday.effects.filterIsInstance<Effect.Notify>().none { it.title == "Streak at risk" })
        // warned-state still advances, so tomorrow gets a fresh check
        assertEquals(100, doneToday.state.streakWarnedOnDay)

        val noStreak = decide(snap(session = session), user = user(streak = 0), fg = null, hour = 22)
        assertTrue(noStreak.effects.filterIsInstance<Effect.Notify>().none { it.title == "Streak at risk" })
    }

    @Test
    fun `no streak warning or saver without a token`() {
        val s = Settings(
            blockedPackages = setOf(blocked),
            streakSaverEnabled = true,
            streakSaverStartHour = 21,
        )
        val d = decide(
            snap(s, SessionState(energy = energy(20))), fg = "com.random.app", hour = 22,
        )
        assertTrue(d.effects.none { it is Effect.LaunchBlocker })
        assertTrue(d.effects.filterIsInstance<Effect.Notify>().none { it.title == "Streak at risk" })
        assertTrue(d.effects.none { it is Effect.WantFreshUser })
    }

    @Test
    fun `streak saver never locks system-allowed packages`() {
        val s = settings().copy(streakSaverEnabled = true, streakSaverStartHour = 21)
        val d = decide(
            snap(s, SessionState(energy = energy(20))), fg = "com.android.systemui", hour = 22,
        )
        assertTrue(d.effects.none { it is Effect.LaunchBlocker })
    }

    @Test
    fun `expiring energy pass extension stays silent and sized to the wait`() {
        val session = SessionState(
            remainingAllowanceMs = 0L, grantedAllowanceMs = 60_000L,
            grantSource = GrantSource.ENERGY, energy = energy(9),
        )
        val d = decide(snap(settings().copy(sessionMinutes = 30), session))
        val grant = d.effects.only<Effect.Grant>()
        assertEquals(GrantSource.ENERGY, grant.source)
        // 1 unit missing at 58 min/unit -> 58 min, capped at 30
        assertEquals(30 * 60_000L, grant.ms)
        assertTrue(d.effects.none { it is Effect.Notify })
        assertTrue(d.effects.none { it is Effect.ClearAllowance })
    }

    @Test
    fun `pending block wants fresh xp only from inside duolingo or duogate`() {
        val session = SessionState(pendingXpSnapshot = 900L, energy = energy(20))
        val inDuo = decide(snap(session = session), user = user(totalXp = 900L), fg = "com.duolingo")
        assertTrue(inDuo.effects.any { it is Effect.WantFreshUser })

        val elsewhere = decide(snap(session = session), user = user(totalXp = 900L), fg = "com.other.app")
        assertTrue(elsewhere.effects.none { it is Effect.WantFreshUser })
    }

    @Test
    fun `a second tick inside an already blocked app does not begin a new block`() {
        val session = SessionState(pendingXpSnapshot = 900L, energy = energy(20))
        val d = decide(snap(session = session), user = user(totalXp = 900L))
        assertTrue(d.effects.none { it is Effect.BeginBlock })
        assertEquals(blocked, d.effects.only<Effect.LaunchBlocker>().pkg)
    }

    @Test
    fun `countdown text reflects the energy state`() {
        val noReading = decide(snap(), fg = null)
        assertTrue(noReading.effects.only<Effect.UpdateCountdown>().text.contains("No energy reading"))

        val ready = decide(snap(session = SessionState(energy = energy(20))), fg = null)
        assertTrue(ready.effects.only<Effect.UpdateCountdown>().text.contains("enough energy"))

        val waiting = decide(snap(session = SessionState(energy = energy(3))), fg = null)
        assertTrue(waiting.effects.only<Effect.UpdateCountdown>().text.contains("Next lesson in"))
    }
}
