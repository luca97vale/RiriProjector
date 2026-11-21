package dev.ririprojector.feature.sender

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ririprojector.net.ReceiverEndpoint

/**
 * One-tap reconnect to the last receiver when it is not in the discovered list (NSD filtered, or
 * the receiver not running yet). An advertised one is marked in the list instead (ADR-012).
 */
@Composable
internal fun LastReceiverCard(quickStart: QuickStart?, enabled: Boolean, onStart: (ReceiverEndpoint) -> Unit) {
    if (quickStart == null || quickStart.advertised) return
    val r = quickStart.endpoint
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onStart(r) }) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.sender_last_receiver), style = MaterialTheme.typography.labelMedium)
            Text(r.name, style = MaterialTheme.typography.titleMedium)
            Text("${r.host}:${r.port}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
