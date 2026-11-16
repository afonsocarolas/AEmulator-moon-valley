/* Added for AEmulator Moon Valley, 2026-11-16. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.util.zip.ZipFile

/**
 * Finds out whether a firmware's system_server uses a hardware manager without checking that it exists, so that the
 * engine keeps that hardware's feature declared instead of declaring it unavailable.
 *
 * The case that started it: a Nokia G60 5G (Android 13). HMD's ActivityManagerService.systemReady() calls
 * FingerprintManager.hasEnrolledFingerprints() unchecked. The engine declares fingerprint unavailable (a fingerprint
 * service without a sensor stalled another firmware's boot), FingerprintService is then never started,
 * getSystemService(FingerprintManager) answers null, system_server dies with a NullPointerException and zygote
 * follows with 137. AOSP's ActivityManagerService never touches the fingerprint manager, so the check is precise.
 *
 * Only the dex code of the named classes (and their nested classes) is read. No Android classes here, so it runs on
 * a plain JVM.
 */
object UncheckedManagers {
    const val AMS = "Lcom/android/server/am/ActivityManagerService;"
    const val FINGERPRINT_MANAGER = "Landroid/hardware/fingerprint/FingerprintManager;"

    private val dexName = Regex("classes\\d*\\.dex")

    /** Whether code of [caller] in the plain dex [d] invokes [method] of [owner]. */
    fun dexCalls(d: ByteArray, caller: String, owner: String, method: String): Boolean = runCatching {
        if (d.size < 112 || d[0] != 'd'.code.toByte() || d[1] != 'e'.code.toByte() || d[2] != 'x'.code.toByte()) return false
        val x = DexTools.Dex(d)
        val ids = DexTools.methodIds(x, owner, method)
        if (ids.isEmpty()) return false
        DexTools.classCodes(x, caller).any { DexTools.codeInvokes(d, it, ids) }
    }.getOrDefault(false)

    /** Whether any classes*.dex of [jar] has [caller] code that invokes [method] of [owner]. */
    fun jarCalls(jar: File, caller: String, owner: String, method: String): Boolean = runCatching {
        ZipFile(jar).use { z ->
            z.entries().asSequence().filter { !it.isDirectory && dexName.matches(it.name) }.any { e ->
                dexCalls(z.getInputStream(e).use { it.readBytes() }, caller, owner, method)
            }
        }
    }.getOrDefault(false)

    /**
     * Whether system_server's ActivityManagerService in [root] uses the fingerprint manager unchecked. The answer is
     * kept in system/.aemu-fingerprint-check (keyed by the jar's size and time) so the jar is read once, not at every boot.
     */
    fun fingerprintNeeded(root: File): Boolean {
        val jar = File(root, "system/framework/services.jar")
        if (!jar.isFile) return false
        val key = "${jar.length()}:${jar.lastModified()}"
        val cache = File(root, "system/.aemu-fingerprint-check")
        runCatching { cache.readText().split('=') }.getOrNull()?.let { if (it.size == 2 && it[0] == key) return it[1] == "1" }
        val r = jarCalls(jar, AMS, FINGERPRINT_MANAGER, "hasEnrolledFingerprints")
        runCatching { cache.writeText("$key=${if (r) 1 else 0}") }
        return r
    }
}
