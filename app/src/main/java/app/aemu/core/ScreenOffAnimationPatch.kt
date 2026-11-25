/* Added for AEmulator Moon Valley, 2026-11-25: the 2.3 screen-off animation must not leave the screen off. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Android 2.3 ends SurfaceFlinger's screen-off animation (turnElectronBeamOff, started by PowerManagerService when
 * config_animateScreenLights is false) with setCanDraw(false), and SurfaceFlinger composes nothing until
 * turnElectronBeamOn, which only follows the kernel's "framebuffer woke up" event. Here the framebuffer never sleeps
 * (wait_for_fb_sleep is a FIFO nobody writes to), so that event never comes: the screen is black for the rest of the run.
 * On the Xperia Play (Sony Ericsson R800i, 2.3.4) the animation already plays while the system starts: a machine that boots
 * and is never seen. The animation becomes a function that returns 0, so the picture simply stays, as on every other release.
 */
internal object ScreenOffAnimationPatch {
    private const val OFF = "_ZN7android14SurfaceFlinger29turnElectronBeamOffImplLockedEi"
    private const val SET_CAN_DRAW = "_ZN7android19DisplayHardwareBase10setCanDrawEb"
    /** movs r0,#0 ; bx lr */
    private val RETURN_0 = byteArrayOf(0x00, 0x20, 0x70, 0x47)

    /** File offset of the function's first instructions; null when it is not there, already patched, or does not switch drawing off. */
    fun offset(d: ByteArray): Int? {
        val syms = ElfPatch.symbolsIn(d, setOf(OFF, SET_CAN_DRAW)) ?: return null
        val off = syms[OFF] ?: return null
        val draw = syms[SET_CAN_DRAW] ?: return null
        if ((off shr 40) and 1L != 1L || (draw shr 40) and 1L != 1L) return null // Thumb only
        val start = (off and 0xffffffffL).toInt()
        val size = ElfPatch.symbolSize(d, OFF) ?: return null
        return start.takeIf { switchesDrawingOff(d, start, size, (draw and 0xffffffffL).toInt()) }
    }

    /** The Thumb function at [start] begins with a push and calls the function at [setCanDraw]. */
    internal fun switchesDrawingOff(d: ByteArray, start: Int, size: Int, setCanDraw: Int): Boolean {
        if (size < 16 || start < 0 || start + size > d.size) return false
        fun u16(o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
        val first = u16(start)
        if ((first and 0xff00) != 0xb500 && first != 0xe92d) return false // push {.., lr} / push.w
        var i = start
        while (i + 4 <= start + size) {
            val h1 = u16(i); val h2 = u16(i + 2)
            if ((h1 and 0xf800) == 0xf000 && (h2 and 0xd000) == 0xd000) { // 32-bit bl
                val s = (h1 shr 10) and 1
                val i1 = ((h2 shr 13) and 1 xor s) xor 1
                val i2 = ((h2 shr 11) and 1 xor s) xor 1
                var o = (s shl 24) or (i1 shl 23) or (i2 shl 22) or ((h1 and 0x3ff) shl 12) or ((h2 and 0x7ff) shl 1)
                if (s == 1) o = o or (-1 shl 25)
                if (i + 4 + o == setCanDraw) return true
                i += 4
            } else i += 2
        }
        return false
    }

    /** 1 when patched. */
    fun apply(f: File): Int {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        val at = offset(d) ?: return 0
        if (!f.canWrite()) f.setWritable(true, true)
        RandomAccessFile(f, "rw").use { raf -> raf.seek(at.toLong()); raf.write(RETURN_0) }
        return 1
    }
}
