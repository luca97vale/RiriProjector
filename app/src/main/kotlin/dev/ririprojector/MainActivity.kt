package dev.ririprojector

import android.app.Activity
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import dev.ririprojector.feature.receiver.ReceiverActivity
import dev.ririprojector.feature.sender.SenderActivity

/** UI-less trampoline: starts the sender or the receiver and finishes. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uiModeManager = getSystemService(UiModeManager::class.java)
        val isTv =
            ModeResolver.isTv(
                hasFireTvFeature = packageManager.hasSystemFeature(ModeResolver.FEATURE_FIRE_TV),
                hasLeanbackFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                isTelevisionUiMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION,
            )
        val mode = ModeResolver.resolve(intent.getStringExtra(ModeResolver.EXTRA_MODE), isTv)
        val target =
            when (mode) {
                AppMode.SENDER -> SenderActivity::class.java
                AppMode.RECEIVER -> ReceiverActivity::class.java
            }
        startActivity(Intent(this, target))
        finish()
    }
}
