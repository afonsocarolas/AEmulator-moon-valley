package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Added for AEmulator Moon Valley, 2026-11-26: see OuyaPad. */
class OuyaPadTest {
    private val kl = """
        # OUYA Game Controller
        key 0x130    BUTTON_A
        key 0x133    BUTTON_X
        key 0x134    BUTTON_Y
        key 0x131    BUTTON_B
        key 0x13e    HOME      #Short Press
        key 0x13f    HOME ALT  #Long Press
        key 0x220    DPAD_UP
        key usage 0x000c006f BRIGHTNESS_UP
        # Left (0x00, 0x01) and right (0x03, 0x04) analog control stick.
        axis 0x00 X flat 4096
        axis 0x01 Y flat 4096
        axis 0x03 Z flat 4096
        axis 0x04 RZ flat 4096
        axis 0x02 LTRIGGER
        axis 0x05 invert RTRIGGER
        axis 0x10 split 0x01 GAS BRAKE
    """.trimIndent()

    @Test fun readsTheFirmwaresLayout() {
        val l = OuyaPad.parse(kl)
        assertEquals(0x130, l.key("BUTTON_A")); assertEquals(0x133, l.key("BUTTON_X"))
        assertEquals(0x13e, l.key("HOME"))                  // the first line wins (short press)
        assertEquals(0x220, l.key("DPAD_UP"))
        assertEquals(545, l.key("DPAD_DOWN"))               // not in the file: the standard key
        assertEquals(0x03 to false, l.axis("Z")); assertEquals(0x05 to true, l.axis("RTRIGGER"))
        assertNull(l.axes["GAS"])
        assertNull(l.keys["0x000c006f"])
        val bits = OuyaPad.keyBitmap(l)
        fun has(c: Int) = bits[c / 8].toInt() and (1 shl (c % 8)) != 0
        assertTrue(has(0x130)); assertTrue(has(0x13e)); assertTrue(has(0x220)); assertTrue(!has(0x10))
    }

    @Test fun phoneControllers() {
        assertEquals("BUTTON_A", OuyaPad.nameOf("KEYCODE_BUTTON_A"))
        assertEquals("DPAD_LEFT", OuyaPad.nameOf("KEYCODE_DPAD_LEFT"))
        assertEquals("HOME", OuyaPad.nameOf("KEYCODE_BUTTON_START"))
        assertEquals("HOME", OuyaPad.nameOf("KEYCODE_BUTTON_MODE"))
        assertNull(OuyaPad.nameOf("KEYCODE_VOLUME_UP"))
    }

    @Test fun ranges() {
        assertEquals(-32768, OuyaPad.raw(-1f, false)); assertEquals(32767, OuyaPad.raw(1f, false)); assertEquals(0, OuyaPad.raw(0f, false))
        assertEquals(-32768, OuyaPad.raw(0f, true)); assertEquals(32767, OuyaPad.raw(1f, true))
        val e = OuyaPad.Events(1).apply { key(0x130, true); axis(0, 5) }.bytes()
        val b = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48, e.size); assertEquals(1, b.getShort(8).toInt()); assertEquals(0x130, b.getShort(10).toInt())
        assertEquals(3, b.getShort(24).toInt()); assertEquals(5, b.getInt(28)); assertEquals(0, b.getShort(40).toInt())
        val h = File("../native/guestshim/vinput.h"); assumeTrue(h.isFile)
        assertTrue("#define VI_OUYA_AXIS ${OuyaPad.AXIS_MAX}" in h.readText())
    }

    @Test fun setupDone() {
        val fresh = OuyaPad.setupDonePrefs(null)!!
        assertTrue(fresh.contains("<boolean name=\"first_run\" value=\"false\" />") && fresh.trim().endsWith("</map>"))
        val merged = OuyaPad.setupDonePrefs("<?xml version='1.0' ?>\n<map>\n    <int name=\"x\" value=\"1\" />\n</map>\n")!!
        assertTrue(merged.contains("name=\"x\"") && merged.contains("first_run"))
        assertNull(OuyaPad.setupDonePrefs(merged))
    }
}
