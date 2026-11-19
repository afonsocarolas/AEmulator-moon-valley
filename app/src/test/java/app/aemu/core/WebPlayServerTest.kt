package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/** Added for AEmulator Moon Valley, 2026-11-19: "Play in a browser" (WebPlayServer). */
class WebPlayServerTest {
    @Test fun fingersKeysButtonsAndWheel() {
        val cmds = WebPlayServer.parseInput("t 0:10:20 1:30:40\nt\nk 30 1\nk 30 0\nn back\nw 100 200 -300")
        assertEquals(listOf(
            WebPlayServer.Cmd.Touch(listOf(InputService.P(0, 10, 20), InputService.P(1, 30, 40))),
            WebPlayServer.Cmd.Touch(emptyList()),
            WebPlayServer.Cmd.Key(30, true), WebPlayServer.Cmd.Key(30, false),
            WebPlayServer.Cmd.Nav("back"),
            WebPlayServer.Cmd.Scroll(100, 200, -300),
        ), cmds)
    }

    @Test fun nonsenseIsSkipped() {
        val cmds = WebPlayServer.parseInput("t 0:1\nt 0:1:2 0:3:4\nt 40:1:1\nk 0 1\nk 30 2\nk 30\nn reboot\nn back now\nw 1 2\nx\n\nrm -rf /")
        assertTrue(cmds.toString(), cmds.isEmpty())
    }

    @Test fun keyAndQuery() {
        assertTrue(WebPlayServer.sameKey("abc", "abc"))
        assertFalse(WebPlayServer.sameKey("abd", "abc"))
        assertFalse(WebPlayServer.sameKey(null, "abc"))
        assertEquals("/frame.jpg" to "k=abc&t=1", WebPlayServer.splitTarget("/frame.jpg?k=abc&t=1"))
        assertEquals("abc", WebPlayServer.queryParam("t=1&k=abc", "k"))
        assertNull(WebPlayServer.queryParam("kk=abc", "k"))
        val k = WebPlayServer.newKey()
        assertEquals(12, k.length)
        assertTrue(k.all { it in "abcdefghijkmnpqrstuvwxyz23456789" })
    }

    @Test fun requestsAreRead() {
        val raw = "POST /input?k=x HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nn homeGET / HTTP/1.1\r\n\r\n"
        val inp = ByteArrayInputStream(raw.toByteArray())
        val a = WebPlayServer.readRequest(inp)!!
        assertEquals("POST", a.method); assertEquals("/input?k=x", a.target); assertEquals("n hom", String(a.body))
        assertNull(WebPlayServer.readRequest(ByteArrayInputStream("GET / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())))
        assertNull(WebPlayServer.readRequest(ByteArrayInputStream("GET / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".toByteArray())))
        assertNull(WebPlayServer.readRequest(ByteArrayInputStream("hello\r\n\r\n".toByteArray())))
    }

    @Test fun overTheNetwork() {
        val got = mutableListOf<String>()
        val controls = object : WebPlayServer.Controls {
            override fun touch(points: List<InputService.P>) { got += "touch $points" }
            override fun key(code: Int, down: Boolean) { got += "key $code $down" }
            override fun nav(button: String) { got += "nav $button" }
            override fun scroll(x: Int, y: Int, dy: Int) { got += "scroll $x $y $dy" }
        }
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3)
        val s = WebPlayServer(0, "secretkey123", "<html>moon</html>", { jpeg }, controls) {}
        assertTrue(s.start())
        try {
            val base = "http://127.0.0.1:${s.boundPort}"
            fun get(path: String): Pair<Int, ByteArray> {
                val c = URL(base + path).openConnection() as HttpURLConnection
                return c.responseCode to (if (c.responseCode < 400) c.inputStream.readBytes() else ByteArray(0))
            }
            assertEquals(403, get("/").first)
            assertEquals(403, get("/frame.jpg?k=wrong").first)
            assertEquals("<html>moon</html>", String(get("/?k=secretkey123").second))
            assertTrue(get("/frame.jpg?k=secretkey123").second.contentEquals(jpeg))
            assertEquals(404, get("/nothing?k=secretkey123").first)
            val c = URL("$base/input?k=secretkey123").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.use { it.write("n home\nk 30 1".toByteArray()) }
            assertEquals(204, c.responseCode)
            assertEquals(listOf("nav home", "key 30 true"), got)
        } finally { s.stop() }
        assertFalse(s.running)
    }
}
