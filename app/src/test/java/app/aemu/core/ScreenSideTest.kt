package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Added for AEmulator Moon Valley, 2026-11-24: which side the guest draws on (see ScreenSide). */
class ScreenSideTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theSideThatDraws() {
        fun pick(logo: Boolean, gl: Boolean, fb: Boolean) = ScreenSide.pick(logo, gl) { fb }
        assertEquals(ScreenSide.Side.SURFACE, pick(false, false, false))
        assertEquals(ScreenSide.Side.FB, pick(false, false, true))
        assertEquals(ScreenSide.Side.SURFACE, pick(false, true, true))
        assertEquals(ScreenSide.Side.SURFACE, pick(false, true, false))
        assertEquals(ScreenSide.Side.LOGO, pick(true, false, true))
        assertEquals(ScreenSide.Side.LOGO, pick(true, true, false))
    }

    @Test fun fbIsNotReadWhileTheBridgeDraws() { ScreenSide.pick(false, true) { throw AssertionError("read") } }

    @Test fun onlyNewPicturesCount() {
        val fb = File(tmp.root, "fb0")
        val a = FbActivity(fb, 1000)
        assertFalse(a.drew())                                   // before the first start
        a.reset(); assertFalse(a.drew())                        // no file
        fb.writeBytes(ByteArray(2000)); assertFalse(a.drew())   // black
        fb.writeBytes(ByteArray(2000) { if (it > 1500) 1 else 0 }); assertFalse(a.drew())   // beyond the screen
        fb.writeBytes(ByteArray(2000) { 5 }); assertTrue(a.drew())
        a.reset(); assertFalse(a.drew())                        // left over from the start before
        fb.writeBytes(ByteArray(2000)); assertFalse(a.drew())   // cleared
        fb.writeBytes(ByteArray(2000) { 6 }); assertTrue(a.drew())
        fb.writeBytes(ByteArray(2000)); assertTrue(a.drew())    // stays, also on a black frame
    }
}
