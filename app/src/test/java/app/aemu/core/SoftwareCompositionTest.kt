package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Added for AEmulator Moon Valley, 2026-11-24: see SoftwareComposition. */
class SoftwareCompositionTest {
    private val galaxy5 = mapOf("debug.sf.hw" to "0", "debug.egl.hw" to "0")
    private val none = emptyMap<String, String>()

    @Test fun galaxy5WithTheGpu() =
        assertEquals(mapOf("debug.sf.hw" to "1", "debug.egl.hw" to "1"), SoftwareComposition.overrides(7, true) { galaxy5[it] })

    @Test fun onlyWhatIsOff() =
        assertEquals(mapOf("debug.sf.hw" to "1"), SoftwareComposition.overrides(8, true) { mapOf("debug.sf.hw" to "0 ", "debug.egl.hw" to "1")[it] })

    @Test fun othersAreLeftAlone() {
        assertEquals(none, SoftwareComposition.overrides(7, false) { galaxy5[it] })    // GPU off: the firmware's software driver
        assertEquals(none, SoftwareComposition.overrides(7, true) { null })
        assertEquals(none, SoftwareComposition.overrides(7, true) { "1" })
        assertEquals(none, SoftwareComposition.overrides(3, true) { "0" })
        assertEquals(none, SoftwareComposition.overrides(19, true) { "0" })
    }
}
