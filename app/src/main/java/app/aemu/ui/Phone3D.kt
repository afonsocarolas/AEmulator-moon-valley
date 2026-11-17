/* Added for AEmulator Moon Valley, 2026-11-17: the 3D phone showcase of a running machine. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aemu.R
import app.aemu.core.SkinRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point after rotation: model x, y (y down), z (towards the viewer). */
private class P3(val x: Float, val y: Float, val z: Float)

/**
 * The phone as a rounded box: the outline of its face (a rounded rectangle, corners sampled) extruded by its depth.
 * Everything is projected here with one camera, so the faces, the sides and the screen picture always agree.
 */
private class PhoneModel(val w: Float, val h: Float, val depth: Float, val corner: Float) {
    /** the outline of the face, clockwise in screen terms, centred on 0,0 */
    val outline: List<Pair<Float, Float>> = buildList {
        val r = corner; val hw = w / 2; val hh = h / 2; val steps = 6
        fun arc(cx: Float, cy: Float, from: Double) {
            for (i in 0..steps) {
                val a = from + (PI / 2) * i / steps
                add((cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat())
            }
        }
        arc(hw - r, -hh + r, -PI / 2)      // top right
        arc(hw - r, hh - r, 0.0)            // bottom right
        arc(-hw + r, hh - r, PI / 2)        // bottom left
        arc(-hw + r, -hh + r, PI)           // top left
    }
}

private class Camera(val ry: Float, val rx: Float, val cx: Float, val cy: Float, val f: Float) {
    private val sy = sin(ry); private val cyr = cos(ry); private val sx = sin(rx); private val cxr = cos(rx)
    fun rot(x: Float, y: Float, z: Float): P3 {
        val x1 = x * cyr + z * sy
        val z1 = -x * sy + z * cyr
        val y2 = y * cxr - z1 * sx
        val z2 = y * sx + z1 * cxr
        return P3(x1, y2, z2)
    }
    fun proj(p: P3): FloatArray { val s = f / (f - p.z); return floatArrayOf(cx + p.x * s, cy + p.y * s) }
    fun at(x: Float, y: Float, z: Float) = proj(rot(x, y, z))
    /** z of a direction after rotation (the normal's facing: > 0 looks at the viewer) */
    fun facing(nx: Float, ny: Float, nz: Float) = rot(nx, ny, nz).z
}

/**
 * Maps the rectangle 0,0..lw,lh of a face's own drawing onto that face of the phone: a perspective of a plane is a
 * projective map, so four corners fix it exactly.
 */
private fun faceMatrix(cam: Camera, lw: Float, lh: Float, corners: List<Triple<Float, Float, Float>>): Matrix {
    val dst = FloatArray(8)
    corners.forEachIndexed { i, (x, y, z) -> val p = cam.at(x, y, z); dst[2 * i] = p[0]; dst[2 * i + 1] = p[1] }
    return Matrix().apply { setPolyToPoly(floatArrayOf(0f, 0f, lw, 0f, lw, lh, 0f, lh), 0, dst, 0, 4) }
}

private fun shade(c: Int, k: Float): Int {
    val f = k.coerceIn(0f, 1.3f)
    fun ch(v: Int) = (v * f).toInt().coerceIn(0, 255)
    return android.graphics.Color.argb(255, ch(android.graphics.Color.red(c)), ch(android.graphics.Color.green(c)), ch(android.graphics.Color.blue(c)))
}

/**
 * The running machine on a phone that turns in 3D. Drag to turn it, double-tap to face it front on; tilting the real
 * phone tilts it a little more. The screen is a live copy of the guest's (about ten frames a second, taken with
 * [grab]), so it keeps playing while it turns. Touches go to the 3D view only; the machine runs on behind it.
 *
 * [skin] / [skinScreen]: the device skin picture of the VM theme, when one is chosen, becomes the phone's front;
 * otherwise a plain phone is drawn around the screen.
 */
@Composable
fun Phone3DDialog(
    guestW: Int, guestH: Int, grab: (IntArray) -> Boolean,
    skin: Bitmap?, skinScreen: SkinRect?, name: String, onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var ry by remember { mutableFloatStateOf(-28f) }
        var rx by remember { mutableFloatStateOf(10f) }
        var spin by remember { mutableStateOf(true) }
        var tiltX by remember { mutableFloatStateOf(0f) }
        var tiltY by remember { mutableFloatStateOf(0f) }
        var frame by remember { mutableStateOf<Bitmap?>(null) }

        // the live screen: three bitmaps taken in turn, so the one being drawn is never the one being filled
        LaunchedEffect(guestW, guestH) {
            if (guestW <= 0 || guestH <= 0) return@LaunchedEffect
            withContext(Dispatchers.Default) {
                val px = IntArray(guestW * guestH)
                val bufs = Array(3) { Bitmap.createBitmap(guestW, guestH, Bitmap.Config.ARGB_8888) }
                var i = 0
                while (isActive) {
                    if (runCatching { grab(px) }.getOrDefault(false)) {
                        val b = bufs[i]; b.setPixels(px, 0, guestW, 0, 0, guestW, guestH)
                        withContext(Dispatchers.Main) { frame = b }
                        i = (i + 1) % bufs.size
                    }
                    delay(100)
                }
            }
        }
        // a slow turn until the person takes over
        LaunchedEffect(spin) {
            var last = withFrameMillis { it }
            while (spin) {
                val now = withFrameMillis { it }
                ry += (now - last) * 0.03f
                last = now
            }
        }
        // the real phone's tilt adds a little (accelerometer, smoothed)
        DisposableEffect(Unit) {
            val sm = ctx.getSystemService(SensorManager::class.java)
            val s = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    tiltY = tiltY * 0.88f + (-e.values[0] * 3f).coerceIn(-25f, 25f) * 0.12f
                    tiltX = tiltX * 0.88f + ((e.values[1] - 7f) * 2.5f).coerceIn(-20f, 20f) * 0.12f
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sm?.unregisterListener(l) }
        }

