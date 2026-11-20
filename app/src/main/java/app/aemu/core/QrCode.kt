/* Added for AEmulator Moon Valley, 2026-11-20: a QR code for the address of "Play in a browser". GPL-3.0; see LICENSE and NOTICE.md.
 * After the QR Code standard (ISO/IEC 18004) as explained by Project Nayuki's "QR Code generator library"; written anew. */
package app.aemu.core

/**
 * A QR code for a short text: byte mode, error correction level M, versions 1 to 10 (up to 213 bytes, plenty for an
 * address). [modules] is [size]×[size], row by row, true for dark; draw it with a quiet zone of 4 modules around it.
 */
class QrCode private constructor(val version: Int, val mask: Int, private val dark: Array<BooleanArray>) {
    val size: Int get() = dark.size
    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y][x]

    companion object {
        // level M, versions 1..10 (index 0 unused)
        private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private const val FORMAT_M = 0
        const val MAX_VERSION = 10

        /** null when [text] is too long for version 10 */
        fun encode(text: String): QrCode? = encode(text.toByteArray(Charsets.UTF_8))

        fun encode(data: ByteArray): QrCode? {
            val version = (1..MAX_VERSION).firstOrNull { v -> 4 + countBits(v) + data.size * 8 <= dataCodewords(v) * 8 } ?: return null
            val capacity = dataCodewords(version) * 8
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, countBits(version))
            data.forEach { bits.append(it.toInt() and 0xff, 8) }
            bits.append(0, minOf(4, capacity - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { bits.append(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val codewords = interleave(version, bits.toBytes())

            val size = version * 4 + 17
            val modules = Array(size) { BooleanArray(size) }
            val function = Array(size) { BooleanArray(size) }
            drawFunctionPatterns(version, modules, function)
            drawCodewords(codewords, modules, function)
            // the mask with the lowest penalty, as readers like it best
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (m in 0 until 8) {
                applyMask(m, modules, function)
                drawFormat(m, modules, function)
                val p = penalty(modules)
                if (p < bestPenalty) { best = m; bestPenalty = p }
                applyMask(m, modules, function)          // XOR again: undone
            }
            applyMask(best, modules, function)
            drawFormat(best, modules, function)
            return QrCode(version, best, modules)
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        fun rawDataModules(ver: Int): Int {
            var r = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val n = ver / 7 + 2
                r -= (25 * n - 10) * n - 55
                if (ver >= 7) r -= 36
            }
            return r
        }

        fun dataCodewords(ver: Int) = rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]

        private fun interleave(ver: Int, data: ByteArray): ByteArray {
            val blocks = BLOCKS[ver]
            val ecc = ECC_PER_BLOCK[ver]
            val raw = rawDataModules(ver) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val divisor = rsDivisor(ecc)
            val list = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until blocks) {
                val len = shortLen - ecc + if (i < shortBlocks) 0 else 1
                val dat = IntArray(len) { data[k + it].toInt() and 0xff }
                k += len
                val rem = rsRemainder(dat, divisor)
                // short blocks get a placeholder where the long ones have their last data byte
                val block = if (i < shortBlocks) dat + intArrayOf(0) + rem else dat + rem
                list.add(block)
            }
            val out = ArrayList<Byte>(raw)
            for (i in list[0].indices) for (j in list.indices) {
                if (i != shortLen - ecc || j >= shortBlocks) out.add(list[j][i].toByte())
            }
            return out.toByteArray()
        }

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsDivisor(degree: Int): IntArray {
            val r = IntArray(degree)
            r[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    r[j] = gfMul(r[j], root)
                    if (j + 1 < degree) r[j] = r[j] xor r[j + 1]
                }
                root = gfMul(root, 0x02)
            }
            return r
        }

        /** the [degree] error correction bytes of [data] (Reed-Solomon over GF(256)) */
        internal fun reedSolomon(data: IntArray, degree: Int): IntArray = rsRemainder(data, rsDivisor(degree))

        private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
            val r = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor r[0]
                System.arraycopy(r, 1, r, 0, r.size - 1)
                r[r.size - 1] = 0
                for (i in r.indices) r[i] = r[i] xor gfMul(divisor[i], factor)
            }
            return r
        }

        private fun alignmentPositions(ver: Int): IntArray {
            if (ver == 1) return IntArray(0)
            val n = ver / 7 + 2
            val step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2
            val size = ver * 4 + 17
            val r = IntArray(n)
            r[0] = 6
            var pos = size - 7
            for (i in n - 1 downTo 1) { r[i] = pos; pos -= step }
            return r
        }

        private fun set(m: Array<BooleanArray>, f: Array<BooleanArray>, x: Int, y: Int, dark: Boolean) { m[y][x] = dark; f[y][x] = true }

        private fun drawFunctionPatterns(ver: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val size = m.size
            for (i in 0 until size) { set(m, f, 6, i, i % 2 == 0); set(m, f, i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx; val y = cy + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    set(m, f, x, y, d != 2 && d != 4)
                }
            }
            val pos = alignmentPositions(ver)
            for (i in pos.indices) for (j in pos.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(m, f, pos[i] + dx, pos[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
            drawFormat(0, m, f)                       // reserves the format areas; drawn for real once the mask is known
            if (ver >= 7) {
                var rem = ver
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (ver shl 12) or rem
                for (i in 0 until 18) {
                    val bit = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(m, f, a, b, bit); set(m, f, b, a, bit)
                }
            }
        }

        fun formatBits(mask: Int): Int {
            val data = (FORMAT_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            return ((data shl 10) or rem) xor 0x5412
        }

        private fun drawFormat(mask: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val size = m.size
            val bits = formatBits(mask)
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(m, f, 8, i, bit(i))
            set(m, f, 8, 7, bit(6)); set(m, f, 8, 8, bit(7)); set(m, f, 7, 8, bit(8))
            for (i in 9 until 15) set(m, f, 14 - i, 8, bit(i))
            for (i in 0 until 8) set(m, f, size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(m, f, 8, size - 15 + i, bit(i))
            set(m, f, 8, size - 8, true)
        }

        private fun drawCodewords(data: ByteArray, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val size = m.size
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!f[y][x] && i < data.size * 8) {
                        m[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            for (y in m.indices) for (x in m.indices) {
                if (f[y][x]) continue
                val flip = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (flip) m[y][x] = !m[y][x]
            }
        }

        /** the standard's penalty rules 1 (runs), 2 (2×2 blocks), 3 (finder-like stretches) and 4 (dark/light balance) */
        private fun penalty(m: Array<BooleanArray>): Int {
            val size = m.size
            var p = 0
            fun line(get: (Int) -> Boolean) {
                var run = 1
                for (i in 1 until size) {
                    if (get(i) == get(i - 1)) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1
                }
                // 1:1:3:1:1 dark-light pattern with four light modules on one side
                for (i in 0..size - 7) {
                    if (get(i) && !get(i + 1) && get(i + 2) && get(i + 3) && get(i + 4) && !get(i + 5) && get(i + 6)) {
                        val before = (i - 4 until i).all { it < 0 || !get(it) }
                        val after = (i + 7 until i + 11).all { it >= size || !get(it) }
                        if (before || after) p += 40
                    }
                }
            }
            for (y in 0 until size) line { m[y][it] }
            for (x in 0 until size) line { m[it][x] }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
            }
            val darkCount = m.sumOf { row -> row.count { it } }
            val total = size * size
            val k = (kotlin.math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
            p += k.coerceAtLeast(0) * 10
            return p
        }

        private class BitBuffer {
            private val bits = ArrayList<Boolean>()
            val size: Int get() = bits.size
            fun append(value: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
            fun toBytes(): ByteArray = ByteArray(bits.size / 8) { b -> var v = 0; for (i in 0 until 8) v = (v shl 1) or (if (bits[b * 8 + i]) 1 else 0); v.toByte() }
        }
    }
}
