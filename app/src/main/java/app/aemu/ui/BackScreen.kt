/* Added for AEmulator Moon Valley, 2026-11-19: the YotaPhone back screen (e-ink, display 2 of the guest). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aemu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** What one look at the back screen gave: the picture, or why there is none. */
class BackScreenShot(val bitmap: Bitmap?, val problem: String = "")

/**
 * The YotaPhone's back screen: the guest draws it as its display 2 (an e-ink panel, see native/hwc1fb), [capture] reads it
 * back (screencap -d 2). It is looked at every two seconds while this is open: e-ink is slow anyway, and every look
 * starts a program in the guest. The picture is shown grey, on paper-coloured ground, in the frame of the phone's back.
 * The machine runs on behind it. With [touch], fingers on the panel go to the back screen's own touch device
 * (bs_virtual_ts), in 0..4095 across the panel; the picture follows at the next look (or sooner, see below).
 */
@Composable
fun BackScreenDialog(capture: () -> BackScreenShot, onDismiss: () -> Unit,
                     touch: ((List<app.aemu.core.TouchpadProtocol.Contact>) -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var shot by remember { mutableStateOf<BackScreenShot?>(null) }
        // after a touch the panel is looked at more often for a few seconds, so what the finger did shows up quickly
        var lastTouch by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                while (isActive) {
                    val s = runCatching { capture() }.getOrElse { BackScreenShot(null, it.toString()) }
                    // keep the last picture when one look fails: e-ink keeps its image too
                    withContext(Dispatchers.Main) { if (s.bitmap != null || shot?.bitmap == null) shot = s }
                    delay(if (System.currentTimeMillis() - lastTouch < 4000) 500 else 2000)
                }
            }
        }
        val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF03040C), Color(0xFF0E1433), Color(0xFF1E2556))))) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.back_title), color = Color(0xFFF4F1DE))
                    FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.phone3d_done)) }
                }
                // the back of the phone: a dark body, the camera at the top, the e-ink panel filling most of it
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.fillMaxHeight().aspectRatio(0.5f).clip(RoundedCornerShape(28.dp)).background(Color(0xFF22252D)).padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.padding(bottom = 10.dp).size(14.dp).background(Color(0xFF0B0D14), CircleShape))
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(540f / 960f).border(3.dp, Color(0xFF55554F))
                                .background(Color(0xFFD9D8D0))
                                .then(if (touch == null) Modifier else Modifier.pointerInput(Unit) {
                                    // every finger on the panel, as contacts in the panel's 0..4095 range
                                    awaitPointerEventScope {
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val w = size.width.coerceAtLeast(1); val h = size.height.coerceAtLeast(1)
                                            val now = ev.changes.filter { it.pressed }.map { c ->
                                                app.aemu.core.TouchpadProtocol.Contact((c.id.value % 32).toInt(),
                                                    (c.position.x / w * 4095f).toInt().coerceIn(0, 4095),
                                                    (c.position.y / h * 4095f).toInt().coerceIn(0, 4095))
                                            }
                                            ev.changes.forEach { it.consume() }
                                            lastTouch = System.currentTimeMillis()
                                            touch(now)
                                        }
                                    }
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            val s = shot
                            when {
                                s?.bitmap != null -> Image(s.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds, colorFilter = grey)
                                s == null -> LoadingIndicator()
                                else -> Text(stringResource(R.string.back_none, s.problem.take(160)), color = Color(0xFF3A3A36),
                                    textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.back_hint), color = Color(0xFFF4F1DE).copy(alpha = 0.7f), textAlign = TextAlign.Center)
            }
        }
    }
}
