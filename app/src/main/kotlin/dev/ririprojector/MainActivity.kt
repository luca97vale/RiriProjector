package dev.ririprojector

import android.app.Activity
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import dev.ririprojector.feature.receiver.ReceiverActivity
import dev.ririprojector.feature.sender.SenderActivity
import dev.ririprojector.feature.sender.SenderState
import dev.ririprojector.feature.sender.SenderStatus

/**
 * UI-less trampoline: starts the sender or the receiver and finishes. It also handles the
 * "Use this device as a receiver/sender" requests from those screens and remembers the choice.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Only through the non-exported alias: other apps can reach the exported launcher, not it.
        val viaSwitchAlias = intent.component?.className == SWITCH_ALIAS
        val switchTo =
            ModeResolver.switchTarget(
                intent.action.takeIf { viaSwitchAlias },
                useAsReceiverAction = SenderActivity.ACTION_USE_AS_RECEIVER,
                useAsSenderAction = ReceiverActivity.ACTION_USE_AS_SENDER,
            )
        if (switchTo != null) prefs.edit().putString(KEY_MODE, switchTo.name).apply()

        val uiModeManager = getSystemService(UiModeManager::class.java)
        val isTv =
            ModeResolver.isTv(
                hasFireTvFeature = packageManager.hasSystemFeature(ModeResolver.FEATURE_FIRE_TV),
                hasLeanbackFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                isTelevisionUiMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION,
            )
        val mode =
            switchTo ?: ModeResolver.resolve(
                intent.getStringExtra(ModeResolver.EXTRA_MODE),
                isTv,
                remembered = AppMode.fromExtra(prefs.getString(KEY_MODE, null)),
                // The mirroring notification opens the app through here: keep it on the sender.
                senderSessionActive = SenderStatus.state.value.let {
                    it !is SenderState.Idle && it !is SenderState.Ended
                },
            )
        val target =
            when (mode) {
                AppMode.SENDER -> SenderActivity::class.java
                AppMode.RECEIVER -> ReceiverActivity::class.java
            }
        // Reuse an existing instance: two receiver screens would race for the server port.
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private companion object {
        const val PREFS = "mode"
        const val KEY_MODE = "chosenMode"

        /** `activity-alias` in the manifest that carries the switch actions. */
        const val SWITCH_ALIAS = "dev.ririprojector.SwitchModeActivity"
    }
}
