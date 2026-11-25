/* Added for AEmulator Moon Valley, 2026-11-25: the S Pen of Samsung's Galaxy Note devices. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The S Pen. Samsung's Wacom driver is an input device "sec_e-pen": one contact with pressure, BTN_TOOL_PEN while the pen is
 * within reach, BTN_TOUCH while it touches, BTN_STYLUS for its button, and a switch that is 1 while the pen is out of its
 * slot. The guest shim answers for such a device (native/guestshim/vinput.h, the same ranges); on engines whose programs do
 * not load the shim the touch screen reports the pen (InputService.penThroughTouch).
 */
object SPen {
    const val MAX = 16383
    const val PRESSURE = 1023
    const val BTN_TOOL_PEN = 320
    const val BTN_TOOL_RUBBER = 321
    const val BTN_TOUCH = 330
    const val BTN_STYLUS = 331
    const val ABS_MT_TOOL_TYPE = 55
    const val MT_TOOL_PEN = 1
    private const val SW_OLD = 0x0e
    private const val SW_NEW = 0x13

    /** The pen at the screen: [x], [y] and [pressure] run 0..1; [tip]: it touches, otherwise it is above. */
    data class State(val x: Float, val y: Float, val pressure: Float, val tip: Boolean, val button: Boolean = false, val eraser: Boolean = false)

    private val MODELS = Regex(
        "(GT-N(70|71|80|51)\\d\\d.*|SM-N\\d{3}.*|SM-P(55|58|60|61|90|35|20|61)\\d.*|SM-T(82|83|86|87|97)\\d.*|" +
            "SHV-E(160|230|250).*|SC-(05D|02E|01F|01G).*|SGH-(I717|T879|I317|T889|I467).*|SCH-(I605|R950|I925|N719).*|SPH-L900.*)", RegexOption.IGNORE_CASE)

    private fun props(root: File) = runCatching { PropArea.parseProps(File(root, "system/build.prop").readText()) }.getOrNull().orEmpty()

    /** A Samsung firmware made for the pen: it says so (com.sec.feature.spen_usp), or it is one of those models. */
    fun supported(root: File, api: Int): Boolean {
        if (api < 9) return false
        val declared = listOf("system/etc/permissions", "system/vendor/etc/permissions", "vendor/etc/permissions", "product/etc/permissions")
            .any { d -> File(root, d).list().orEmpty().any { it.contains("spen_usp", true) || it.contains("feature.spen", true) } }
        if (declared) return true
        val p = props(root)
        val samsung = p["ro.product.brand"]?.trim().equals("samsung", true) || p["ro.product.manufacturer"]?.trim().equals("samsung", true)
        return samsung && MODELS.matches(p["ro.product.model"]?.trim().orEmpty())
    }

    /**
     * The code of the slot's switch: 0x0e up to the Note II and Note 8.0 (Exynos 4), 0x13 from the Note 3 on. An Exynos 4
     * device on Android 4.3 or later gets both. 0x0e must not reach Android 10, where it mutes the microphone.
     */
    fun switches(root: File, api: Int): List<Int> {
        if (api <= 17) return listOf(SW_OLD)
        val p = props(root)
        val exynos4 = listOf("ro.board.platform", "ro.hardware", "ro.product.board").any { k ->
            p[k]?.trim()?.lowercase()?.let { it.startsWith("exynos4") || it.startsWith("smdk4") } == true
        }
        return if (exynos4 && api < 29) listOf(SW_OLD, SW_NEW) else listOf(SW_NEW)
    }

    /** What the shim reads: out or not, and which codes (bit 0 and bit 1 of a digit). */
    fun stateFile(out: Boolean, switches: List<Int>): String =
        (if (out) "1" else "0") + ((if (SW_OLD in switches) 1 else 0) + (if (SW_NEW in switches) 2 else 0))

    private class Events(nanos: Long) {
        private val b = ByteBuffer.allocate(64 * 16).order(ByteOrder.LITTLE_ENDIAN)
        private val sec = (nanos / 1_000_000_000).toInt()
        private val usec = ((nanos % 1_000_000_000) / 1000).toInt()
        fun put(type: Int, code: Int, value: Int) { b.putInt(sec).putInt(usec).putShort(type.toShort()).putShort(code.toShort()).putInt(value) }
        fun sync() = put(0, 0, 0)
        fun bytes(): ByteArray = b.array().copyOf(b.position())
    }

    fun slot(out: Boolean, switches: List<Int>, nanos: Long): ByteArray {
        val e = Events(nanos)
        for (c in switches) e.put(5 /* EV_SW */, c, if (out) 1 else 0)
        e.sync()
        return e.bytes()
    }

    /** The digitizer's reports, in the order of Samsung's driver. */
    class Encoder {
        private var last: State? = null

        fun reset() { last = null }

        private fun report(e: Events, s: State, tool: Int) {
            e.put(3, 0, (s.x * MAX).toInt().coerceIn(0, MAX))
            e.put(3, 1, (s.y * MAX).toInt().coerceIn(0, MAX))
            e.put(3, 24, if (s.tip) (s.pressure * PRESSURE).toInt().coerceIn(1, PRESSURE) else 0)
            e.put(1, BTN_STYLUS, if (s.button) 1 else 0)
            e.put(1, BTN_TOUCH, if (s.tip) 1 else 0)
            e.put(1, tool, 1)
            e.sync()
        }

        private fun leave(e: Events, s: State, tool: Int) {
            // the button is let go and the tip lifted before the pen goes out of reach
            if (s.tip || s.button) report(e, s.copy(tip = false, button = false), tool)
            e.put(3, 24, 0)
            e.put(1, BTN_STYLUS, 0)
            e.put(1, BTN_TOUCH, 0)
            e.put(1, tool, 0)
            e.sync()
        }

        private fun tool(s: State) = if (s.eraser) BTN_TOOL_RUBBER else BTN_TOOL_PEN

        /** null: the pen went out of reach. Empty when nothing changed. */
        fun frame(s: State?, nanos: Long): ByteArray {
            val was = last
            if (s == was) return ByteArray(0)
            val e = Events(nanos)
            var near = was
            if (was != null && (s == null || tool(s) != tool(was))) { leave(e, was, tool(was)); near = null }
            if (s != null) {
                // a pen always comes from above: first there, then its button, then its tip
                if (near == null && (s.tip || s.button)) report(e, s.copy(tip = false, button = false), tool(s))
                if (s.tip && s.button && near?.button != true) report(e, s.copy(tip = near?.tip == true), tool(s))
                report(e, s, tool(s))
            }
            last = s
            return e.bytes()
        }
    }
}
