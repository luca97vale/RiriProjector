// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbProtocol.kt; modified for RiriProjector.
// See LICENSE-Shizuku.
package dev.ririprojector.adb

/** ADB wire constants (packages/modules/adb/adb.h). */
object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534C5453

    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 4096
    const val A_STLS_VERSION = 0x01000000

    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3

    /** NSD service types of Wireless debugging (Android 11+). */
    const val SERVICE_TLS_CONNECT = "_adb-tls-connect._tcp"
    const val SERVICE_TLS_PAIRING = "_adb-tls-pairing._tcp"
}
