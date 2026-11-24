package app.aemu.importer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Added for AEmulator Moon Valley, 2026-11-24: which firmware gets the Galaxy 5's 240x320 screen. */
class Galaxy5Test {
    @Test fun models() {
        for (m in listOf("GT-I5500", "GT-I5503", "GT-I5503T", "GT-I5508", "GT-I5500B", " GT-I5503 ")) assertTrue(m, Analyzer.isGalaxy5("Samsung", m, 7))
        for (m in listOf("GT-I5510", "GT-I5800", "GT-I9000", "GT-I55030")) assertFalse(m, Analyzer.isGalaxy5("Samsung", m, 7))
        assertTrue(Analyzer.isGalaxy5("samsung", "GT-I5500", 8))
        assertFalse(Analyzer.isGalaxy5("samsung", "GT-I5500", 10))   // (other people's builds scale)
        assertFalse(Analyzer.isGalaxy5("htc", "GT-I5500", 7))
    }
}
