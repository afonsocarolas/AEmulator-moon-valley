/* Added for AEmulator Moon Valley, 2026-11-27: arranging the on-screen controllers (see core/PadLayout). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.PadLayout
import kotlin.math.roundToInt

/** One controller's layout while it is shown, and whether it is being arranged. */
class PadEditState(initial: PadLayout, private val save: (PadLayout) -> Unit) {
    var layout by mutableStateOf(initial)
        private set
    var editing by mutableStateOf(false)
    var selected by mutableStateOf<String?>(null)

    fun update(l: PadLayout) { layout = l }
    fun move(id: String, dxDp: Float, dyDp: Float) { val i = layout.item(id); layout = layout.with(id, i.copy(dx = i.dx + dxDp, dy = i.dy + dyDp)) }
    fun scale(id: String, s: Float) { layout = layout.with(id, layout.item(id).copy(scale = s.coerceIn(PadLayout.MIN_SCALE, PadLayout.MAX_SCALE))) }
    fun finish() { editing = false; selected = null; save(layout) }
    fun reset() { layout = PadLayout(); selected = null }
}

val LocalPadEdit = compositionLocalOf<PadEditState?> { null }

/**
 * A control of an on-screen controller: moved and sized as the layout says. While arranging, a finger on it selects it
 * and drags it, and never reaches the control itself.
 */
fun Modifier.editable(id: String): Modifier = composed {
    val st = LocalPadEdit.current ?: return@composed this
    val it = st.layout.item(id)
    val density = LocalDensity.current.density
    var m = this
        .offset { IntOffset((it.dx * density).roundToInt(), (it.dy * density).roundToInt()) }
        .graphicsLayer { scaleX = it.scale; scaleY = it.scale; alpha = st.layout.opacity }
    if (st.editing) {
        m = m.border(if (st.selected == id) 2.dp else 1.dp, if (st.selected == id) Color(0xffffd54f) else Color.White.copy(alpha = 0.5f),
            RoundedCornerShape(8.dp))
            .pointerInput(id, st) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        for (c in e.changes) {
                            if (c.pressed && !c.previousPressed) st.selected = id
                            if (c.pressed && c.previousPressed) {
                                val d = c.position - c.previousPosition
                                // (the finger's movement is in the control's own, scaled units)
                                val s = st.layout.item(id).scale
                                st.move(id, d.x * s / density, d.y * s / density)
                            }
                            c.consume()
                        }
                    }
                }
            }
    }
    m
}

/** The arranging tools: the selected control's size, the controller's opacity, Reset and Done. */
@Composable
fun PadEditPanel(st: PadEditState, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        modifier = modifier.widthIn(max = 420.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.padedit_hint), style = MaterialTheme.typography.bodySmall)
            val sel = st.selected
            if (sel != null) {
                Text(stringResource(R.string.padedit_size), style = MaterialTheme.typography.labelMedium)
                Slider(value = st.layout.item(sel).scale, onValueChange = { st.scale(sel, it) },
                    valueRange = PadLayout.MIN_SCALE..PadLayout.MAX_SCALE)
            }
            Text(stringResource(R.string.padedit_opacity), style = MaterialTheme.typography.labelMedium)
            Slider(value = st.layout.opacity, onValueChange = { st.update(st.layout.copy(opacity = it)) },
                valueRange = PadLayout.MIN_OPACITY..1f)
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { st.reset() }) { Text(stringResource(R.string.padedit_reset)) }
                TextButton(onClick = { st.finish() }) { Text(stringResource(R.string.padedit_done)) }
            }
        }
    }
}
