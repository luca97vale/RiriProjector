// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt; modified for RiriProjector
// (public SSLSockets API on 31+, bounded reads, no logging of key material). See LICENSE-Shizuku.
package dev.ririprojector.adb

import android.annotation.SuppressLint
import android.net.ssl.SSLSockets
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** SPAKE2 + AES-128-GCM state, in native code over BoringSSL (`libriri_adb.so`). */
internal class PairingContext private constructor(private var ptr: Long) : Closeable {
    val msg: ByteArray = nativeMsg(ptr)

    fun initCipher(theirMsg: ByteArray): Boolean = nativeInitCipher(ptr, theirMsg)

    fun encrypt(input: ByteArray): ByteArray? = nativeEncrypt(ptr, input)

    fun decrypt(input: ByteArray): ByteArray? = nativeDecrypt(ptr, input)

    override fun close() {
        if (ptr != 0L) nativeDestroy(ptr)
        ptr = 0L
    }

    private external fun nativeMsg(ptr: Long): ByteArray

    private external fun nativeInitCipher(ptr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(ptr: Long, input: ByteArray): ByteArray?

    private external fun nativeDecrypt(ptr: Long, input: ByteArray): ByteArray?

    private external fun nativeDestroy(ptr: Long)

    companion object {
        init {
            System.loadLibrary("riri_adb")
        }

        fun create(password: ByteArray): PairingContext? =
            nativeConstructor(true, password).takeIf { it != 0L }?.let(::PairingContext)

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

/**
 * Pairs this app's [key] with Wireless debugging using the 6-digit code from Settings → Wireless
 * debugging → "Pair device with pairing code". Blocking: call on an IO thread. Android 11+.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val key: AdbKey,
    private val timeoutMs: Int = 10_000,
) : Closeable {
    private var socket: Socket? = null
    private var context: PairingContext? = null

    /** Throws [AdbInvalidPairingCodeException] for a wrong code, [AdbException] for protocol errors. */
    fun pair() {
        // Owned before connect(), so close() also releases a socket whose connect failed.
        val plain = Socket().also { socket = it }.apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = timeoutMs
        }
        val tls = key.sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        socket = tls
        tls.startHandshake()
        val input = DataInputStream(tls.inputStream)
        val output = DataOutputStream(tls.outputStream)

        val material = exportKeyingMaterial(tls)
        val ctx = PairingContext.create(pairingCode.toByteArray() + material)
            ?: throw AdbException("cannot create the pairing context")
        context = ctx

        // SPAKE2 messages.
        write(output, PairingPacket.TYPE_SPAKE2_MSG, ctx.msg)
        val theirMsg = read(input, PairingPacket.TYPE_SPAKE2_MSG)
        if (!ctx.initCipher(theirMsg)) throw AdbInvalidPairingCodeException()

        // Encrypted PeerInfo: our public key out, theirs in (a wrong code fails to decrypt).
        val ours = ctx.encrypt(PairingPacket.peerInfo(key.adbPublicKey)) ?: throw AdbException("encrypt failed")
        write(output, PairingPacket.TYPE_PEER_INFO, ours)
        val theirs = read(input, PairingPacket.TYPE_PEER_INFO)
        val decrypted = ctx.decrypt(theirs) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != PairingPacket.MAX_PEER_INFO_SIZE) throw AdbException("bad peer info ${decrypted.size}")
    }

    private fun write(output: DataOutputStream, type: Byte, payload: ByteArray) {
        output.write(PairingPacket.header(type, payload.size))
        output.write(payload)
        output.flush()
    }

    private fun read(input: DataInputStream, expected: Byte): ByteArray {
        val header = ByteArray(PairingPacket.HEADER_SIZE)
        input.readFully(header)
        val (type, size) = PairingPacket.parseHeader(header)
        if (type != expected) throw AdbException("unexpected pairing packet $type")
        return ByteArray(size).also { input.readFully(it) }
    }

    override fun close() {
        runCatching { socket?.close() }
        context?.close()
        context = null
    }

    companion object {
        private const val LABEL = "adb-label\u0000"
        private const val SIZE = 64

        /** TLS exporter (RFC 5705) bound into the SPAKE2 password, as adb does. */
        @SuppressLint("NewApi", "PrivateApi", "DiscouragedPrivateApi")
        internal fun exportKeyingMaterial(socket: SSLSocket): ByteArray {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return SSLSockets.exportKeyingMaterial(socket, LABEL, null, SIZE)
                    ?: throw AdbException("TLS exporter unavailable")
            }
            // Android 11 only: the platform Conscrypt behind the hidden-API list (ADR-014).
            HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/")
            val conscrypt = Class.forName("com.android.org.conscrypt.Conscrypt")
            val method = conscrypt.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
            )
            return method.invoke(null, socket, LABEL, null, SIZE) as ByteArray
        }
    }
}
