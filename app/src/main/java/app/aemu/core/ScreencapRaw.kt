/* Added for AEmulator Moon Valley, 2026-11-19. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * The raw output of the guest's `screencap` (no -p): three little-endian ints, width, height and the pixel format,
 * then the rows. Used for the YotaPhone back screen ("screencap -d 2"): encoding a PNG inside the guest would cost
 * seconds under the translator, the raw dump is a copy. No Android classes here, so it runs on a plain JVM.
 */
object ScreencapRaw {
    // android/graphics/PixelFormat
    const val RGBA_8888 = 1
    const val RGBX_8888 = 2
    const val RGB_888 = 3
    const val RGB_565 = 4
    const val BGRA_8888 = 5

    class Frame(val width: Int, val height: Int, val argb: IntArray)

    private fun u4(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    /** The picture as ARGB pixels, or null when [b] is not a screencap dump of a format known here. */
    fun decode(b: ByteArray): Frame? {
        if (b.size < 12) return null
        val w = u4(b, 0); val h = u4(b, 4); val f = u4(b, 8)
        if (w !in 1..8192 || h !in 1..8192) return null
        val bpp = when (f) { RGBA_8888, RGBX_8888, BGRA_8888 -> 4; RGB_888 -> 3; RGB_565 -> 2; else -> return null }
        val n = w * h
        // 4.4 writes rows of exactly width pixels; a bigger dump (a stride) is read row by row from its own length
        val stride = ((b.size - 12) / h / bpp).coerceAtLeast(w)
        if (12L + stride.toLong() * (h - 1) * bpp + w.toLong() * bpp > b.size) return null
        val out = IntArray(n)
        for (y in 0 until h) {
            var o = 12 + y * stride * bpp
            for (x in 0 until w) {
                out[y * w + x] = when (f) {
                    RGBA_8888, RGBX_8888 -> {
                        val r = b[o].toInt() and 0xff; val g = b[o + 1].toInt() and 0xff; val bl = b[o + 2].toInt() and 0xff
                        (0xff shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                    BGRA_8888 -> {
                        val bl = b[o].toInt() and 0xff; val g = b[o + 1].toInt() and 0xff; val r = b[o + 2].toInt() and 0xff
                        (0xff shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                    RGB_888 -> (0xff shl 24) or ((b[o].toInt() and 0xff) shl 16) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() and 0xff)
                    else -> {
                        val v = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
                        val r = (v shr 11) and 0x1f; val g = (v shr 5) and 0x3f; val bl = v and 0x1f
                        (0xff shl 24) or ((r * 255 / 31) shl 16) or ((g * 255 / 63) shl 8) or (bl * 255 / 31)
                    }
                }
                o += bpp
            }
        }
        return Frame(w, h, out)
    }
}
