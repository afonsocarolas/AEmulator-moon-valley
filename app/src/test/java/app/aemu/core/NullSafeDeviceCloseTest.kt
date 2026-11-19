package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Added for AEmulator Moon Valley, 2026-11-19: the YotaPhone 2 SurfaceFlinger close helper (see ElfPatch.nullSafeDeviceClose). */
class NullSafeDeviceCloseTest {
    @get:Rule val tmp = TemporaryFolder()

    private val helper = byteArrayOf(0x08, 0xb5.toByte(), 0xc3.toByte(), 0x6b, 0x98.toByte(), 0x47, 0x08, 0xbd.toByte())

    /** an ELF-looking file with [helper] at each of [at] (even offsets) */
    private fun elf(vararg at: Int): ByteArray {
        val d = ByteArray(0x300)
        d[0] = 0x7f; d[1] = 'E'.code.toByte(); d[2] = 'L'.code.toByte(); d[3] = 'F'.code.toByte(); d[4] = 1
        for (a in at) helper.copyInto(d, a)
        return d
    }

    @Test fun theOneHelperIsMadeNullSafe() {
        val f = tmp.newFile(); f.writeBytes(elf(0x1fc))
        assertEquals(1, ElfPatch.nullSafeDeviceClose(f))
        val d = f.readBytes()
        assertArrayEquals(ElfPatch.nullSafeDeviceCloseBytes(), d.copyOfRange(0x1fc, 0x204))
        // cbz r0 / ldr r3,[r0,#0x3c] kept / bx r3 / bx lr
        assertArrayEquals(byteArrayOf(0x08, 0xb1.toByte(), 0xc3.toByte(), 0x6b, 0x18, 0x47, 0x70, 0x47), d.copyOfRange(0x1fc, 0x204))
        // the rest of the file is untouched, and a second run finds nothing to do
        assertTrue(d.copyOfRange(0, 0x1fc).contentEquals(elf().copyOfRange(0, 0x1fc)))
        assertEquals(0, ElfPatch.nullSafeDeviceClose(f))
    }

    @Test fun twoMatchesAreLeftAlone() {
        val f = tmp.newFile(); val before = elf(0x100, 0x180); f.writeBytes(before)
        assertEquals(0, ElfPatch.nullSafeDeviceClose(f))
        assertArrayEquals(before, f.readBytes())
    }

    @Test fun oddOffsetOrNotElfIsLeftAlone() {
        assertEquals(emptyList<Int>(), ElfPatch.nullSafeDeviceCloseOffsets(elf().also { helper.copyInto(it, 0x101) }))
        val notElf = elf(0x100).also { it[0] = 0 }
        assertEquals(emptyList<Int>(), ElfPatch.nullSafeDeviceCloseOffsets(notElf))
    }
}
