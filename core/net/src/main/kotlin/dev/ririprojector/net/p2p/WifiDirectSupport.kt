package dev.ririprojector.net.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Whether this device can use the Wi-Fi Direct link, and which runtime permissions it needs. */
object WifiDirectSupport {
    /** The sender reads the receiver's address from its group (`WifiP2pDevice.getIpAddress`). */
    const val MIN_SENDER_SDK = 35

    /**
     * Runtime permissions P2P discovery and joining need at [sdk]: none on API 28 (Fire OS 7),
     * location on 29–32, nearby Wi-Fi devices from 33. Pure.
     */
    @SuppressLint("InlinedApi") // only returned for the API level that defines it
    fun requiredPermissions(sdk: Int): List<String> = when {
        sdk >= 33 -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        sdk >= 29 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> emptyList()
    }

    fun hasFeature(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun missingPermissions(context: Context): List<String> = requiredPermissions(Build.VERSION.SDK_INT).filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    /** Feature present and permissions granted. */
    fun usable(context: Context): Boolean = hasFeature(context) && missingPermissions(context).isEmpty()

    /** The sender side also needs API 35. */
    fun usableAsSender(context: Context): Boolean = Build.VERSION.SDK_INT >= MIN_SENDER_SDK && hasFeature(context)
}
