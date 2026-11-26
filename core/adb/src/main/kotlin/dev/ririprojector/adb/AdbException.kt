// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbException.kt; modified for RiriProjector.
// See LICENSE-Shizuku.
package dev.ririprojector.adb

open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The pairing code was wrong: the peer's PeerInfo did not decrypt. */
class AdbInvalidPairingCodeException : AdbException("wrong pairing code")

/** The device refused our key (not paired, or pairing revoked). */
class AdbAuthException(message: String) : AdbException(message)
