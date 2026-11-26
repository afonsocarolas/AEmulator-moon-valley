/* Added for AEmulator Moon Valley, 2026-11-26: the OUYA's controller. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The OUYA's game controller. The guest shim answers for an "OUYA Game Controller" with the OUYA's Bluetooth ids
 * (native/guestshim/vinput.h), so the firmware reads its own key layout, Vendor_2836_Product_0001.kl. Which Linux key
 * stands for which button is read from that file here, and every button is sent by its Android name: the O button is
 * BUTTON_A, U is BUTTON_X, Y is BUTTON_Y, A is BUTTON_B. A controller on the phone maps one to one by the same names.
 */
object OuyaPad {
    const val LAYOUT = "system/usr/keylayout/Vendor_2836_Product_0001.kl"
    const val AXIS_MAX = 32767

    /** The firmware's layout: Android key name -> Linux key, Android axis name -> (Linux axis, inverted). */
    class Layout(val keys: Map<String, Int>, val axes: Map<String, Pair<Int, Boolean>>) {
        fun key(name: String): Int? = keys[name] ?: FALLBACK_KEYS[name]
        /** Linux axis for an Android axis name; the standard one when the file does not name it */
        fun axis(name: String): Pair<Int, Boolean>? = axes[name] ?: FALLBACK_AXES[name]?.let { it to false }
    }

    /** Linux gamepad keys for names a layout leaves out (a firmware without the file, or a name it lacks). */
    private val FALLBACK_KEYS = mapOf(
        "BUTTON_A" to 304, "BUTTON_B" to 305, "BUTTON_X" to 307, "BUTTON_Y" to 308,
        "BUTTON_L1" to 310, "BUTTON_R1" to 311, "BUTTON_L2" to 312, "BUTTON_R2" to 313,
        "BUTTON_THUMBL" to 317, "BUTTON_THUMBR" to 318,
        "DPAD_UP" to 544, "DPAD_DOWN" to 545, "DPAD_LEFT" to 546, "DPAD_RIGHT" to 547,
        "HOME" to 316, "MENU" to 315, "BUTTON_START" to 315, "BUTTON_SELECT" to 314, "BUTTON_MODE" to 316, "BACK" to 158,
    )
    private val FALLBACK_AXES = mapOf("X" to 0, "Y" to 1, "LTRIGGER" to 2, "Z" to 3, "RZ" to 4, "RTRIGGER" to 5, "HAT_X" to 16, "HAT_Y" to 17)

    /** A firmware with this controller's layout, on an engine whose programs load the shim. */
    fun supported(root: File, arm: Boolean, engine: Engine): Boolean =
        arm && engine != Engine.N64 && File(root, LAYOUT).isFile

    /** Reads a .kl: "key <code> <NAME> [flags]" and "axis <code> [invert] <NAME> [flat n]"; split axes are left out. */
    fun parse(text: String): Layout {
        val keys = LinkedHashMap<String, Int>(); val axes = LinkedHashMap<String, Pair<Int, Boolean>>()
        for (raw in text.lineSequence()) {
            val p = raw.substringBefore('#').trim().split(Regex("\\s+"))
            if (p.size < 3) continue
            val code = p[1].let { if (it.startsWith("0x")) it.drop(2).toIntOrNull(16) else it.toIntOrNull() } ?: continue
            when (p[0]) {
                "key" -> if (p[1] != "usage") keys.putIfAbsent(p[2], code)
                "axis" -> when (p[2]) {
                    "split" -> {}
                    "invert" -> if (p.size >= 4) axes.putIfAbsent(p[3], code to true)
                    else -> axes.putIfAbsent(p[2], code to false)
                }
            }
        }
        return Layout(keys, axes)
    }

    fun layout(root: File): Layout = parse(runCatching { File(root, LAYOUT).readText() }.getOrDefault(""))

    /** The keys the shim declares (bit n = Linux key n), as the layout and the fallbacks name them. */
    fun keyBitmap(layout: Layout): ByteArray {
        val b = ByteArray(96)
        for (c in layout.keys.values + FALLBACK_KEYS.values) if (c in 0 until b.size * 8) b[c / 8] = (b[c / 8].toInt() or (1 shl (c % 8))).toByte()
        return b
    }

    /** The on-screen buttons, by their Android names. */
    enum class Button(val android: String, val label: String) {
        O("BUTTON_A", "O"), U("BUTTON_X", "U"), Y("BUTTON_Y", "Y"), A("BUTTON_B", "A"),
        L1("BUTTON_L1", "L1"), R1("BUTTON_R1", "R1"), L2("BUTTON_L2", "L2"), R2("BUTTON_R2", "R2"),
        L3("BUTTON_THUMBL", "L3"), R3("BUTTON_THUMBR", "R3"),
        UP("DPAD_UP", "▲"), DOWN("DPAD_DOWN", "▼"), LEFT("DPAD_LEFT", "◀"), RIGHT("DPAD_RIGHT", "▶"),
        HOME("HOME", "OUYA"), MENU("MENU", "MENU"),
    }

    /** The Android name of a phone controller's key (KEYCODE_BUTTON_A -> BUTTON_A), as the OUYA's layout names it. */
    fun nameOf(keyCodeString: String): String? {
        val n = keyCodeString.removePrefix("KEYCODE_")
        return when (n) {
            "BUTTON_START" -> "HOME"            // the OUYA has no Start or Menu: its OUYA button opens the menu
            "BUTTON_MODE" -> "HOME"
            "BUTTON_SELECT" -> "BACK"
            else -> n.takeIf { it.startsWith("BUTTON_") || it.startsWith("DPAD_") }
        }
    }

    /**
     * The OUYA's first-time setup cannot finish any more (it pairs controllers over Bluetooth, then needs an account on
     * OUYA's servers, which closed in 2019). An OUYA that went through it has "first_run" false in the launcher's
     * preferences: the launcher (OOBEUtils.hasOOBECompleted) then records the console as provisioned and opens its home.
     * Returns the preferences file's new text, or null when nothing is to change.
     */
    fun setupDonePrefs(old: String?): String? {
        if (old != null && old.contains("name=\"first_run\"")) return null
        val entry = "<boolean name=\"first_run\" value=\"false\" />"
        if (old == null || !old.contains("</map>")) return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    $entry\n</map>\n"
        return old.replace("</map>", "    $entry\n</map>")
    }

    /** -1..1 (a trigger 0..1) as the shim's range */
    fun raw(v: Float, trigger: Boolean): Int {
        val x = if (trigger) v.coerceIn(0f, 1f) * 2f - 1f else v.coerceIn(-1f, 1f)
        return if (x < 0) (x * (AXIS_MAX + 1)).toInt() else (x * AXIS_MAX).toInt()
    }

    class Events(nanos: Long) {
        private val b = ByteBuffer.allocate(64 * 16).order(ByteOrder.LITTLE_ENDIAN)
        private val sec = (nanos / 1_000_000_000).toInt(); private val usec = ((nanos % 1_000_000_000) / 1000).toInt()
        var count = 0; private set
        fun put(type: Int, code: Int, value: Int) { b.putInt(sec).putInt(usec).putShort(type.toShort()).putShort(code.toShort()).putInt(value); count++ }
        fun key(code: Int, down: Boolean) = put(1, code, if (down) 1 else 0)
        fun axis(code: Int, value: Int) = put(3, code, value)
        fun bytes(): ByteArray { put(0, 0, 0); return b.array().copyOf(b.position()) }
    }
}
