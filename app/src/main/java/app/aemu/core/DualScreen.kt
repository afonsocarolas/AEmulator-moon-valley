/* Added for AEmulator Moon Valley, 2026-11-23: two-screen phones (ZTE Axon M). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.Context
import java.io.File

/**
 * The ZTE Axon M (Z999, Android 7.1.2) has two 1080x1920 panels, and its framework treats them as one 2160x1920 display
 * and one 2160x1920 touch panel: com.zte.duallcd (binder service "zdmm") only sets the display's forced size (1080x1920
 * or 2160x1920) and persist.display.mode. The main panel is the left half: LogicalDisplay puts a one-panel picture at
 * 0,0, and TouchInputMapper takes raw x beyond 1079 for the second panel. So such a machine gets a double-wide screen
 * (the sizes are fixed in ZTE's code), the hinge sensor the M button asks for (native/sensorshal, type 34), and the
 * window shows the left half while only one panel is in use.
 */
object DualScreen {
    const val PANEL_W = 1080
    const val PANEL_H = 1920
    const val DENSITY = 480
    const val HALL_MARKER = "system/etc/aemu-hall"
    const val MODE_PROP = "persist.display.mode"

    /** the modes of com.zte.duallcd by their number in [MODE_PROP] */
    const val SINGLE = 1
    const val EXTENDED = 2
    const val DUAL = 4
    const val MIRROR = 8

    /** a firmware with ZTE's two-screen framework (its app lists and its second-screen launcher), as far as it was tested: 7.1 arm64 */
    fun firmware(root: File): Boolean = File(root, "system/etc/half_docked_white_list.xml").isFile &&
        File(root, "system/priv-app/AllApp_ZTE").isDirectory

    fun supported(img: GuestImage, root: File): Boolean = img.engine == Engine.N64 && img.api == 25 && firmware(root)

    private fun key(id: String) = "dual_screen_$id"
    fun enabled(ctx: Context, id: String): Boolean = app.aemu.AppPrefs.prefs(ctx).getBoolean(key(id), true)
    fun setEnabled(ctx: Context, id: String, on: Boolean) { app.aemu.AppPrefs.prefs(ctx).edit().putBoolean(key(id), on).apply() }

    /** the screen both panels make, whatever size the machine's settings name */
    fun widen(s: VmSettings): VmSettings = s.copy(width = PANEL_W * 2, height = PANEL_H, density = DENSITY, hostResolution = false)

    fun isWide(width: Int, height: Int) = width == PANEL_W * 2 && height == PANEL_H

    /** only the left panel is in use. Until the boot animation is over ([booted]) the whole display is drawn on. */
    fun onePanel(mode: String?, booted: Boolean): Boolean {
        if (!booted) return false
        return when (mode?.trim()?.toIntOrNull() ?: SINGLE) { SINGLE, MIRROR -> true; else -> false }
    }

    /** IDisplayModeManager.switchMode(int) is the interface's first call */
    fun switchCommand(mode: Int) = "service call zdmm 1 i32 $mode"
}
