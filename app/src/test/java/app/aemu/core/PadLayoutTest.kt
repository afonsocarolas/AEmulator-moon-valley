package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Added for AEmulator Moon Valley, 2026-11-27: see PadLayout. */
class PadLayoutTest {
    @Test fun roundTrip() {
        val l = PadLayout(0.6f).with("dpad", PadLayout.Item(12.5f, -30f, 1.4f)).with("L", PadLayout.Item(-4f, 0f, 0.8f))
        assertEquals(l, PadLayout.decode(l.encode()))
        assertEquals(PadLayout(), PadLayout.decode(null))
        assertEquals(PadLayout(), PadLayout.decode(""))
    }

    @Test fun untouchedControlsAreLeftOut() {
        val l = PadLayout().with("face", PadLayout.Item()).with("R", PadLayout.Item(1f, 2f, 1f))
        assertEquals("opacity=1.0;R=1.0,2.0,1.0", l.encode())
        assertEquals(PadLayout.Item(), PadLayout.decode(l.encode()).item("face"))
    }

    @Test fun badValuesAreKeptInRange() {
        val l = PadLayout.decode("opacity=0;dpad=1,2,9;face=x,y,z;=1,2,3;L=1,2")
        assertEquals(PadLayout.MIN_OPACITY, l.opacity)
        assertEquals(PadLayout.MAX_SCALE, l.item("dpad").scale)
        assertEquals(PadLayout.Item(), l.item("face"))
        assertEquals(1, l.items.size)
    }
}
