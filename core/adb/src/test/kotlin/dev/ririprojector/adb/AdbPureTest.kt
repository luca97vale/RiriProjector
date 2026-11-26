package dev.ririprojector.adb

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbPureTest {
    private fun roundTrip(m: AdbMessage) = AdbMessage.read(DataInputStream(ByteArrayInputStream(m.toByteArray())))

    @Test
    fun `message round trips with header fields`() {
        val m = AdbMessage.ofString(AdbProtocol.A_OPEN, 1, 0, "shell:id")

        val back = roundTrip(m)

        assertThat(back.command).isEqualTo(AdbProtocol.A_OPEN)
        assertThat(back.arg0).isEqualTo(1)
        assertThat(String(back.data)).isEqualTo("shell:id\u0000")
        assertThat(m.toByteArray().size).isEqualTo(AdbMessage.HEADER_LENGTH + 9)
    }

    @Test
    fun `header is little-endian with magic and byte-sum checksum`() {
        val b = ByteBuffer.wrap(AdbMessage(AdbProtocol.A_WRTE, 2, 3, byteArrayOf(1, -1)).toByteArray())
            .order(ByteOrder.LITTLE_ENDIAN)

        assertThat(b.getInt(0)).isEqualTo(AdbProtocol.A_WRTE)
        assertThat(b.getInt(12)).isEqualTo(2)
        assertThat(b.getInt(16)).isEqualTo(1 + 255)
        assertThat(b.getInt(20)).isEqualTo(AdbProtocol.A_WRTE xor -1)
    }

    @Test
    fun `bad magic, length or checksum is rejected`() {
        val good = AdbMessage(AdbProtocol.A_OKAY, 1, 2, byteArrayOf(5)).toByteArray()
        fun mutated(index: Int, value: Int) =
            good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(index, value) }

        listOf(mutated(20, 0), mutated(12, -1), mutated(12, AdbMessage.MAX_DATA + 1), mutated(16, 99)).forEach {
            assertThrows(AdbException::class.java) { AdbMessage.read(DataInputStream(ByteArrayInputStream(it))) }
        }
    }

    @Test
    fun `zero checksum is accepted as adbd sends since protocol 0x01000001`() {
        val bytes = AdbMessage(AdbProtocol.A_OKAY, 1, 2, byteArrayOf(5)).toByteArray()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0)

        assertThat(AdbMessage.read(DataInputStream(ByteArrayInputStream(bytes))).data).isEqualTo(byteArrayOf(5))
    }

    @Test
    fun `public key struct matches android_pubkey layout`() {
        val key = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair().public as RSAPublicKey

        val encoded = AdbPublicKey.encode(key.modulus, key.publicExponent, "riri@test")
        val text = String(encoded)
        val struct = Base64.getDecoder().decode(text.substringBefore(' '))
        val b = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)

        assertThat(text).endsWith(" riri@test\u0000")
        assertThat(struct.size).isEqualTo(AdbPublicKey.STRUCT_SIZE)
        assertThat(b.getInt(0)).isEqualTo(64)
        // n0inv * N[0] == -1 mod 2^32
        val n0 = key.modulus.mod(BigInteger.ONE.shiftLeft(32)).toLong()
        assertThat((b.getInt(4).toLong() * n0).toInt()).isEqualTo(-1)
        // modulus words, little-endian
        val modulusLe = struct.copyOfRange(8, 8 + 256).reversedArray()
        assertThat(BigInteger(1, modulusLe)).isEqualTo(key.modulus)
        assertThat(b.getInt(AdbPublicKey.STRUCT_SIZE - 4)).isEqualTo(65537)
    }

    @Test
    fun `pairing header round trips and rejects bad values`() {
        val h = PairingPacket.header(PairingPacket.TYPE_PEER_INFO, 8208)

        assertThat(PairingPacket.parseHeader(h)).isEqualTo(PairingPacket.TYPE_PEER_INFO to 8208)
        listOf(
            byteArrayOf(2, 0, 0, 0, 0, 1),
            byteArrayOf(1, 7, 0, 0, 0, 1),
            byteArrayOf(1, 0, 0, 0, 0, 0),
            PairingPacket.header(PairingPacket.TYPE_SPAKE2_MSG, PairingPacket.MAX_PAYLOAD_SIZE + 1),
        ).forEach { assertThrows(AdbException::class.java) { PairingPacket.parseHeader(it) } }
    }

    @Test
    fun `peer info is 8192 bytes with type and key`() {
        val p = PairingPacket.peerInfo(byteArrayOf(9, 8, 7))

        assertThat(p.size).isEqualTo(8192)
        assertThat(p.copyOfRange(0, 4)).isEqualTo(byteArrayOf(0, 9, 8, 7))
    }
}

