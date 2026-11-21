package dev.ririprojector.feature.sender

import android.content.Context
import android.content.SharedPreferences

/** [KeyValueStore] on a private SharedPreferences file; writes are asynchronous (`apply`). */
internal class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putAll(values: Map<String, String?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        editor.apply()
    }

    companion object {
        private const val FILE = "riri_sender_settings"

        fun create(context: Context) = SharedPreferencesStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
