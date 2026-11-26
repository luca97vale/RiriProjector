package dev.ririprojector.feature.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import dev.ririprojector.adb.ShellAccessState
import dev.ririprojector.helper.ShellAccessManager

/**
 * Keeps the app alive while the user reads the pairing code in Settings (ADR-014) and takes the code
 * from a notification reply, so nobody has to switch back. A short service on API 34+: pairing takes
 * well under its three-minute budget. The code is passed straight to [ShellAccessManager] and never
 * stored or logged.
 */
class PairingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var pairing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CODE -> onCode(intent)
            ACTION_CANCEL -> stop()
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.pairing_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val notification = askNotification(null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        ShellAccessManager.get(this).startPairing()
    }

    private fun onCode(intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
        if (pairing) return
        pairing = true
        notify(progressNotification())
        ShellAccessManager.get(this).pair(code) { state ->
            main.post {
                pairing = false
                when (state) {
                    ShellAccessState.Ready, ShellAccessState.Starting, is ShellAccessState.Paired -> {
                        notifyResult(getString(R.string.pairing_done))
                        stop()
                    }

                    is ShellAccessState.Failed ->
                        if (state.reason == ShellAccessState.Reason.WRONG_CODE ||
                            state.reason == ShellAccessState.Reason.PAIRING_FAILED
                        ) {
                            // Let the user try again from the same notification.
                            notify(askNotification(getString(R.string.pairing_wrong_code)))
                            ShellAccessManager.get(this).startPairing()
                        } else {
                            notifyResult(getString(R.string.second_screen_failed_start))
                            stop()
                        }

                    else -> notify(askNotification(getString(R.string.pairing_wrong_code)))
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stop()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun askNotification(error: String?): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.pairing_code_label)).build()
        val reply = PendingIntent.getService(
            this,
            REQUEST_CODE,
            Intent(this, PairingService::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            Intent(this, PairingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(null, getString(R.string.pairing_enter_code), reply)
            .addRemoteInput(input)
            .build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle(getString(R.string.pairing_title))
            .setContentText(error ?: getString(R.string.pairing_text))
            .setStyle(Notification.BigTextStyle().bigText(error ?: getString(R.string.pairing_text)))
            .setOngoing(true)
            .setOnlyAlertOnce(error == null)
            .addAction(action)
            .addAction(Notification.Action.Builder(null, getString(R.string.pairing_cancel), cancel).build())
            .build()
    }

    private fun progressNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_slideshow)
        .setContentTitle(getString(R.string.pairing_title))
        .setContentText(getString(R.string.pairing_in_progress))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /** A separate, dismissable notification so the result outlives the foreground one. */
    private fun notifyResult(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            RESULT_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentTitle(getString(R.string.pairing_title))
                .setContentText(text)
                .setAutoCancel(true)
                .setTimeoutAfter(RESULT_TIMEOUT_MS)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "pairing"
        private const val NOTIFICATION_ID = 35
        private const val RESULT_ID = 36
        private const val REQUEST_CODE = 10
        private const val REQUEST_CANCEL = 11
        private const val RESULT_TIMEOUT_MS = 15_000L
        private const val KEY_CODE = "code"
        private const val ACTION_CODE = "dev.ririprojector.sender.PAIRING_CODE"
        private const val ACTION_CANCEL = "dev.ririprojector.sender.PAIRING_CANCEL"

        /** Starts pairing and opens Wireless debugging, where the user taps "Pair device with pairing code". */
        fun startAndOpenSettings(context: Context) {
            context.startForegroundService(Intent(context, PairingService::class.java))
            openWirelessDebugging(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PairingService::class.java))
        }

        /**
         * Wireless debugging has no public intent: open Developer options highlighting it (as Shizuku
         * does), falling back to plain Settings.
         */
        fun openWirelessDebugging(context: Context) {
            val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(dev) }
                .recoverCatching { context.startActivity(fallback) }
        }
    }
}
