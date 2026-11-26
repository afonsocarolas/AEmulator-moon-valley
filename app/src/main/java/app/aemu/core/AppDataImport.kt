/* Added for AEmulator Moon Valley, 2026-11-26: an app's data folder copied into the machine. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Games of the 2.x era often came as an APK and a folder of data for the phone's internal storage,
 * /data/data/<package> (FIFA 10 of the Xperia Play: com.eamobile.Fifa). Without it they try to download it from servers
 * that are long gone. The folder is copied there on the host side and its files are given to the app's user in the
 * owners table qemu reads (dhd.owners, see TreeFixer.seedOwners), as the guest itself cannot change owners.
 */
object AppDataImport {
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** the app's native libraries are its installer's business, never the backup's */
    private val SKIP = setOf("lib")

    sealed class Result {
        data class Copied(val pkg: String, val files: Int) : Result()
        object NoPackage : Result()
        data class NotInstalled(val pkg: String) : Result()
    }

    /** The package folder in what was picked: the folder itself, or one inside it (data/data/<package>), installed first. */
    fun locate(picked: File, installed: (String) -> Boolean): Pair<String, File>? {
        val found = ArrayList<Pair<String, File>>()
        fun walk(f: File, depth: Int) {
            if (!f.isDirectory) return
            if (PACKAGE.matches(f.name)) { found.add(f.name to f); return }
            if (depth < 3) f.listFiles()?.sortedBy { it.name }?.forEach { walk(it, depth + 1) }
        }
        walk(picked, 0)
        return found.firstOrNull { installed(it.first) } ?: found.firstOrNull()
    }

    /** The user id packages.xml gives [pkg] (its own, or its shared user's). */
    fun uidOf(root: File, pkg: String): Int? {
        val text = runCatching { File(root, "data/system/packages.xml").readText() }.getOrNull() ?: return null
        val tag = Regex("<package name=\"${Regex.escape(pkg)}\"[^>]*?>").find(text)?.value ?: return null
        Regex("\\suserId=\"(\\d+)\"").find(tag)?.let { return it.groupValues[1].toInt() }
        val shared = Regex("\\ssharedUserId=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return null
        // a number in older files, a shared user's name in some
        return shared.toIntOrNull() ?: Regex("<shared-user name=\"${Regex.escape(shared)}\"[^>]*?\\suserId=\"(\\d+)\"")
            .find(text)?.groupValues?.get(1)?.toInt()
    }

    /**
     * Puts [src]'s contents into the app's data folder, replacing what has the same name, and makes them the app's.
     * [owners]: qemu's owners table; null leaves owners alone (tests).
     */
    fun install(root: File, pkg: String, src: File, owners: File?, log: (String) -> Unit): Result {
        val dest = File(root, "data/data/$pkg")
        if (!dest.isDirectory) return Result.NotInstalled(pkg)
        val uid = uidOf(root, pkg)
        val copied = ArrayList<File>()
        for (e in src.listFiles().orEmpty().sortedBy { it.name }) {
            if (e.name in SKIP) continue
            val to = File(dest, e.name)
            to.deleteRecursively()
            if (!e.renameTo(to)) e.copyRecursively(to, overwrite = true)
            to.walkTopDown().forEach { copied.add(it) }
        }
        val rows = StringBuilder()
        for (f in copied) {
            runCatching { f.setReadable(true, false); f.setWritable(true, false); if (f.isDirectory) f.setExecutable(true, false) }
            if (owners != null && uid != null) {
                val st = runCatching { Os.lstat(f.absolutePath) }.getOrNull() ?: continue
                if (OsConstants.S_ISLNK(st.st_mode)) continue
                rows.append(String.format("%016x %016x %08x %08x\n", st.st_dev, st.st_ino, uid, uid))
            }
        }
        if (rows.isNotEmpty()) owners?.appendText(rows.toString())
        log("app data: ${copied.size} files into /data/data/$pkg (uid ${uid ?: "unknown"})")
        return Result.Copied(pkg, copied.count { it.isFile })
    }
}
