package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Added for AEmulator Moon Valley, 2026-11-23: two-screen phones (see DualScreen). */
class DualScreenTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun onlyZtesTwoScreenFirmware() {
        val root = tmp.newFolder("root")
        assertFalse(DualScreen.firmware(root))
        File(root, "system/etc").mkdirs()
        File(root, "system/etc/half_docked_white_list.xml").writeText("<x/>")
        assertFalse(DualScreen.firmware(root))
        File(root, "system/priv-app/AllApp_ZTE").mkdirs()
        assertTrue(DualScreen.firmware(root))
    }

    @Test fun panelsByMode() {
        // the whole display until the boot animation is over
        assertFalse(DualScreen.onePanel("1", booted = false))
        assertTrue(DualScreen.onePanel("1", booted = true))
        assertTrue(DualScreen.onePanel("8", booted = true))
        assertTrue(DualScreen.onePanel(null, booted = true))
        assertTrue(DualScreen.onePanel("", booted = true))
        for (wide in listOf("2", "4", " 4\n", "256")) assertFalse(wide, DualScreen.onePanel(wide, booted = true))
    }

    @Test fun widenKeepsTheRest() {
        val s = VmSettings(width = 1080, height = 1920, hostResolution = true)
        val w = DualScreen.widen(s)
        assertTrue(DualScreen.isWide(w.width, w.height))
        assertFalse(DualScreen.isWide(s.width, s.height))
        assertFalse(w.hostResolution)
        assertEquals(s, w.copy(width = s.width, height = s.height, density = s.density, hostResolution = true))
    }

    @Test fun command() = assertEquals("service call zdmm 1 i32 4", DualScreen.switchCommand(DualScreen.DUAL))
}
