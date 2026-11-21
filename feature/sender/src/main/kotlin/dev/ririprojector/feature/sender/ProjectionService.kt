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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.net.p2p.P2pFailure
import dev.ririprojector.net.p2p.P2pGroupOwner
import dev.ririprojector.net.p2p.P2pLink
import dev.ririprojector.net.p2p.P2pPolicy
import dev.ririprojector.net.p2p.P2pReceiver
import dev.ririprojector.net.p2p.WifiDirectSupport
import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.SyncMode
import dev.ririprojector.protocol.model.VideoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service of type `mediaProjection` that owns the [MediaProjection] and the
 * [ProjectionHost] (Android 14+ rules: `startForeground` before `getMediaProjection`, callback
 * registered before the VirtualDisplay, one projection token per session).
 */
class ProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var session: ProjectionHost? = null
    private val main = Handler(Looper.getMainLooper())

    // Wi-Fi Direct (ADR-011): the group is created after consent and owned by this service.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var joinJob: Job? = null
    private var p2pLink: P2pLink? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession(ByeReason.USER_STOPPED)
            return START_NOT_STICKY
        }
        if (session != null || joinJob != null || intent == null) return START_NOT_STICKY
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

                // API 34+: the only size source there; covers rotation and one-app window resizes.
                override fun onCapturedContentResize(width: Int, height: Int) {
                    session?.onSourceResized(width, height)
                }
            },
            main,
        )
        if (Build.VERSION.SDK_INT < 34) {
            // Before Android 14 the VirtualDisplay mirrors the whole screen: follow its rotation.
            getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        }

        val (source, dpi) = displayInfo()
        val consentShownAtMs = intent.getLongExtra(EXTRA_CONSENT_SHOWN_AT, System.currentTimeMillis())
        val consentResultAtMs = intent.getLongExtra(EXTRA_CONSENT_RESULT_AT, System.currentTimeMillis())
        val appContext = applicationContext
        val preferences = preferencesFrom(intent)

        fun startHost(endpoint: ReceiverEndpoint, outageLimitMs: Long, firstConnectWindowMs: Long = 0) {
            val s =
                ProjectionHost(
                    target = endpoint,
                    preferences = preferences,
                    projection = mp,
                    initialSource = source,
                    densityDpi = dpi,
                    audioPermitted =
                        Build.VERSION.SDK_INT >= 29 &&
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    detectShare = { SharedAppDetector.detect(appContext, consentShownAtMs, consentResultAtMs) },
                    versionMismatchText = getString(R.string.sender_error_version),
                    onEnded = { main.post { shutdown() } },
                    outageLimitMs = outageLimitMs,
                    firstConnectWindowMs = firstConnectWindowMs,
                )
            session = s
            s.start()
        }

        val directAddress = intent.getStringExtra(EXTRA_DIRECT_ADDRESS)
        if (directAddress != null && Build.VERSION.SDK_INT >= WifiDirectSupport.MIN_SENDER_SDK) {
            startDirect(P2pReceiver(target.name, directAddress, target.port)) {
                startHost(it, P2pPolicy.OUTAGE_LIMIT_MS, P2pPolicy.FIRST_CONNECT_WINDOW_MS)
            }
        } else {
            startHost(target, P2pPolicy.ROUTER_OUTAGE_LIMIT_MS)
        }
        return START_NOT_STICKY
    }

    /**
     * Wi-Fi Direct (ADR-011): creates the group, waits for the receiver to join, then starts the
     * host on the receiver's group address. A lost group ends the session for good.
     */
    @RequiresApi(35)
    private fun startDirect(receiver: P2pReceiver, startHost: (ReceiverEndpoint) -> Unit) {
        SenderStatus.set(SenderState.Connecting(ReceiverEndpoint(receiver.name, DIRECT_HOST, receiver.port)))
        joinJob =
            scope.launch {
                try {
                    // Usually already running since the pick (overlapping consent); fall back to a fresh
                    // attempt if there is none or it failed (e.g. timed out while consent was open).
                    val claimedAt = SystemClock.elapsedRealtime()
                    val early = EarlyDirectLink.claim(receiver.deviceAddress)
                    val link =
                        try {
                            early?.awaitOrRelease()
                        } catch (e: P2pFailure) {
                            Log.w(TAG, "early Wi-Fi Direct attempt failed (${e.message}), retrying")
                            null
                        }
                            ?: P2pGroupOwner(
                                this@ProjectionService,
                            ).open(receiver, EarlyDirectLink.senderName(this@ProjectionService))
                    p2pLink = link
                    Log.i(
                        TAG,
                        "Wi-Fi Direct link ready ${SystemClock.elapsedRealtime() - claimedAt} ms after consent (early: ${early != null})",
                    )
                    SenderStatus.setDirect(DirectLink(link.frequencyMhz))
                    startHost(link.endpoint)
                    link.lost.first()
                    // The receiver's own BYE usually arrives first: it decides the message then.
                    delay(LINK_LOST_GRACE_MS)
                    Log.i(TAG, "Wi-Fi Direct group lost")
                    stopSession(ByeReason.NORMAL, getString(R.string.sender_error_p2p_lost, receiver.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Wi-Fi Direct failed: ${e.message}")
                    SenderStatus.set(SenderState.Ended(p2pMessage(e, receiver.name)))
                    shutdown()
                }
            }
    }

    private fun p2pMessage(e: Exception, name: String): String = when (e) {
        is P2pFailure.Unsupported -> getString(R.string.sender_error_p2p_unsupported)
        is P2pFailure.Busy -> getString(R.string.sender_error_p2p_busy)
        is P2pFailure.Timeout -> getString(R.string.sender_error_p2p_timeout, name)
        is SecurityException -> getString(R.string.sender_error_p2p_permission)
        else -> getString(R.string.sender_error_p2p_failed, e.message ?: e.javaClass.simpleName)
    }

    private fun stopSession(reason: ByeReason, message: String? = null) {
        val s = session
        if (s == null) shutdown() else s.stop(reason, message)
    }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val (info, _) = displayInfo()
                session?.onSourceResized(info.width, info.height)
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }

    private fun shutdown() {
        if (Build.VERSION.SDK_INT < 34) {
            runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        }
        session = null
        // Stop while joining, a failed join, or the host ended: the group goes after the host.
        joinJob?.cancel()
        joinJob = null
        closeLink()
        projection?.let { runCatching { it.stop() } }
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeLink() {
        p2pLink?.close()
        p2pLink = null
        SenderStatus.setDirect(null)
    }

    override fun onDestroy() {
        session?.stop(ByeReason.USER_STOPPED)
        projection?.let { runCatching { it.stop() } }
        scope.cancel()
        closeLink()
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
        private const val EXTRA_CONSENT_RESULT_AT = "consentResultAt"
        private const val EXTRA_CONSENT_SHOWN_AT = "consentShownAt"
        private const val EXTRA_DIRECT_ADDRESS = "directAddress"

        /** Placeholder host shown while the Wi-Fi Direct group forms; the real one comes from the group. */
        const val DIRECT_HOST = "wifi-direct"
        private const val LINK_LOST_GRACE_MS = 300L

        fun start(
            context: Context,
            resultCode: Int,
            consent: Intent,
            target: ReceiverEndpoint,
            prefs: StreamPreferences,
            consentShownAtMs: Long,
            consentResultAtMs: Long,
            /** Set for a Wi-Fi Direct session: the receiver's P2P device address (ADR-011). */
            directAddress: String? = null,
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
                    .putExtra(EXTRA_CONSENT_RESULT_AT, consentResultAtMs)
                    .putExtra(EXTRA_CONSENT_SHOWN_AT, consentShownAtMs)
                    .putExtra(EXTRA_DIRECT_ADDRESS, directAddress)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
