/* Added for AEmulator Moon Valley, 2026-11-25: the controls of the S Pen while it is out (see core/SPen). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.aemu.R
import kotlin.math.roundToInt

/** At the left edge; drag it anywhere. [side]: the pen's button, held while it is lit. */
@Composable
fun SPenBar(finger: Boolean, hover: Boolean, side: Boolean, onFinger: (Boolean) -> Unit, onHover: (Boolean) -> Unit,
            onSide: (Boolean) -> Unit, onPutBack: () -> Unit) {
    var area by remember { mutableStateOf(IntSize.Zero) }
    var own by remember { mutableStateOf(IntSize.Zero) }
    var moved by remember { mutableStateOf(Offset.Zero) }
    fun inside(o: Offset) = Offset(o.x.coerceIn(0f, maxOf(0f, (area.width - own.width).toFloat())),
        ((area.height - own.height) / 2f).coerceAtLeast(0f).let { o.y.coerceIn(-it, it) })
    Box(Modifier.fillMaxSize().displayCutoutPadding().padding(8.dp).onSizeChanged { area = it }) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.CenterStart)
                .offset { inside(moved).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                .onSizeChanged { own = it }
                .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); moved = inside(inside(moved) + d) } }) {
            Column {
                Toggle(Icons.Rounded.Draw, stringResource(R.string.spen_finger), finger, onFinger)
                Toggle(Icons.Rounded.KeyboardDoubleArrowUp, stringResource(R.string.spen_hover), hover, onHover)
                Toggle(Icons.Rounded.RadioButtonChecked, stringResource(R.string.spen_side), side, onSide)
                IconButton(onClick = onPutBack) { Icon(Icons.Rounded.Close, stringResource(R.string.spen_put)) }
            }
        }
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    IconToggleButton(checked = on, onCheckedChange = onChange, colors = IconButtonDefaults.iconToggleButtonColors(
        checkedContainerColor = MaterialTheme.colorScheme.primary, checkedContentColor = MaterialTheme.colorScheme.onPrimary)) { Icon(icon, label) }
}
