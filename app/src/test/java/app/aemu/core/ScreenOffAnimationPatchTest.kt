package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Added for AEmulator Moon Valley, 2026-11-25: see ScreenOffAnimationPatch. */
class ScreenOffAnimationPatchTest {
    @get:Rule val tmp = TemporaryFolder()

    // turnElectronBeamOffImplLocked of the Xperia Play (R800i, 4.0.2.A.0.62), at 0x2221c; setCanDraw is at 0x19948
    private val code = "70b50e468eb000210546fff7abf9fef7e5fa0446f7f78cfb002837d095f852321bb9d5f85802002833dd95f8b01219b92fe0284604f0b4fd00204ff07e5301460246f5f786ee40f61140f5f7f0ec4ff48040f5f784ee40f61140f5f7c4ec266be26a0aa900250b950d9601ae30460c920a95f5f712ed31462046f6f7a1ff3046f5f772e820462946f7f750fb00200eb070bdf006cdd4cfe7".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun image() = ByteArray(0x22400).also { code.copyInto(it, 0x2221c) }

    @Test fun theCallIsFound() = assertTrue(ScreenOffAnimationPatch.switchesDrawingOff(image(), 0x2221c, code.size, 0x19948))

    @Test fun nothingElseIsTouched() {
        assertFalse(ScreenOffAnimationPatch.switchesDrawingOff(image(), 0x2221c, code.size, 0x19950))      // no such call
        val done = image().also { byteArrayOf(0x00, 0x20, 0x70, 0x47).copyInto(it, 0x2221c) }
        assertFalse(ScreenOffAnimationPatch.switchesDrawingOff(done, 0x2221c, code.size, 0x19948))         // patched already
        assertFalse(ScreenOffAnimationPatch.switchesDrawingOff(image(), 0x22380, code.size, 0x19948))      // beyond the file
        assertNull(ScreenOffAnimationPatch.offset(image()))                                                // not an ELF file
    }

    @Test fun theRealLibrary() {
        val so = System.getenv("AEMU_TEST_R800I_SF")?.let { File(it) }
        assumeTrue(so != null && so.isFile)
        val f = tmp.newFile("libsurfaceflinger.so"); so!!.copyTo(f, overwrite = true)
        assertEquals(1, ScreenOffAnimationPatch.apply(f))
        assertEquals(0, ScreenOffAnimationPatch.apply(f))
        val a = so.readBytes(); val b = f.readBytes()
        assertEquals(a.size, b.size)
        assertEquals((0x2221c..0x2221f).toList(), a.indices.filter { a[it] != b[it] })
    }
}
