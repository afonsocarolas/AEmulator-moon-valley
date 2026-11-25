/* Added for AEmulator Moon Valley, 2026-11-25: the gamepad of the Sony Ericsson Xperia Play. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The slide-out gamepad of the Xperia Play (R800i and its versions, board "zeus"). The firmware has the layouts of the
 * phone's two keypads (keypad-zeus.kl, keypad-game-zeus.kl); the guest shim answers for two input devices of these names
 * (native/guestshim/vinput.h, which lists the same keys) and for the slider, a lid switch that is 0 when the pad is out.
 */
object XperiaPad {
    /** [code]: the Linux key the phone's keypad sends. [game]: on the second keypad (Start, Select). */
    enum class Button(val code: Int, val game: Boolean = false) {
        UP(103), LEFT(105), RIGHT(106), DOWN(108),
        CROSS(304), CIRCLE(305), SQUARE(307), TRIANGLE(308),
        L(310), R(311),
        START(28, true), SELECT(314, true),
    }

    /** the keys the shim declares for the two devices (see vinput.h) */
    val KEYPAD = intArrayOf(103, 105, 106, 108, 308, 305, 304, 307, 310, 311, 226, 139, 102, 158, 217)
    val GAME_KEYPAD = intArrayOf(115, 114, 314, 28)

    /** A firmware with this gamepad, on an engine whose programs load the shim. */
    fun supported(root: File, api: Int, arm: Boolean, engine: Engine): Boolean =
        arm && api in 9..15 && engine != Engine.N64 && File(root, "system/usr/keylayout/keypad-zeus.kl").isFile

    /** What a stick or a finger on the D-pad presses; [x] right and [y] down, -1..1, nothing inside [dead]. */
    fun directions(x: Float, y: Float, dead: Float): Set<Button> {
        if (x * x + y * y < dead * dead) return emptySet()
        val out = LinkedHashSet<Button>(2)
        // eight equal sectors: the smaller axis counts from tan(22.5 degrees) of the larger one
        if (abs(x) >= abs(y) * DIAGONAL) out.add(if (x < 0) Button.LEFT else Button.RIGHT)
        if (abs(y) >= abs(x) * DIAGONAL) out.add(if (y < 0) Button.UP else Button.DOWN)
        return out
    }

    /** A button of the diamond at [x], [y] (-1..1 in its square); its middle, and a finger between two, press nothing or both. */
    fun face(x: Float, y: Float, dead: Float): Set<Button> = directions(x, y, dead).mapTo(LinkedHashSet(2)) {
        when (it) { Button.UP -> Button.TRIANGLE; Button.DOWN -> Button.CROSS; Button.LEFT -> Button.SQUARE; else -> Button.CIRCLE }
    }

    /** The button an Android gamepad key means; [pad]: the key comes from a gamepad (its D-pad keys are not a keyboard's). */
    fun fromAndroid(keyCode: Int, pad: Boolean): Button? = when (keyCode) {
        96 -> Button.CROSS          // KEYCODE_BUTTON_A
        97 -> Button.CIRCLE         // B
        99 -> Button.SQUARE         // X
        100 -> Button.TRIANGLE      // Y
        102, 104 -> Button.L        // L1, L2
        103, 105 -> Button.R        // R1, R2
        108 -> Button.START
        109 -> Button.SELECT
        19 -> if (pad) Button.UP else null
        20 -> if (pad) Button.DOWN else null
        21 -> if (pad) Button.LEFT else null
        22 -> if (pad) Button.RIGHT else null
        else -> null
    }

    /** One event and its SYN_REPORT, as 32-bit struct input_event. */
    private fun event(type: Int, code: Int, value: Int, nanos: Long): ByteArray {
        val b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        fun put(t: Int, c: Int, v: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(t.toShort()).putShort(c.toShort()).putInt(v)
        }
        put(type, code, value); put(0, 0, 0)
        return b.array()
    }

    fun key(b: Button, down: Boolean, nanos: Long) = event(1 /* EV_KEY */, b.code, if (down) 1 else 0, nanos)
    fun slide(open: Boolean, nanos: Long) = event(5 /* EV_SW */, 0 /* SW_LID */, if (open) 0 else 1, nanos)

    private const val DIAGONAL = 0.41421356f
}
