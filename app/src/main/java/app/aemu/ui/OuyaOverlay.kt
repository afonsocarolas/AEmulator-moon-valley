/* Added for AEmulator Moon Valley, 2026-11-26: the OUYA's controller on the screen (see core/OuyaPad). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aemu.core.OuyaPad.Button
import app.aemu.core.XperiaPad
import kotlin.math.sqrt

private val PLATE = Color(0xff15171c)
private val INK = Color.White
private val O_GREEN = Color(0xff3fb34f)
private val U_BLUE = Color(0xff3b6fd8)
private val Y_YELLOW = Color(0xffe8c22b)
private val A_RED = Color(0xffd8433b)

/**
 * The OUYA controller over the picture: sticks at the bottom corners, the D-pad and O U Y A above them, the shoulder
 * buttons at the top corners and the OUYA button at the top. [onStick]: 0 left, 1 right, x and y -1..1 (y down).
 */
@Composable
fun OuyaOverlay(onButton: (Button, Boolean) -> Unit, onStick: (Int, Float, Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = minOf(maxWidth, maxHeight)
        val stick = (short * 0.36f).coerceIn(96.dp, 170.dp)
        val cluster = (short * 0.30f).coerceIn(90.dp, 150.dp)
        Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("L2", Button.L2, onButton); Pill("L1", Button.L1, onButton)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("R1", Button.R1, onButton); Pill("R2", Button.R2, onButton)
        }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Pill("OUYA", Button.HOME, onButton, 72.dp)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Zone(Modifier.size(cluster), "D-pad", { x, y ->
                XperiaPad.directions(x, y, 0.22f).mapTo(LinkedHashSet()) { when (it) {
                    XperiaPad.Button.UP -> Button.UP; XperiaPad.Button.DOWN -> Button.DOWN; XperiaPad.Button.LEFT -> Button.LEFT; else -> Button.RIGHT } }
            }, onButton) { drawDpad(it) }
            Stick(Modifier.size(stick), "Left stick") { x, y -> onStick(0, x, y) }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End) {
            Zone(Modifier.size(cluster), "Buttons", { x, y ->
                XperiaPad.face(x, y, 0.25f).mapTo(LinkedHashSet()) { when (it) {
                    XperiaPad.Button.TRIANGLE -> Button.Y; XperiaPad.Button.CROSS -> Button.O; XperiaPad.Button.SQUARE -> Button.U; else -> Button.A } }
            }, onButton) { drawFace(it) }
            Stick(Modifier.size(stick), "Right stick") { x, y -> onStick(1, x, y) }
        }
    }
}

/** A stick: the finger's place from the middle, -1..1 each way; back to the middle when it lets go. */
@Composable
private fun Stick(modifier: Modifier, label: String, onMove: (Float, Float) -> Unit) {
    var at by remember { mutableStateOf(Offset.Zero) }
    val send by rememberUpdatedState(onMove)
    Canvas(modifier.semantics { contentDescription = label }.pointerInput(Unit) {
        awaitPointerEventScope {
            try {
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.pressed }
                    e.changes.forEach { it.consume() }
                    val r = size.width / 2f
                    var x = 0f; var y = 0f
                    if (c != null) {
                        x = (c.position.x - r) / r; y = (c.position.y - size.height / 2f) / r
                        val d = sqrt(x * x + y * y)
                        if (d > 1f) { x /= d; y /= d }
                    }
                    if (Offset(x, y) != at) { at = Offset(x, y); send(x, y) }
                }
            } finally { at = Offset.Zero; send(0f, 0f) }
        }
    }) {
        val r = size.minDimension / 2
        drawCircle(PLATE.copy(alpha = 0.35f), r)
        drawCircle(INK.copy(alpha = 0.4f), r, style = Stroke(1.5.dp.toPx()))
        drawCircle(INK.copy(alpha = if (at == Offset.Zero) 0.35f else 0.75f), r * 0.38f, center + at * (r * 0.62f))
    }
}

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
            } finally { for (b in held) send(b, false); held = emptySet() }
        }
    }) { draw(held) }
}

