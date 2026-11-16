package app.aemu.core

import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Added for AEmulator Moon Valley, 2026-11-16. Fixtures: javac --release 8 + d8 (build-tools 36.0.0, --min-api 26).
 * Both have android.hardware.fingerprint.FingerprintManager (hasEnrolledFingerprints() and (int)) and a
 * LockSettingsService that calls hasEnrolledFingerprints(int) behind a null check, as AOSP may. In [nokia]
 * ActivityManagerService.systemReady() calls hasEnrolledFingerprints() unchecked after a switch (HMD's Nokia G60 5G);
 * in [stock] it does not touch the fingerprint manager at all.
 */
class UncheckedManagersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val nokia = Base64.getDecoder().decode(
        "ZGV4CjAzOABI7HwqHzmCE7u6kNQgmR1ajWkDwjzGH9HoBQAAcAAAAHhWNBIAAAAAAAAAAEgFAAAWAAAAcAAAAAgAAADIAAAABQAA" +
        "AOgAAAACAAAAJAEAAAkAAAA0AQAAAwAAAHwBAAAMBAAA3AEAAAQDAAAMAwAAKQMAAEIDAABFAwAAeAMAAKgDAADfAwAA8wMAAAkE" +
        "AAAjBAAAJgQAACoEAAAtBAAAMQQAADYEAAA9BAAAVgQAAFsEAABjBAAAaAQAAHUEAAADAAAABAAAAAUAAAAGAAAABwAAAAgAAAAK" +
        "AAAADAAAAAoAAAAGAAAAAAAAAAsAAAAGAAAA7AIAAAwAAAAHAAAAAAAAAA0AAAAHAAAA9AIAAA4AAAAHAAAA/AIAAAIAAQARAAAA" +
        "AgAAABIAAAABAAAAAAAAAAEAAgAQAAAAAQADABAAAAACAAAAAAAAAAIAAQAUAAAAAwAAAAAAAAADAAQADwAAAAQAAAAAAAAABQAA" +
        "ABMAAAABAAAAAQAAAAQAAAAAAAAAAgAAAAAAAAAVBQAAAAAAAAIAAAABAAAABAAAAAAAAAABAAAAAAAAACcFAAAAAAAAAwAAAAEA" +
        "AAAEAAAAAAAAAAkAAAAAAAAAOQUAAAAAAAACAAEAAAAAANACAAACAAAAEgAPAAIAAgAAAAAA1AIAAAIAAAASAQ8BAQABAAEAAADQ" +
        "AgAABAAAAHAQBwAAAA4AAQABAAEAAADZAgAABAAAAHAQBwAAAA4AAwACAAEAAADdAgAAJgAAAFIQAQAsABoAAAASECgFEwAJACgC" +
        "EiBZEAEAVBAAAG4QAQAAAAoAOAAFABIwWRABAHIQCAACAA4AAAICAAAAAAAFAAAACAAAAAUAAAADAAMAAgAAAOUCAAAMAAAAOAEK" +
        "AG4gAgAhAAoBOAEEABIRKAISAQ8BAQABAAEAAADZAgAABAAAAHAQBwAAAA4AAgAOAAIBAA4AAwAOAAYBAA7StDwAAwIAAA4AAAEA" +
        "AAAFAAAAAQAAAAAAAAACAAAAAQAAAAY8aW5pdD4AG0FjdGl2aXR5TWFuYWdlclNlcnZpY2UuamF2YQAXRmluZ2VycHJpbnRNYW5h" +
        "Z2VyLmphdmEAAUkAMUxhbmRyb2lkL2hhcmR3YXJlL2ZpbmdlcnByaW50L0ZpbmdlcnByaW50TWFuYWdlcjsALkxjb20vYW5kcm9p" +
        "ZC9zZXJ2ZXIvYW0vQWN0aXZpdHlNYW5hZ2VyU2VydmljZTsANUxjb20vYW5kcm9pZC9zZXJ2ZXIvbG9ja3NldHRpbmdzL0xvY2tT" +
        "ZXR0aW5nc1NlcnZpY2U7ABJMamF2YS9sYW5nL09iamVjdDsAFExqYXZhL2xhbmcvUnVubmFibGU7ABhMb2NrU2V0dGluZ3NTZXJ2" +
        "aWNlLmphdmEAAVYAAlZMAAFaAAJaSQADWkxJAAVjaGVjawAXaGFzRW5yb2xsZWRGaW5nZXJwcmludHMAA21GbQAGbVN0YXRlAANy" +
        "dW4AC3N5c3RlbVJlYWR5AJ0Bfn5EOHsiYmFja2VuZCI6ImRleCIsImNvbXBpbGF0aW9uLW1vZGUiOiJkZWJ1ZyIsImhhcy1jaGVj" +
        "a3N1bXMiOmZhbHNlLCJtaW4tYXBpIjoyNiwic2hhLTEiOiJhN2FkMThhNzA0NjBiNzk5ZDA0ODJlNDk3YzEwOWE3NWJmN2Y5MWRl" +
        "IiwidmVyc2lvbiI6IjguMTAuOS1kZXYifQAAAAECAIGABIQEAQHcAwEB8AMAAgEBAAABAAOBgAScBAQBtAQAAAEBBYGABLgFBgCQ" +
        "BQANAAAAAAAAAAEAAAAAAAAAAQAAABYAAABwAAAAAgAAAAgAAADIAAAAAwAAAAUAAADoAAAABAAAAAIAAAAkAQAABQAAAAkAAAA0" +
        "AQAABgAAAAMAAAB8AQAAASAAAAcAAADcAQAAAyAAAAUAAADQAgAAARAAAAMAAADsAgAAAiAAABYAAAAEAwAAACAAAAMAAAAVBQAA" +
        "ABAAAAEAAABIBQAA")
    private val stock = Base64.getDecoder().decode(
        "ZGV4CjAzOACi7NuFBQiPdmEuj9/JY7w0yA1TqcQQdSSsBQAAcAAAAHhWNBIAAAAAAAAAAAwFAAAVAAAAcAAAAAgAAADEAAAABQAA" +
        "AOQAAAABAAAAIAEAAAkAAAAoAQAAAwAAAHABAADcAwAA0AEAANACAADYAgAA9QIAAA4DAAARAwAARAMAAHQDAACrAwAAvwMAANUD" +
        "AADvAwAA8gMAAPYDAAD5AwAA/QMAAAIEAAAJBAAAIgQAACoEAAAvBAAAPAQAAAMAAAAEAAAABQAAAAYAAAAHAAAACAAAAAoAAAAM" +
        "AAAACgAAAAYAAAAAAAAACwAAAAYAAAC4AgAADAAAAAcAAAAAAAAADQAAAAcAAADAAgAADgAAAAcAAADIAgAAAgAAABEAAAABAAAA" +
        "AAAAAAEAAgAQAAAAAQADABAAAAACAAAAAAAAAAIAAQATAAAAAwAAAAAAAAADAAQADwAAAAQAAAAAAAAABQAAABIAAAABAAAAAQAA" +
        "AAQAAAAAAAAAAgAAAAAAAADcBAAAAAAAAAIAAAABAAAABAAAAAAAAAABAAAAAAAAAO4EAAAAAAAAAwAAAAEAAAAEAAAAAAAAAAkA" +
        "AAAAAAAA/gQAAAAAAAACAAEAAAAAAKACAAACAAAAEgAPAAIAAgAAAAAApAIAAAIAAAASAQ8BAQABAAEAAACgAgAABAAAAHAQBwAA" +
        "AA4AAQABAAEAAACgAgAABAAAAHAQBwAAAA4AAwACAAEAAACpAgAAFAAAAFIQAAArAAwAAAASECgCEiBZEAAAchAIAAIADgAAAQEA" +
        "AAAAAAUAAAADAAMAAgAAAK4CAAAMAAAAOAEKAG4gAgAhAAoBOAEEABIRKAISAQ8BAQABAAEAAAC0AgAABAAAAHAQBwAAAA4AAgAO" +
        "AAIBAA4ABAEADgADAgAADgADAA4AAQAAAAUAAAABAAAAAAAAAAIAAAABAAAABjxpbml0PgAbQWN0aXZpdHlNYW5hZ2VyU2Vydmlj" +
        "ZS5qYXZhABdGaW5nZXJwcmludE1hbmFnZXIuamF2YQABSQAxTGFuZHJvaWQvaGFyZHdhcmUvZmluZ2VycHJpbnQvRmluZ2VycHJp" +
        "bnRNYW5hZ2VyOwAuTGNvbS9hbmRyb2lkL3NlcnZlci9hbS9BY3Rpdml0eU1hbmFnZXJTZXJ2aWNlOwA1TGNvbS9hbmRyb2lkL3Nl" +
        "cnZlci9sb2Nrc2V0dGluZ3MvTG9ja1NldHRpbmdzU2VydmljZTsAEkxqYXZhL2xhbmcvT2JqZWN0OwAUTGphdmEvbGFuZy9SdW5u" +
        "YWJsZTsAGExvY2tTZXR0aW5nc1NlcnZpY2UuamF2YQABVgACVkwAAVoAAlpJAANaTEkABWNoZWNrABdoYXNFbnJvbGxlZEZpbmdl" +
        "cnByaW50cwAGbVN0YXRlAANydW4AC3N5c3RlbVJlYWR5AJ0Bfn5EOHsiYmFja2VuZCI6ImRleCIsImNvbXBpbGF0aW9uLW1vZGUi" +
        "OiJkZWJ1ZyIsImhhcy1jaGVja3N1bXMiOmZhbHNlLCJtaW4tYXBpIjoyNiwic2hhLTEiOiJhN2FkMThhNzA0NjBiNzk5ZDA0ODJl" +
        "NDk3YzEwOWE3NWJmN2Y5MWRlIiwidmVyc2lvbiI6IjguMTAuOS1kZXYifQAAAAECAIGABPgDAQHQAwEB5AMAAQEBAAADgYAEkAQE" +
        "AagEAAABAQWBgASIBQYA4AQNAAAAAAAAAAEAAAAAAAAAAQAAABUAAABwAAAAAgAAAAgAAADEAAAAAwAAAAUAAADkAAAABAAAAAEA" +
        "AAAgAQAABQAAAAkAAAAoAQAABgAAAAMAAABwAQAAASAAAAcAAADQAQAAAyAAAAUAAACgAgAAARAAAAMAAAC4AgAAAiAAABUAAADQ" +
        "AgAAACAAAAMAAADcBAAAABAAAAEAAAAMBQAA")

    private fun calls(d: ByteArray) = UncheckedManagers.dexCalls(d, UncheckedManagers.AMS, UncheckedManagers.FINGERPRINT_MANAGER, "hasEnrolledFingerprints")

    @Test fun nokiaStyleCallIsFound() = assertTrue(calls(nokia))

    @Test fun anotherClassCallingItDoesNotCount() = assertFalse(calls(stock))

    @Test fun notADex() = assertFalse(calls(ByteArray(300)))

    private fun root(dex: ByteArray): File {
        val r = tmp.newFolder()
        val jar = File(r, "system/framework/services.jar").apply { parentFile.mkdirs() }
        ZipOutputStream(jar.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("classes.dex")); z.write(dex); z.closeEntry()
        }
        return r
    }

    @Test fun jarAndCache() {
        val r = root(nokia)
        assertTrue(UncheckedManagers.fingerprintNeeded(r))
        val cache = File(r, "system/.aemu-fingerprint-check")
        assertTrue(cache.readText().endsWith("=1"))
        // the cached answer is used while the jar is the same one
        cache.writeText(cache.readText().replace("=1", "=0"))
        assertFalse(UncheckedManagers.fingerprintNeeded(r))
    }

    @Test fun stockJarIsNotNeeded() {
        val r = root(stock)
        assertFalse(UncheckedManagers.fingerprintNeeded(r))
        assertEquals(true, File(r, "system/.aemu-fingerprint-check").readText().endsWith("=0"))
    }

    @Test fun noServicesJar() = assertFalse(UncheckedManagers.fingerprintNeeded(tmp.newFolder()))
}
