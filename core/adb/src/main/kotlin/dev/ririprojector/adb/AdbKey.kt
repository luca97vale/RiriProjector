// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbKey.kt; modified for RiriProjector (renamed key
// alias and store, no logging of key material). See LICENSE-Shizuku.
package dev.ririprojector.adb

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Where the wrapped private key lives (SharedPreferences on device). */
interface AdbKeyStore {
    fun put(bytes: ByteArray)

    fun get(): ByteArray?
}

/** Stores the AES-GCM-wrapped key in its own prefs file, excluded from backups (data_extraction_rules). */
class PreferenceAdbKeyStore(private val preferences: SharedPreferences) : AdbKeyStore {
    override fun put(bytes: ByteArray) {
        preferences.edit().putString(KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    override fun get(): ByteArray? = preferences.getString(KEY, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    companion object {
        /** Prefs file name; keep in sync with `data_extraction_rules.xml`. */
        const val FILE = "riri_adb_key"
        private const val KEY = "adbkey"
    }
}

/**
 * The RSA-2048 identity RiriProjector pairs with Wireless debugging. The private key is wrapped by an
 * AndroidKeyStore AES key, so a copied prefs file is useless on another device. [name] is shown in
 * Settings → Wireless debugging → Paired devices.
 */
class AdbKey(private val store: AdbKeyStore, name: String) {
    private val encryptionKey: Key = getOrCreateEncryptionKey()
    private val privateKey: RSAPrivateKey = getOrCreatePrivateKey()
    private val certificate: X509Certificate

    /** ADB-encoded public key, sent in pairing and in AUTH RSAPUBLICKEY. */
    val adbPublicKey: ByteArray

    init {
        val crt = privateKey as? RSAPrivateCrtKey ?: throw AdbException("RSA key has no public exponent")
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val holder = X509v3CertificateBuilder(
            X500Name("CN=00"),
            BigInteger.ONE,
            Date(0),
            Date(2_461_449_600_000L),
            Locale.ROOT,
            X500Name("CN=00"),
            SubjectPublicKeyInfo.getInstance(publicKey.encoded),
        ).build(signer)
        certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
        adbPublicKey = AdbPublicKey.encode(crt.modulus, crt.publicExponent, name)
    }

    /** Signs an AUTH token (legacy, non-TLS adbd) with PKCS#1 v1.5 SHA-1 padding as adbd expects. */
    fun sign(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(data)
    }

    /** TLS 1.3 with our certificate as client identity; adbd's self-signed certificate is accepted. */
    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager()), arrayOf(trustAll()), SecureRandom()) }
    }

    private fun getOrCreateEncryptionKey(): Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(ENCRYPTION_KEY_ALIAS, null)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            ENCRYPTION_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        store.get()?.let { wrapped ->
            runCatching {
                val plain = unwrap(wrapped)
                return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(plain)) as RSAPrivateKey
            }
            // Unreadable (keystore reset, restored from elsewhere): make a new key; the user pairs again.
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
        generator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val key = generator.generateKeyPair().private as RSAPrivateKey
        store.put(wrap(key.encoded))
        return key
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(plain)
        return cipher.iv + sealed
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_SIZE + TAG_SIZE) { "wrapped key too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(TAG_SIZE * 8, wrapped, 0, IV_SIZE))
        cipher.updateAAD(AAD)
        return cipher.doFinal(wrapped, IV_SIZE, wrapped.size - IV_SIZE)
    }

    private fun keyManager() = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?) =
            if (keyTypes.any { it == "RSA" }) ALIAS else null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == ALIAS) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) privateKey else null

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
            null
    }

    // adbd presents a self-signed certificate; trust comes from the pairing (SPAKE2) and adbd's key list,
    // and the connection is to this device only (loopback or its own address).
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun trustAll() = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            Unit

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ENCRYPTION_KEY_ALIAS = "riri_adbkey_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val ALIAS = "key"
        val AAD = ByteArray(16).also { "adbkey".toByteArray().copyInto(it) }

        /** PKCS#1 v1.5 type-1 padding plus the SHA-1 DigestInfo prefix for a 2048-bit key. */
        val SIGNATURE_PADDING: ByteArray = ByteArray(2048 / 8 - 20).also { p ->
            val digestInfo = byteArrayOf(
                0x00, 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
            )
            p[0] = 0x00
            p[1] = 0x01
            for (i in 2 until p.size - digestInfo.size + 1) p[i] = 0xFF.toByte()
            digestInfo.copyInto(p, p.size - digestInfo.size)
        }
    }
}
