package app.aemu.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Added for AEmulator Moon Valley, 2026-11-26: see AppDataImport. */
class AppDataImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(f: File, text: String = "x") { f.parentFile?.mkdirs(); f.writeText(text) }

    @Test fun findsThePackageFolder() {
        val picked = tmp.newFolder("data")
        file(File(picked, "data/com.eamobile.Fifa/files/game.dat"))
        file(File(picked, "data/com.other.App/files/a"))
        val (pkg, dir) = AppDataImport.locate(picked) { it == "com.other.App" }!!
        assertEquals("com.other.App", pkg); assertEquals("com.other.App", dir.name)
        assertEquals("com.eamobile.Fifa", AppDataImport.locate(picked) { false }!!.first)
        assertEquals("com.eamobile.Fifa", AppDataImport.locate(File(picked, "data/com.eamobile.Fifa")) { true }!!.first)
        assertNull(AppDataImport.locate(tmp.newFolder("empty")) { true })
    }

    @Test fun userIds() {
        val root = tmp.newFolder("root")
        file(File(root, "data/system/packages.xml"), """
            <packages>
            <package name="com.eamobile.Fifa" codePath="/data/app/x.apk" flags="0" ft="1" userId="10045">
            <package name="com.shared.num" sharedUserId="10012" flags="0">
            <package name="com.shared.name" sharedUserId="com.shared" flags="0">
            <shared-user name="com.shared" userId="10077">
            </packages>""".trimIndent())
        assertEquals(10045, AppDataImport.uidOf(root, "com.eamobile.Fifa"))
        assertEquals(10012, AppDataImport.uidOf(root, "com.shared.num"))
        assertEquals(10077, AppDataImport.uidOf(root, "com.shared.name"))
        assertNull(AppDataImport.uidOf(root, "com.missing"))
    }

    @Test fun copiesIntoTheAppsFolder() {
        val root = tmp.newFolder("root")
        val dest = File(root, "data/data/com.eamobile.Fifa")
        file(File(dest, "lib/libgame.so"), "installed")
        file(File(dest, "files/old.dat"), "old")
        val src = tmp.newFolder("src")
        file(File(src, "files/game.dat"), "new"); file(File(src, "shared_prefs/p.xml")); file(File(src, "lib/libgame.so"), "backup")
        val r = AppDataImport.install(root, "com.eamobile.Fifa", src, null) {} as AppDataImport.Result.Copied
        assertEquals(2, r.files)
        assertEquals("new", File(dest, "files/game.dat").readText())
        assertFalse(File(dest, "files/old.dat").exists())          // the folder is replaced, not merged
        assertTrue(File(dest, "shared_prefs/p.xml").isFile)
        assertEquals("installed", File(dest, "lib/libgame.so").readText())
        assertEquals(AppDataImport.Result.NotInstalled("com.x.Y"), AppDataImport.install(root, "com.x.Y", src, null) {})
    }
}
