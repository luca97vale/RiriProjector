package dev.ririprojector.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * Receives the shell helper's binder (ADR-014). The manifest guards it with
 * `INTERACT_ACROSS_USERS_FULL`; here only uid 0 or 2000 is accepted too, so a privileged app holding
 * that permission cannot hand over a fake helper.
 */
class HelperProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperMain.METHOD_SEND_BINDER) return null
        val caller = Binder.getCallingUid()
        if (caller != ROOT_UID && caller != SHELL_UID) {
            Log.w(TAG, "binder from uid $caller refused")
            return null
        }
        val binder = extras?.getBinder(HelperMain.EXTRA_BINDER) ?: return null
        HelperBinderSlot.offer(binder)
        return Bundle()
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val TAG = "Riri/HelperProvider"
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
    }
}

/** Hands the binder from the provider (binder thread) to whoever is waiting for it. */
internal object HelperBinderSlot {
    private val lock = Object()
    private var binder: IBinder? = null

    fun offer(b: IBinder) = synchronized(lock) {
        binder = b
        lock.notifyAll()
    }

    /** Clears any stale binder before a new start. */
    fun reset() = synchronized(lock) { binder = null }

    fun await(timeoutMs: Long): IBinder? = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (binder == null) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            lock.wait(left)
        }
        binder
    }
}
