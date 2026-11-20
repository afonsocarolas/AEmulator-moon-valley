/* Added for AEmulator Moon Valley, 2026-11-14: the moon UI (title, starry sky over a valley, moon-phase button)
 * and the Moon Valley easter egg. 2026-11-20: the moon button shows the real moon of tonight; a real full moon has a
 * secret of its own. GPL-3.0; see LICENSE and NOTICE.md. */
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import app.aemu.core.MoonPhase
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * Added 2026-11-20: the moon as it looks in the sky, [illumination] 0..1 of its face lit, on the right while [waxing]
 * and on the left while waning (as seen from the northern hemisphere). The terminator is half an ellipse.
 */
private fun DrawScope.drawRealMoon(center: Offset, radius: Float, illumination: Float, waxing: Boolean, lit: Color, dark: Color) {
    drawCircle(dark, radius, center)
    val k = illumination.coerceIn(0f, 1f)
    if (k < 0.005f) return
    val e = radius * kotlin.math.abs(1f - 2f * k)            // half-width of the terminator ellipse
    val circle = Rect(center, radius)
    val ellipse = Rect(center.x - e, center.y - radius, center.x + e, center.y + radius)
    val path = Path().apply {
        // the right half of the disc, top to bottom ...
        arcTo(circle, -90f, 180f, forceMoveTo = true)
        // ... and back up along the terminator: through the right (a crescent) or the left (gibbous)
        if (k < 0.5f) arcTo(ellipse, 90f, -180f, forceMoveTo = false) else arcTo(ellipse, 90f, 180f, forceMoveTo = false)
        close()
    }
    if (waxing) drawPath(path, lit)
    else withTransform({ scale(-1f, 1f, center) }) { drawPath(path, lit) }
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

/** the real moon now, looked at again every ten minutes */
@Composable
private fun rememberRealMoon(): MoonPhase {
    var moon by remember { mutableStateOf(MoonPhase.at(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) { delay(10 * 60 * 1000L); moon = MoonPhase.at(System.currentTimeMillis()) }
    }
    return moon
}

/**
 * The moon in the home screen's bar: tonight's real moon (2026-11-20). Each tap waxes it a little; reach the full moon
 * (7 quick taps) and Moon Valley opens. It goes back to the real moon when left alone for a few seconds. On a real
 * full-moon night it glows gold and one tap is enough. A long press tells about tonight's moon.
 */
@Composable
fun MoonPhaseButton(onFullMoon: () -> Unit) {
    val real = rememberRealMoon()
    val needed = if (real.isFullMoon) 1 else TAPS_TO_FULL_MOON
    var taps by remember { mutableIntStateOf(0) }
    var tonight by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val start = real.illumination.toFloat()
    val phase by animateFloatAsState(
        start + (1f - start) * taps / needed,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "phase",
    )
    LaunchedEffect(taps) {
        if (taps in 1 until needed) { delay(3000); taps = 0 }
        if (taps >= needed) { delay(450); onFullMoon(); taps = 0 }
    }
    val glow by rememberInfiniteTransition(label = "fullglow").animateFloat(
        0.25f, 0.5f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "glow",
    )
    val cs = MaterialTheme.colorScheme
    val night = cs.surface.luminance() < 0.5f
    val lit = if (real.isFullMoon) MoonGold else if (night) MoonLight else cs.primary
    val desc = stringResource(R.string.moon_button)
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .combinedClickable(
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); tonight = true },
                onClick = {
                    if (taps < needed) {
                        taps++
                        haptic.performHapticFeedback(if (taps == needed) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                    }
                },
            )
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2f
            if (real.isFullMoon) drawCircle(MoonGold.copy(alpha = glow), r * 1.0f, center)
            else if (taps == needed) drawCircle(MoonGold.copy(alpha = 0.35f), r * 1.0f, center)
            drawRealMoon(center, r * 0.82f, phase, real.waxing, lit, lit.copy(alpha = 0.18f))
        }
    }
    if (tonight) MoonTonightDialog(real) { tonight = false }
}

