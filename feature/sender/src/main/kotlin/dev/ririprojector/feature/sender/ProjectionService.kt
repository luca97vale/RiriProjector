package dev.ririprojector.feature.sender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.content.IntentCompat
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.SyncMode
import dev.ririprojector.protocol.model.VideoCodec

/**
 * Foreground service of type `mediaProjection` that owns the [MediaProjection] and the
 * [SenderSession] (Android 14+ rules: `startForeground` before `getMediaProjection`, callback
 * registered before the VirtualDisplay, one projection token per session).
 */
class ProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var session: SenderSession? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession(ByeReason.USER_STOPPED)
            return START_NOT_STICKY
        }
        if (session != null || intent == null) return START_NOT_STICKY
        val consent = IntentCompat.getParcelableExtra(intent, EXTRA_CONSENT, Intent::class.java)
        val target = ReceiverEndpoint.parse(
            intent.getStringExtra(EXTRA_TARGET) ?: "",
            intent.getStringExtra(EXTRA_NAME) ?: "",
        )
        if (consent == null || target == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(target.name)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp =
            try {
                mpm.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), consent)
            } catch (e: Exception) {
                Log.w(TAG, "getMediaProjection failed: ${e.message}")
                null
            }
        if (mp == null) {
            SenderStatus.set(SenderState.Ended(getString(R.string.sender_error_projection)))
            shutdown()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection stopped by the system or the user")
                    stopSession(ByeReason.PROJECTION_REVOKED, getString(R.string.sender_error_revoked))
                }

                // API 34+, single-app sharing: the shared app went to the background or came back.
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    Log.i(TAG, "shared content visible: $isVisible")
                    session?.onContentVisibilityChanged(isVisible)
                }
            },
            main,
        )

        val (source, dpi) = displayInfo()
        val singleApp = intent.getBooleanExtra(EXTRA_SINGLE_APP, false)
        val consentShownAtMs = intent.getLongExtra(EXTRA_CONSENT_SHOWN_AT, System.currentTimeMillis())
        val appContext = applicationContext
        val s =
            SenderSession(
                target = target,
                preferences = preferencesFrom(intent),
                projection = mp,
                source = source,
                densityDpi = dpi,
                audioPermitted =
                    Build.VERSION.SDK_INT >= 29 &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                singleApp = singleApp,
                detectAudioUid = { SharedAppDetector.detectUid(appContext, consentShownAtMs) },
                versionMismatchText = getString(R.string.sender_error_version),
                onEnded = { main.post { shutdown() } },
            )
        session = s
        s.start()
        return START_NOT_STICKY
    }

    private fun stopSession(reason: ByeReason, message: String? = null) {
        val s = session
        if (s == null) shutdown() else s.stop(reason, message)
    }

    private fun shutdown() {
        session = null
        projection?.let { runCatching { it.stop() } }
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        session?.stop(ByeReason.USER_STOPPED)
        projection?.let { runCatching { it.stop() } }
        super.onDestroy()
    }

    private fun startForegroundCompat(targetName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.sender_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, ProjectionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentTitle(getString(R.string.sender_notification_title, targetName))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(null, getString(R.string.sender_stop), stop).build(),
                ).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayInfo(): Pair<DisplayInfo, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val info = DisplayInfo(metrics.widthPixels, metrics.heightPixels, display.refreshRate.toInt().coerceAtLeast(1))
        return info to metrics.densityDpi
    }

    private fun preferencesFrom(intent: Intent): StreamPreferences {
        val codec = intent.getStringExtra(EXTRA_CODEC)?.let { name ->
            VideoCodec.entries.firstOrNull { it.name == name }
        }
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 0)
        return StreamPreferences(
            preferredVideoCodec = codec,
            maxHeight = intent.getIntExtra(EXTRA_MAX_HEIGHT, 1080),
            maxFps = intent.getIntExtra(EXTRA_MAX_FPS, 60),
            bitrateBps = bitrate.takeIf { it > 0 },
            audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, true),
            syncMode =
                SyncMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SYNC) } ?: SyncMode.LIP_SYNC,
        )
    }

    companion object {
        private const val TAG = "Riri/Projection"
        private const val CHANNEL = "mirroring"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.ririprojector.action.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_CONSENT = "consent"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_CODEC = "codec"
        private const val EXTRA_MAX_HEIGHT = "maxHeight"
        private const val EXTRA_MAX_FPS = "maxFps"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_SYNC = "sync"
        private const val EXTRA_SINGLE_APP = "singleApp"
        private const val EXTRA_CONSENT_SHOWN_AT = "consentShownAt"

        fun start(
            context: Context,
            resultCode: Int,
            consent: Intent,
            target: ReceiverEndpoint,
            prefs: StreamPreferences,
            singleApp: Boolean,
            consentShownAtMs: Long,
        ) {
            val intent =
                Intent(context, ProjectionService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_CONSENT, consent)
                    .putExtra(EXTRA_TARGET, "${target.host}:${target.port}")
                    .putExtra(EXTRA_NAME, target.name)
                    .putExtra(EXTRA_CODEC, prefs.preferredVideoCodec?.name)
                    .putExtra(EXTRA_MAX_HEIGHT, prefs.maxHeight ?: 1080)
                    .putExtra(EXTRA_MAX_FPS, prefs.maxFps ?: 60)
                    .putExtra(EXTRA_BITRATE, prefs.bitrateBps ?: 0)
                    .putExtra(EXTRA_AUDIO, prefs.audioEnabled)
                    .putExtra(EXTRA_SYNC, prefs.syncMode.name)
                    .putExtra(EXTRA_SINGLE_APP, singleApp)
                    .putExtra(EXTRA_CONSENT_SHOWN_AT, consentShownAtMs)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
