// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbKey.kt (adbEncoded); modified for RiriProjector
// (pure, java.util.Base64). See LICENSE-Shizuku.
package dev.ririprojector.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * ADB's public-key format (libcrypto_utils/android_pubkey.cpp): the RSAPublicKey struct (modulus
 * size in words, n0inv, modulus and R² as little-endian words, exponent), base64-encoded, then
 * " name" and a NUL. Pure.
 */
object AdbPublicKey {
    const val MODULUS_SIZE = 2048 / 8
    private const val MODULUS_WORDS = MODULUS_SIZE / 4
    const val STRUCT_SIZE = 4 + 4 + MODULUS_SIZE + MODULUS_SIZE + 4

    fun encode(modulus: BigInteger, exponent: BigInteger, name: String): ByteArray =
        Base64.getEncoder().encode(struct(modulus, exponent)) + " $name\u0000".toByteArray()

    fun struct(modulus: BigInteger, exponent: BigInteger): ByteArray {
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = modulus.mod(r32).modInverse(r32).negate()
        val rr = BigInteger.ONE.shiftLeft(MODULUS_SIZE * 8).modPow(BigInteger.valueOf(2), modulus)
        val buffer = ByteBuffer.allocate(STRUCT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MODULUS_WORDS)
        buffer.putInt(n0inv.toInt())
        putWords(buffer, modulus, r32)
        putWords(buffer, rr, r32)
        buffer.putInt(exponent.toInt())
        return buffer.array()
    }

    /** Little-endian 32-bit words, zero-padded to the modulus size. */
    private fun putWords(buffer: ByteBuffer, value: BigInteger, r32: BigInteger) {
        var rest = value
        repeat(MODULUS_WORDS) {
            val parts = rest.divideAndRemainder(r32)
            buffer.putInt(parts[1].toInt())
            rest = parts[0]
        }
    }
}