/** Long press on the moon: tonight's moon, how much of it is lit, and when the next full moon comes. */
@Composable
private fun MoonTonightDialog(moon: MoonPhase, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val names = stringArrayResource(R.array.moon_phase_names)
    val next = remember(moon) {
        val t = MoonPhase.nextFullMoon(System.currentTimeMillis())
        java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(java.util.Date(t))
    }
    val seen = remember { app.aemu.AppPrefs.prefs(ctx).getInt(FULL_MOONS_SEEN_KEY, 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Canvas(Modifier.size(72.dp)) {
                val r = size.minDimension / 2f
                if (moon.isFullMoon) drawCircle(MoonGold.copy(alpha = 0.3f), r, center)
                drawRealMoon(center, r * 0.85f, moon.illumination.toFloat(), moon.waxing,
                    if (moon.isFullMoon) MoonGold else MoonLight, Color(0xFF2A2F4A))
            }
        },
        title = { Text(stringResource(R.string.moon_tonight_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                Text(names[moon.name.ordinal], style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(stringResource(R.string.moon_tonight_lit, (moon.illumination * 100).toInt()), textAlign = TextAlign.Center)
                if (moon.isFullMoon) Text(stringResource(R.string.moon_tonight_full), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary)
                else Text(stringResource(R.string.moon_tonight_next, next), textAlign = TextAlign.Center)
                if (seen > 0) Text(stringResource(R.string.moon_full_seen, seen), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.moon_egg_close)) } },
    )
}

private const val FULL_MOONS_SEEN_KEY = "moon_full_moons_seen"
private const val LAST_FULL_MOON_KEY = "moon_last_full_lunation"

private const val STARS_CAUGHT_KEY = "moon_stars_caught"

private class Flight(val x: Float, val y: Float)
private class Catch(val x: Float, val y: Float, val count: Int)

/**
 * Added 2026-11-16: now and then a shooting star crosses the home screen's sky. Tap it while it flies to catch it; the
 * app keeps count. Only the little area around the star takes touches, and only while it flies, so nothing else on the
 * screen is ever covered.
 */
@Composable
fun ShootingStarLayer(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    var flight by remember { androidx.compose.runtime.mutableStateOf<Flight?>(null) }
    var progress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var caught by remember { androidx.compose.runtime.mutableStateOf<Catch?>(null) }
    LaunchedEffect(Unit) {
        delay(Random.nextLong(8_000, 15_000))                      // the first one comes soon, so it gets noticed
        while (true) {
            flight = Flight(0.55f + Random.nextFloat() * 0.4f, 0.10f + Random.nextFloat() * 0.2f)
            progress = 0f
            val t0 = androidx.compose.runtime.withFrameMillis { it }
            while (flight != null && progress < 1f) {
                progress = (androidx.compose.runtime.withFrameMillis { it } - t0) / 1900f
            }
            flight = null
            delay(Random.nextLong(25_000, 60_000))
        }
    }
    LaunchedEffect(caught) { if (caught != null) { delay(2600); caught = null } }

    val cs = MaterialTheme.colorScheme
    val night = cs.surface.luminance() < 0.5f
    val starColor = if (night) MoonLight else cs.primary
    // the star travels down and to the left, across half the screen
    fun headX(f: Flight) = f.x - 0.5f * progress
    fun headY(f: Flight) = f.y + 0.22f * progress

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth; val h = maxHeight
        Canvas(Modifier.fillMaxSize()) {
            flight?.let { f ->
                val head = Offset(headX(f) * size.width, headY(f) * size.height)
                val tail = head + Offset(0.16f * size.width, -0.07f * size.height)
                val fade = if (progress > 0.8f) (1f - progress) / 0.2f else 1f
                drawLine(Brush.linearGradient(listOf(starColor.copy(alpha = 0.9f * fade), Color.Transparent), head, tail),
                    head, tail, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Brush.radialGradient(listOf(starColor.copy(alpha = fade), Color.Transparent), head, 9.dp.toPx()), 9.dp.toPx(), head)
            }
            caught?.let { c ->
                // a little burst where it was caught
                val p = Offset(c.x * size.width, c.y * size.height)
                for (k in 0 until 8) {
                    val a = k * PI.toFloat() / 4f
                    drawLine(MoonGold, p + Offset(kotlin.math.cos(a), sin(a)) * 10.dp.toPx(),
                        p + Offset(kotlin.math.cos(a), sin(a)) * 18.dp.toPx(), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        flight?.let { f ->
            Box(
                Modifier.offset(x = w * headX(f) - 28.dp, y = h * headY(f) - 28.dp).size(56.dp)
                    .pointerInput(Unit) {
                        detectTapGestures {
                        val now = flight ?: return@detectTapGestures
                        val sp = app.aemu.AppPrefs.prefs(ctx)
                        val n = sp.getInt(STARS_CAUGHT_KEY, 0) + 1
                        sp.edit().putInt(STARS_CAUGHT_KEY, n).apply()
                        caught = Catch(headX(now), headY(now), n)
                        flight = null
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
            )
        }
        caught?.let { c ->
            androidx.compose.material3.Surface(
                shape = CircleShape, color = cs.surfaceContainerHigh.copy(alpha = 0.95f),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = h * (c.y + 0.06f)),
            ) {
                Text(stringResource(R.string.star_caught, c.count), style = MaterialTheme.typography.labelLarge,
                    color = cs.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
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
        // 2026-11-20: on a real full-moon night the moon rises gold, and each full moon visited here is counted once
        val ctx = LocalContext.current
        val realFull = remember { MoonPhase.at(System.currentTimeMillis()).isFullMoon }
        val seen = remember {
            if (!realFull) 0 else {
                val sp = app.aemu.AppPrefs.prefs(ctx)
                val lunation = MoonPhase.lunation(System.currentTimeMillis())
                var n = sp.getInt(FULL_MOONS_SEEN_KEY, 0)
                if (sp.getLong(LAST_FULL_MOON_KEY, Long.MIN_VALUE) != lunation) {
                    n++
                    sp.edit().putInt(FULL_MOONS_SEEN_KEY, n).putLong(LAST_FULL_MOON_KEY, lunation).apply()
                }
                n
            }
        }
        val moonColor = if (realFull) MoonGold else MoonLight

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
                drawMoon(c, r, 1f, moonColor, moonColor)
                drawValley(Color(0xFF151B40), Color(0xFF0A0E24), height = size.height * 0.22f)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(if (realFull) R.string.moon_egg_full_title else R.string.moon_egg_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = moonColor, textAlign = TextAlign.Center)
                Text(if (realFull) stringResource(R.string.moon_egg_full_line) else line,
                    style = MaterialTheme.typography.bodyLarge, color = MoonLight.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                if (realFull) Text(stringResource(R.string.moon_full_seen, seen), style = MaterialTheme.typography.bodyMedium,
                    color = MoonGold.copy(alpha = 0.9f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.moon_egg_close)) }
            }
        }
    }
}
