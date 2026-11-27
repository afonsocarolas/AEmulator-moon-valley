package app.aemu.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Added for AEmulator Moon Valley, 2026-11-27: see AsecStore. */
class AsecStoreTest {
    @Test fun anInstallToTheCard() {
        val root = Files.createTempDirectory("asec").toFile()
        val dir = File(root, "mnt/asec")
        val a = AsecStore(dir, "/mnt/asec")
        fun cmd(s: String) = a.answer(s.split(' '))

        assertEquals(listOf("200 Asec listed."), cmd("list"))
        // what the 2.3 installer does: a temporary container, the APK copied in, finalized, renamed, mounted
        assertEquals(listOf("200 Container created"), cmd("create smdl2tmp1 11 fat key 10012"))
        assertEquals(listOf("211 /mnt/asec/smdl2tmp1"), cmd("path smdl2tmp1"))
        File(dir, "smdl2tmp1/pkg.apk").writeText("apk")
        File(dir, "smdl2tmp1/lib").mkdirs(); File(dir, "smdl2tmp1/lib/libminecraftpe.so").writeText("so")
        assertEquals(listOf("200 Container finalized"), cmd("finalize smdl2tmp1"))
        assertEquals(listOf("200 Container unmounted"), cmd("unmount smdl2tmp1"))
        assertEquals(listOf("200 Container renamed"), cmd("rename smdl2tmp1 com.mojang.minecraftpe-1"))
        assertEquals(listOf("200 Container mounted"), cmd("mount com.mojang.minecraftpe-1 key 1000"))
        assertEquals(listOf("211 /mnt/asec/com.mojang.minecraftpe-1"), cmd("path com.mojang.minecraftpe-1"))
        assertTrue(File(dir, "com.mojang.minecraftpe-1/lib/libminecraftpe.so").isFile)
        // after a reboot the installer finds it again
        assertEquals(listOf("111 com.mojang.minecraftpe-1", "200 Asec listed."), cmd("list"))

        assertEquals(listOf("400 Container already exists"), cmd("create com.mojang.minecraftpe-1 11 fat key 1000"))
        assertEquals(listOf("406 Container not found"), cmd("path smdl2tmp1"))
        assertEquals(listOf("406 Container not found"), cmd("mount nothing key 1000"))
        assertEquals(listOf("400 Bad container id"), cmd("create .. 1 fat key 0"))
        assertEquals(listOf("400 Bad container id"), cmd("create a/b 1 fat key 0"))

        assertEquals(listOf("200 Container created"), cmd("create smdl2tmp2 1 fat key 1000"))
        assertEquals(listOf("200 Container destroyed"), cmd("destroy smdl2tmp2"))
        assertEquals(listOf("111 com.mojang.minecraftpe-1", "200 Asec listed."), cmd("list"))
    }
}
