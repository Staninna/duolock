package dev.stan.duolock.duolingo

import dev.stan.duolock.data.EnergyReading
import dev.stan.duolock.data.GateSnapshot

/**
 * The one energy question the whole app asks: can a lesson happen right now,
 * and if not, when? Derived once per moment from a [GateSnapshot]; every
 * consumer (gate, notifications, status screen, lock screen) reads this
 * instead of re-running the estimator by hand.
 */
class EnergyStatus private constructor(
    /** Estimated current units; null when the meter has never been read. */
    val units: Int?,
    /** 0 = enough energy now; null = no reading. */
    val minutesUntilLesson: Long?,
    val threshold: Int,
    val refillMinutesPerUnit: Int,
    val reading: EnergyReading?,
) {
    val noReading: Boolean get() = units == null

    /** Below the lesson threshold on a real reading: the gate must not block. */
    val lowForLesson: Boolean get() = units != null && units < threshold

    /** Human wait time, or null when no wait applies (ready, or no reading). */
    val waitText: String?
        get() = minutesUntilLesson?.takeIf { it > 0 }?.let { EnergyEstimator.formatWait(it) }

    /** The canonical "when is the next lesson possible" sentence. */
    fun nextLessonSentence(): String = when {
        noReading -> "No energy reading yet. Open Duolingo once so DuoGate can see the meter."
        waitText != null -> "Next lesson in about $waitText."
        else -> "You have enough energy for a lesson right now."
    }

    companion object {
        fun of(snapshot: GateSnapshot, now: Long): EnergyStatus {
            val reading = snapshot.session.energy
            val rate = snapshot.refillMinutesPerUnit
            val threshold = snapshot.settings.minEnergyForLesson
            val units = reading?.let {
                EnergyEstimator.estimate(it.units, it.atMs, now, rate)
            }
            val untilLesson = reading?.let {
                EnergyEstimator.minutesUntilEnergy(threshold, it.units, it.atMs, now, rate)
            }
            return EnergyStatus(units, untilLesson, threshold, rate, reading)
        }
    }
}
