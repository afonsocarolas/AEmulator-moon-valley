package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

/** Added for AEmulator Moon Valley, 2026-11-20: the real moon on the home screen. Eclipses pin the exact moments. */
class MoonPhaseTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun hoursFromFull(iso: String) = (MoonPhase.at(ms(iso)).age - MoonPhase.SYNODIC / 2) * 24
    private fun hoursFromNew(iso: String) = MoonPhase.at(ms(iso)).age.let { if (it > MoonPhase.SYNODIC / 2) it - MoonPhase.SYNODIC else it } * 24

    @Test fun lunarEclipsesAreFullMoons() {
        // total lunar eclipses: 2025-03-14 06:59, 2025-09-07 18:12, 2026-03-03 11:34 UTC; partial 2026-08-28 04:13
        for (t in listOf("2025-03-14T06:59:00Z", "2025-09-07T18:12:00Z", "2026-03-03T11:34:00Z", "2026-08-28T04:13:00Z")) {
            val h = hoursFromFull(t)
            assertTrue("$t is ${"%.1f".format(h)} h from full", abs(h) < 6)
            assertTrue(MoonPhase.at(ms(t)).isFullMoon)
            assertEquals(MoonPhase.Name.FULL, MoonPhase.at(ms(t)).name)
            assertTrue(MoonPhase.at(ms(t)).illumination > 0.99)
        }
    }

    @Test fun solarEclipsesAreNewMoons() {
        // 2024-04-08 18:17, 2026-02-17 12:12, 2026-08-12 17:46 UTC
        for (t in listOf("2024-04-08T18:17:00Z", "2026-02-17T12:12:00Z", "2026-08-12T17:46:00Z")) {
            val h = hoursFromNew(t)
            assertTrue("$t is ${"%.1f".format(h)} h from new", abs(h) < 6)
            assertEquals(MoonPhase.Name.NEW, MoonPhase.at(ms(t)).name)
            assertTrue(MoonPhase.at(ms(t)).illumination < 0.01)
            assertFalse(MoonPhase.at(ms(t)).isFullMoon)
        }
    }

    @Test fun waxingThenWaning() {
        val newMoon = ms("2026-08-12T17:46:00Z")
        val day = 86_400_000L
        val w = MoonPhase.at(newMoon + 5 * day)
        assertTrue(w.waxing); assertEquals(MoonPhase.Name.WAXING_CRESCENT, w.name)
        assertEquals(MoonPhase.Name.FIRST_QUARTER, MoonPhase.at(newMoon + (7.4 * day).toLong()).name)
        val g = MoonPhase.at(newMoon + 22 * day)
        assertFalse(g.waxing); assertEquals(MoonPhase.Name.LAST_QUARTER, g.name)
        assertEquals(MoonPhase.Name.WANING_CRESCENT, MoonPhase.at(newMoon + 26 * day).name)
    }

    @Test fun oneLunationPerFullMoon() {
        // the nights around one full moon count once; the next full moon is the next number
        val a = MoonPhase.lunation(ms("2026-08-27T20:00:00Z"))
        assertEquals(a, MoonPhase.lunation(ms("2026-08-29T02:00:00Z")))
        assertEquals(a + 1, MoonPhase.lunation(ms("2026-09-26T12:00:00Z")))
    }

    @Test fun nextFullMoonFindsTheEclipse() {
        val next = MoonPhase.nextFullMoon(ms("2026-08-20T00:00:00Z"))
        assertTrue("off by ${(next - ms("2026-08-28T04:13:00Z")) / 3_600_000.0} h", abs(next - ms("2026-08-28T04:13:00Z")) < 6 * 3_600_000L)
        // from just after a full moon, the following one
        val after = MoonPhase.nextFullMoon(ms("2026-03-03T18:00:00Z"))
        assertTrue(after - ms("2026-03-03T18:00:00Z") > 25L * 86_400_000L)
    }
}