@Composable
private fun Pill(text: String, button: Button, onButton: (Button, Boolean) -> Unit, width: Dp = 56.dp) {
    Box(contentAlignment = Alignment.Center) {
        Zone(Modifier.size(width, 34.dp), text, { _, _ -> setOf(button) }, onButton) { held ->
            val r = CornerRadius(size.height / 2)
            drawRoundRect(PLATE.copy(alpha = if (held.isEmpty()) 0.45f else 0.8f), cornerRadius = r)
            drawRoundRect(INK.copy(alpha = if (held.isEmpty()) 0.45f else 0.95f), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        }
        Text(text, color = INK.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
    }
}

private fun DrawScope.drawDpad(held: Set<Button>) {
    val r = size.minDimension / 2
    drawCircle(PLATE.copy(alpha = 0.35f), r)
    for ((b, dx, dy) in listOf(Triple(Button.UP, 0f, -1f), Triple(Button.DOWN, 0f, 1f), Triple(Button.LEFT, -1f, 0f), Triple(Button.RIGHT, 1f, 0f))) {
        val c = center + Offset(dx, dy) * (r * 0.6f)
        drawCircle(INK.copy(alpha = if (b in held) 0.85f else 0.25f), r * 0.26f, c)
    }
}

private fun DrawScope.drawFace(held: Set<Button>) {
    val r = size.minDimension / 2
    for ((b, dx, dy, col) in listOf(Quad(Button.Y, 0f, -1f, Y_YELLOW), Quad(Button.O, 0f, 1f, O_GREEN),
            Quad(Button.U, -1f, 0f, U_BLUE), Quad(Button.A, 1f, 0f, A_RED))) {
        val c = center + Offset(dx, dy) * (r * 0.62f)
        val key = r * 0.32f
        val on = b in held
        // a dark button with a grey rim, the letter in its colour, as on the controller
        drawCircle(Color(if (on) 0xff34373e else 0xff101114).copy(alpha = if (on) 0.95f else 0.8f), key, c)
        drawCircle(Color(0xff8a8f98).copy(alpha = if (on) 0.9f else 0.55f), key, c, style = Stroke(1.5.dp.toPx()))
        drawLetter(b, c, key * 0.5f, col.copy(alpha = if (on) 1f else 0.9f), maxOf(2.dp.toPx(), key * 0.12f))
    }
}

/** The OUYA's letters: O a ring, U a cup, Y a cup on a stem, A an arch with a bar; [s] is half the letter's height. */
private fun DrawScope.drawLetter(b: Button, c: Offset, s: Float, col: Color, w: Float) {
    val line = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val hw = s * 0.62f
    when (b) {
        Button.O -> drawCircle(col, s * 0.72f, c, style = line)
        Button.U -> drawPath(Path().apply {
            moveTo(c.x - hw, c.y - s * 0.75f); lineTo(c.x - hw, c.y + s * 0.1f)
            arcTo(Rect(c.x - hw, c.y - s * 0.5f, c.x + hw, c.y + s * 0.72f), 180f, -180f, false)
            lineTo(c.x + hw, c.y - s * 0.75f)
        }, col, style = line)
        Button.Y -> {
            drawPath(Path().apply {
                moveTo(c.x - hw, c.y - s * 0.8f); lineTo(c.x - hw, c.y - s * 0.45f)
                arcTo(Rect(c.x - hw, c.y - s * 1.0f, c.x + hw, c.y + s * 0.1f), 180f, -180f, false)
                lineTo(c.x + hw, c.y - s * 0.8f)
            }, col, style = line)
            drawLine(col, Offset(c.x, c.y + s * 0.1f), Offset(c.x, c.y + s * 0.8f), w, StrokeCap.Round)
        }
        else -> {
            drawPath(Path().apply {
                moveTo(c.x - hw, c.y + s * 0.75f); lineTo(c.x - hw, c.y - s * 0.05f)
                arcTo(Rect(c.x - hw, c.y - s * 0.7f, c.x + hw, c.y + s * 0.6f), 180f, 180f, false)
                lineTo(c.x + hw, c.y + s * 0.75f)
            }, col, style = line)
            drawLine(col, Offset(c.x - hw, c.y + s * 0.2f), Offset(c.x + hw, c.y + s * 0.2f), w, StrokeCap.Round)
        }
    }
}

private data class Quad(val b: Button, val dx: Float, val dy: Float, val col: Color)
