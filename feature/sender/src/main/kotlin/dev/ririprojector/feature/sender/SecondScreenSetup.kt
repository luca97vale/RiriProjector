package dev.ririprojector.feature.sender

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ririprojector.adb.ShellAccessState
import dev.ririprojector.adb.ShellAccessState.Reason
import dev.ririprojector.helper.ShellAccessManager

/**
 * Developer-mode card for the hidden second-screen mode (issue 35, ADR-014): pairs with Wireless
 * debugging, restores shell access after a reboot or Wi-Fi change, and runs the helper self-test.
 */
@Composable
internal fun SecondScreenSetup() {
    val context = LocalContext.current
    val manager = remember { ShellAccessManager.get(context) }
    val state by manager.state.collectAsState()
    var code by remember { mutableStateOf("") }
    var selfTest by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(manager) {
        manager.watch()
        onDispose { manager.unwatch() }
    }
    // Paired and Wireless debugging on: start the helper without asking.
    LaunchedEffect(state) {
        if (state == ShellAccessState.Paired(wirelessDebuggingOn = true)) manager.start()
        if (state != ShellAccessState.Ready) selfTest = null
    }

    BrandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.second_screen_title), style = MaterialTheme.typography.titleSmall)
            Hint(stringResource(R.string.second_screen_intro))
            when (val s = state) {
                ShellAccessState.Unsupported -> Hint(stringResource(R.string.second_screen_unsupported))

                ShellAccessState.NotPaired, is ShellAccessState.Pairing, is ShellAccessState.Failed -> {
                    if (s is ShellAccessState.Failed) Hint(failure(s.reason), error = true)
                    if (s is ShellAccessState.Failed && s.paired) {
                        // Still paired: retrying the start is enough, no new code needed.
                        TextButton(onClick = { manager.start() }) { Text(stringResource(R.string.second_screen_retry)) }
                        if (s.reason == Reason.CONNECT_FAILED) OpenSettings()
                    }
                    Hint(stringResource(R.string.second_screen_steps))
                    if (s is ShellAccessState.Pairing) {
                        Hint(
                            stringResource(
                                if (s.portFound) R.string.second_screen_found else R.string.second_screen_searching,
                            ),
                        )
                    }
                    Button(
                        onClick = { PairingService.startAndOpenSettings(context) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(stringResource(R.string.second_screen_pair)) }
                    if (s is ShellAccessState.Pairing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { v -> code = v.filter(Char::isDigit).take(CODE_LENGTH) },
                                label = { Text(stringResource(R.string.second_screen_code)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    manager.pair(code) { result ->
                                        if (result !is ShellAccessState.Pairing) PairingService.stop(context)
                                    }
                                    code = ""
                                },
                                enabled = code.length == CODE_LENGTH && s.portFound,
                            ) { Text(stringResource(R.string.second_screen_submit)) }
                        }
                    }
                }

                is ShellAccessState.Paired -> {
                    Hint(stringResource(R.string.second_screen_paired_off))
                    OpenSettings()
                    Forget { manager.forget() }
                }

                ShellAccessState.Starting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Hint(stringResource(R.string.second_screen_starting))
                }

                ShellAccessState.Ready -> {
                    Hint(stringResource(R.string.second_screen_ready))
                    selfTest?.let {
                        Hint(
                            stringResource(
                                if (it) R.string.second_screen_self_test_ok else R.string.second_screen_self_test_fail,
                            ),
                            error = !it,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { manager.selfTest { ok -> selfTest = ok } },
                            shape = MaterialTheme.shapes.medium,
                        ) { Text(stringResource(R.string.second_screen_self_test)) }
                        Forget { manager.forget() }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenSettings() {
    val context = LocalContext.current
    TextButton(onClick = { PairingService.openWirelessDebugging(context) }) {
        Text(stringResource(R.string.second_screen_open_settings))
    }
}

@Composable
private fun Forget(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.second_screen_forget)) }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun failure(reason: Reason): String = stringResource(
    when (reason) {
        Reason.WRONG_CODE -> R.string.second_screen_wrong_code
        Reason.PAIRING_FAILED -> R.string.second_screen_failed_pairing
        Reason.CONNECT_FAILED -> R.string.second_screen_failed_connect
        Reason.HELPER_DID_NOT_START -> R.string.second_screen_failed_start
    },
)

private const val CODE_LENGTH = 6
