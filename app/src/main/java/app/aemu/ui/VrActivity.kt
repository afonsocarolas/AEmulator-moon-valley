/* Added for AEmulator Moon Valley, 2026-11-21: VR mode on Meta Quest. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import app.aemu.R
import app.aemu.core.InputService
import app.aemu.core.VrNative
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The running machine in VR (menu: VR mode, on a headset only). It runs in the machine's own process (one class per
 * slot, see the subclasses below) so it reaches the machine directly: the GPU bridge draws into a SurfaceTexture of
 * this activity instead of the window's SurfaceView, and the fb0 picture is read as for browser play. The native side
 * (core/VrNative, src/main/cpp/vr.cpp) draws the sky, the screen and the controllers' rays; this side turns the
 * trigger into touches and the buttons into Home, Back and Recents. The left controller's menu button leaves VR.
 */
open class VrActivity : Activity() {
    private var loop: Thread? = null
    private val running = AtomicBoolean(false)
    @Volatile private var handle = 0L
    private val lock = Any()
    @Volatile private var surface: Surface? = null
    private var oldResurface: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = VmHost.vm
        val src = VmHost.screen
        if (vm == null || src == null || src.w <= 0 || src.h <= 0 || !VrNative.ok) {
            Toast.makeText(this, getString(R.string.vr_failed, if (!VrNative.ok) "libaemuvr" else getString(R.string.vr_no_machine)), Toast.LENGTH_LONG).show()
            finish(); return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        running.set(true)
        loop = thread(name = "vr-render") { render(src) }
    }

    override fun onDestroy() {
        // left some other way than the menu button (the system closed it): end the loop, give the screen back
        // (the render thread ends on its own once the runtime has closed the session)
        running.set(false)
        synchronized(lock) { handle.takeIf { it != 0L }?.let { VrNative.requestExit(it) } }
        handBack()
        super.onDestroy()
    }

    private fun render(src: VmHost.ScreenSource) {
        val vm = VmHost.vm ?: return
        val h = VrNative.create(this, src.w, src.h)
        if (h == 0L) {
            val why = VrNative.lastError()
            vm.log("vr: could not start: $why")
            runOnUiThread { Toast.makeText(this, getString(R.string.vr_failed, why), Toast.LENGTH_LONG).show(); finish() }
            return
        }
        handle = h
        vm.log("vr: started, screen ${src.w}x${src.h}")
        val st = SurfaceTexture(VrNative.oesTexture(h))
        st.setDefaultBufferSize(src.w, src.h)
        val fresh = AtomicBoolean(false)
        st.setOnFrameAvailableListener { fresh.set(true) }
        takeScreen(st)

        // fb0 (the software path, and the GPU path until its first frame): read on a thread of its own, ~20 a second
        val fbFrame = AtomicBoolean(false)
        val fbPx = IntArray(src.w * src.h)
        val readPx = IntArray(src.w * src.h)
        val grabber = thread(name = "vr-fb") {
            var last = 0
            while (running.get()) {
                val t = System.currentTimeMillis()
                if (!src.gpu() && !fbFrame.get() && src.grab(readPx)) {
                    val sum = readPx.contentHashCode()
                    if (sum != last) { last = sum; synchronized(fbPx) { readPx.copyInto(fbPx) }; fbFrame.set(true) }
                }
                Thread.sleep((50 - (System.currentTimeMillis() - t)).coerceIn(5, 50))
            }
        }

        hint()?.let { (px, w, ht) -> VrNative.setHint(h, px, w, ht) }
        val out = FloatArray(11)
        val m = FloatArray(16)
        val touch = TouchState(vm, src.w, src.h)
        var lastScroll = 0L
        while (true) {
            if (fresh.getAndSet(false)) {
                runCatching { st.updateTexImage(); st.getTransformMatrix(m); VrNative.oesFrame(h, m) }
            }
            if (fbFrame.get()) { synchronized(fbPx) { VrNative.upload(h, fbPx, src.w, src.h) }; fbFrame.set(false) }
            if (VrNative.frame(h, out) == 1) break
            if (out[0] < 0.5f) { touch.lift(); continue }
            touch.update(out)
            if (out[7] > 0.5f) thread { vm.input.press(vm.input.homeCode) }
            if (out[8] > 0.5f) thread { vm.input.press(InputService.KEY_BACK) }
            if (out[9] > 0.5f) thread { vm.input.recents(vm.img.api, vm.img.skin) }
            // the thumbstick scrolls: pulled back reads on down the page (the finger goes up)
            val now = System.currentTimeMillis()
            if (kotlin.math.abs(out[10]) > 0.7f && !touch.anyDown && now - lastScroll > 380) {
                lastScroll = now
                val x = src.w / 2
                val y0 = src.h / 2
                val reach = src.h / 3 * (if (out[10] < 0) 1 else -1)
                thread(name = "vr-scroll") { vm.input.swipe(x, y0, x, (y0 - reach).coerceIn(1, src.h - 2), 200) }
            }
        }
        touch.lift()
        running.set(false)
        grabber.join(500)
        synchronized(lock) { handle = 0L }
        VrNative.destroy(h)
        vm.log("vr: ended")
        // give the screen back to the window before it shows again, then go
        val done = CountDownLatch(1)
        runOnUiThread { handBack(); done.countDown(); if (!isFinishing) finish() }
        done.await(2, TimeUnit.SECONDS)
        st.release()
    }

