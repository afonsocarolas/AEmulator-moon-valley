/* Added for AEmulator Moon Valley, 2026-11-20: the real moon's phase, for the moon on the home screen. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The moon as it is at [epochMillis]. Mean synodic month from a known new moon (2000-01-06 18:14 UTC) plus the largest
 * corrections of the true moon (Meeus, chapter 49, the leading terms), so phase times come out within a few hours.
 * That is plenty for a picture and for "is tonight a full moon".
 */
class MoonPhase private constructor(
    /** days since the last new moon, 0 until [SYNODIC] */
    val age: Double,
) {
    /** 0 at new moon, 1 at full moon */
    val illumination: Double get() = (1 - cos(2 * PI * age / SYNODIC)) / 2
    /** growing toward full: lit on the right (as seen from the northern hemisphere) */
    val waxing: Boolean get() = age < SYNODIC / 2
    /** within a day and a half of the full moon: the night (or two) people call "full moon" */
    val isFullMoon: Boolean get() = abs(age - SYNODIC / 2) <= 1.5

    val name: Name get() = when {
        age < 1.5 || age > SYNODIC - 1.5 -> Name.NEW
        isFullMoon -> Name.FULL
        abs(age - SYNODIC / 4) <= 1.0 -> Name.FIRST_QUARTER
        abs(age - SYNODIC * 3 / 4) <= 1.0 -> Name.LAST_QUARTER
        age < SYNODIC / 4 -> Name.WAXING_CRESCENT
        age < SYNODIC / 2 -> Name.WAXING_GIBBOUS
        age < SYNODIC * 3 / 4 -> Name.WANING_GIBBOUS
        else -> Name.WANING_CRESCENT
    }

    enum class Name { NEW, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS, FULL, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT }

    companion object {
        const val SYNODIC = 29.530588853
        private const val DAY_MS = 86_400_000.0
        /** 2000-01-06 18:14 UTC, a new moon */
        private const val NEW_MOON_2000_MS = 947_182_440_000L

        fun at(epochMillis: Long): MoonPhase {
            val days = (epochMillis - NEW_MOON_2000_MS) / DAY_MS
            var mean = days % SYNODIC
            if (mean < 0) mean += SYNODIC
            // the true moon runs ahead of or behind the mean one by up to ~14 hours
            val lunations = days / SYNODIC
            val m = Math.toRadians(2.5534 + 29.10535670 * lunations)          // the sun's mean anomaly
            val mp = Math.toRadians(201.5643 + 385.81693528 * lunations)      // the moon's mean anomaly
            val f = Math.toRadians(160.7108 + 390.67050284 * lunations)      // the moon's argument of latitude
            val shift = -(-0.4072 * sin(mp) + 0.1721 * sin(m) + 0.0161 * sin(2 * mp) - 0.0104 * sin(2 * f))
            // Meeus gives the corrections for new moons and, slightly different, for full moons: blend them by the phase
            val phaseAngle = 2 * PI * mean / SYNODIC
            val weight = cos(phaseAngle)                                       // 1 at new moon, -1 at full
            val fullShift = -(-0.40614 * sin(mp) + 0.17302 * sin(m) + 0.01614 * sin(2 * mp) - 0.01043 * sin(2 * f))
            val corrected = mean + (shift * (1 + weight) + fullShift * (1 - weight)) / 2
            return MoonPhase(((corrected % SYNODIC) + SYNODIC) % SYNODIC)
        }

        /** which lunation (counted from the new moon of 2000-01-06) [epochMillis] falls in: one number per full moon */
        fun lunation(epochMillis: Long): Long {
            val days = (epochMillis - NEW_MOON_2000_MS) / DAY_MS
            return Math.floor((days - at(epochMillis).age) / SYNODIC + 0.5).toLong()
        }

        /** the moment (UTC, ms) of the next full moon after [epochMillis], to within a few hours */
        fun nextFullMoon(epochMillis: Long): Long {
            var days = SYNODIC / 2 - at(epochMillis).age
            if (days < 0.05) days += SYNODIC
            // the mean guess, then the hour (within a day and a half each way) where the true moon is fullest
            val guess = epochMillis + (days * DAY_MS).toLong()
            return (-36..36).map { guess + it * 3_600_000L }.filter { it > epochMillis }
                .minByOrNull { abs(at(it).age - SYNODIC / 2) } ?: guess
        }
    }
}
