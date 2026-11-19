package dev.ririprojector.feature.sender

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/** An app coming to the foreground, from `UsageEvents.ACTIVITY_RESUMED`. Wall-clock ms. */
data class ResumeEvent(val timeMs: Long, val packageName: String)

/**
 * Finds the app the user shared in single-app mode (ADR-009). Android (≤ 16) does not report the
 * choice, but the system brings the chosen app to the front right after the consent dialog: the
 * last app resumed since the dialog was shown, other than this app, System UI and the launcher,
 * is the shared one. Pure logic; [SharedAppDetector] feeds it from `UsageStatsManager`.
 */
object SharedAppPicker {
    fun pick(events: List<ResumeEvent>, sinceMs: Long, excluded: Set<String>): String? = events
        .filter { it.timeMs >= sinceMs && it.packageName !in excluded }
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
     * Blocking (polls up to [timeoutMs]): the uid of the app brought to the front since
     * [consentShownAtMs] (wall clock), or null without Usage access or if none was seen.
     */
    fun detectUid(context: Context, consentShownAtMs: Long, timeoutMs: Long = 3_000): Int? {
        if (!hasUsageAccess(context)) {
            Log.w(TAG, "no usage access: cannot tell which app is shared")
            return null
        }
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val excluded = SYSTEM_UI + context.packageName + homePackages(context)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val pkg = SharedAppPicker.pick(resumes(usm, consentShownAtMs - 1_000), consentShownAtMs, excluded)
            if (pkg != null) {
                val uid = uidOf(context, pkg)
                Log.i(TAG, "shared app: $pkg (uid $uid)")
                return uid
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
            Thread.sleep(200)
        }
        Log.w(TAG, "no app came to the front after the consent dialog")
        return null
    }

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
