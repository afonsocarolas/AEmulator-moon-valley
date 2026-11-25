package app.aemu.core

import app.aemu.core.XperiaPad.Button
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Added for AEmulator Moon Valley, 2026-11-25: see XperiaPad. */
class XperiaPadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theShimDeclaresTheSameKeys() {
        val h = File("../native/guestshim/vinput.h")
        assumeTrue(h.isFile)
        fun keys(name: String) = Regex("$name\\[] = \\{([^}]*)}").find(h.readText())!!.groupValues[1].split(',').map { it.trim().toInt() }
        assertEquals(XperiaPad.KEYPAD.toList() + 0, keys("vi_zeus_keys"))
        assertEquals(XperiaPad.GAME_KEYPAD.toList() + 0, keys("vi_zgame_keys"))
        for (b in Button.entries) assertTrue(b.name, b.code in if (b.game) XperiaPad.GAME_KEYPAD else XperiaPad.KEYPAD)
    }

    @Test fun eightDirections() {
        fun d(x: Float, y: Float) = XperiaPad.directions(x, y, 0.2f)
        assertEquals(emptySet<Button>(), d(0f, 0f))
        assertEquals(emptySet<Button>(), d(0.1f, -0.1f))
        assertEquals(setOf(Button.UP), d(0.1f, -1f))
        assertEquals(setOf(Button.DOWN), d(-0.1f, 1f))
        assertEquals(setOf(Button.LEFT), d(-1f, 0.3f))
        assertEquals(setOf(Button.RIGHT), d(1f, 0f))
        assertEquals(setOf(Button.RIGHT, Button.UP), d(0.8f, -0.8f))
        assertEquals(setOf(Button.LEFT, Button.DOWN), d(-0.5f, 0.9f))
    }

    @Test fun faceButtons() {
        assertEquals(setOf(Button.TRIANGLE), XperiaPad.face(0f, -0.6f, 0.25f))
        assertEquals(setOf(Button.CROSS), XperiaPad.face(0f, 0.6f, 0.25f))
        assertEquals(setOf(Button.SQUARE), XperiaPad.face(-0.6f, 0f, 0.25f))
        assertEquals(setOf(Button.CIRCLE), XperiaPad.face(0.6f, 0f, 0.25f))
        assertEquals(setOf(Button.CIRCLE, Button.CROSS), XperiaPad.face(0.6f, 0.6f, 0.25f))
        assertEquals(emptySet<Button>(), XperiaPad.face(0f, 0.1f, 0.25f))
    }

    @Test fun controllers() {
        assertEquals(Button.CROSS, XperiaPad.fromAndroid(96, true))
        assertEquals(Button.START, XperiaPad.fromAndroid(108, true))
        assertEquals(Button.UP, XperiaPad.fromAndroid(19, true))
        assertNull(XperiaPad.fromAndroid(19, false))          // a keyboard's arrow
        assertNull(XperiaPad.fromAndroid(24, true))           // volume
        assertNull(XperiaPad.fromAndroid(4, true))            // back
    }

    private fun events(a: ByteArray): List<List<Int>> {
        val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until a.size / 16).map { listOf(b.getShort(it * 16 + 8).toInt(), b.getShort(it * 16 + 10).toInt(), b.getInt(it * 16 + 12)) }
    }

    @Test fun eventsOfTheGuest() {
        assertEquals(listOf(listOf(1, 304, 1), listOf(0, 0, 0)), events(XperiaPad.key(Button.CROSS, true, 5_000_000_000)))
        assertEquals(listOf(listOf(1, 28, 0), listOf(0, 0, 0)), events(XperiaPad.key(Button.START, false, 1)))
        assertEquals(listOf(listOf(5, 0, 0), listOf(0, 0, 0)), events(XperiaPad.slide(true, 1)))     // lid switch 0: open
        assertEquals(listOf(listOf(5, 0, 1), listOf(0, 0, 0)), events(XperiaPad.slide(false, 1)))
    }

    @Test fun whichFirmware() {
        val root = tmp.newFolder("r")
        assertFalse(XperiaPad.supported(root, 10, true, Engine.KK))
        File(root, "system/usr/keylayout").mkdirs(); File(root, "system/usr/keylayout/keypad-zeus.kl").writeText("key 103 DPAD_UP\n")
        assertTrue(XperiaPad.supported(root, 10, true, Engine.KK))
        assertFalse(XperiaPad.supported(root, 10, false, Engine.KK))
        assertFalse(XperiaPad.supported(root, 19, true, Engine.KK))
    }
}
