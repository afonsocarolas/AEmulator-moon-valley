/* Added for AEmulator Moon Valley, 2026-11-14: the moon UI (title, starry sky over a valley, moon-phase button)
 * and the Moon Valley easter egg. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aemu.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

const val MOON_TITLE = "AEmulator Moon Valley"

/** Taps on the moon needed to reach the full moon (and the easter egg). */
private const val TAPS_TO_FULL_MOON = 7

private val MoonLight = Color(0xFFF4F1DE)
private val MoonGold = Color(0xFFFFD98A)

@Composable
fun MoonTitle(style: TextStyle, color: Color) {
    Text(MOON_TITLE, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val base: Float)

private fun stars(count: Int, seed: Int, maxY: Float) = Random(seed).let { rnd ->
    List(count) {
        Star(rnd.nextFloat(), rnd.nextFloat() * maxY, 0.6f + rnd.nextFloat() * 1.6f,
            rnd.nextFloat() * 2f * PI.toFloat(), 0.35f + rnd.nextFloat() * 0.65f)
    }
}

private fun DrawScope.drawStars(list: List<Star>, color: Color, t: Float) {
    for (s in list) {
        val a = s.base * (0.55f + 0.45f * sin(t + s.phase))
        drawCircle(color.copy(alpha = color.alpha * a), radius = s.r.dp.toPx(), center = Offset(s.x * size.width, s.y * size.height))
    }
}

/** Two rolling hills along the bottom: the valley. */
private fun DrawScope.drawValley(far: Color, near: Color, height: Float) {
    val w = size.width; val h = size.height; val top = h - height
    val farHill = Path().apply {
        moveTo(0f, top + height * 0.35f)
        cubicTo(w * 0.25f, top - height * 0.05f, w * 0.45f, top + height * 0.30f, w * 0.65f, top + height * 0.15f)
        cubicTo(w * 0.80f, top + height * 0.05f, w * 0.92f, top + height * 0.20f, w, top + height * 0.10f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    val nearHill = Path().apply {
        moveTo(0f, top + height * 0.60f)
        cubicTo(w * 0.20f, top + height * 0.40f, w * 0.40f, top + height * 0.75f, w * 0.60f, top + height * 0.55f)
        cubicTo(w * 0.78f, top + height * 0.38f, w * 0.90f, top + height * 0.55f, w, top + height * 0.50f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(farHill, far)
    drawPath(nearHill, near)
}

/**
 * Draws the moon at [phase] (0 = new moon, 1 = full): the dark side stays faintly visible, the lit side is the moon
 * disc minus a shadow disc sliding off to the left. A few soft craters on the lit side.
 */
private fun DrawScope.drawMoon(center: Offset, radius: Float, phase: Float, lit: Color, dark: Color, craters: Boolean = true) {
    drawCircle(dark, radius, center)
    val disc = Path().apply { addOval(Rect(center, radius)) }
    val shadowX = center.x - radius * 2f * phase.coerceIn(0f, 1f)
    val shadow = Path().apply { addOval(Rect(Offset(shadowX, center.y), radius * 1.02f)) }
    val litPath = Path().apply { op(disc, shadow, PathOperation.Difference) }
    drawPath(litPath, lit)
    if (craters && phase > 0.05f) {
        val c = Color.Black.copy(alpha = 0.08f)
        drawCircle(c, radius * 0.22f, center + Offset(radius * 0.30f, -radius * 0.25f))
        drawCircle(c, radius * 0.14f, center + Offset(radius * 0.45f, radius * 0.35f))
        drawCircle(c, radius * 0.10f, center + Offset(-radius * 0.15f, radius * 0.45f))
        drawCircle(c, radius * 0.12f, center + Offset(-radius * 0.40f, -radius * 0.30f))
    }
}

/** The home screen's backdrop: a sky that follows the theme, twinkling stars up top, a moonlit valley below. */
@Composable
fun MoonSky(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val night = cs.surface.luminance() < 0.5f
    val list = remember { stars(70, seed = 1969, maxY = 0.5f) }
    val t by rememberInfiniteTransition(label = "sky").animateFloat(
        0f, 2f * PI.toFloat(), infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "twinkle",
    )
    val skyTop = if (night) Color(0xFF070918) else cs.surface
    val skyBottom = cs.surfaceContainerLow
    val starColor = if (night) MoonLight.copy(alpha = 0.9f) else cs.primary.copy(alpha = 0.35f)
    val far = cs.primaryContainer.copy(alpha = if (night) 0.35f else 0.55f)
    val near = cs.surfaceContainerHigh.copy(alpha = if (night) 0.9f else 0.8f)
    Canvas(modifier.background(Brush.verticalGradient(listOf(skyTop, skyBottom)))) {
        drawStars(list, starColor, t)
        drawValley(far, near, height = size.height * 0.18f)
    }
}

/**
 * The moon in the home screen's bar. Each tap waxes it a little; reach the full moon (7 quick taps) and Moon Valley
 * opens. It wanes back to a crescent when left alone for a few seconds.
 */
@Composable
fun MoonPhaseButton(onFullMoon: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val phase by animateFloatAsState(
        0.25f + 0.75f * taps / TAPS_TO_FULL_MOON,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "phase",
    )
    LaunchedEffect(taps) {
        if (taps in 1 until TAPS_TO_FULL_MOON) { delay(3000); taps = 0 }
        if (taps >= TAPS_TO_FULL_MOON) { delay(450); onFullMoon(); taps = 0 }
    }
    val cs = MaterialTheme.colorScheme
    val night = cs.surface.luminance() < 0.5f
    val lit = if (night) MoonLight else cs.primary
    val desc = stringResource(R.string.moon_button)
    IconButton(onClick = {
        if (taps < TAPS_TO_FULL_MOON) {
            taps++
            haptic.performHapticFeedback(if (taps == TAPS_TO_FULL_MOON) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        }
    }, modifier = Modifier.semantics { contentDescription = desc }) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2f
            if (taps == TAPS_TO_FULL_MOON) drawCircle(MoonGold.copy(alpha = 0.35f), r * 1.0f, center)
            drawMoon(center, r * 0.82f, phase, lit, lit.copy(alpha = 0.18f), craters = false)
        }
    }
}

/**
 * Added 2026-11-16: the Moon Valley boot screen, shown full screen while a firmware starts and before it draws
 * anything. The moon rises slowly over the valley; stars twinkle. It doesn't take touches.
 */
@Composable
fun MoonBootBackdrop(modifier: Modifier = Modifier) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(14000, easing = FastOutSlowInEasing)) }
    val list = remember { stars(110, seed = 2008, maxY = 0.7f) }
    val inf = rememberInfiniteTransition(label = "boot")
    val t by inf.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "twinkle")
    val glow by inf.animateFloat(0.9f, 1.08f, infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "glow")
    Canvas(modifier.background(Brush.verticalGradient(listOf(Color(0xFF03040C), Color(0xFF0E1433), Color(0xFF1E2556))))) {
        drawStars(list, MoonLight, t)
        val r = size.minDimension * 0.11f
        val c = Offset(size.width * 0.72f, size.height * (0.92f - 0.7f * rise.value))
        drawCircle(Brush.radialGradient(listOf(MoonGold.copy(alpha = 0.28f), Color.Transparent), c, r * 3f * glow), r * 3f * glow, c)
        drawMoon(c, r, 0.3f + 0.7f * rise.value, MoonLight, MoonLight.copy(alpha = 0.12f))
        drawValley(Color(0xFF151B40), Color(0xFF0A0E24), height = size.height * 0.2f)
    }
}

