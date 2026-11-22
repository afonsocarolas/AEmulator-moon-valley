package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Added for AEmulator Moon Valley, 2026-11-22: Sony's packed odex files (see SonyCodx). */
class SonyCodxTest {
    @get:Rule val tmp = TemporaryFolder()

    /** an ELF32 stub with one section, at 0x100, holding a codx block of [inner] */
    private fun packed(inner: ByteArray): ByteArray {
        val opts = LZMA2Options()
        val z = ByteArrayOutputStream()
        LZMAOutputStream(z, opts, false).use { it.write(inner) }   // no end marker, like Sony's
        val data = z.toByteArray()
        val props = (opts.pb * 5 + opts.lp) * 9 + opts.lc
        val blockAt = 0x100
        val shoff = blockAt + 44 + data.size
        val b = ByteBuffer.allocate(shoff + 40).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1, 1))
        b.putInt(0x20, shoff); b.putShort(0x2e, 40); b.putShort(0x30, 1)
        b.position(blockAt)
        b.put("codx".toByteArray()); b.putInt(0); b.putInt(1); b.putInt(1)
        b.putInt(inner.size); b.putInt(44 + data.size); b.putInt(0); b.putInt(0); b.putInt(5)
        b.put(props.toByte()); b.putInt(opts.dictSize); b.put(ByteArray(3))
        b.put(data)
        b.putInt(shoff + 16, blockAt); b.putInt(shoff + 20, 44 + data.size)
        return b.array()
    }

    private fun innerElf(): ByteArray {
        val r = java.util.Random(7)
        val x = ByteArray(300_000) { i -> if (i % 7 == 0) r.nextInt(256).toByte() else (i / 13).toByte() }
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()).copyInto(x)
        return x
    }

    @Test fun unpacksInPlace() {
        val inner = innerElf()
        val f = File(tmp.newFolder("system", "framework", "arm"), "services.odex").apply { writeBytes(packed(inner)) }
        val blk = SonyCodx.block(f)
        assertNotNull(blk)
        assertEquals(inner.size.toLong(), blk!!.unpacked)
        assertEquals(0x5d, blk.props)
        assertEquals(inner.size.toLong(), SonyCodx.unpack(f, blk))
        assertArrayEquals(inner, f.readBytes())
        assertNull(SonyCodx.block(f))                    // done once: the real file is no pack
    }

    @Test fun treeWalkSkipsPlainFiles() {
        val sys = tmp.newFolder("system")
        val inner = innerElf()
        val a = File(File(sys, "app/Foo/arm").apply { mkdirs() }, "Foo.odex").apply { writeBytes(packed(inner)) }
        val plain = File(File(sys, "framework/arm").apply { mkdirs() }, "boot.oat").apply { writeBytes(inner) }
        val log = StringBuilder()
        SonyCodx.unpackTree(sys) { log.appendLine(it) }
        assertArrayEquals(inner, a.readBytes())
        assertArrayEquals(inner, plain.readBytes())
        assert(log.contains("1 unpacked")) { log }
    }

    /** the real thing, when a services.odex of the Xperia C3 firmware is at hand (CODX_SAMPLE=path) */
    @Test fun realSonyFile() {
        val src = File(System.getenv("CODX_SAMPLE") ?: "")
        assumeTrue(src.isFile)
        val f = File(tmp.newFolder("real"), "services.odex").apply { writeBytes(src.readBytes()) }
        val blk = SonyCodx.block(f)!!
        assertEquals(blk.unpacked, SonyCodx.unpack(f, blk))
        val d = f.readBytes()
        val dex = String(d, 0, d.size, Charsets.ISO_8859_1).indexOf("dex\n035\u0000")
        val size = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getInt(dex + 32)
        assert(dex > 0 && dex + size <= d.size)
        assert(String(d, Charsets.ISO_8859_1).contains("Lcom/android/server/SystemServer;"))
    }
}
