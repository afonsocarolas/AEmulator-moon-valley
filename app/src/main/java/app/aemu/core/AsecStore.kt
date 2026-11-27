/* Added for AEmulator Moon Valley, 2026-11-27: apps installed to the SD card (secure containers). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/**
 * The secure containers vold makes for apps installed to the SD card (android:installLocation="preferExternal",
 * Android 2.2 and later). A real vold keeps each one as an encrypted image on the card and mounts it at
 * ASEC_MOUNTPOINT/<cid>; here each container is just a folder there, kept in the machine's own tree.
 *
 * Without them the package installer was told "created" for a container that never appeared, could not copy the APK
 * into it, and the install failed with "Application not installed" (Minecraft - Pocket Edition 0.8.1 on the R800i).
 */
class AsecStore(private val dir: File, private val guestDir: String, private val log: (String) -> Unit = {}) {

    /** [args]: the words after "asec". The replies are vold's, without the sequence number. */
    fun answer(args: List<String>): List<String> {
        val sub = args.getOrElse(0) { "" }
        val cid = args.getOrElse(1) { "" }
        val c = if (ok(cid)) File(dir, cid) else null
        return when (sub) {
            "list" -> (dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()).map { "111 $it" } +
                "200 Asec listed."
            "create" -> when {
                c == null -> listOf("400 Bad container id")
                c.exists() -> listOf("400 Container already exists")
                else -> {
                    open(dir); c.mkdirs()
                    // the installer copies the APK and its libraries in as its own uid
                    c.setReadable(true, false); c.setWritable(true, false); c.setExecutable(true, false)
                    log("vold: asec created $cid")
                    listOf("200 Container created")
                }
            }
            "finalize", "fixperms" -> if (c?.isDirectory == true) { seal(c); listOf("200 Container ${if (sub == "finalize") "finalized" else "permissions fixed"}") }
                else notFound()
            "mount" -> if (c?.isDirectory == true) { open(dir); listOf("200 Container mounted") } else notFound()
            "unmount" -> if (c?.isDirectory == true) listOf("200 Container unmounted") else notFound()
            "destroy" -> { if (c != null && c.exists()) { c.deleteRecursively(); log("vold: asec destroyed $cid") }; listOf("200 Container destroyed") }
            "rename" -> {
                val to = args.getOrElse(2) { "" }
                when {
                    c?.isDirectory != true -> notFound()
                    !ok(to) || File(dir, to).exists() -> listOf("400 Container already exists")
                    !c.renameTo(File(dir, to)) -> listOf("400 Container rename failed")
                    else -> listOf("200 Container renamed")
                }
            }
            "path", "fspath" -> if (c?.isDirectory == true) listOf("211 ${guestDir.trimEnd('/')}/$cid") else notFound()
            else -> listOf("200 ok")
        }
    }

    private fun notFound() = listOf("406 Container not found")

    /** no path tricks in a container id ("pkg-1", "smdl2tmp1") */
    private fun ok(cid: String) = cid.isNotEmpty() && cid != "." && cid != ".." && cid.none { it == '/' || it == '\\' || it == '\u0000' }

    /** the mount point itself: everyone may walk through it (a real one is tmpfs, mode 755) */
    private fun open(d: File) {
        d.mkdirs(); d.setReadable(true, false); d.setExecutable(true, false)
    }

    /** a finished container is read-only for everyone but its owner, like vold's read-only remount */
    private fun seal(c: File) {
        c.walkTopDown().forEach { f ->
            f.setReadable(true, false)
            f.setWritable(false, false); f.setWritable(true, true)
            if (f.isDirectory || f.parentFile?.name == "lib") f.setExecutable(true, false)
        }
    }
}