/** The loading indicator of the boot card: a moon running through its phases, new to full, again and again. */
@Composable
fun MoonPhaseSpinner(modifier: Modifier = Modifier) {
    val p by rememberInfiniteTransition(label = "phases").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "phase",
    )
    val lit = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(lit.copy(alpha = 0.12f * p), r, center)
        drawMoon(center, r * 0.72f, p, lit, lit.copy(alpha = 0.15f), craters = false)
    }
}

/** The easter egg: a full moon rises over Moon Valley while shooting stars cross the sky. */
@Composable
fun MoonValleyEgg(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val rise = remember { Animatable(0f) }
        LaunchedEffect(Unit) { rise.animateTo(1f, tween(2200, easing = FastOutSlowInEasing)) }
        val list = remember { stars(140, seed = 7, maxY = 0.75f) }
        val inf = rememberInfiniteTransition(label = "egg")
        val t by inf.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "twinkle")
        val glow by inf.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "glow")
        val shoots = listOf(0, 1100, 2300).map { offset ->
            inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3400, easing = LinearEasing), initialStartOffset = StartOffset(offset)),
                label = "shoot$offset")
        }
        val lines = stringArrayResource(R.array.moon_egg_lines)
        val line = remember { lines.random() }

        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF03040C), Color(0xFF0E1433), Color(0xFF1E2556)))),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawStars(list, MoonLight, t)
                // shooting stars: a bright head with a fading tail, top right to bottom left
                shoots.forEachIndexed { i, s ->
                    val p = s.value
                    if (p < 0.45f) {
                        val k = p / 0.45f
                        val start = Offset(size.width * (0.95f - i * 0.22f), size.height * (0.05f + i * 0.08f))
                        val head = start + Offset(-size.width * 0.55f * k, size.height * 0.28f * k)
                        val tail = head + Offset(size.width * 0.12f, -size.height * 0.06f)
                        drawLine(
                            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f * (1f - k)), Color.Transparent), head, tail),
                            head, tail, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                        )
                    }
                }
                // the moon rises from behind the valley
                val r = size.minDimension * 0.17f
                val c = Offset(size.width * 0.5f, size.height * (0.95f - 0.62f * rise.value))
                drawCircle(Brush.radialGradient(listOf(MoonGold.copy(alpha = 0.35f), Color.Transparent), c, r * 2.6f * glow), r * 2.6f * glow, c)
                drawMoon(c, r, 1f, MoonLight, MoonLight)
                drawValley(Color(0xFF151B40), Color(0xFF0A0E24), height = size.height * 0.22f)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.moon_egg_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = MoonLight, textAlign = TextAlign.Center)
                Text(line, style = MaterialTheme.typography.bodyLarge, color = MoonLight.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.moon_egg_close)) }
            }
        }
    }
}
