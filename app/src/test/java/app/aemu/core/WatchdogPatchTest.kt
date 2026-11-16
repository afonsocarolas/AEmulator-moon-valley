package app.aemu.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A tiny dex with com.android.server.Watchdog.run()V, built here so that the test needs no binary fixture. */
class WatchdogPatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun mutf(s: String): ByteArray = ByteArrayOutputStream().apply { write(s.length); write(s.toByteArray(Charsets.UTF_8)); write(0) }.toByteArray()

    /** [kill] is the Watchdog's kill message; null leaves it out (a Watchdog this patch does not know). */
    private fun dex(kill: String? = "*** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler"): ByteArray {
        val strings = listOfNotNull("V", "Lcom/android/server/Watchdog;", "run", kill)
        val hdr = 0x70
        val stringIdsOff = hdr
        val typeIdsOff = stringIdsOff + 4 * strings.size
        val protoIdsOff = typeIdsOff + 4 * 2
        val methodIdsOff = protoIdsOff + 12
        val classDefsOff = methodIdsOff + 8
        val dataOff = classDefsOff + 32
        val strData = strings.map { mutf(it) }
        val strOffs = ArrayList<Int>(); var o = dataOff
        for (sd in strData) { strOffs += o; o += sd.size }
        val classDataOff = o
        val classDataLen = 8                       // 4 sizes, then one method: diff, access, code_off (two uleb bytes)
        val codeOff = (classDataOff + classDataLen + 3) and 3.inv()
        require(codeOff < 1 shl 14)
        val total = codeOff + 20
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        b.putInt(32, total); b.putInt(36, hdr); b.putInt(40, 0x12345678)
        b.putInt(56, strings.size); b.putInt(60, stringIdsOff); b.putInt(64, 2); b.putInt(68, typeIdsOff); b.putInt(72, 1); b.putInt(76, protoIdsOff)
        b.putInt(88, 1); b.putInt(92, methodIdsOff); b.putInt(96, 1); b.putInt(100, classDefsOff); b.putInt(104, total - dataOff); b.putInt(108, dataOff)
        for ((i, so) in strOffs.withIndex()) b.putInt(stringIdsOff + 4 * i, so)
        b.putInt(typeIdsOff, 1); b.putInt(typeIdsOff + 4, 0)                 // type 0 = Watchdog (string 1), type 1 = V (string 0)
        b.putInt(protoIdsOff, 0); b.putInt(protoIdsOff + 4, 1); b.putInt(protoIdsOff + 8, 0)   // shorty "V", returns type 1 (V)
        b.putShort(methodIdsOff, 0); b.putShort(methodIdsOff + 2, 0); b.putInt(methodIdsOff + 4, 2)   // class type 0, proto 0, name "run"
        b.putInt(classDefsOff, 0); b.putInt(classDefsOff + 4, 1); b.putInt(classDefsOff + 8, -1); b.putInt(classDefsOff + 16, -1); b.putInt(classDefsOff + 24, classDataOff)
        var p = dataOff; for (sd in strData) { b.position(p); b.put(sd); p += sd.size }
        b.position(classDataOff)
        b.put(0); b.put(0); b.put(0); b.put(1)                                // static, instance, direct, virtual counts
        b.put(0); b.put(1); b.put((codeOff and 0x7f or 0x80).toByte()); b.put((codeOff ushr 7).toByte())
        b.putShort(codeOff, 1); b.putShort(codeOff + 2, 1); b.putShort(codeOff + 4, 0); b.putShort(codeOff + 6, 0); b.putInt(codeOff + 8, 0)
        b.putInt(codeOff + 12, 2)                                             // insns_size: const/4 v0, #0 ; return-void
        b.put(codeOff + 16, 0x12); b.put(codeOff + 17, 0); b.put(codeOff + 18, 0x0e); b.put(codeOff + 19, 0)
        val d = b.array()
        DexTools.fixHeader(d)
        return d
    }

    private fun headerValid(d: ByteArray): Boolean {
        val ck = (0..3).fold(0) { a, k -> a or ((d[8 + k].toInt() and 255) shl (8 * k)) }
        return Adler32().apply { update(d, 12, d.size - 12) }.value.toInt() == ck &&
            MessageDigest.getInstance("SHA-1").digest(d.copyOfRange(32, d.size)).contentEquals(d.copyOfRange(12, 32))
    }

    private fun firstInsn(d: ByteArray): Int {
        val x = DexTools.Dex(d)
        return DexTools.methodCode(x, "run", "Lcom/android/server/Watchdog;", voidOnly = true)!![0] + 16
    }

    @Test fun fixtureIsValid() { assertTrue(headerValid(dex())); firstInsn(dex()) }

    @Test fun watchdogRunReturnsAtOnce() {
        val d = dex(); val at = firstInsn(d)
        assertEquals(WatchdogPatch.Outcome.PATCHED, WatchdogPatch.patchDex(d).outcome)
        assertEquals(0x0e, d[at].toInt()); assertEquals(0, d[at + 1].toInt())
        assertTrue(headerValid(d))
    }

    @Test fun secondRunIsAlready() {
        val d = dex(); WatchdogPatch.patchDex(d)
        assertEquals(WatchdogPatch.Outcome.ALREADY, WatchdogPatch.patchDex(d).outcome)
    }

    @Test fun aWatchdogWithoutItsKillMessageIsNotTouched() {
        val d = dex(kill = null); val before = d.clone()
        assertEquals(WatchdogPatch.Outcome.UNEXPECTED, WatchdogPatch.patchDex(d).outcome)
        assertArrayEquals(before, d)
    }

    @Test fun otherFilesAreAbsent() = assertEquals(WatchdogPatch.Outcome.ABSENT, WatchdogPatch.patchDex(ByteArray(300)).outcome)

    private fun jar(stored: Boolean, extra: String? = null): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            val d = dex()
            val e = ZipEntry("classes.dex")
            if (stored) { e.method = ZipEntry.STORED; e.size = d.size.toLong(); e.compressedSize = d.size.toLong(); e.crc = CRC32().apply { update(d) }.value }
            z.putNextEntry(e); z.write(d); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write((extra ?: "Manifest-Version: 1.0\n").toByteArray()); z.closeEntry()
        }
        return f
    }

    private fun roundTrip(stored: Boolean) {
        val f = jar(stored); val bak = File(f.path + ".orig"); val stock = f.readBytes()
        assertEquals(WatchdogPatch.Outcome.PATCHED, WatchdogPatch.patchJar(f, bak).outcome)
        assertArrayEquals(stock, bak.readBytes())
        ZipFile(f).use { z ->
            val e = z.getEntry("classes.dex")
            assertEquals(stored, e.method == ZipEntry.STORED)
            val d = z.getInputStream(e).readBytes()
            assertTrue(headerValid(d)); assertEquals(0x0e, d[firstInsn(d)].toInt())
        }
        assertEquals(WatchdogPatch.Outcome.ALREADY, WatchdogPatch.patchJar(f, bak).outcome)
    }

    @Test fun storedJar() = roundTrip(true)
    @Test fun deflatedJar() = roundTrip(false)

    @Test fun stockPlusPatch_isRecognisedAndAnythingElseIsNot() {
        val f = jar(true); val stock = File(f.path + ".orig")
        WatchdogPatch.patchJar(f, stock)
        assertTrue(WatchdogPatch.isStockPlusPatch(f, stock))
        // a jar some other build edited as well (here: another manifest) is not "stock plus this patch"
        val other = jar(true, extra = "Manifest-Version: 1.0\nX-Other-Edit: 1\n"); WatchdogPatch.patchJar(other, null)
        assertFalse(WatchdogPatch.isStockPlusPatch(other, stock))
        // and the stock jar itself is not either: it still has to be patched
        assertFalse(WatchdogPatch.isStockPlusPatch(stock, stock))
        assertFalse(File(f.parentFile, f.name + ".aemu-chk").exists())
    }

    // Added for AEmulator Moon Valley (2026-11-16): an optimised services.jar (Samsung One UI 8, Android 16) has no
    // Watchdog.run(); the loop is inlined into Watchdog$$ExternalSyntheticLambda0.run(). Fixture: javac --release 8 + d8
    // (build-tools 36.0.0, --min-api 26) of a Watchdog whose thread runs Lambda0 (the loop, with a switch before the kill
    // message) and a second Lambda1.run() without the message, which must stay as it is.
    private val inlinedDex: String =
        "ZGV4CjAzOACSXpbBKDQJSHHyvN+w4T2JXn595VsY4HS8CAAAcAAAAHhWNBIAAAAAAAAAABwIAAArAAAAcAAAAA4AAAAcAQAACAAA" +
        "AFQBAAAGAAAAtAEAAA8AAADkAQAAAwAAAFwCAAAABgAAvAIAAOgEAAAPBQAAFwUAABoFAAAdBQAAIAUAACQFAAAoBQAAYQUAAJoF" +
        "AAC5BQAA0AUAAOQFAAD6BQAADgYAACkGAAA9BgAAUQYAAFQGAABYBgAAXQYAAIYGAACvBgAAvgYAAMIGAADFBgAA0QYAANkGAADc" +
        "BgAA3wYAAOIGAADlBgAA6gYAAPIGAAD6BgAAAwcAAAgHAAARBwAAFgcAAB0HAAAnBwAAMQcAAEEHAAACAAAAAwAAAAcAAAAIAAAA" +
        "CQAAAAoAAAALAAAADAAAAA0AAAAOAAAADwAAABAAAAARAAAAFwAAAAQAAAAIAAAAAAAAAAUAAAAJAAAAwAQAAAYAAAAJAAAAyAQA" +
        "ABEAAAAMAAAAAAAAABIAAAAMAAAA0AQAABIAAAAMAAAA2AQAABMAAAAMAAAA4AQAABIAAAAMAAAAyAQAAAIABAAfAAAAAwAEAB8A" +
        "AAAEAAcAIAAAAAQAAAAhAAAABAALACIAAAAKAAUAIwAAAAIABAABAAAAAgADACUAAAADAAQAAQAAAAMAAwAlAAAABAADAAEAAAAE" +
        "AAUAGQAAAAQAAwAmAAAABQAHACQAAAAGAAMAAQAAAAkAAwABAAAACQABABoAAAAJAAIAGgAAAAkAAAAnAAAACwAGAAEAAAALAAMA" +
        "JgAAAAIAAAAQAAAABgAAALgEAAAUAAAAAAAAAOEHAAAAAAAAAwAAABAAAAAGAAAAuAQAABUAAAAAAAAA8QcAAAAAAAAEAAAAAQAA" +
        "AAYAAAAAAAAAFgAAAAAAAAABCAAAAAAAAAIAAgABAAAAkAQAAAYAAABwEAgAAABbAQAADgAHAAEAAwAAAJUEAABwAAAAEjAjAQ0A" +
        "JgFdAAAAVGIAAFIiAwAsAkQAAAAaAh4AKAwaAh0AKAkaAhwAKAYaAhsAKAMaAhgAVGMAAFIzAwA3AyYAYgMFACIECQBwEAkABAAa" +
        "BQAAbiALAFQADARuIAsAJAAMAlRkAABSRAMAtARFAAEEbjAKAAIBDABuEAwAAAAMAG4gBwADAA4AVGIAAFIjAwDYAwMBWSMDACi5" +
        "AAIEAAAAAAABAAAAAgAAAAcAAAAPAAAADAAAAAkAAAAGAAAAAAMIAAMAAAAwdQAAAAAAAGDqAAAAAAAAkF8BAAAAAAACAAIAAQAA" +
        "AJAEAAAGAAAAcBAIAAAAWwEBAA4AAwABAAIAAACjBAAADQAAAFQgAQASAVkBAwBiAAUAGgEpAG4gBwAQAA4AAAAEAAEAAwAAAKcE" +
        "AAAZAAAAcBAIAAMAIgALACIBAgBwIAAAMQAaAigAcDANABACWzAEACIAAwBwIAIAMABbMAIADgAAAAIAAgAAAAAArQQAAAcAAABS" +
        "AQMA2AEBAVkBAwAOAAAAAgABAAEAAACyBAAABgAAAFQQBABuEA4AAAAOAAQBAA4ABgAOawEVD2kBIw8fhwAFAA4AAgAOPOEABwEA" +
        "DgAGAA4AAAABAAAABwAAAAEAAAABAAAAAQAAAAgAAAABAAAABAAAAAEAAAAGAAAAAgAAAAcACAAlKioqIFdBVENIRE9HIEtJTExJ" +
        "TkcgU1lTVEVNIFBST0NFU1M6IAAGPGluaXQ+AAFJAAFKAAFMAAJMSgACTEwAN0xjb20vYW5kcm9pZC9zZXJ2ZXIvV2F0Y2hkb2ck" +
        "JEV4dGVybmFsU3ludGhldGljTGFtYmRhMDsAN0xjb20vYW5kcm9pZC9zZXJ2ZXIvV2F0Y2hkb2ckJEV4dGVybmFsU3ludGhldGlj" +
        "TGFtYmRhMTsAHUxjb20vYW5kcm9pZC9zZXJ2ZXIvV2F0Y2hkb2c7ABVMamF2YS9pby9QcmludFN0cmVhbTsAEkxqYXZhL2xhbmcv" +
        "T2JqZWN0OwAUTGphdmEvbGFuZy9SdW5uYWJsZTsAEkxqYXZhL2xhbmcvU3RyaW5nOwAZTGphdmEvbGFuZy9TdHJpbmdCdWlsZGVy" +
        "OwASTGphdmEvbGFuZy9TeXN0ZW07ABJMamF2YS9sYW5nL1RocmVhZDsAAVYAAlZMAANWTEwAJ1dhdGNoZG9nJCRFeHRlcm5hbFN5" +
        "bnRoZXRpY0xhbWJkYTAuamF2YQAnV2F0Y2hkb2ckJEV4dGVybmFsU3ludGhldGljTGFtYmRhMS5qYXZhAA1XYXRjaGRvZy5qYXZh" +
        "AAJbSgABYQAKYWRkTW9uaXRvcgAGYXBwZW5kAAFiAAFjAAFkAAFlAANmJDAABm1PdGhlcgAGbVN0YXRlAAdtVGhyZWFkAANvdXQA" +
        "B3ByaW50bG4AA3J1bgAFc3RhcnQACHRvU3RyaW5nAAh3YXRjaGRvZwAOd2F0Y2hkb2cgcmVzZXQAnQF+fkQ4eyJiYWNrZW5kIjoi" +
        "ZGV4IiwiY29tcGlsYXRpb24tbW9kZSI6ImRlYnVnIiwiaGFzLWNoZWNrc3VtcyI6ZmFsc2UsIm1pbi1hcGkiOjI2LCJzaGEtMSI6" +
        "ImE3YWQxOGE3MDQ2MGI3OTlkMDQ4MmU0OTdjMTA5YTc1YmY3ZjkxZGUiLCJ2ZXJzaW9uIjoiOC4xMC45LWRldiJ9AAABAQEAEACA" +
        "gAS8BQER2AUAAQEBARACgIAEyAcDEeQHAAMBAgIQAQABEASBgASQCAUB1AgBAfQIAAAADQAAAAAAAAABAAAAAAAAAAEAAAArAAAA" +
        "cAAAAAIAAAAOAAAAHAEAAAMAAAAIAAAAVAEAAAQAAAAGAAAAtAEAAAUAAAAPAAAA5AEAAAYAAAADAAAAXAIAAAEgAAAHAAAAvAIA" +
        "AAMgAAAGAAAAkAQAAAEQAAAGAAAAuAQAAAIgAAArAAAA6AQAAAAgAAADAAAA4QcAAAAQAAABAAAAHAgAAA=="

    private fun inlined(): ByteArray = java.util.Base64.getDecoder().decode(inlinedDex)

    private fun runCode(d: ByteArray, cls: String): Int =
        DexTools.methodCodesInClasses(DexTools.Dex(d), "run", cls).single().second + 16

    @Test fun inlinedLoopInTheLambdaIsPatched() {
        val d = inlined()
        val loop = runCode(d, "Lcom/android/server/Watchdog\$\$ExternalSyntheticLambda0;")
        val other = runCode(d, "Lcom/android/server/Watchdog\$\$ExternalSyntheticLambda1;")
        val otherBefore = d.copyOfRange(other, other + 8)
        assertTrue(d[loop].toInt() != 0x0e)
        val r = WatchdogPatch.patchDex(d)
        assertEquals(WatchdogPatch.Outcome.PATCHED, r.outcome)
        assertTrue(r.detail, r.detail.contains("ExternalSyntheticLambda0"))
        assertEquals(0x0e, d[loop].toInt()); assertEquals(0, d[loop + 1].toInt())
        assertArrayEquals(otherBefore, d.copyOfRange(other, other + 8))
        assertTrue(headerValid(d))
        assertEquals(WatchdogPatch.Outcome.ALREADY, WatchdogPatch.patchDex(d).outcome)
    }

    @Test fun inlinedDexWithoutKillMessageIsNotTouched() {
        val d = inlined()
        // break the message in place (same length), so no string starts with the mark any more
        val mark = WatchdogPatch.KILL_MARK.toByteArray(Charsets.UTF_8)
        val at = (0..d.size - mark.size).first { i -> mark.indices.all { d[i + it] == mark[it] } }
        d[at] = '#'.code.toByte(); DexTools.fixHeader(d)
        val before = d.clone()
        assertEquals(WatchdogPatch.Outcome.UNEXPECTED, WatchdogPatch.patchDex(d).outcome)
        assertArrayEquals(before, d)
    }
}
