/* Added for AEmulator Moon Valley, 2026-11-25: the Xperia Play's gamepad on the screen (see core/XperiaPad). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aemu.core.XperiaPad
import app.aemu.core.XperiaPad.Button

private val PLATE = Color(0xff15171c)
private val INK = Color.White

/**
 * The buttons over the picture. [turned]: with the gamepad out the guest lays its picture on its side (the phone is held
 * sideways), so the buttons are laid out for that: up is the window's right side.
 */
@Composable
fun GamepadOverlay(turned: Boolean, onButton: (Button, Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val area = if (turned) Modifier.requiredSize(maxHeight, maxWidth).rotate(90f) else Modifier.fillMaxSize()
        val short = minOf(maxWidth, maxHeight)
        val cluster = (short * 0.42f).coerceIn(128.dp, 190.dp)
        Box(area) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Pill("L", Button.L, cluster * 0.62f, onButton)
                Zone(Modifier.size(cluster), "D-pad", { x, y -> XperiaPad.directions(x, y, 0.22f) }, onButton) { drawDpad(it) }
                Pill("SELECT", Button.SELECT, cluster * 0.5f, onButton, 30.dp)
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Pill("R", Button.R, cluster * 0.62f, onButton)
                Zone(Modifier.size(cluster), "Buttons", { x, y -> XperiaPad.face(x, y, 0.25f) }, onButton) { drawFace(it) }
                Pill("START", Button.START, cluster * 0.5f, onButton, 30.dp)
            }
        }
    }
}

/** A part of the pad: every finger on it presses what [hit] says for its place (-1..1), also while it slides. */
@Composable
private fun Zone(modifier: Modifier, label: String, hit: (Float, Float) -> Set<Button>, onButton: (Button, Boolean) -> Unit,
                 draw: DrawScope.(Set<Button>) -> Unit) {
    var held by remember { mutableStateOf(emptySet<Button>()) }
    val send by rememberUpdatedState(onButton)
    val haptic = LocalHapticFeedback.current
    Canvas(modifier.semantics { contentDescription = label }.pointerInput(Unit) {
        awaitPointerEventScope {
            try {
                while (true) {
                    val e = awaitPointerEvent()
                    val now = LinkedHashSet<Button>()
                    for (c in e.changes) {
                        if (c.pressed) now += hit(c.position.x / size.width * 2f - 1f, c.position.y / size.height * 2f - 1f)
                        c.consume()
                    }
                    if (now != held) {
                        for (b in held) if (b !in now) send(b, false)
                        val fresh = now.filter { it !in held }
                        for (b in fresh) send(b, true)
                        if (fresh.isNotEmpty()) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        held = now
                    }
                }
            } finally {
                for (b in held) send(b, false)
                held = emptySet()
            }
        }
    }) { draw(held) }
}

@Composable
private fun Pill(text: String, button: Button, width: Dp, onButton: (Button, Boolean) -> Unit, height: Dp = 40.dp) {
    Box(contentAlignment = Alignment.Center) {
        Zone(Modifier.size(width, height), text, { _, _ -> setOf(button) }, onButton) { held ->
            val r = CornerRadius(size.height / 2)
            drawRoundRect(PLATE.copy(alpha = if (held.isEmpty()) 0.45f else 0.8f), cornerRadius = r)
            drawRoundRect(INK.copy(alpha = if (held.isEmpty()) 0.45f else 0.95f), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        }
        Text(text, color = INK.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
    }
}

private fun DrawScope.drawDpad(held: Set<Button>) {
    val r = size.minDimension / 2
    drawCircle(PLATE.copy(alpha = 0.4f), r)
    val arm = r * 0.36f
    for ((b, dx, dy) in listOf(Triple(Button.UP, 0f, -1f), Triple(Button.DOWN, 0f, 1f), Triple(Button.LEFT, -1f, 0f), Triple(Button.RIGHT, 1f, 0f))) {
        val on = b in held
        val c = center + Offset(dx, dy) * (r * 0.58f)
        val w = if (dx != 0f) r * 0.62f else arm * 1.5f
        val h = if (dx != 0f) arm * 1.5f else r * 0.62f
        drawRoundRect(INK.copy(alpha = if (on) 0.85f else 0.16f), c - Offset(w / 2, h / 2), Size(w, h), CornerRadius(arm * 0.35f))
        val t = arm * 0.34f
        val p = Path().apply {
            val tip = c + Offset(dx, dy) * t
            val side = Offset(dy, dx) * t
            moveTo(tip.x, tip.y)
            lineTo(c.x - dx * t + side.x, c.y - dy * t + side.y)
            lineTo(c.x - dx * t - side.x, c.y - dy * t - side.y)
            close()
        }
        drawPath(p, if (on) PLATE else INK.copy(alpha = 0.8f))
    }
}

private fun DrawScope.drawFace(held: Set<Button>) {
    val r = size.minDimension / 2
    val key = r * 0.36f
    for ((b, dx, dy) in listOf(Triple(Button.TRIANGLE, 0f, -1f), Triple(Button.CROSS, 0f, 1f), Triple(Button.SQUARE, -1f, 0f), Triple(Button.CIRCLE, 1f, 0f))) {
        val on = b in held
        val c = center + Offset(dx, dy) * (r * 0.62f)
        drawCircle(PLATE.copy(alpha = if (on) 0.85f else 0.45f), key, c)
        drawCircle(INK.copy(alpha = if (on) 0.95f else 0.45f), key, c, style = Stroke(1.5.dp.toPx()))
        val ink = INK.copy(alpha = if (on) 1f else 0.8f)
        val s = key * 0.45f
        val line = Stroke(2.dp.toPx())
        when (b) {
            Button.CIRCLE -> drawCircle(ink, s, c, style = line)
            Button.SQUARE -> drawRect(ink, c - Offset(s, s) * 0.9f, Size(s, s) * 1.8f, style = line)
            Button.CROSS -> {
                drawLine(ink, c - Offset(s, s), c + Offset(s, s), line.width)
                drawLine(ink, c + Offset(-s, s), c + Offset(s, -s), line.width)
            }
            else -> drawPath(Path().apply {
                moveTo(c.x, c.y - s); lineTo(c.x + s, c.y + s * 0.75f); lineTo(c.x - s, c.y + s * 0.75f); close()
            }, ink, style = line)
        }
    }
}
