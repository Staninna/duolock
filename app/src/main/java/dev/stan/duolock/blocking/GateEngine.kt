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
    private const val STREAK_WARN_HOUR = 21
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
        val settings = snapshot.settings
        val session = snapshot.session
        val effects = mutableListOf<Effect>()
        var st = state

        // The write-behind counters only mean anything against a live grant.
        if (session.grantedAllowanceMs == 0L && st.unflushedConsumedMs != 0L) {
            st = st.copy(unflushedConsumedMs = 0L)
        }

        if (settings.blockedPackages.isEmpty() && !settings.streakSaverEnabled) {
            return Decision(effects, st)
        }

        val energy = EnergyStatus.of(snapshot, now)
        val xpToday = user?.let { LessonVerifier.xpToday(it.xpGains, now = now) }

        // Once per evening: warn if today's XP is still zero and the streak is real.
        if (hour >= STREAK_WARN_HOUR && st.streakWarnedOnDay != dayOfYear && settings.hasAuth) {
            if (user == null || now - user.fetchedAtMs > STREAK_WARN_MAX_AGE_MS) {
                effects += Effect.WantFreshUser
            } else {
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
        }

        // Streak Saver locks everything (minus the whitelist) from the
        // configured hour until midnight, while today's XP is zero. Needs the
        // API: without data it stays off rather than locking with no exit.
        val saverArmed = settings.streakSaverEnabled && settings.hasAuth &&
            hour >= settings.streakSaverStartHour
        if (saverArmed && user == null) effects += Effect.WantFreshUser
        val saverActive = saverArmed && xpToday == 0L && (user?.streak ?: 0) > 0
        if (saverActive && !st.saverWasActive) {
            effects += Effect.Notify(
                "Streak Saver active",
                "Everything is locked until today's lesson is done. Calls and your allowed apps still work."
            )
        }
        st = st.copy(saverWasActive = saverActive)

        val countdownBase = energy.nextLessonSentence()
        effects += Effect.UpdateCountdown(
            if (saverActive) "Streak Saver on. $countdownBase" else countdownBase
        )

        val fg = foreground ?: return Decision(effects, st)
        val inBlockedApp = if (saverActive) {
            fg !in settings.streakSaverWhitelist && fg !in systemAllowedPackages
        } else {
            fg in settings.blockedPackages
        }
        val enteredBlockedApp = inBlockedApp && fg != st.lastForegroundPkg
        st = st.copy(lastForegroundPkg = fg)

        val remaining = session.remainingAllowanceMs - st.unflushedConsumedMs
        if (remaining > 0) {
            // Confirm the gate ran on every entry into a blocked app. The
            // allowance clock stays in the background: the only time the user
            // sees is when the next lesson is actually possible.
            if (enteredBlockedApp) {
                effects += Effect.Notify("Gate check: open", energy.nextLessonSentence())
            }
            // The allowance only burns while a blocked app is actually on screen.
            if (inBlockedApp) {
                st = st.copy(unflushedConsumedMs = st.unflushedConsumedMs + POLL_MS)
                if (st.unflushedConsumedMs >= FLUSH_EVERY_MS || remaining - POLL_MS <= 0) {
                    effects += Effect.Flush(st.unflushedConsumedMs, st.unflushedFallbackMs)
                    st = st.copy(unflushedConsumedMs = 0L, unflushedFallbackMs = 0L)
                }
                // Don't nag for a lesson that can't be done.
                if (!energy.lowForLesson && !session.reminderSentForSession &&
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
            if (remaining - POLL_MS > 0) return Decision(effects, st)
        }

        // Allowance used up. If it just ran out, clear it -- unless energy is
        // still too low for a lesson: then extend silently instead of locking
        // the app the user is in.
        if (session.grantedAllowanceMs != 0L && remaining <= 0) {
            st = st.copy(unflushedConsumedMs = 0L)
            if (energy.lowForLesson && inBlockedApp) {
                effects += energyPass(energy, settings.sessionMinutes, silent = true)
                return Decision(effects, st)
            }
            effects += Effect.ClearAllowance
            effects += Effect.Notify(
                "Time's up",
                if (energy.waitText != null)
                    "Next lesson possible in about ${energy.waitText}."
                else
                    "You have enough energy. One lesson buys the next round."
            )
        }

        val pendingSnapshot = session.pendingXpSnapshot
        if (pendingSnapshot != null) {
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
            // the pass and return them to the app they wanted.
            if (fg == duolingoPackage && energy.lowForLesson) {
                effects += energyPass(energy, settings.sessionMinutes, silent = false)
                st.lastBlockedPkg?.let { effects += Effect.LaunchApp(it) }
                return Decision(effects, st)
            }

            // Be polite: only want fresh XP while the user is in Duolingo or DuoGate.
            if (settings.hasAuth && (fg == duolingoPackage || fg == ownPackage)) {
                effects += Effect.WantFreshUser
            }
            val fallbackDone = session.fallbackAccumulatedMs + st.unflushedFallbackMs >=
                settings.fallbackLessonMinutes * 60_000L
            val xpDone = user != null && LessonVerifier.lessonCompleted(pendingSnapshot, user.totalXp)
            if (xpDone || fallbackDone) {
                st = st.copy(unflushedConsumedMs = 0L, unflushedFallbackMs = 0L)
                effects += Effect.Grant(settings.sessionMinutes * 60_000L, GrantSource.LESSON)
                effects += Effect.Notify(
                    "Unlocked",
                    if (energy.waitText != null)
                        "Lesson done. Next lesson possible in about ${energy.waitText}."
                    else
                        "Lesson done. You already have energy for another one."
                )
                return Decision(effects, st)
            }
        }

        if (inBlockedApp) {
            // Never block below the lesson threshold: the gate would be a dead end.
            if (energy.lowForLesson) {
                effects += energyPass(energy, settings.sessionMinutes, silent = false)
                return Decision(effects, st)
            }
            if (pendingSnapshot == null) {
                effects += Effect.BeginBlock(user?.totalXp ?: 0L)
                st = st.copy(unflushedFallbackMs = 0L)
            }
            st = st.copy(lastBlockedPkg = fg)
            effects += Effect.LaunchBlocker(fg)
        }
        return Decision(effects, st)
    }

    /**
     * A pass sized to how long energy needs to refill up to the lesson
     * threshold, capped at the session length so the meter is re-checked every
     * round. `lowForLesson` guarantees a reading, so the wait is never null.
     */
    private fun energyPass(energy: EnergyStatus, sessionMinutes: Int, silent: Boolean): List<Effect> {
        val untilLesson = energy.minutesUntilLesson ?: 0L
        val waitMin = untilLesson.coerceIn(1, sessionMinutes.toLong())
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
