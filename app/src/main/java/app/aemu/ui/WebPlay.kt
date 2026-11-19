/* Added for AEmulator Moon Valley, 2026-11-19: the address of "Play in a browser" (see core/WebPlayServer). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R

/** Where to point a browser, with copy and share; Stop ends it (the link stops working at once). */
@Composable
fun WebPlayDialog(url: String, onStop: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.webplay_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.webplay_open))
                SelectionContainer {
                    Text(url, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("AEmulator Moon Valley", url))
                        Toast.makeText(context, R.string.webplay_copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.webplay_copy)) }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    }) { Text(stringResource(R.string.webplay_share)) }
                }
                Text(stringResource(R.string.webplay_warning), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.phone3d_done)) } },
        dismissButton = { TextButton(onClick = onStop) { Text(stringResource(R.string.webplay_stop)) } },
    )
}
