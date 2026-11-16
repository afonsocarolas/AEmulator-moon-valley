package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Added for AEmulator Moon Valley, 2026-11-16. */
class BootFailureReasonTest {
    private fun r(tag: String, msg: String, prio: Char = 'E') = GuestLog.Rec(prio, tag, msg)

    @Test fun watchdogKillWithItsStack() {
        val recs = listOf(
            r("SystemServer", "Starting phase 500", 'I'),
            r("Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on main thread (main) for 60s"),
            r("Watchdog", "main annotated stack trace:", 'W'),
            r("Watchdog", "    at com.samsung.android.knox.KnoxGuardService.onBootPhase(KnoxGuardService.java:120)", 'W'),
            r("Watchdog", "    at com.android.server.SystemServiceManager.startBootPhase(SystemServiceManager.java:300)", 'W'),
            r("Watchdog", "    at com.android.server.SystemServer.startOtherServices(SystemServer.java:3000)", 'W'),
            r("Watchdog", "    at com.android.server.SystemServer.run(SystemServer.java:900)", 'W'),
        )
        val s = BootFailureReason.explain(recs)!!
        assertTrue(s, s.startsWith("system_server was killed by Android's watchdog: Blocked in handler on main thread (main) for 60s"))
        assertTrue(s, s.contains("stuck in com.samsung.android.knox.KnoxGuardService.onBootPhase ← com.android.server.SystemServiceManager.startBootPhase ← com.android.server.SystemServer.startOtherServices"))
        assertTrue(s, !s.contains("SystemServer.run"))
    }

    @Test fun fatalExceptionInOneRecord() {
        val recs = listOf(r("AndroidRuntime", """*** FATAL EXCEPTION IN SYSTEM PROCESS: main
java.lang.RuntimeException: Failed to boot service com.samsung.android.server.Foo
	at com.android.server.SystemServiceManager.startBootPhase(SystemServiceManager.java:301)
	at com.android.server.SystemServer.startOtherServices(SystemServer.java:3001)
	at com.android.server.SystemServer.run(SystemServer.java:901)
Caused by: java.lang.NullPointerException: Attempt to invoke a method on a null object reference
	at com.samsung.android.server.Foo.onBootPhase(Foo.java:42)
	... 5 more"""))
        val s = BootFailureReason.explain(recs)!!
        assertTrue(s, s.startsWith("system_server crashed on thread main: java.lang.RuntimeException: Failed to boot service com.samsung.android.server.Foo"))
        assertTrue(s, s.contains("— at com.android.server.SystemServiceManager.startBootPhase ← com.android.server.SystemServer.startOtherServices"))
        assertTrue(s, s.contains("caused by java.lang.NullPointerException: Attempt to invoke a method on a null object reference"))
    }

    @Test fun theLastReportWins() {
        val recs = listOf(
            r("Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: first"),
            r("Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: second"),
        )
        assertTrue(BootFailureReason.explain(recs)!!.contains(": second"))
    }

    @Test fun nothingKnownGivesNull() = assertNull(BootFailureReason.explain(listOf(r("ActivityManager", "Start proc 123:com.foo", 'I'))))

    @Test fun longReasonsAreCut() {
        val s = BootFailureReason.explain(listOf(r("Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: " + "x".repeat(2000))))!!
        assertEquals(560, s.length)
    }

    @Test fun theFirstServiceThatDidNotStartIsNamed() {
        // the Android 16 Qualcomm image: StorageManagerService fails first, ArtManagerLocal dies of the gap later
        val recs = listOf(
            r("SystemServer", """BOOT FAILURE starting StorageManagerService
java.lang.RuntimeException: Failed to start service com.android.server.StorageManagerService${'$'}Lifecycle: onStart threw an exception
	at com.android.server.SystemServiceManager.startService(SystemServiceManager.java:282)
Caused by: java.lang.IllegalStateException: Failed to find update_engine
	at android.os.UpdateEngine.<init>(UpdateEngine.java:257)
	... 7 more"""),
            r("SystemServer", """BOOT FAILURE starting StorageStatsService
java.lang.RuntimeException: Failed to start service
Caused by: java.lang.NullPointerException"""),
            r("AndroidRuntime", """*** FATAL EXCEPTION IN SYSTEM PROCESS: main
java.lang.NullPointerException: Attempt to invoke virtual method 'java.lang.Class java.lang.Object.getClass()' on a null object reference
	at com.android.server.art.ArtManagerLocal${'$'}Injector.getStorageManager(ArtManagerLocal.java:1789)
	at com.android.server.art.ArtManagerLocal${'$'}Injector.<init>(ArtManagerLocal.java:1672)"""),
        )
        val s = BootFailureReason.explain(recs)!!
        assertTrue(s, s.contains("— first failure: StorageManagerService (IllegalStateException: Failed to find update_engine)"))
        assertTrue(s, !s.contains("StorageStatsService"))
    }
}
