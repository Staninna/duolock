package dev.stan.duolock.updates

/**
 * Dotted numeric version comparison, tolerant of the leading "v" GitHub tags
 * carry and of ragged segment counts ("1.1" beats "1.0.9"). Anything that
 * isn't plain numbers compares as "not newer": an update we can't reason
 * about is one we don't offer.
 */
object Version {

    fun parse(raw: String): List<Int>? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        return trimmed.split(".").map { part ->
            part.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val new = parse(candidate) ?: return false
        val old = parse(current) ?: return false
        for (i in 0 until maxOf(new.size, old.size)) {
            val a = new.getOrElse(i) { 0 }
            val b = old.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
