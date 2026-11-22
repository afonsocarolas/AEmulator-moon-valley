/* Added for AEmulator Moon Valley, 2026-11-22: Sony's packed precompiled code ("codx"). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.tukaani.xz.LZMAInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sony's Lollipop firmware (seen on the Xperia C3, D2533 19.4.A.0.182) ships nearly every odex/oat file packed: a small
 * ELF whose .text section is a "codx" block holding the real file, LZMA-compressed. The phone unpacks them somewhere
 * below the runtime; here libart got the packed file as it is, took the stub's dex header for the dex and died in
 * system_server reading string ids out of the compressed bytes (SIGSEGV in libart, "zygote exited with 137").
 * Each packed file is replaced by the file it holds, once (775 MB on that firmware; boot.oat itself is not packed).
 *
 * The block, little-endian 32-bit fields: "codx", 0, 1, 1, unpacked size, packed size, two checksums, the length of
 * the LZMA properties (5), the properties (lc/lp/pb byte, dictionary size), padded to 4; then raw LZMA1 data.
 */
object SonyCodx {
    private val MAGIC = byteArrayOf('c'.code.toByte(), 'o'.code.toByte(), 'd'.code.toByte(), 'x'.code.toByte())

    /** where a packed block starts in [f] (an ELF32), or -1 */
    fun blockOffset(f: RandomAccessFile): Long {
        if (f.length() < 0x34) return -1
        val eh = ByteArray(0x34).also { f.seek(0); f.readFully(it) }
        if (eh[0] != 0x7f.toByte() || eh[1] != 'E'.code.toByte() || eh[2] != 'L'.code.toByte() || eh[3] != 'F'.code.toByte()) return -1
        if (eh[4].toInt() != 1 || eh[5].toInt() != 1) return -1                      // ELF32, little-endian (ARM odex)
        val b = ByteBuffer.wrap(eh).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = b.getInt(0x20).toLong() and 0xffffffffL
        val shentsize = b.getShort(0x2e).toInt() and 0xffff
        val shnum = b.getShort(0x30).toInt() and 0xffff
        if (shentsize < 40 || shnum !in 1..64 || shoff + shnum.toLong() * shentsize > f.length()) return -1
        val sh = ByteArray(shentsize * shnum).also { f.seek(shoff); f.readFully(it) }
        val sb = ByteBuffer.wrap(sh).order(ByteOrder.LITTLE_ENDIAN)
        val m = ByteArray(4)
        for (i in 0 until shnum) {
            val off = sb.getInt(i * shentsize + 16).toLong() and 0xffffffffL
            val size = sb.getInt(i * shentsize + 20).toLong() and 0xffffffffL
            if (size < HEADER || off + HEADER > f.length()) continue
            f.seek(off); f.readFully(m)
            if (m.contentEquals(MAGIC)) return off
        }
        return -1
    }

    class Block(val offset: Long, val unpacked: Long, val packed: Long, val props: Int, val dictSize: Int)

    /** the packed block of [file], or null when it is not one (or not one this understands) */
    fun block(file: File): Block? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val off = blockOffset(f)
            if (off < 0) return null
            val h = ByteArray(HEADER).also { f.seek(off); f.readFully(it) }
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(4) != 0 || b.getInt(8) != 1 || b.getInt(12) != 1 || b.getInt(32) != 5) return null
            val unpacked = b.getInt(16).toLong() and 0xffffffffL
            val packed = b.getInt(20).toLong() and 0xffffffffL
            val props = h[36].toInt() and 0xff
            val dict = b.getInt(37)
            if (unpacked < 0x34 || props >= 9 * 5 * 5 || dict <= 0) return null
            Block(off, unpacked, packed, props, dict)
        }
    }.getOrNull()

    /**
     * Replaces [file] by the file its block holds. The new file is written next to it and checked (an ELF of the
     * size the block names) before it takes the name. @return the bytes written, or -1 if it was not unpacked
     */
    fun unpack(file: File, blk: Block): Long {
        val tmp = File(file.parentFile, file.name + ".aemu-unpack")
        try {
            FileInputStream(file).use { raw ->
                raw.channel.position(blk.offset + HEADER)
                LZMAInputStream(BufferedInputStream(raw, 1 shl 16), blk.unpacked, blk.props.toByte(), blk.dictSize).use { z ->
                    tmp.outputStream().use { out -> z.copyTo(out, 1 shl 16) }
                }
            }
            val head = ByteArray(4).also { b -> tmp.inputStream().use { it.read(b) } }
            if (tmp.length() != blk.unpacked || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte()) { tmp.delete(); return -1 }
            tmp.setReadable(true, false)
            file.setWritable(true, false)
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) { tmp.delete(); return -1 } }
            return blk.unpacked
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Every packed odex/oat file under [system] unpacked in place. Folders the app write-protects are opened for it
     * (TreeFixer.protectOat closes them again right after). Nothing is done when the space for all of it is not there.
     */
    fun unpackTree(system: File, log: (String) -> Unit) {
        val found = ArrayList<Pair<File, Block>>()
        fun walk(d: File, depth: Int) {
            if (depth > 6 || d.name == ".aemu-oat") return
            d.listFiles()?.forEach { f ->
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.isFile && (f.name.endsWith(".odex") || f.name.endsWith(".oat")) && f.length() >= HEADER) block(f)?.let { found += f to it }
            }
        }
        for (top in listOf("framework", "app", "priv-app", "vendor/app", "vendor/framework")) File(system, top).takeIf { it.isDirectory }?.let { walk(it, 0) }
        if (found.isEmpty()) return
        val need = found.sumOf { (f, b) -> b.unpacked - f.length() }
        val free = system.usableSpace
        if (free < need + 256L * 1024 * 1024) {
            log("✖ Sony packed precompiled code: ${found.size} files need ${need shr 20} MB more to unpack, ${free shr 20} MB free; system_server will not start until there is room")
            return
        }
        log("Sony packed precompiled code (codx): unpacking ${found.size} files (${need shr 20} MB more)")
        val t0 = System.currentTimeMillis()
        var done = 0
        var failed = 0
        for ((f, b) in found) {
            f.parentFile?.setWritable(true, false)
            val r = runCatching { unpack(f, b) }.getOrElse { log("codx: ${f.name}: ${it.message}"); -1L }
            if (r > 0) done++ else failed++
        }
        log("Sony packed precompiled code: $done unpacked" + (if (failed > 0) ", $failed failed" else "") +
            " in ${(System.currentTimeMillis() - t0) / 1000} s")
    }

    private const val HEADER = 44
}