        val bodyColor = remember(skin, skinScreen) {
            // the bezel above the screen gives a skin's colour; a plain phone is graphite
            if (skin != null && skinScreen != null) runCatching {
                skin.getPixel((skin.width * 0.5f).toInt(), (skin.height * skinScreen.t * 0.5f).toInt().coerceAtLeast(1))
                    .let { if (android.graphics.Color.alpha(it) < 128) 0xFF2A2D36.toInt() else it or 0xFF000000.toInt() }
            }.getOrDefault(0xFF2A2D36.toInt()) else 0xFF2A2D36.toInt()
        }
        val title = stringResource(R.string.phone3d_back_title)
        val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        spin = false
                        val turns = Math.round(ry / 360f) * 360f
                        scope.launch { animate(ry, turns, animationSpec = tween(600)) { v, _ -> ry = v } }
                        scope.launch { animate(rx, 0f, animationSpec = tween(600)) { v, _ -> rx = v } }
                    })
                }
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { spin = false }) { change, d ->
                        change.consume()
                        ry += d.x * 0.4f
                        rx = (rx - d.y * 0.4f).coerceIn(-70f, 70f)
                    }
                },
        ) {
            MoonBootBackdrop(Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                // size the phone to the view: its face keeps the guest's shape (or the skin picture's)
                val sAsp = guestW.toFloat().coerceAtLeast(1f) / guestH.toFloat().coerceAtLeast(1f)
                val asp: Float; val scr: RectF   // screen rectangle in face fractions
                if (skin != null && skinScreen != null) {
                    asp = skin.width.toFloat() / skin.height
                    scr = RectF(skinScreen.l, skinScreen.t, skinScreen.r, skinScreen.b)
                } else {
                    val sw = 0.92f; val sh = sw / sAsp
                    val bodyH = sh / 0.84f            // 8 % bezel above and below
                    asp = 1f / bodyH
                    scr = RectF(0.04f, 0.08f, 0.96f, 0.92f)
                }
                val h = min(size.height * 0.66f, size.width * 0.66f / asp)
                val w = h * asp
                val model = PhoneModel(w, h, depth = w * 0.075f, corner = w * 0.09f)
                val cam = Camera(((ry + tiltY) * PI / 180).toFloat(), ((rx + tiltX) * PI / 180).toFloat(),
                    size.width / 2, size.height * 0.46f, f = max(w, h) * 3.2f)
                val zf = model.depth / 2; val zb = -model.depth / 2
                val light = floatArrayOf(-0.45f, -0.55f, 0.7f).let { v -> val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); floatArrayOf(v[0] / n, v[1] / n, v[2] / n) }

                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    // the sides: one quad per pair of outline points, those that look at the viewer, lit by their normal
                    val o = model.outline
                    val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    for (k in o.indices) {
                        val (x0, y0) = o[k]; val (x1, y1) = o[(k + 1) % o.size]
                        val ex = x1 - x0; val ey = y1 - y0
                        val len = sqrt(ex * ex + ey * ey).coerceAtLeast(1e-3f)
                        val nx = ey / len; val ny = -ex / len          // outward for a clockwise outline (y down)
                        val n = cam.rot(nx, ny, 0f)
                        if (n.z <= 0f) continue
                        val lit = 0.35f + 0.75f * max(0f, n.x * light[0] + n.y * light[1] + n.z * light[2])
                        sidePaint.color = shade(bodyColor, lit)
                        val a = cam.at(x0, y0, zf); val b = cam.at(x1, y1, zf); val bb = cam.at(x1, y1, zb); val ab = cam.at(x0, y0, zb)
                        val path = android.graphics.Path().apply { moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(bb[0], bb[1]); lineTo(ab[0], ab[1]); close() }
                        nc.drawPath(path, sidePaint)
                    }

                    val hw = w / 2; val hh = h / 2
                    val front = cam.facing(0f, 0f, 1f) > 0f
                    if (front) {
                        val m = faceMatrix(cam, w, h, listOf(Triple(-hw, -hh, zf), Triple(hw, -hh, zf), Triple(hw, hh, zf), Triple(-hw, hh, zf)))
                        nc.save(); nc.concat(m)
                        paint.color = bodyColor
                        nc.drawRoundRect(RectF(0f, 0f, w, h), model.corner, model.corner, paint)
                        if (skin != null) nc.drawBitmap(skin, null, RectF(0f, 0f, w, h), paint)
                        else {
                            // a plain phone: a camera dot and an earpiece above the screen
                            paint.color = 0xFF0B0D14.toInt()
                            nc.drawCircle(w / 2, h * 0.04f, w * 0.018f, paint)
                            nc.drawRoundRect(RectF(w * 0.40f, h * 0.025f, w * 0.48f, h * 0.035f), w, w, paint)
                        }
                        val sr = RectF(scr.left * w, scr.top * h, scr.right * w, scr.bottom * h)
                        paint.color = android.graphics.Color.BLACK
                        nc.drawRect(sr, paint)
                        frame?.let { nc.drawBitmap(it, null, sr, paint) }
                        // a soft glare that slides as the phone turns
                        val g = (((ry + tiltY) % 360f + 360f) % 360f) / 360f
                        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = android.graphics.LinearGradient(sr.left + sr.width() * (g * 2 - 0.5f), sr.top, sr.left + sr.width() * (g * 2), sr.bottom,
                                intArrayOf(0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
                        }
                        nc.drawRect(sr, gp)
                        nc.restore()
                    } else {
                        // the back, drawn the right way round for whoever looks at it: its left is the model's right
                        val m = faceMatrix(cam, w, h, listOf(Triple(hw, -hh, zb), Triple(-hw, -hh, zb), Triple(-hw, hh, zb), Triple(hw, hh, zb)))
                        nc.save(); nc.concat(m)
                        paint.color = shade(bodyColor, 1.08f)
                        nc.drawRoundRect(RectF(0f, 0f, w, h), model.corner, model.corner, paint)
                        // the camera module
                        paint.color = shade(bodyColor, 0.6f)
                        nc.drawRoundRect(RectF(w * 0.07f, h * 0.04f, w * 0.30f, h * 0.17f), w * 0.05f, w * 0.05f, paint)
                        paint.color = 0xFF0B0D14.toInt()
                        nc.drawCircle(w * 0.185f, h * 0.075f, w * 0.045f, paint)
                        nc.drawCircle(w * 0.185f, h * 0.135f, w * 0.045f, paint)
                        // the Moon Valley crescent and names
                        val mc = 0xFFF4F1DE.toInt()
                        paint.color = mc
                        nc.drawCircle(w / 2, h * 0.46f, w * 0.12f, paint)
                        paint.color = shade(bodyColor, 1.08f)
                        nc.drawCircle(w / 2 + w * 0.06f, h * 0.46f - w * 0.035f, w * 0.11f, paint)
                        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mc; textAlign = Paint.Align.CENTER; textSize = w * 0.065f; isFakeBoldText = true }
                        nc.drawText(title, w / 2, h * 0.64f, tp)
                        tp.textSize = w * 0.045f; tp.isFakeBoldText = false; tp.alpha = 190
                        nc.drawText(name.take(40), w / 2, h * 0.69f, tp)
                        nc.restore()
                    }
                }
            }
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.phone3d_done)) }
                FilterChip(selected = spin, onClick = { spin = !spin }, label = { Text(stringResource(R.string.phone3d_spin)) })
            }
            Text(stringResource(R.string.phone3d_hint), color = Color(0xFFF4F1DE).copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp))
        }
    }
}
