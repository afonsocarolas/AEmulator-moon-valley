/* Added for AEmulator Moon Valley, 2026-11-24: which side the guest draws on. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * With the in-app GPU bridge the guest's frames go straight to the app's surface and fb0 stays empty, so the window shows
 * the surface. But not every firmware takes the bridge: Android 1.0-1.5 has no egl.cfg and no libGLES_* drivers (its
 * libGLES_CM asks for "gpu" hardware and falls back to PixelFlinger), and it draws into fb0 whatever the GPU setting says.
 * The surface then stays empty for ever: a black screen over a machine that runs. So the window follows the side that draws.
 */
object ScreenSide {
    enum class Side { SURFACE, LOGO, FB }

    /**
     * [logo]: Samsung's boot logo paints into fb0 now. [bridgeDrew]: the bridge has put a frame on the surface since this start
     * of the machine. [fbDrew]: fb0 has changed since then (asked only when it matters).
     */
    fun pick(logo: Boolean, bridgeDrew: Boolean, fbDrew: () -> Boolean): Side = when {
        logo -> Side.LOGO
        bridgeDrew -> Side.SURFACE
        fbDrew() -> Side.FB
        else -> Side.SURFACE
    }

    /** A hash of the first [bytes] of [fb], 0 when there is nothing but zeros (or no file). */
    fun hash(fb: File, bytes: Long): Long = runCatching {
        RandomAccessFile(fb, "r").use { r ->
            val buf = ByteArray(64 * 1024)
            var left = minOf(bytes, r.length()); var h = 0L; var any = false
            while (left > 0) {
                val n = r.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n <= 0) break
                for (i in 0 until n) { val v = buf[i].toInt(); if (v != 0) any = true; h = 31 * h + v }
                left -= n
            }
            if (!any) 0L else if (h == 0L) 1L else h
        }
    }.getOrDefault(0L)
}

/** Whether fb0 has been drawn on since [reset]: what an earlier start of the machine left there does not count. */
class FbActivity(private val fb: File, private val bytes: Long) {
    private var base = 0L
    private var ready = false
    private var drew = false

    @Synchronized fun reset() { base = ScreenSide.hash(fb, bytes); ready = true; drew = false }

    @Synchronized fun drew(): Boolean {
        if (!ready || drew) return drew
        val h = ScreenSide.hash(fb, bytes)
        if (h != 0L && h != base) drew = true
        return drew
    }
}
