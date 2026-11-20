package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Added for AEmulator Moon Valley, 2026-11-20: the QR code of "Play in a browser". */
class QrCodeTest {
    @Test fun reedSolomonOfTheStandardsExample() {
        // ISO/IEC 18004 annex I: "01234567" as 1-M, its 16 data codewords and their 10 error correction codewords
        val data = intArrayOf(0x10, 0x20, 0x0C, 0x56, 0x61, 0x80, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11)
        assertArrayEquals(intArrayOf(0xA5, 0x24, 0xD4, 0xC1, 0xED, 0x36, 0xC7, 0x87, 0x2C, 0x55), QrCode.reedSolomon(data, 10))
    }

    @Test fun formatBitsOfLevelM() {
        // from the standard's table: M with masks 0 and 5
        assertEquals(0b101010000010010, QrCode.formatBits(0))
        assertEquals(0b100000011001110, QrCode.formatBits(5))
    }

    @Test fun capacities() {
        assertEquals(16, QrCode.dataCodewords(1))
        assertEquals(28, QrCode.dataCodewords(2))
        assertEquals(216, QrCode.dataCodewords(10))
        assertEquals(26, QrCode.rawDataModules(1) / 8)
        assertEquals(346, QrCode.rawDataModules(10) / 8)        // 216 data + 5 blocks × 26
    }

    @Test fun anAddressFits() {
        val q = QrCode.encode("http://192.168.100.200:8089/?k=abcdefghjkmn")!!
        assertEquals(4, q.version)               // 44 bytes: version 4 at level M holds 62
        assertEquals(33, q.size)
        // the three finder patterns: dark corners, a light ring, a dark middle
        for ((x, y) in listOf(0 to 0, q.size - 7 to 0, 0 to q.size - 7)) {
            assertTrue(q.isDark(x, y)); assertTrue(!q.isDark(x + 1, y + 1)); assertTrue(q.isDark(x + 3, y + 3))
        }
        assertTrue(q.isDark(8, q.size - 8))      // the module that is always dark
        assertNull(QrCode.encode("x".repeat(300)))
    }
}
