// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt; modified for RiriProjector (timeouts,
// captured shell output, typed auth failure). See LICENSE-Shizuku.
package dev.ririprojector.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Minimal ADB client for this device's own adbd (Wireless debugging): CNXN, then STLS (Android 11+,
 * TLS with our paired [key]) or legacy AUTH, then one `shell:` service. Blocking: use an IO thread.
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
    private val timeoutMs: Int = 10_000,
) : Closeable {
    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    fun connect() {
        val plain = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = timeoutMs
        }
        socket = plain
        input = DataInputStream(plain.getInputStream())
        output = DataOutputStream(plain.getOutputStream())

        write(AdbMessage.ofString(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "host::"))
        var message = read()
        when (message.command) {
            AdbProtocol.A_STLS -> {
                write(AdbMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0))
                val tls = key.sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
                socket = tls
                // adbd rejects an unpaired certificate during the handshake.
                runCatching { tls.startHandshake() }.onFailure { throw AdbAuthException("TLS refused: ${it.message}") }
                input = DataInputStream(tls.inputStream)
                output = DataOutputStream(tls.outputStream)
                message = read()
            }

            AdbProtocol.A_AUTH -> {
                if (message.arg0 != AdbProtocol.ADB_AUTH_TOKEN) throw AdbException("expected AUTH TOKEN")
                write(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, key.sign(message.data)))
                message = read()
                if (message.command != AdbProtocol.A_CNXN) {
                    // Would need the user to accept our key on screen: not the Wireless-debugging flow.
                    throw AdbAuthException("signature not accepted")
                }
            }
        }
        if (message.command != AdbProtocol.A_CNXN) throw AdbException("expected CNXN, got $message")
    }

    /**
     * Runs [command] through `shell:` and returns its output (stdout and stderr, at most [maxOutput]
     * bytes) once the stream closes. A command that backgrounds itself returns at once.
     */
    fun shell(command: String, maxOutput: Int = 64 * 1024): String {
        val localId = 1
        write(AdbMessage.ofString(AdbProtocol.A_OPEN, localId, 0, "shell:$command"))
        val out = ByteArrayOutputStream()
        var message = read()
        if (message.command == AdbProtocol.A_CLSE) return ""
        if (message.command != AdbProtocol.A_OKAY) throw AdbException("shell not opened: $message")
        while (true) {
            message = read()
            val remoteId = message.arg0
            when (message.command) {
                AdbProtocol.A_WRTE -> {
                    if (out.size() <
                        maxOutput
                    ) {
                        out.write(message.data, 0, minOf(message.data.size, maxOutput - out.size()))
                    }
                    write(AdbMessage(AdbProtocol.A_OKAY, localId, remoteId))
                }

                AdbProtocol.A_CLSE -> {
                    write(AdbMessage(AdbProtocol.A_CLSE, localId, remoteId))
                    return out.toString(Charsets.UTF_8.name())
                }

                AdbProtocol.A_OKAY -> Unit

                else -> throw AdbException("unexpected $message")
            }
        }
    }

    private fun write(message: AdbMessage) {
        output.write(message.toByteArray())
        output.flush()
    }

    private fun read(): AdbMessage = AdbMessage.read(input)

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
