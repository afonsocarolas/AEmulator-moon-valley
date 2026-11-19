package app.aemu.core

import java.io.File

/**
 * Added for AEmulator Plus, 2026-11-12. The stand-in composer (native/hwc1fb, see TreeFixer.framebufferComposer) goes into every
 * 4.2 - 4.4 image first. A vendor-modified SurfaceFlinger (MediaTek's, for one) can call composer entry points the stand-in does
 * not have and dies with SIGSEGV right after it starts. When that happens before the system has booted, the stand-in is taken out
 * again and the system is restarted without it; a marker file in the image directory remembers the verdict, so the image keeps
 * running on the framebuffer HAL alone (the same way QemuFallback remembers the translator choice).
 *
 * To try the stand-in again on an image: delete the file "hwc-standin-off" from its directory.
 *
 * Modified for AEmulator Moon Valley, 2026-11-19: the marker holds the revision of the stand-in it was written for
 * ([REVISION]; markers of older builds hold "1"). A caller may ask for one more try when the stand-in has changed since
 * ([disabled] with retryOlder): YotaPhone 2 firmware crashed with the stand-in that had no back screen (display 2), the
 * one that has it gets one chance; if surfaceflinger crashes again the new marker keeps it out for good.
 */
object HwcFallback {
    private const val MARKER = "hwc-standin-off"
    private const val NAME_STRING = "AEmulator framebuffer hwcomposer"
    /** the stand-in composer revision a marker refers to (2: the YotaPhone back screen as display 2) */
    const val REVISION = "2"

    private fun marker(paths: VmPaths) = File(paths.dir, MARKER)

    /**
     * a surfaceflinger crashed with the stand-in in place: it stays out of this image. With [retryOlder], a marker
     * written for an older stand-in does not count (the caller gives the current one a try).
     */
    fun disabled(paths: VmPaths, retryOlder: Boolean = false): Boolean {
        val m = marker(paths)
        if (!m.exists()) return false
        if (!retryOlder) return true
        return runCatching { m.readText().trim() }.getOrDefault("1") == REVISION
    }

    /** forgets an older verdict, so the current stand-in is installed again */
    fun clear(paths: VmPaths): Boolean = marker(paths).delete()

    fun disable(paths: VmPaths): Boolean = runCatching { marker(paths).writeText(REVISION); true }.getOrDefault(false)

    /** the stand-in composer of this engine (it carries its own name string), from this or an earlier build */
    fun isStandIn(f: File): Boolean = f.isFile && runCatching {
        !java.nio.file.Files.isSymbolicLink(f.toPath()) && String(f.readBytes(), Charsets.ISO_8859_1).contains(NAME_STRING)
    }.getOrDefault(false)

    /** the stand-in is in the guest's hw directory right now */
    fun installed(root: File): Boolean = isStandIn(File(root, "system/lib/hw/hwcomposer.default.so"))

    /** SIGSEGV (139) or SIGABRT (134) of surfaceflinger: what the vendor-composer mismatch looks like */
    fun isCrashExit(code: Int): Boolean = code == 139 || code == 134

    /** Takes the stand-in out of the tree (the live module and the copy sanitize() parks); true when something was removed. */
    fun remove(root: File): Boolean {
        var removed = false
        File(root, "system/lib/hw/hwcomposer.default.so").takeIf { isStandIn(it) }?.let { removed = it.delete() || removed }
        File(root, "system/.aemu-parked/system#lib#hw#hwcomposer.default.so").takeIf { isStandIn(it) }?.let { removed = it.delete() || removed }
        return removed
    }
}
