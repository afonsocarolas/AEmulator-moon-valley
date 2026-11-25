package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Added for AEmulator Moon Valley, 2026-11-25: see SemcRil. */
class SemcRilTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(name: String, brand: String, maker: String, dex: String): File {
        val root = tmp.newFolder(name)
        File(root, "system/framework").mkdirs()
        File(root, "system/build.prop").writeText("ro.product.brand=$brand\nro.product.manufacturer=$maker\n")
        ZipOutputStream(File(root, "system/framework/framework.jar").outputStream()).use {
            it.putNextEntry(ZipEntry("classes.dex")); it.write(dex.toByteArray()); it.closeEntry()
        }
        return root
    }

    private val icc = "x\u0000Lcom/android/internal/telephony/RIL\$IccHandler;\u0000y"

    @Test fun whichFirmware() {
        assertTrue(SemcRil.uses(tree("a", "SEMC", "Sony Ericsson", icc), 10))
        assertFalse(SemcRil.uses(tree("b", "SEMC", "Sony Ericsson", "AOSP's RIL"), 10))
        assertFalse(SemcRil.uses(tree("c", "google", "samsung", icc), 10))
        assertFalse(SemcRil.uses(tree("d", "SEMC", "Sony Ericsson", icc), 15))
        assertFalse(SemcRil.uses(tmp.newFolder("e"), 10))
    }

    /** reads the parcel the way RIL.responseIccCardStatus of the R800i does: card state, GSM index, applications */
    private fun read(p: ByteArray): List<Int> {
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        b.int; val card = b.int; b.int
        var gsm = 0; repeat(b.int) { gsm = b.int }
        repeat(b.int) { b.int }
        val n = b.int
        var type = -1; var state = -1
        repeat(n) { type = b.int; state = b.int; b.int; assertEquals(-1, b.int); assertEquals(-1, b.int); b.int; b.int; b.int }
        assertFalse(b.hasRemaining())
        return listOf(card, gsm, n, type, state)
    }

    @Test fun cardStatus() {
        assertEquals(listOf(1, 0, 1, 1, 5), read(SemcRil.simStatus(true)))
        assertEquals(listOf(0, 0, 0, -1, -1), read(SemcRil.simStatus(false)))
    }

    private fun request(aid: String?): ByteBuffer {
        val b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(7).putInt(9)                       // request and serial, read already
        b.putInt(0)
        if (aid == null) b.putInt(-1) else {
            b.putInt(aid.length); aid.forEach { b.putChar(it) }; b.putChar('\u0000')
            while (b.position() % 4 != 0) b.put(0)
        }
        b.putInt(0xb0).putInt(0x2fe2).putInt(-1).putInt(0).putInt(0).putInt(10)
        b.flip(); b.position(8)
        return b
    }

    @Test fun simIoWithAndWithoutAid() {
        for (aid in listOf(null, "", "A000", "A0000000871002")) {
            val b = request(aid)
            val r = LegacySimIo.handle(SemcRil.simIo(b))
            assertEquals(0x90, r.sw1)
            assertEquals(LegacySimIo.ICCID, r.hex!!.chunked(2).joinToString("") { "${it[1]}${it[0]}" })
            assertEquals(8, b.position())
        }
        assertEquals(0x86, LegacySimIo.handle(SemcRil.simIo(ByteBuffer.allocate(4))).sw2)
    }

    @Test fun theRealFramework() {
        val jar = System.getenv("AEMU_TEST_R800I_FW")?.let { File(it) }
        assumeTrue(jar != null && jar.isFile)
        val root = tree("r", "SEMC", "Sony Ericsson", "")
        jar!!.copyTo(File(root, "system/framework/framework.jar"), overwrite = true)
        assertTrue(SemcRil.uses(root, 10))
    }
}
