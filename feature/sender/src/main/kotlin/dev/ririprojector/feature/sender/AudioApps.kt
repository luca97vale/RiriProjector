package dev.ririprojector.feature.sender

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An app the user can pick as the audio source when sharing a single app (ADR-009). */
data class AudioApp(val packageName: String, val label: String)

object AudioApps {
    /**
     * Launchable apps except this one, sorted by label. Blocking (PackageManager I/O): call off the
     * main thread. Visibility comes from the LAUNCHER `<queries>` entry in the manifest.
     */
    fun launchable(context: Context): List<AudioApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { AudioApp(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /** The uid that plays [packageName]'s audio, or null if the app is gone or not visible. */
    fun uidOf(context: Context, packageName: String): Int? = try {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