    /** the GPU bridge draws into [st] from now on (on the main thread, like the window's surface callbacks) */
    private fun takeScreen(st: SurfaceTexture) {
        val vm = VmHost.vm ?: return
        val done = CountDownLatch(1)
        runOnUiThread {
            VmHost.vrOn = true
            surface = Surface(st)
            vm.surface = surface
            oldResurface = vm.resurface
            // the guest restarted: the bridge wants a fresh window (as GuestVm.reseatGlSurface asks of the SurfaceView)
            vm.resurface = {
                runOnUiThread {
                    if (VmHost.vrOn) {
                        val old = surface
                        vm.surface = null
                        surface = Surface(st)
                        vm.surface = surface
                        old?.release()
                    }
                }
            }
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
    }

    /** back to the window's SurfaceView (if it has one now; else its next surfaceCreated takes over) */
    private fun handBack() {
        if (!VmHost.vrOn) return
        VmHost.vrOn = false
        val vm = VmHost.vm
        if (vm != null) {
            oldResurface?.let { vm.resurface = it }
            vm.surface = VmHost.panelSurface?.takeIf { it.isValid }
        }
        surface?.release()
        surface = null
    }

    /** the controls, drawn once as a picture to hang under the screen */
    private fun hint(): Triple<IntArray, Int, Int>? = runCatching {
        val lines = resources.getStringArray(R.array.vr_hint)
        val w = 1100
        val lineH = 46
        val ht = 36 + lineH * lines.size
        val bmp = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0101428.toInt() }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), ht.toFloat()), 34f, 34f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8ECFF.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER }
        lines.forEachIndexed { i, s -> c.drawText(s, w / 2f, 18f + lineH * (i + 1) - 12f, text) }
        val px = IntArray(w * ht)
        bmp.getPixels(px, 0, w, 0, 0, w, ht)
        bmp.recycle()
        Triple(px, w, ht)
    }.getOrNull()

    /** the triggers as fingers: left hand finger 1, right hand finger 0; only changes are sent */
    private class TouchState(val vm: app.aemu.core.GuestVm, val w: Int, val h: Int) {
        private var last: List<InputService.P> = emptyList()
        val anyDown get() = last.isNotEmpty()
        fun update(out: FloatArray) {
            val now = ArrayList<InputService.P>(2)
            for (hand in 1 downTo 0) {
                val b = 1 + hand * 3
                if (out[b] > 0.5f) now += InputService.P(1 - hand,
                    (out[b + 1] * w).toInt().coerceIn(0, w - 1), (out[b + 2] * h).toInt().coerceIn(0, h - 1))
            }
            if (now != last) { vm.input.touch(now); last = now }
        }
        fun lift() { if (last.isNotEmpty()) { vm.input.touch(emptyList()); last = emptyList() } }
    }
}

/** VR mode of the machine in process slot 1..3 (see VmActivitySlots) */
class VrActivity1 : VrActivity()
class VrActivity2 : VrActivity()
class VrActivity3 : VrActivity()
