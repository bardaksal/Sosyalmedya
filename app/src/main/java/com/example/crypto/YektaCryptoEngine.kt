package com.example.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedEnvelope(
    val ciphertextB64: String,
    val nonceB64: String,
    val ephemeralPubKeyB64: String,
    val onionHopsCount: Int
)

data class KademliaNodeDistance(
    val nodeHexId: String,
    val xorDistance: BigInteger,
    val bucketIndex: Int
)

class YektaCryptoEngine(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    private val identityKeyPair: KeyPair by lazy { loadOrCreateIdentityKeyPair() }

    fun getPublicKeyBase64(): String =
        Base64.encodeToString(identityKeyPair.public.encoded, Base64.NO_WRAP)

    fun getPublicKeyFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identityKeyPair.public.encoded)
        return digest.take(16).joinToString("") { "%02X".format(it) }
    }

    fun getKademliaNodeHexId(): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(identityKeyPair.public.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadOrCreateIdentityKeyPair(): KeyPair {
        val privB64 = prefs.getString(KEY_PRIVATE, null)
        val pubB64 = prefs.getString(KEY_PUBLIC, null)
        if (!privB64.isNullOrBlank() && !pubB64.isNullOrBlank()) {
            return try {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                KeyPair(pub, priv)
            } catch (_: Exception) {
                generateAndPersistKeyPair()
            }
        }
        return generateAndPersistKeyPair()
    }

    private fun generateAndPersistKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val kp = kpg.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    /**
     * Derives a 256-bit AES key using classical ECDH P-256 + HKDF-SHA512.
     */
    private fun deriveClassicEcdhAesKey(
        privateKey: java.security.PrivateKey,
        peerPublicKey: PublicKey,
        contextSalt: ByteArray
    ): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublicKey, true)
        val sharedSecret = ka.generateSecret()

        val hmacExtract = Mac.getInstance("HmacSHA512")
        hmacExtract.init(SecretKeySpec(contextSalt, "HmacSHA512"))
        val prk = hmacExtract.doFinal(sharedSecret)

        val hmacExpand = Mac.getInstance("HmacSHA512")
        hmacExpand.init(SecretKeySpec(prk, "HmacSHA512"))
        val info = "OMNIMESH_ECDH_P256_HKDF_SHA512_V1".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0x01)
        val okm = hmacExpand.doFinal(info)
        return SecretKeySpec(okm.copyOfRange(0, 32), "AES")
    }

    /**
     * Encrypts a direct payload with classical AES-256-GCM using an ephemeral ECDH keypair.
     * Multi-hop onion routing is disabled in Phase 1.
     */
    fun encryptForPeer(
        plaintext: String,
        recipientPubKeyB64: String,
        @Suppress("UNUSED_PARAMETER") relayPublicKeysB64: List<String> = emptyList()
    ): EncryptedEnvelope {
        val kf = KeyFactory.getInstance("EC")
        val recipientKey = kf.generatePublic(
            X509EncodedKeySpec(Base64.decode(recipientPubKeyB64, Base64.NO_WRAP))
        )

        val ephemeralKpg = KeyPairGenerator.getInstance("EC")
        ephemeralKpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val ephemeralPair = ephemeralKpg.generateKeyPair()

        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aesKey = deriveClassicEcdhAesKey(
            privateKey = ephemeralPair.private,
            peerPublicKey = recipientKey,
            contextSalt = iv
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val payloadBytes = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        return EncryptedEnvelope(
            ciphertextB64 = Base64.encodeToString(payloadBytes, Base64.NO_WRAP),
            nonceB64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            ephemeralPubKeyB64 = Base64.encodeToString(ephemeralPair.public.encoded, Base64.NO_WRAP),
            onionHopsCount = 1
        )
    }

    fun decryptFromPeer(
        ciphertextB64: String,
        nonceB64: String,
        ephemeralPubKeyB64: String
    ): String? {
        return try {
            val kf = KeyFactory.getInstance("EC")
            val ephemeralPub = kf.generatePublic(
                X509EncodedKeySpec(Base64.decode(ephemeralPubKeyB64, Base64.NO_WRAP))
            )
            val iv = Base64.decode(nonceB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            val aesKey = deriveClassicEcdhAesKey(
                privateKey = identityKeyPair.private,
                peerPublicKey = ephemeralPub,
                contextSalt = iv
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ciphertext)
            String(plain, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "yekta_crypto_identity"
        private const val KEY_PRIVATE = "ecdh_priv_pkcs8"
        private const val KEY_PUBLIC = "ecdh_pub_x509"

        fun computeXorDistance(hexA: String, hexB: String): KademliaNodeDistance {
            val a = BigInteger(hexA.ifBlank { "0" }, 16)
            val b = BigInteger(hexB.ifBlank { "0" }, 16)
            val xor = a.xor(b)
            val bucket = if (xor == BigInteger.ZERO) 0 else xor.bitLength()
            return KademliaNodeDistance(nodeHexId = hexB, xorDistance = xor, bucketIndex = bucket)
        }

        fun deriveNodeHexFromUid(uid: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(uid.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

object ContentHasher {
    fun sha256Hex(text: String): String {
        val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ")
        return sha256Hex(normalized.toByteArray(StandardCharsets.UTF_8))
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes a 64-bit Difference Hash (dHash) over a 9x8 downscaled grayscale bitmap.
     * Detects cropped, re-encoded, or resized duplicate visuals in constant time.
     */
    fun computeBitmapDHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val lum = IntArray(9 * 8)
        for (y in 0 until 8) {
            for (x in 0 until 9) {
                val p = scaled.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                lum[y * 9 + x] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }
        var bits = 0UL
        var idx = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (lum[y * 9 + x] < lum[y * 9 + x + 1]) {
                    bits = bits or (1UL shl idx)
                }
                idx++
            }
        }
        return "%016x".format(bits.toLong())
    }

    /**
     * Computes a 64-bit token shingle SimHash for fast local near-duplicate text screening.
     */
    fun computeTextSimHash(text: String): String {
        val tokens = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
        if (tokens.isEmpty()) return "0000000000000000"

        val weights = IntArray(64)
        val md = MessageDigest.getInstance("SHA-256")
        for (token in tokens) {
            val hash = md.digest(token.toByteArray(StandardCharsets.UTF_8))
            for (bit in 0 until 64) {
                val byteVal = hash[bit / 8].toInt()
                val isOne = ((byteVal shr (bit % 8)) and 1) == 1
                weights[bit] += if (isOne) 1 else -1
            }
        }
        var simHash = 0UL
        for (bit in 0 until 64) {
            if (weights[bit] > 0) {
                simHash = simHash or (1UL shl bit)
            }
        }
        return "%016x".format(simHash.toLong())
    }

    fun hammingDistanceHex(hexA: String, hexB: String): Int {
        if (hexA.length != 16 || hexB.length != 16) return 64
        return try {
            val a = hexA.toULong(16)
            val b = hexB.toULong(16)
            (a xor b).countOneBits()
        } catch (_: Exception) {
            64
        }
    }
}
