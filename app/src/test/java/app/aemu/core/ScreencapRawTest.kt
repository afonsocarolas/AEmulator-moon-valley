package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Added for AEmulator Moon Valley, 2026-11-19. */
class ScreencapRawTest {
    private fun dump(w: Int, h: Int, f: Int, px: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + px.size).order(ByteOrder.LITTLE_ENDIAN).putInt(w).putInt(h).putInt(f).put(px).array()

    @Test fun rgba() {
        val px = byteArrayOf(0x10, 0x20, 0x30, 0xff.toByte(), 0xff.toByte(), 0, 0, 0)
        val fr = ScreencapRaw.decode(dump(2, 1, ScreencapRaw.RGBA_8888, px))!!
        assertEquals(2, fr.width); assertEquals(1, fr.height)
        assertArrayEquals(intArrayOf(0xff102030.toInt(), 0xffff0000.toInt()), fr.argb)
    }

    @Test fun bgra() {
        val fr = ScreencapRaw.decode(dump(1, 1, ScreencapRaw.BGRA_8888, byteArrayOf(0x30, 0x20, 0x10, 0)))!!
        assertArrayEquals(intArrayOf(0xff102030.toInt()), fr.argb)
    }

    @Test fun rgb565() {
        // pure white and pure green
        val fr = ScreencapRaw.decode(dump(2, 1, ScreencapRaw.RGB_565, byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x07)))!!
        assertArrayEquals(intArrayOf(0xffffffff.toInt(), 0xff00ff00.toInt()), fr.argb)
    }

    @Test fun shortOrUnknownIsNull() {
        assertNull(ScreencapRaw.decode(ByteArray(8)))
        assertNull(ScreencapRaw.decode(dump(2, 2, 99, ByteArray(16))))
        assertNull(ScreencapRaw.decode(dump(4, 4, ScreencapRaw.RGBA_8888, ByteArray(10))))      // fewer pixels than it says
    }

    @Test fun epdSizedFrame() {
        val w = 540; val h = 960
        val fr = ScreencapRaw.decode(dump(w, h, ScreencapRaw.RGBA_8888, ByteArray(w * h * 4) { 0x80.toByte() }))!!
        assertEquals(w * h, fr.argb.size); assertEquals(0xff808080.toInt(), fr.argb[w * h - 1])
    }
}
