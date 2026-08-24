package dev.stan.duolock.blocking

import dev.stan.duolock.data.GateSnapshot
import dev.stan.duolock.data.GrantSource
import dev.stan.duolock.duolingo.EnergyStatus
import dev.stan.duolock.duolingo.LessonVerifier
import dev.stan.duolock.duolingo.XpGain

/**
 * The whole gate policy as a pure function: persisted state in, effects out.
 * Nothing here touches Android, the network, or the clock — the service feeds
 * it a [GateSnapshot], the latest Duolingo [User] data, the foreground package
 * and the time, and executes the returned [Effect]s.
 */
object GateEngine {

    const val POLL_MS = 1_000L
    private const val FLUSH_EVERY_MS = 10_000L

    /** A reading this fresh counts as verified (the reader stores within seconds). */
    private const val FRESH_READING_MS = 5 * 60_000L
    /** A user fetch older than this doesn't count for the evening streak warning. */
    private const val STREAK_WARN_MAX_AGE_MS = 10 * 60_000L

    /** Duolingo API data as of [fetchedAtMs]; refreshed off the tick. */
    data class User(
        val totalXp: Long,
        val xpGains: List<XpGain>,
        val streak: Int,
        val fetchedAtMs: Long,
    )

    /** Per-process working state; everything durable lives in the snapshot. */
    data class TickState(
        val lastForegroundPkg: String? = null,
        val lastBlockedPkg: String? = null,
        val saverWasActive: Boolean = false,
        val streakWarnedOnDay: Int = -1,
        /** Allowance burned but not yet flushed to the store. */
        val unflushedConsumedMs: Long = 0L,
        /** Duolingo foreground time not yet flushed to the store. */
        val unflushedFallbackMs: Long = 0L,
    )

    sealed interface Effect {
        /** Persistent-notification countdown line (executor dedupes). */
        data class UpdateCountdown(val text: String) : Effect
        data class Notify(val title: String, val text: String) : Effect
        /** Flush unflushed counters to the store in one transaction. */
        data class Flush(val consumedMs: Long, val fallbackMs: Long) : Effect
        /** Grant scroll time and dismiss the lock screen. */
        data class Grant(val ms: Long, val source: GrantSource) : Effect
        /** Allowance ran out with energy available: back to locked. */
        data object ClearAllowance : Effect
        data object MarkReminderSent : Effect
        /** Start a block: persist the XP baseline before anything else. */
        data class BeginBlock(val xpSnapshot: Long) : Effect
        data class LaunchBlocker(val pkg: String) : Effect
        /** Bounce the user back to the app they wanted. */
        data class LaunchApp(val pkg: String) : Effect
        /** Ask the background refresher for fresher Duolingo data. */
        data object WantFreshUser : Effect
    }

    data class Decision(val effects: List<Effect>, val state: TickState)

    fun decide(
        snapshot: GateSnapshot,
        user: User?,
        foreground: String?,
        now: Long,
        hour: Int,
        dayOfYear: Int,
        state: TickState,
        systemAllowedPackages: Set<String>,
        ownPackage: String = "dev.stan.duolock",
        duolingoPackage: String = AppMonitorService.DUOLINGO_PKG,
    ): Decision {
        val tick = Tick(
            snapshot, user, foreground, now, hour, dayOfYear, state,
            systemAllowedPackages, ownPackage, duolingoPackage,
        )
        tick.run()
        return Decision(tick.effects, tick.st)
    }

