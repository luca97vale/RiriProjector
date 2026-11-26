package dev.ririprojector.helper

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.view.InputEvent

/**
 * Platform access from inside `app_process` as the shell user (ADR-014), the way scrcpy does it:
 * reflection on framework classes, which hidden-API checks do not block in that process. Only the
 * helper process uses this object; the app never touches it.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
internal object ShellPlatform {
    const val SHELL_PACKAGE = "com.android.shell"
    private const val PER_USER_RANGE = 100_000
    private const val INJECT_MODE_ASYNC = 0

    /** The system context of a bare ActivityThread, wrapped to speak as `com.android.shell`. */
    val context: Context by lazy {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        if (Build.VERSION.SDK_INT >= 31) fillConfigurationController(at, thread)
        val system = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
        ShellContext(system)
    }

    /**
     * Vendor code (e.g. One UI's CompatSandbox) reads `ActivityThread.mConfigurationController`, which a
     * bare ActivityThread lacks (as in scrcpy's Workarounds). Best effort.
     */
    private fun fillConfigurationController(at: Class<*>, thread: Any) {
        runCatching {
            val internal = Class.forName("android.app.ActivityThreadInternal")
            val controller = Class.forName("android.app.ConfigurationController")
                .getDeclaredConstructor(internal)
                .apply { isAccessible = true }
                .newInstance(thread)
            at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
        }
    }

    val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
    }

    /**
     * uid of [packageName] for [userId]; never taken from the command line. Asks IPackageManager for
     * that user (the app may live only in a work profile), falling back to user 0.
     */
    fun packageUid(packageName: String, userId: Int): Int {
        val perUser = runCatching {
            val pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)!!
            val method = pm.javaClass.methods.first {
                it.name == "getPackageUid" && it.parameterTypes.size == 3
            }
            // (String, long flags, int userId) on API 33+, (String, int flags, int userId) before.
            val flags: Any = if (method.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0
            method.invoke(pm, packageName, flags, userId) as Int
        }.getOrNull()
        if (perUser != null && perUser >= 0) return perUser
        val appId = context.packageManager.getPackageUid(packageName, 0) % PER_USER_RANGE
        return userId * PER_USER_RANGE + appId
    }

    private val inputManager: Any by lazy {
        if (Build.VERSION.SDK_INT >= 34) {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } else {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
        }
    }

    private val injectMethod by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    private val setDisplayId by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    /** Injects [event] into [displayId] asynchronously (needs INJECT_EVENTS: shell has it). */
    fun inject(event: InputEvent, displayId: Int): Boolean {
        setDisplayId.invoke(event, displayId)
        return injectMethod.invoke(inputManager, event, INJECT_MODE_ASYNC) as Boolean
    }

    /**
     * Calls `call(method, extras)` on [authority]'s provider of [userId] via
     * `IActivityManager.getContentProviderExternal`, as shell. Used once, to hand the binder over.
     */
    fun callProvider(authority: String, userId: Int, method: String, extras: Bundle): Bundle? {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token: IBinder = Binder()
        val holder = am.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java,
            Int::class.javaPrimitiveType,
            IBinder::class.java,
            String::class.java,
        ).invoke(am, authority, userId, token, "riri_helper") ?: return null
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            val call = provider.javaClass.methods.first { it.name == "call" && it.parameterTypes.size in 5..6 }
            return if (call.parameterTypes.size == 5) {
                // API 31+: (AttributionSource, authority, method, arg, extras)
                call.invoke(provider, attribution(), authority, method, null, extras) as Bundle?
            } else {
                // API 30: (callingPkg, attributionTag, authority, method, arg, extras)
                call.invoke(provider, SHELL_PACKAGE, null, authority, method, null, extras) as Bundle?
            }
        } finally {
            runCatching {
                am.javaClass.getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java,
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(am, authority, token, userId)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun attribution(): Any = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()

    /** `getPackageName()` = shell, so DisplayManager, InputManager and AppOps accept our calls. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE

        override fun getOpPackageName() = SHELL_PACKAGE

        @SuppressLint("NewApi")
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()

        override fun getApplicationContext(): Context = this
    }
}
