/* Added for AEmulator Moon Valley, 2026-11-19: play a running machine from a web browser. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Play in a browser": a tiny HTTP server on the phone, so any browser on the same network (a PC, a tablet, another
 * phone) shows the guest's screen and drives it with mouse, touch and keyboard. No viewer to install, unlike VNC.
 *
 * Every request must carry the session's random key (`?k=`), which is part of the address the app shows; anything
 * without it gets 403. It is plain HTTP on the local network: whoever has the address controls the machine, so the
 * app stops the server with the machine and on demand.
 *
 *  - `GET /?k=KEY`            the page ([page], the browser does the rest)
 *  - `GET /frame.jpg?k=KEY`   the current screen as a JPEG (503 while there is none)
 *  - `POST /input?k=KEY`      commands, one per line, see [parseInput]
 */
class WebPlayServer(
    private val port: Int,
    val key: String,
    private val page: String,
    private val frames: Frames,
    private val controls: Controls,
    private val log: (String) -> Unit,
) {
    fun interface Frames {
        /** the guest's screen now, as a JPEG; null when there is none to show */
        fun jpeg(): ByteArray?
    }

    interface Controls {
        /** the fingers now down, in guest pixels; empty lifts them all */
        fun touch(points: List<InputService.P>)
        fun key(code: Int, down: Boolean)
        /** a named button: back, home, recents, menu, power, volup, voldown */
        fun nav(button: String)
        /** a mouse wheel turn at (x, y): a swipe of [dy] guest pixels (positive scrolls down the content) */
        fun scroll(x: Int, y: Int, dy: Int)
    }

    sealed class Cmd {
        data class Touch(val points: List<InputService.P>) : Cmd()
        data class Key(val code: Int, val down: Boolean) : Cmd()
        data class Nav(val button: String) : Cmd()
        data class Scroll(val x: Int, val y: Int, val dy: Int) : Cmd()
    }

    @Volatile private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val frameLock = Any()
    private var lastFrame: ByteArray? = null
    private var lastFrameAt = 0L
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())

    val running: Boolean get() = server != null
    val boundPort: Int get() = server?.localPort ?: port

    fun start(): Boolean {
        if (server != null) return true
        return runCatching {
            val s = ServerSocket(port, 8)
            server = s
            Thread({ accept(s) }, "webplay-accept").apply { isDaemon = true }.start()
            log("webplay: listening on port ${s.localPort}")
        }.onFailure { log("webplay: cannot listen on port $port: ${it.message}") }.isSuccess
    }

    fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        log("webplay: stopped")
    }

    private fun accept(s: ServerSocket) {
        while (server === s) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            clients.add(c)
            Thread({
                runCatching { serve(c) }.onFailure { e ->
                    if (server != null && e !is SocketTimeoutException) log("webplay: ${c.inetAddress.hostAddress}: ${e.message ?: e}")
                }
                runCatching { c.close() }
                clients.remove(c)
            }, "webplay-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(c: Socket) {
        c.tcpNoDelay = true
        c.soTimeout = 60_000
        val inp = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream().buffered(1 shl 16)
        while (server != null) {
            val req = readRequest(inp) ?: return
            val keepAlive = !req.headers["connection"].equals("close", true)
            handle(req, out, c)
            out.flush()
            if (!keepAlive) return
        }
    }

    private fun handle(req: Request, out: OutputStream, c: Socket) {
        val (path, query) = splitTarget(req.target)
        if (!sameKey(queryParam(query, "k"), key)) {
            respond(out, 403, "text/plain; charset=utf-8", "This address needs the key the app shows.".toByteArray())
            return
        }
        val who = c.inetAddress.hostAddress ?: "?"
        if (seen.add(who)) log("webplay: $who connected")
        when {
            req.method == "GET" && (path == "/" || path == "/index.html") ->
                respond(out, 200, "text/html; charset=utf-8", page.toByteArray())
            req.method == "GET" && path == "/frame.jpg" -> {
                val jpg = frame()
                if (jpg == null) respond(out, 503, "text/plain; charset=utf-8", "no picture yet".toByteArray())
                else respond(out, 200, "image/jpeg", jpg)
            }
            req.method == "POST" && path == "/input" -> {
                parseInput(String(req.body, Charsets.UTF_8)).forEach(::run)
                respond(out, 204, null, ByteArray(0))
            }
            else -> respond(out, 404, "text/plain; charset=utf-8", "not found".toByteArray())
        }
    }

    /** One grab serves every browser asking within the same few milliseconds. */
    private fun frame(): ByteArray? = synchronized(frameLock) {
        val now = System.currentTimeMillis()
        if (lastFrame == null || now - lastFrameAt >= 40) {
            frames.jpeg()?.let { lastFrame = it; lastFrameAt = now } ?: return null
        }
        lastFrame
    }

    private fun run(cmd: Cmd) = runCatching {
        when (cmd) {
            is Cmd.Touch -> controls.touch(cmd.points)
            is Cmd.Key -> controls.key(cmd.code, cmd.down)
            is Cmd.Nav -> controls.nav(cmd.button)
            is Cmd.Scroll -> controls.scroll(cmd.x, cmd.y, cmd.dy)
        }
    }.onFailure { log("webplay: input: ${it.message ?: it}") }

    class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 64
        private const val MAX_BODY = 64 * 1024
        private val NAV = setOf("back", "home", "recents", "menu", "power", "volup", "voldown")

        /** a key nobody guesses: 12 letters and digits, without the ones easy to misread */
        fun newKey(random: java.util.Random = SecureRandom()): String =
            (1..12).map { "abcdefghijkmnpqrstuvwxyz23456789"[random.nextInt(32)] }.joinToString("")

        fun sameKey(given: String?, key: String): Boolean =
            given != null && MessageDigest.isEqual(given.toByteArray(), key.toByteArray())

        /** `/path?query` to (path, query) */
        fun splitTarget(target: String): Pair<String, String> {
            val q = target.indexOf('?')
            return if (q < 0) target to "" else target.substring(0, q) to target.substring(q + 1)
        }

        fun queryParam(query: String, name: String): String? = query.split('&').firstNotNullOfOrNull { part ->
            val eq = part.indexOf('=')
            val k = if (eq < 0) part else part.substring(0, eq)
            if (k != name) null else runCatching { URLDecoder.decode(if (eq < 0) "" else part.substring(eq + 1), "UTF-8") }.getOrNull()
        }

        /**
         * The page's commands, one per line; anything malformed is skipped.
         *  - `t` then `id:x:y` for each finger down (guest pixels); `t` alone lifts every finger
         *  - `k CODE 1|0` a Linux key code going down or up
         *  - `n NAME` a button (back, home, recents, menu, power, volup, voldown)
         *  - `w X Y DY` a mouse wheel turn at (X, Y)
         */
        fun parseInput(body: String): List<Cmd> = body.lineSequence().take(256).mapNotNull { raw ->
            val parts = raw.trim().split(' ').filter { it.isNotEmpty() }
            when (parts.firstOrNull()) {
                "t" -> {
                    val pts = parts.drop(1).take(10).map { p ->
                        val f = p.split(':')
                        if (f.size != 3) return@mapNotNull null
                        val id = f[0].toIntOrNull()?.takeIf { it in 0..31 } ?: return@mapNotNull null
                        val x = f[1].toIntOrNull()?.takeIf { it in 0..16383 } ?: return@mapNotNull null
                        val y = f[2].toIntOrNull()?.takeIf { it in 0..16383 } ?: return@mapNotNull null
                        InputService.P(id, x, y)
                    }
                    if (pts.map { it.id }.toSet().size != pts.size) null else Cmd.Touch(pts)
                }
                "k" -> {
                    val code = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..767 }
                    val down = when (parts.getOrNull(2)) { "1" -> true; "0" -> false; else -> null }
                    if (code == null || down == null || parts.size != 3) null else Cmd.Key(code, down)
                }
                "n" -> parts.getOrNull(1)?.takeIf { it in NAV && parts.size == 2 }?.let { Cmd.Nav(it) }
                "w" -> {
                    val v = parts.drop(1).map { it.toIntOrNull() }
                    if (v.size != 3 || v.any { it == null }) null
                    else Cmd.Scroll(v[0]!!.coerceIn(0, 16383), v[1]!!.coerceIn(0, 16383), v[2]!!.coerceIn(-4000, 4000))
                }
                else -> null
            }
        }.toList()

        /** One HTTP/1.1 request, or null at the end of the connection (or on nonsense). */
        fun readRequest(inp: InputStream): Request? {
            val first = readLine(inp) ?: return null
            val parts = first.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(inp) ?: return null
                if (line.isEmpty()) break
                if (headers.size >= MAX_HEADERS) return null
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            if (headers["transfer-encoding"] != null) return null
            val len = headers["content-length"]?.let { it.toIntOrNull() ?: return null } ?: 0
            if (len !in 0..MAX_BODY) return null
            val body = ByteArray(len)
            var got = 0
            while (got < len) {
                val n = inp.read(body, got, len - got)
                if (n < 0) return null
                got += n
            }
            return Request(parts[0], parts[1], headers, body)
        }

        private fun readLine(inp: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length >= MAX_LINE) return null
                sb.append(b.toChar())
            }
        }

        fun respond(out: OutputStream, status: Int, type: String?, body: ByteArray) {
            val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Service Unavailable" }
            val head = StringBuilder("HTTP/1.1 $status $reason\r\n")
            if (type != null) head.append("Content-Type: $type\r\n")
            head.append("Content-Length: ${body.size}\r\n")
            head.append("Cache-Control: no-store\r\n")
            head.append("X-Content-Type-Options: nosniff\r\n")
            head.append("Referrer-Policy: no-referrer\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body)
        }
    }
}
