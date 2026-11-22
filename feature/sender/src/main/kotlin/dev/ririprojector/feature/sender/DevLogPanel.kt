package dev.ririprojector.feature.sender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ririprojector.net.devlog.DevEvent
import dev.ririprojector.net.devlog.DevEventLog
import dev.ririprojector.net.devlog.DevLevel

/**
 * Developer mode (#33): the live log of the current share's connection steps. Bounded height with
 * its own scroll that follows the newest line; WARN/ERROR lines stand out. Kept after the share
 * ends, until the next one starts.
 */
@Composable
internal fun DevLogPanel(events: List<DevEvent>, onCopy: () -> Unit, onShare: () -> Unit) {
    val scroll = rememberScrollState()
    // Follow the newest line (a collapsed duplicate changes the last line, not the size).
    LaunchedEffect(events.size, events.lastOrNull()) { scroll.animateScrollTo(scroll.maxValue) }
    BrandCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sender_dev_log_title), style = MaterialTheme.typography.titleSmall)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                events.forEach { e ->
                    Text(
                        DevEventLog.formatLine(e),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color =
                            when (e.level) {
                                DevLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                DevLevel.WARN -> LocalRiriColors.current.tan
                                DevLevel.ERROR -> MaterialTheme.colorScheme.error
                            },
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.sender_dev_log_copy))
                }
                FilledTonalButton(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.sender_dev_log_share))
                }
            }
        }
    }
}