    /**
     * One tick of the gate, written as a sequence of named policy steps. The
     * order is load-bearing: each step in [run] may finish the tick, and
     * everything after it assumes it didn't.
     */
    private class Tick(
        snapshot: GateSnapshot,
        private val user: User?,
        private val foreground: String?,
        private val now: Long,
        private val hour: Int,
        private val dayOfYear: Int,
        var st: TickState,
        private val systemAllowedPackages: Set<String>,
        private val ownPackage: String,
        private val duolingoPackage: String,
    ) {
        val effects = mutableListOf<Effect>()

        private val settings = snapshot.settings
        private val session = snapshot.session
        private val energy = EnergyStatus.of(snapshot, now)
        private val xpToday = user?.let { LessonVerifier.xpToday(it.xpGains, now = now) }

        private val readingAgeMs = energy.reading?.let { now - it.atMs }
        private val saverArmed = settings.streakSaverEnabled && settings.hasAuth &&
            hour >= settings.streakSaverStartHour

        /**
         * Low on a reading recent enough to trust without a re-check. The
         * limit collapses to zero while Streak Saver is armed: near the streak
         * deadline every low reading must be re-verified.
         */
        private val staleLimit = if (saverArmed) 0L else settings.staleReadingMinutes * 60_000L
        private val lowVerified = energy.lowForLesson &&
            readingAgeMs != null && readingAgeMs <= staleLimit

        /** Low on a reading taken minutes ago: the post-visit verification. */
        private val lowFresh = energy.lowForLesson &&
            readingAgeMs != null && readingAgeMs <= FRESH_READING_MS

        fun run() {
            dropStaleLedger()
            if (settings.blockedPackages.isEmpty() && !settings.streakSaverEnabled) return
            warnStreakAtRisk()
            val saverActive = runStreakSaver()
            updateCountdown(saverActive)

            val fg = foreground ?: return
            val inBlockedApp = if (saverActive) {
                fg !in settings.streakSaverWhitelist && fg !in systemAllowedPackages
            } else {
                fg in settings.blockedPackages
            }
            val enteredBlockedApp = inBlockedApp && fg != st.lastForegroundPkg
            st = st.copy(lastForegroundPkg = fg)

            val passObsolete = revokeObsoleteEnergyPass()
            if (burnAllowance(passObsolete, inBlockedApp, enteredBlockedApp)) return
            if (expireAllowance(passObsolete, inBlockedApp)) return
            if (resolvePendingBlock(fg)) return
            enforceBlock(fg, inBlockedApp)
        }

        /** The write-behind counters only mean anything against a live grant. */
        private fun dropStaleLedger() {
            if (session.grantedAllowanceMs == 0L && st.unflushedConsumedMs != 0L) {
                st = st.copy(unflushedConsumedMs = 0L)
            }
        }

        /** Once per evening: warn if today's XP is still zero and the streak is real. */
        private fun warnStreakAtRisk() {
            if (hour < settings.streakWarnHour || st.streakWarnedOnDay == dayOfYear ||
                !settings.hasAuth
            ) return
            if (user == null || now - user.fetchedAtMs > STREAK_WARN_MAX_AGE_MS) {
                effects += Effect.WantFreshUser
                return
            }
            st = st.copy(streakWarnedOnDay = dayOfYear)
            if (xpToday != null && xpToday <= 0 && user.streak > 0) {
                effects += Effect.Notify(
                    "Streak at risk",
                    if (energy.waitText != null)
                        "No XP yet today, and lesson energy refills in about ${energy.waitText}. " +
                            "Your ${user.streak}-day streak needs a lesson before midnight."
                    else
                        "No XP yet today. One lesson before midnight keeps your ${user.streak}-day streak."
                )
            }
        }

        /**
         * Streak Saver locks everything (minus the whitelist) from the
         * configured hour until midnight, while today's XP is zero. Needs the
         * API: without data it stays off rather than locking with no exit.
         * Returns whether the lockdown is active this tick.
         */
        private fun runStreakSaver(): Boolean {
            if (saverArmed && user == null) effects += Effect.WantFreshUser
            val saverActive = saverArmed && xpToday == 0L && (user?.streak ?: 0) > 0
            if (saverActive && !st.saverWasActive) {
                effects += Effect.Notify(
                    "Streak Saver active",
                    "Everything is locked until today's lesson is done. Calls and your allowed apps still work."
                )
            }
            st = st.copy(saverWasActive = saverActive)
            return saverActive
        }

        private fun updateCountdown(saverActive: Boolean) {
            val base = energy.nextLessonSentence()
            effects += Effect.UpdateCountdown(if (saverActive) "Streak Saver on. $base" else base)
        }

        /**
         * A low-energy free pass is only as good as the reading it was based
         * on: the moment the meter shows enough for a lesson, revoke it and
         * let the normal gate take over. Otherwise a recovered meter keeps
         * scrolling free on a stale pass.
         */
        private fun revokeObsoleteEnergyPass(): Boolean {
            val obsolete = session.grantSource == GrantSource.ENERGY &&
                session.grantedAllowanceMs > 0 && !energy.noReading && !energy.lowForLesson
            if (obsolete) effects += Effect.ClearAllowance
            return obsolete
        }

        private fun remainingMs(passObsolete: Boolean): Long =
            if (passObsolete) 0L
            else session.remainingAllowanceMs - st.unflushedConsumedMs

        /**
         * While an allowance is live: confirm the gate on entry, burn time in
         * blocked apps, flush periodically, remind at half time. Returns true
         * while the allowance stays open, ending the tick.
         */
        private fun burnAllowance(
            passObsolete: Boolean, inBlockedApp: Boolean, enteredBlockedApp: Boolean,
        ): Boolean {
            val remaining = remainingMs(passObsolete)
            if (remaining <= 0) return false
            // Confirm the gate ran on every entry into a blocked app. The
            // allowance clock stays in the background: the only time the user
            // sees is when the next lesson is actually possible.
            if (enteredBlockedApp && settings.notifyGateOpen) {
                effects += Effect.Notify("Gate is open", energy.nextLessonSentence())
            }
            // The allowance only burns while a blocked app is actually on screen.
            if (inBlockedApp) {
                st = st.copy(unflushedConsumedMs = st.unflushedConsumedMs + POLL_MS)
                if (st.unflushedConsumedMs >= FLUSH_EVERY_MS || remaining - POLL_MS <= 0) {
                    effects += Effect.Flush(st.unflushedConsumedMs, st.unflushedFallbackMs)
                    st = st.copy(unflushedConsumedMs = 0L, unflushedFallbackMs = 0L)
                }
                // Don't nag for a lesson that can't be done.
                if (settings.notifyHalfway &&
                    !energy.lowForLesson && !session.reminderSentForSession &&
                    session.grantedAllowanceMs > 0 &&
                    remaining - POLL_MS <= session.grantedAllowanceMs / 2
                ) {
                    effects += Effect.MarkReminderSent
                    effects += Effect.Notify(
                        "Halfway there",
                        "You can do another lesson right now to reset the clock."
                    )
                }
            }
            return remaining - POLL_MS > 0
        }

        /**
         * Allowance used up. If it just ran out, clear it — unless energy is
         * still too low for a lesson: then extend silently instead of locking
         * the app the user is in. Returns true when the tick ends here.
         */
        private fun expireAllowance(passObsolete: Boolean, inBlockedApp: Boolean): Boolean {
            if (passObsolete || session.grantedAllowanceMs == 0L || remainingMs(false) > 0) {
                return false
            }
            st = st.copy(unflushedConsumedMs = 0L)
            if (lowVerified && inBlockedApp) {
                effects += energyPass(silent = true)
                return true
            }
            effects += Effect.ClearAllowance
            effects += Effect.Notify(
                "Time's up",
                if (energy.waitText != null)
                    "Next lesson in about ${energy.waitText}."
                else
                    "You have enough energy. One lesson buys the next round."
            )
            return false
        }

        /**
         * A block is pending: accrue Duolingo foreground time toward the
         * fallback unlock, bounce back on a verified-low meter, and grant the
         * session once a lesson (or the fallback) completes. Returns true when
         * the tick ends here.
         */
        private fun resolvePendingBlock(fg: String): Boolean {
            val pendingSnapshot = session.pendingXpSnapshot ?: return false

            // Fallback: count Duolingo foreground time.
            if (fg == duolingoPackage) {
                st = st.copy(unflushedFallbackMs = st.unflushedFallbackMs + POLL_MS)
                if (st.unflushedFallbackMs >= FLUSH_EVERY_MS) {
                    effects += Effect.Flush(0L, st.unflushedFallbackMs)
                    st = st.copy(unflushedFallbackMs = 0L)
                }
            }

            // Instant bounce-back: the lock sent the user into Duolingo, the
            // reader stored the real meter, and it's below the threshold. Grant
            // the pass and return them to the app they wanted. Only a reading
            // taken minutes ago counts — this is the verification a stale-low
            // reading was sent here for, so the pre-visit value must not do.
            if (fg == duolingoPackage && lowFresh) {
                effects += energyPass(silent = false)
                st.lastBlockedPkg?.let { effects += Effect.LaunchApp(it) }
                return true
            }

            // Be polite: only want fresh XP while the user is in Duolingo or DuoGate.
            if (settings.hasAuth && (fg == duolingoPackage || fg == ownPackage)) {
                effects += Effect.WantFreshUser
            }
            val fallbackDone = session.fallbackAccumulatedMs + st.unflushedFallbackMs >=
                settings.fallbackLessonMinutes * 60_000L
            val xpDone = user != null && LessonVerifier.lessonCompleted(pendingSnapshot, user.totalXp)
            if (!xpDone && !fallbackDone) return false
            st = st.copy(unflushedConsumedMs = 0L, unflushedFallbackMs = 0L)
            effects += Effect.Grant(settings.sessionMinutes * 60_000L, GrantSource.LESSON)
            effects += Effect.Notify(
                "Unlocked",
                if (energy.waitText != null)
                    "Lesson done. Next lesson in about ${energy.waitText}."
                else
                    "Lesson done. You already have energy for another one."
            )
            return true
        }

        /** The end of the line: lock the app on screen, or wave low energy through. */
        private fun enforceBlock(fg: String, inBlockedApp: Boolean) {
            if (!inBlockedApp) return
            // Never block below the lesson threshold: the gate would be a dead
            // end. But only a reading recent enough to trust skips the lock —
            // a stale one falls through to the lock screen, whose "open
            // Duolingo" path re-reads the meter and bounces the user back.
            if (lowVerified || lowFresh) {
                effects += energyPass(silent = false)
                return
            }
            if (session.pendingXpSnapshot == null) {
                effects += Effect.BeginBlock(user?.totalXp ?: 0L)
                st = st.copy(unflushedFallbackMs = 0L)
            }
            st = st.copy(lastBlockedPkg = fg)
            effects += Effect.LaunchBlocker(fg)
        }

        /**
         * A pass sized to how long energy needs to refill up to the lesson
         * threshold, capped at the session length so the meter is re-checked
         * every round. `lowForLesson` guarantees a reading, so the wait is
         * never null.
         */
        private fun energyPass(silent: Boolean): List<Effect> {
            val untilLesson = energy.minutesUntilLesson ?: 0L
            val waitMin = untilLesson.coerceIn(1, settings.sessionMinutes.toLong())
            val grant = Effect.Grant(waitMin * 60_000L, GrantSource.ENERGY)
            if (silent) return listOf(grant)
            return listOf(
                grant,
                Effect.Notify(
                    "Not enough energy, free pass",
                    "${energy.nextLessonSentence()} Until then you scroll free."
                ),
            )
        }
    }
}
