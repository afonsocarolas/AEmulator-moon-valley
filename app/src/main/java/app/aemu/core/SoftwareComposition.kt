/* Added for AEmulator Moon Valley, 2026-11-24: 2.x firmware that asks for software composition. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * Low-end 2.x phones (Samsung Galaxy 5, GT-I5503) switch the GPU off in build.prop: with debug.sf.hw=0 SurfaceFlinger accepts
 * only an EGL_SLOW_CONFIG, with debug.egl.hw=0 libEGL loads only the software driver. With the GPU on, the bridge stands under
 * both driver names and has no slow config: SurfaceFlinger finds none, gets no context and dies in strlen() of the missing
 * extension list, before the boot animation. Then both must say 1, what they are on a phone that says nothing.
 */
object SoftwareComposition {
    private val KEYS = listOf("debug.sf.hw", "debug.egl.hw")

    /** [bridge]: the 32-bit engines' GPU bridge is the machine's driver. [firmware]: a property as the firmware has it. */
    fun overrides(api: Int, bridge: Boolean, firmware: (String) -> String?): Map<String, String> =
        if (!bridge || api !in 5..15) emptyMap()
        else KEYS.filter { k -> firmware(k)?.trim()?.toIntOrNull() == 0 }.associateWith { "1" }
}
