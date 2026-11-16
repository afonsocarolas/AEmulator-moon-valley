/* Added for AEmulator Moon Valley, 2026-11-16. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/**
 * When zygote exits with 137 and nothing more specific is known ("cause not established"), Android itself usually wrote
 * why system_server died: either its Watchdog killed it ("*** WATCHDOG KILLING SYSTEM PROCESS: ...", followed by the
 * stuck thread's stack) or it threw ("*** FATAL EXCEPTION IN SYSTEM PROCESS: <thread>", the exception and its stack in
 * the same record). This turns the last such report into one line for the failure screen, so a screenshot is enough to
 * see what to fix next. No Android classes here, so it runs on a plain JVM.
 */
object BootFailureReason {
    const val WATCHDOG_MARK = "WATCHDOG KILLING SYSTEM PROCESS"
    const val FATAL_MARK = "FATAL EXCEPTION IN SYSTEM PROCESS"
    /** system_server's own note when one of its services fails to start; it carries on, and dies later of the gap */
    const val BOOT_FAILURE_MARK = "BOOT FAILURE starting "
    private const val MAX = 560

    /** " — first failure: <service> (<root cause>)" for the first service that did not start, or "". */
    private fun firstBootFailure(recs: List<GuestLog.Rec>, before: Int): String {
        val i = recs.subList(0, before.coerceIn(0, recs.size)).indexOfFirst { BOOT_FAILURE_MARK in it.msg }
        if (i < 0) return ""
        val lines = recs[i].msg.lines().toMutableList()
        if (lines.none { it.trim().startsWith("Caused by:") }) lines += recs.drop(i + 1).take(40).flatMap { it.msg.lines() }
        val service = lines.first().substringAfter(BOOT_FAILURE_MARK).trim()
        val cause = lines.firstOrNull { it.trim().startsWith("Caused by:") }?.trim()?.removePrefix("Caused by:")?.trim()
            ?.removePrefix("java.lang.")
        return " — first failure: " + service + (if (cause != null) " ($cause)" else "")
    }

    private fun frame(line: String): String? = line.trim().takeIf { it.startsWith("at ") }?.removePrefix("at ")?.substringBefore('(')

    /** The reason as one line, or null when the records have no report of system_server's death. */
    fun explain(recs: List<GuestLog.Rec>): String? {
        val wd = recs.indexOfLast { WATCHDOG_MARK in it.msg }
        if (wd >= 0) {
            val why = recs[wd].msg.substringAfter(WATCHDOG_MARK).trimStart(':', ' ').lineSequence().first().trim()
            // the stuck thread's stack follows, one frame per record or all in one
            val frames = recs.drop(wd + 1).take(80).flatMap { it.msg.lines() }.mapNotNull(::frame).take(3)
            val sb = StringBuilder("system_server was killed by Android's watchdog")
            if (why.isNotEmpty()) sb.append(": ").append(why)
            if (frames.isNotEmpty()) sb.append(" — stuck in ").append(frames.joinToString(" ← "))
            sb.append(firstBootFailure(recs, wd))
            return sb.append(" (zygote exited with 137 as a result)").toString().take(MAX)
        }
        val fe = recs.indexOfLast { FATAL_MARK in it.msg }
        if (fe >= 0) {
            val lines = recs[fe].msg.lines().toMutableList()
            // the stack may also come as records of their own
            if (lines.none { it.trim().startsWith("at ") }) lines += recs.drop(fe + 1).take(80).flatMap { it.msg.lines() }
            val thread = lines.first().substringAfter(FATAL_MARK).trimStart(':', ' ').trim()
            val ex = lines.drop(1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("at ") && !it.trim().startsWith("...") }?.trim()
            val frames = lines.mapNotNull(::frame).take(2)
            val cause = lines.lastOrNull { it.trim().startsWith("Caused by:") }?.trim()?.removePrefix("Caused by:")?.trim()
            val sb = StringBuilder("system_server crashed")
            if (thread.isNotEmpty()) sb.append(" on thread ").append(thread)
            if (ex != null) sb.append(": ").append(ex)
            if (frames.isNotEmpty()) sb.append(" — at ").append(frames.joinToString(" ← "))
            if (cause != null && cause != ex) sb.append(" — caused by ").append(cause)
            sb.append(firstBootFailure(recs, fe))
            return sb.append(" (zygote exited with 137 as a result)").toString().take(MAX)
        }
        return null
    }

    /** Reads the guest's log buffers under [root] (system first: that is where the Watchdog writes). */
    fun fromGuest(root: File): String? = runCatching {
        for (buffer in listOf("system", "crash", "main")) {
            explain(GuestLog.tail(root, 1024 * 1024, buffer))?.let { return@runCatching it }
        }
        null
    }.getOrNull()
}
