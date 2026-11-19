package dev.ririprojector.feature.receiver

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Fire TV entry point. Placeholder until M3 adds the decoder surface. */
class ReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            TextView(this).apply {
                setText(R.string.receiver_waiting)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                textSize = 28f
                gravity = Gravity.CENTER
            },
        )
    }
}
