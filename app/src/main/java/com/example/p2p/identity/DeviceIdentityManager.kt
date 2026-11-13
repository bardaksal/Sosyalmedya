package com.example.p2p.identity

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

data class DeviceIdentity(
    val deviceId: String,
    val publicKeyB64: String,
    val publicKeyFingerprint: String
)

/**
 * Manages the device-local cryptographic identity (persistent across app restarts)
 * completely independent of any optional cloud user account.
 */
class DeviceIdentityManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    private val keyPair: KeyPair by lazy { loadOrCreateIdentityKeyPair() }
    val deviceId: String by lazy { loadOrCreateDeviceId() }

    fun getDeviceIdentity(): DeviceIdentity {
        return DeviceIdentity(
            deviceId = deviceId,
            publicKeyB64 = getPublicKeyBase64(),
            publicKeyFingerprint = getPublicKeyFingerprint()
        )
    }

    fun getPublicKey(): PublicKey = keyPair.public

    fun getPublicKeyBytes(): ByteArray = keyPair.public.encoded

    fun getPublicKeyBase64(): String =
        Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

    fun getPublicKeyFingerprint(): String =
        fingerprintOf(keyPair.public.encoded)

    /**
     * Short 8-byte binary tag derived from deviceId for compact BLE advertising frames.
     */
    fun getShortAdvertisingTag(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(StandardCharsets.UTF_8))
        return digest.copyOfRange(0, 8)
    }

    fun signWithIdentity(data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private, secureRandom)
        sig.update(data)
        return sig.sign()
    }

    /**
     * Computes a raw ECDH P-256 shared secret between this device's long-term identity private key
     * and [peerPublicKeyEncoded] (X.509 EC public key). Keeps the private key encapsulated inside
     * [DeviceIdentityManager].
     */
    fun performIdentityEcdh(peerPublicKeyEncoded: ByteArray): ByteArray? {
        if (peerPublicKeyEncoded.size !in 32..512) return null
        return try {
            val kf = KeyFactory.getInstance("EC")
            val peerPublicKey = kf.generatePublic(X509EncodedKeySpec(peerPublicKeyEncoded))
            val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
            ka.init(keyPair.private)
            ka.doPhase(peerPublicKey, true)
            ka.generateSecret()
        } catch (_: Exception) {
            null
        }
    }

    private fun loadOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val bytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val uuid = UUID.nameUUIDFromBytes(bytes).toString()
        val created = "omn-$uuid"
        prefs.edit().putString(KEY_DEVICE_ID, created).commit()
        return created
    }

    private fun loadOrCreateIdentityKeyPair(): KeyPair {
        val privB64 = prefs.getString(KEY_PRIV_PKCS8, null)
        val pubB64 = prefs.getString(KEY_PUB_X509, null)
        if (!privB64.isNullOrBlank() && !pubB64.isNullOrBlank()) {
            try {
                val kf = KeyFactory.getInstance("EC")
                val priv: PrivateKey = kf.generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
                )
                val pub: PublicKey = kf.generatePublic(
                    X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP))
                )
                return KeyPair(pub, priv)
            } catch (_: Exception) {
                // Corrupt storage falls back to regenerating a clean device keypair
            }
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val generated = kpg.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIV_PKCS8, Base64.encodeToString(generated.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUB_X509, Base64.encodeToString(generated.public.encoded, Base64.NO_WRAP))
            .commit()
        return generated
    }

    companion object {
        private const val PREFS_NAME = "omnimesh_device_identity"
        private const val KEY_DEVICE_ID = "device_id_v1"
        private const val KEY_PRIV_PKCS8 = "identity_ec_priv_pkcs8"
        private const val KEY_PUB_X509 = "identity_ec_pub_x509"

        fun fingerprintOf(publicKeyEncoded: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
            return digest.take(16).joinToString("") { "%02X".format(it) }
        }

        fun verifyIdentitySignature(
            publicKeyEncoded: ByteArray,
            data: ByteArray,
            signatureBytes: ByteArray
        ): Boolean {
            return try {
                val kf = KeyFactory.getInstance("EC")
                val pub = kf.generatePublic(X509EncodedKeySpec(publicKeyEncoded))
                val sig = Signature.getInstance("SHA256withECDSA")
                sig.initVerify(pub)
                sig.update(data)
                sig.verify(signatureBytes)
            } catch (_: Exception) {
                false
            }
        }
    }
}
