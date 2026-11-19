package dev.ririprojector.feature.sender

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log

/** What the user chose in the system share dialog (ADR-009). */
sealed interface ShareScope {
    /** The whole display: capture every app's sound. */
    data object EntireScreen : ShareScope

    /** One app: capture only its sound; [uid] null if the app could not be resolved. */
    data class SingleApp(val uid: Int?) : ShareScope

    /** No Usage access: the choice cannot be seen, so every app's sound is captured. */
    data object Unknown : ShareScope
}

/** An app coming to the foreground, from `UsageEvents.ACTIVITY_RESUMED`. Wall-clock ms. */
data class ResumeEvent(val timeMs: Long, val packageName: String)

/**
 * Finds the app the user shared in single-app mode (ADR-009). Android (≤ 16) does not report the
 * choice, but the system brings the chosen app to the front right after the consent dialog: the
 * last app resumed since the dialog was shown, other than this app, System UI and the launcher,
 * is the shared one. Pure logic; [SharedAppDetector] feeds it from `UsageStatsManager`.
 */
object SharedAppPicker {
    fun pick(events: List<ResumeEvent>, sinceMs: Long, untilMs: Long, excluded: Set<String>): String? = events
        .filter { it.timeMs in sinceMs..untilMs && it.packageName !in excluded }
        .maxByOrNull { it.timeMs }
        ?.packageName
}

/** Android side of [SharedAppPicker]: needs the user-granted "Usage access" (app op). */
object SharedAppDetector {
    private const val TAG = "Riri/SharedApp"
    private val SYSTEM_UI = setOf("com.android.systemui")

    /** True when the user allowed Usage access for this app in Settings. */
    fun hasUsageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false // single-app sharing needs API 34 anyway
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** Opens the system screen where the user grants Usage access. */
    fun usageAccessSettings(): Intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * Blocking (session thread; may wait until [WINDOW_MS] after the consent result): what the user
     * chose in the system dialog. An app brought to the front between the dialog being shown
     * ([consentShownAtMs]) and [WINDOW_MS] after its result ([consentResultAtMs]) means one app
     * was shared; none means the entire screen. All times are wall clock (UsageEvents).
     */
    fun detect(context: Context, consentShownAtMs: Long, consentResultAtMs: Long): ShareScope {
        if (Build.VERSION.SDK_INT < 34) return ShareScope.EntireScreen // no single-app option
        if (!hasUsageAccess(context)) {
            Log.w(TAG, "no usage access: cannot tell what was shared")
            return ShareScope.Unknown
        }
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val excluded = SYSTEM_UI + context.packageName + homePackages(context)
        val untilMs = consentResultAtMs + WINDOW_MS
        while (true) {
            val events = resumes(usm, consentShownAtMs - 1_000)
            val pkg = SharedAppPicker.pick(events, consentShownAtMs, untilMs, excluded)
            if (pkg != null) {
                val uid = uidOf(context, pkg)
                Log.i(TAG, "one app shared: $pkg (uid $uid)")
                return ShareScope.SingleApp(uid)
            }
            val left = untilMs - System.currentTimeMillis()
            if (left <= 0) break
            Thread.sleep(minOf(left, 200))
        }
        Log.i(TAG, "no app came to the front: entire screen shared")
        return ShareScope.EntireScreen
    }

    private const val WINDOW_MS = 1_500L

    private fun resumes(usm: UsageStatsManager, fromMs: Long): List<ResumeEvent> {
        val out = ArrayList<ResumeEvent>()
        val events = usm.queryEvents(fromMs, System.currentTimeMillis() + 1_000) ?: return out
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) out += ResumeEvent(e.timeStamp, e.packageName)
        }
        return out
    }

    private fun homePackages(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }

    private fun uidOf(context: Context, packageName: String): Int? = try {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