class ShellAccessTest {
    private fun run(start: ShellAccessState, vararg events: ShellAccessEvent) =
        events.fold(start) { s, e -> ShellAccess.reduce(s, e) }

    @Test
    fun `Android 10 is unsupported and stays so`() {
        val s = ShellAccess.initial(29, paired = true)

        assertThat(s).isEqualTo(ShellAccessState.Unsupported)
        assertThat(run(s, ShellAccessEvent.PairingRequested())).isEqualTo(ShellAccessState.Unsupported)
    }

    @Test
    fun `first setup pairs then starts the helper`() {
        val s = run(
            ShellAccess.initial(36, paired = false),
            ShellAccessEvent.PairingRequested(),
            ShellAccessEvent.PairingPort(37_000),
            ShellAccessEvent.PairingSucceeded,
            ShellAccessEvent.StartRequested,
            ShellAccessEvent.HelperStarted,
        )

        assertThat(s).isEqualTo(ShellAccessState.Ready)
    }

    @Test
    fun `wrong code fails without pairing`() {
        val s = run(ShellAccessState.Pairing(true), ShellAccessEvent.PairingFailed(wrongCode = true))

        assertThat(s).isEqualTo(ShellAccessState.Failed(ShellAccessState.Reason.WRONG_CODE, paired = false))
        assertThat(run(s, ShellAccessEvent.StartRequested)).isEqualTo(s)
    }

    @Test
    fun `after a reboot the user only turns Wireless debugging back on`() {
        val booted = ShellAccess.initial(36, paired = true)

        assertThat(booted).isEqualTo(ShellAccessState.Paired(wirelessDebuggingOn = false))
        assertThat(run(booted, ShellAccessEvent.ConnectPort(41_000)))
            .isEqualTo(ShellAccessState.Paired(wirelessDebuggingOn = true))
    }

    @Test
    fun `helper death returns to paired and a refused key needs pairing again`() {
        assertThat(run(ShellAccessState.Ready, ShellAccessEvent.HelperDied(connectPortKnown = false)))
            .isEqualTo(ShellAccessState.Paired(wirelessDebuggingOn = false))
        assertThat(run(ShellAccessState.Starting, ShellAccessEvent.KeyRefused)).isEqualTo(ShellAccessState.NotPaired)
    }

    @Test
    fun `connect failure keeps the pairing and a new port recovers`() {
        val failed = run(ShellAccessState.Starting, ShellAccessEvent.ConnectFailed)

        assertThat(ShellAccess.isPaired(failed)).isTrue()
        assertThat(run(failed, ShellAccessEvent.ConnectPort(40_000)))
            .isEqualTo(ShellAccessState.Paired(wirelessDebuggingOn = true))
    }

    @Test
    fun `a running helper ignores adbd going away`() {
        assertThat(run(ShellAccessState.Ready, ShellAccessEvent.ConnectPort(-1))).isEqualTo(ShellAccessState.Ready)
    }

    @Test
    fun `helper idle exit with Wireless debugging still on can restart at once`() {
        assertThat(run(ShellAccessState.Ready, ShellAccessEvent.HelperDied(connectPortKnown = true)))
            .isEqualTo(ShellAccessState.Paired(wirelessDebuggingOn = true))
    }

    @Test
    fun `re-pairing from a failure keeps the earlier pairing when cancelled or wrong`() {
        val failed = ShellAccessState.Failed(ShellAccessState.Reason.HELPER_DID_NOT_START, paired = true)
        val pairing = run(failed, ShellAccessEvent.PairingRequested())

        assertThat(pairing).isEqualTo(ShellAccessState.Pairing(portFound = false, paired = true))
        assertThat(ShellAccess.isPaired(pairing)).isTrue()
        assertThat(run(pairing, ShellAccessEvent.PairingCancelled(connectPortKnown = true)))
            .isEqualTo(ShellAccessState.Paired(wirelessDebuggingOn = true))
        assertThat(ShellAccess.isPaired(run(pairing, ShellAccessEvent.PairingFailed(wrongCode = true)))).isTrue()
    }

    @Test
    fun `cancelling a first pairing goes back to not paired`() {
        val s = run(ShellAccessState.NotPaired, ShellAccessEvent.PairingRequested(portKnown = true))

        assertThat(s).isEqualTo(ShellAccessState.Pairing(portFound = true))
        assertThat(run(s, ShellAccessEvent.PairingCancelled(connectPortKnown = true)))
            .isEqualTo(ShellAccessState.NotPaired)
    }
}
