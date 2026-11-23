/* Added for AEmulator Moon Valley, 2026-11-23: the modes of a two-screen phone (see core/DualScreen). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.DualScreen

/**
 * [active]: this start of the machine has both panels, [mode] is the one it is in; [onMode] asks the firmware for
 * another. [enabled] / [onEnabled]: whether the next start has both.
 */
@Composable
fun DualScreenDialog(active: Boolean, mode: Int, enabled: Boolean, onMode: (Int) -> Unit, onEnabled: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dual_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) {
                    Text(stringResource(R.string.dual_hint))
                    for ((m, label) in listOf(DualScreen.SINGLE to R.string.dual_single, DualScreen.DUAL to R.string.dual_dual,
                        DualScreen.EXTENDED to R.string.dual_extended, DualScreen.MIRROR to R.string.dual_mirror)) {
                        if (m == mode) Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
                        else OutlinedButton(onClick = { onMode(m) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.dual_enable), Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { on = it; onEnabled(it) })
                }
                if (on != active) Text(stringResource(R.string.dual_restart), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.phone3d_done)) } },
    )
}
