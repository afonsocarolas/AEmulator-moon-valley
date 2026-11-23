package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Added for AEmulator Moon Valley, 2026-11-23: touch of the first Galaxy Tab (see SidewaysTouchPatch). */
class SidewaysTouchPatchTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val ctor = hex("012704f134002763")
    private val turn = hex("032f18bf7d1c30462563")
    private val swap = hex("42f0010313b1a762e56201e0a562e762")
    private val gap = ByteArray(6) { 0x46 }

    @Test fun allFourSitesAndOnlyOnce() {
        val d = gap + ctor + gap + turn + gap + swap + gap + ctor
        assertEquals(SidewaysTouchPatch.PATCHED, SidewaysTouchPatch.apply(d))
        assertArrayEquals(gap + hex("002704f134002763") + gap + hex("3d4600bf00bf30462563") + gap +
            hex("42f0010302e0a762e56201e0a562e762") + gap + hex("002704f134002763"), d)
        val once = d.copyOf()
        assertEquals(SidewaysTouchPatch.ALREADY, SidewaysTouchPatch.apply(d))
        assertArrayEquals(once, d)
    }

    @Test fun anythingElseIsLeftAlone() {
        for (d in listOf(ByteArray(100), gap + ctor + turn + swap, ctor + ctor + turn, ctor + ctor + turn + turn + swap, ByteArray(0))) {
            val before = d.copyOf()
            assertEquals(SidewaysTouchPatch.ABSENT, SidewaysTouchPatch.apply(d))
            assertArrayEquals(before, d)
        }
    }

    /** With TOUCH_SAMPLE=<a real libandroid_servers.so>; TOUCH_OUT=<file> keeps the result. */
    @Test fun realLibrary() {
        val d = System.getenv("TOUCH_SAMPLE")?.let { File(it).readBytes() } ?: return
        val before = d.copyOf()
        assertEquals(SidewaysTouchPatch.PATCHED, SidewaysTouchPatch.apply(d))
        println("touch sample: bytes changed at " + d.indices.filter { d[it] != before[it] }.joinToString { it.toString(16) })
        System.getenv("TOUCH_OUT")?.let { File(it).writeBytes(d) }
    }
}
