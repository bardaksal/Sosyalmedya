package com.example.p2p.crypto

import com.example.p2p.identity.DeviceIdentityManager
import kotlinx.coroutines.CancellationException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class SessionState {
    UNINITIALIZED,
    HANDSHAKING,
    ESTABLISHED,
    FAILED,
    CLOSED
}

data class SecureSession(
    val peerDeviceId: String,
    val peerIdentityPubKey: ByteArray,
    val peerFingerprint: String,
    val txKey: SecretKey,
    val rxKey: SecretKey,
    val establishedAt: Long,
    val seenSessionNonces: MutableSet<String> = HashSet()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureSession) return false
        return peerDeviceId == other.peerDeviceId &&
            peerIdentityPubKey.contentEquals(other.peerIdentityPubKey) &&
            establishedAt == other.establishedAt
    }

    override fun hashCode(): Int {
        var result = peerDeviceId.hashCode()
        result = 31 * result + peerIdentityPubKey.contentHashCode()
        result = 31 * result + establishedAt.hashCode()
        return result
    }
}

sealed interface EnvelopeVerificationResult {
    data class Valid(val plaintext: ByteArray, val envelope: MessageEnvelope) : EnvelopeVerificationResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Valid) return false
            return plaintext.contentEquals(other.plaintext) && envelope == other.envelope
        }

        override fun hashCode(): Int = 31 * plaintext.contentHashCode() + envelope.hashCode()
    }

    data class InvalidMessage(val reason: String) : EnvelopeVerificationResult
    data class ReplayDetected(val messageId: String) : EnvelopeVerificationResult
    data class Expired(val messageId: String) : EnvelopeVerificationResult
}

data class HandshakeInitPacket(
    val protocolVersion: Int,
    val senderDeviceId: String,
    val identityPubKey: ByteArray,
    val ephemeralPubKey: ByteArray,
    val clientNonce: ByteArray,
    val signature: ByteArray
) {
    fun serialize(): ByteArray {
        val idBytes = senderDeviceId.toByteArray(StandardCharsets.UTF_8)
        val total = 4 + 2 + idBytes.size + 2 + identityPubKey.size +
            2 + ephemeralPubKey.size + 2 + clientNonce.size + 2 + signature.size
        val buf = ByteBuffer.allocate(total)
        buf.putInt(protocolVersion)
        putShortBytes(buf, idBytes)
        putShortBytes(buf, identityPubKey)
        putShortBytes(buf, ephemeralPubKey)
        putShortBytes(buf, clientNonce)
        putShortBytes(buf, signature)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeInitPacket) return false
        return protocolVersion == other.protocolVersion &&
            senderDeviceId == other.senderDeviceId &&
            identityPubKey.contentEquals(other.identityPubKey) &&
            ephemeralPubKey.contentEquals(other.ephemeralPubKey) &&
            clientNonce.contentEquals(other.clientNonce) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + senderDeviceId.hashCode()
        result = 31 * result + identityPubKey.contentHashCode()
        result = 31 * result + ephemeralPubKey.contentHashCode()
        result = 31 * result + clientNonce.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        fun deserialize(bytes: ByteArray): HandshakeInitPacket? {
            return try {
                val buf = ByteBuffer.wrap(bytes)
                if (buf.remaining() < 4) return null
                val version = buf.int
                val idBytes = readShortBytes(buf, 1..256) ?: return null
                val idPub = readShortBytes(buf, 32..512) ?: return null
                val ephPub = readShortBytes(buf, 32..512) ?: return null
                val nonce = readShortBytes(buf, 16..64) ?: return null
                val sig = readShortBytes(buf, 32..256) ?: return null
                if (buf.hasRemaining()) return null
                HandshakeInitPacket(
                    protocolVersion = version,
                    senderDeviceId = String(idBytes, StandardCharsets.UTF_8),
                    identityPubKey = idPub,
                    ephemeralPubKey = ephPub,
                    clientNonce = nonce,
                    signature = sig
                )
            } catch (_: BufferUnderflowException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

data class HandshakeAckPacket(
    val protocolVersion: Int,
    val responderDeviceId: String,
    val initiatorDeviceId: String,
    val identityPubKey: ByteArray,
    val ephemeralPubKey: ByteArray,
    val serverNonce: ByteArray,
    val signature: ByteArray
) {
    fun serialize(): ByteArray {
        val respBytes = responderDeviceId.toByteArray(StandardCharsets.UTF_8)
        val initBytes = initiatorDeviceId.toByteArray(StandardCharsets.UTF_8)
        val total = 4 + 2 + respBytes.size + 2 + initBytes.size +
            2 + identityPubKey.size + 2 + ephemeralPubKey.size +
            2 + serverNonce.size + 2 + signature.size
        val buf = ByteBuffer.allocate(total)
        buf.putInt(protocolVersion)
        putShortBytes(buf, respBytes)
        putShortBytes(buf, initBytes)
        putShortBytes(buf, identityPubKey)
        putShortBytes(buf, ephemeralPubKey)
        putShortBytes(buf, serverNonce)
        putShortBytes(buf, signature)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeAckPacket) return false
        return protocolVersion == other.protocolVersion &&
            responderDeviceId == other.responderDeviceId &&
            initiatorDeviceId == other.initiatorDeviceId &&
            identityPubKey.contentEquals(other.identityPubKey) &&
            ephemeralPubKey.contentEquals(other.ephemeralPubKey) &&
            serverNonce.contentEquals(other.serverNonce) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + responderDeviceId.hashCode()
        result = 31 * result + initiatorDeviceId.hashCode()
        result = 31 * result + identityPubKey.contentHashCode()
        result = 31 * result + ephemeralPubKey.contentHashCode()
        result = 31 * result + serverNonce.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        fun deserialize(bytes: ByteArray): HandshakeAckPacket? {
            return try {
                val buf = ByteBuffer.wrap(bytes)
                if (buf.remaining() < 4) return null
                val version = buf.int
                val respBytes = readShortBytes(buf, 1..256) ?: return null
                val initBytes = readShortBytes(buf, 1..256) ?: return null
                val idPub = readShortBytes(buf, 32..512) ?: return null
                val ephPub = readShortBytes(buf, 32..512) ?: return null
                val nonce = readShortBytes(buf, 16..64) ?: return null
                val sig = readShortBytes(buf, 32..256) ?: return null
                if (buf.hasRemaining()) return null
                HandshakeAckPacket(
                    protocolVersion = version,
                    responderDeviceId = String(respBytes, StandardCharsets.UTF_8),
                    initiatorDeviceId = String(initBytes, StandardCharsets.UTF_8),
                    identityPubKey = idPub,
                    ephemeralPubKey = ephPub,
                    serverNonce = nonce,
                    signature = sig
                )
            } catch (_: BufferUnderflowException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

private fun putShortBytes(buf: ByteBuffer, data: ByteArray) {
    buf.putShort(data.size.toShort())
    buf.put(data)
}

private fun readShortBytes(buf: ByteBuffer, range: IntRange): ByteArray? {
    if (buf.remaining() < 2) return null
    val len = buf.short.toInt() and 0xFFFF
    if (len !in range || buf.remaining() < len) return null
    return ByteArray(len).also { buf.get(it) }
}

interface ReplayStore {
    suspend fun isMessageSeen(messageId: String): Boolean
    suspend fun recordMessageSeen(
        messageId: String,
        senderDeviceId: String,
        nonceHex: String,
        seenAt: Long,
        expiresAt: Long
    )
}

class InMemoryReplayStore : ReplayStore {
    private val seenIds = LinkedHashSet<String>()
    override suspend fun isMessageSeen(messageId: String): Boolean = synchronized(seenIds) {
        seenIds.contains(messageId)
    }

    override suspend fun recordMessageSeen(
        messageId: String,
        senderDeviceId: String,
        nonceHex: String,
        seenAt: Long,
        expiresAt: Long
    ) {
        synchronized(seenIds) {
            seenIds.add(messageId)
        }
    }
}

data class PendingClientHandshake(
    val localEphemeralKeyPair: KeyPair,
    val clientNonce: ByteArray,
    val initPacket: HandshakeInitPacket
)

/**
 * Classical authenticated key-exchange and AES-256-GCM transport encryption for OmniMesh Phase 1.
 * Uses ECDH (P-256) + ECDSA (P-256 / SHA-256) + HKDF-SHA256 + AES-256-GCM.
 */
class OmniMeshCrypto(
    private val identityManager: DeviceIdentityManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxE2eNonceCacheSize: Int = DEFAULT_MAX_E2E_NONCE_CACHE_SIZE
) {
    init {
        require(maxE2eNonceCacheSize > 0) { "maxE2eNonceCacheSize must be positive" }
    }

    private val secureRandom = SecureRandom()
    private val seenE2eNonces = LinkedHashMap<String, Long>()

    fun createHandshakeInit(): PendingClientHandshake {
        val ephPair = generateEphemeralEcPair()
        val clientNonce = ByteArray(16).also { secureRandom.nextBytes(it) }
        val localId = identityManager.deviceId
        val identityPub = identityManager.getPublicKeyBytes()
        val ephPub = ephPair.public.encoded

        val transcript = buildInitTranscript(localId, identityPub, ephPub, clientNonce)
        val sig = identityManager.signWithIdentity(transcript)

        val packet = HandshakeInitPacket(
            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
            senderDeviceId = localId,
            identityPubKey = identityPub,
            ephemeralPubKey = ephPub,
            clientNonce = clientNonce,
            signature = sig
        )
        return PendingClientHandshake(
            localEphemeralKeyPair = ephPair,
            clientNonce = clientNonce,
            initPacket = packet
        )
    }

    fun processHandshakeInitAndCreateAck(
        initPacket: HandshakeInitPacket,
        expectedPinnedPublicKey: ByteArray? = null
    ): Pair<HandshakeAckPacket, SecureSession>? {
        if (initPacket.protocolVersion != MessageEnvelope.CURRENT_PROTOCOL_VERSION) return null
        if (initPacket.senderDeviceId.isBlank() || initPacket.senderDeviceId == identityManager.deviceId) {
            return null
        }
        if (expectedPinnedPublicKey != null && !expectedPinnedPublicKey.contentEquals(initPacket.identityPubKey)) {
            return null
        }

        val initTranscript = buildInitTranscript(
            initPacket.senderDeviceId,
            initPacket.identityPubKey,
            initPacket.ephemeralPubKey,
            initPacket.clientNonce
        )
        if (!DeviceIdentityManager.verifyIdentitySignature(
                initPacket.identityPubKey,
                initTranscript,
                initPacket.signature
            )
        ) {
            return null
        }

        val peerEphemeralPub = decodeEcPublicKey(initPacket.ephemeralPubKey) ?: return null
        val localEphPair = generateEphemeralEcPair()
        val serverNonce = ByteArray(16).also { secureRandom.nextBytes(it) }
        val responderId = identityManager.deviceId
        val responderIdPub = identityManager.getPublicKeyBytes()
        val responderEphPub = localEphPair.public.encoded

        val ackTranscript = buildAckTranscript(
            responderDeviceId = responderId,
            initiatorDeviceId = initPacket.senderDeviceId,
            responderIdentityPubKey = responderIdPub,
            responderEphemeralPubKey = responderEphPub,
            initiatorEphemeralPubKey = initPacket.ephemeralPubKey,
            serverNonce = serverNonce,
            clientNonce = initPacket.clientNonce
        )
        val ackSig = identityManager.signWithIdentity(ackTranscript)

        val (txKey, rxKey) = deriveDirectionalKeys(
            localEphemeralPrivate = localEphPair.private,
            peerEphemeralPublic = peerEphemeralPub,
            clientNonce = initPacket.clientNonce,
            serverNonce = serverNonce,
            initiatorDeviceId = initPacket.senderDeviceId,
            responderDeviceId = responderId,
            initiatorEphPub = initPacket.ephemeralPubKey,
            responderEphPub = responderEphPub,
            isInitiator = false
        ) ?: return null

        val ackPacket = HandshakeAckPacket(
            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
            responderDeviceId = responderId,
            initiatorDeviceId = initPacket.senderDeviceId,
            identityPubKey = responderIdPub,
            ephemeralPubKey = responderEphPub,
            serverNonce = serverNonce,
            signature = ackSig
        )

        val session = SecureSession(
            peerDeviceId = initPacket.senderDeviceId,
            peerIdentityPubKey = initPacket.identityPubKey,
            peerFingerprint = DeviceIdentityManager.fingerprintOf(initPacket.identityPubKey),
            txKey = txKey,
            rxKey = rxKey,
            establishedAt = clock()
        )
        return ackPacket to session
    }

    fun completeClientHandshake(
        pending: PendingClientHandshake,
        ackPacket: HandshakeAckPacket,
        expectedPinnedPublicKey: ByteArray? = null
    ): SecureSession? {
        if (ackPacket.protocolVersion != MessageEnvelope.CURRENT_PROTOCOL_VERSION) return null
        if (ackPacket.initiatorDeviceId != identityManager.deviceId) return null
        if (ackPacket.responderDeviceId.isBlank() || ackPacket.responderDeviceId == identityManager.deviceId) {
            return null
        }
        if (expectedPinnedPublicKey != null && !expectedPinnedPublicKey.contentEquals(ackPacket.identityPubKey)) {
            return null
        }

        val ackTranscript = buildAckTranscript(
            responderDeviceId = ackPacket.responderDeviceId,
            initiatorDeviceId = identityManager.deviceId,
            responderIdentityPubKey = ackPacket.identityPubKey,
            responderEphemeralPubKey = ackPacket.ephemeralPubKey,
            initiatorEphemeralPubKey = pending.localEphemeralKeyPair.public.encoded,
            serverNonce = ackPacket.serverNonce,
            clientNonce = pending.clientNonce
        )
        if (!DeviceIdentityManager.verifyIdentitySignature(
                ackPacket.identityPubKey,
                ackTranscript,
                ackPacket.signature
            )
        ) {
            return null
        }

        val peerEphemeralPub = decodeEcPublicKey(ackPacket.ephemeralPubKey) ?: return null
        val (txKey, rxKey) = deriveDirectionalKeys(
            localEphemeralPrivate = pending.localEphemeralKeyPair.private,
            peerEphemeralPublic = peerEphemeralPub,
            clientNonce = pending.clientNonce,
            serverNonce = ackPacket.serverNonce,
            initiatorDeviceId = identityManager.deviceId,
            responderDeviceId = ackPacket.responderDeviceId,
            initiatorEphPub = pending.localEphemeralKeyPair.public.encoded,
            responderEphPub = ackPacket.ephemeralPubKey,
            isInitiator = true
        ) ?: return null

        return SecureSession(
            peerDeviceId = ackPacket.responderDeviceId,
            peerIdentityPubKey = ackPacket.identityPubKey,
            peerFingerprint = DeviceIdentityManager.fingerprintOf(ackPacket.identityPubKey),
            txKey = txKey,
            rxKey = rxKey,
            establishedAt = clock()
        )
    }

    fun encryptEnvelope(
        session: SecureSession,
        plaintext: ByteArray,
        messageId: String = MessageEnvelope.newMessageId(),
        createdAt: Long = clock(),
        ttl: Int = MessageEnvelope.DEFAULT_TTL
    ): MessageEnvelope {
        require(ttl in 0..MessageEnvelope.MAX_TTL) { "Invalid TTL: $ttl" }
        val nonce = ByteArray(MessageEnvelope.NONCE_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val draft = MessageEnvelope.create(
            messageId = messageId,
            senderDeviceId = identityManager.deviceId,
            destinationDeviceId = session.peerDeviceId,
            createdAt = createdAt,
            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
            ttl = ttl,
            hopCount = 0,
            nonce = nonce,
            ciphertext = ByteArray(0),
            authenticationTag = ByteArray(MessageEnvelope.AUTH_TAG_SIZE_BYTES)
        )
        val aad = draft.buildAdditionalAuthenticatedData()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, session.txKey, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val combined = cipher.doFinal(plaintext)

        val splitIndex = combined.size - MessageEnvelope.AUTH_TAG_SIZE_BYTES
        val ciphertext = combined.copyOfRange(0, splitIndex)
        val tag = combined.copyOfRange(splitIndex, combined.size)
        return draft.copy(
            encryptedPayload = draft.encryptedPayload.copy(
                ciphertext = ciphertext,
                authenticationTag = tag
            )
        )
    }

    suspend fun decryptAndVerifyEnvelope(
        session: SecureSession,
        envelope: MessageEnvelope,
        replayStore: ReplayStore
    ): EnvelopeVerificationResult {
        if (envelope.encryptionMode != PayloadEncryptionMode.TRANSPORT_SESSION) {
            return EnvelopeVerificationResult.InvalidMessage("Expected transport-session payload mode")
        }
        if (envelope.protocolVersion != MessageEnvelope.CURRENT_PROTOCOL_VERSION) {
            return EnvelopeVerificationResult.InvalidMessage("Unsupported protocol version")
        }
        if (envelope.destinationDeviceId != identityManager.deviceId) {
            return EnvelopeVerificationResult.InvalidMessage("Destination device mismatch")
        }
        if (envelope.senderDeviceId != session.peerDeviceId) {
            return EnvelopeVerificationResult.InvalidMessage("Sender device mismatch")
        }
        if (envelope.ttl < 0 || envelope.ttl > MessageEnvelope.MAX_TTL ||
            envelope.hopCount < 0 || envelope.hopCount > MessageEnvelope.MAX_HOP_COUNT
        ) {
            return EnvelopeVerificationResult.InvalidMessage("Invalid routing TTL or hopCount")
        }
        if (envelope.nonce.size != MessageEnvelope.NONCE_SIZE_BYTES ||
            envelope.authenticationTag.size != MessageEnvelope.AUTH_TAG_SIZE_BYTES
        ) {
            return EnvelopeVerificationResult.InvalidMessage("Invalid nonce or authentication tag size")
        }

        val now = clock()
        if (envelope.createdAt < now - MAX_MESSAGE_AGE_MS) {
            return EnvelopeVerificationResult.Expired(envelope.messageId)
        }
        if (envelope.createdAt > now + MAX_FUTURE_CLOCK_SKEW_MS) {
            return EnvelopeVerificationResult.InvalidMessage("Timestamp skew out of bounds")
        }

        val nonceHex = envelope.nonce.joinToString("") { "%02x".format(it) }
        synchronized(session.seenSessionNonces) {
            if (session.seenSessionNonces.contains(nonceHex)) {
                return EnvelopeVerificationResult.ReplayDetected(envelope.messageId)
            }
        }
        if (replayStore.isMessageSeen(envelope.messageId)) {
            return EnvelopeVerificationResult.ReplayDetected(envelope.messageId)
        }

        val plaintext = try {
            val combined = ByteArray(envelope.ciphertext.size + envelope.authenticationTag.size)
            System.arraycopy(envelope.ciphertext, 0, combined, 0, envelope.ciphertext.size)
            System.arraycopy(envelope.authenticationTag, 0, combined, envelope.ciphertext.size, envelope.authenticationTag.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, session.rxKey, GCMParameterSpec(128, envelope.nonce))
            cipher.updateAAD(envelope.buildAdditionalAuthenticatedData())
            cipher.doFinal(combined)
        } catch (e: CancellationException) {
            throw e
        } catch (_: GeneralSecurityException) {
            return EnvelopeVerificationResult.InvalidMessage("Integrity or decryption verification failed")
        } catch (_: IllegalArgumentException) {
            return EnvelopeVerificationResult.InvalidMessage("Integrity or decryption verification failed")
        }

        // Record replay markers only AFTER GCM authentication succeeds
        synchronized(session.seenSessionNonces) {
            session.seenSessionNonces.add(nonceHex)
        }
        replayStore.recordMessageSeen(
            messageId = envelope.messageId,
            senderDeviceId = envelope.senderDeviceId,
            nonceHex = nonceHex,
            seenAt = now,
            expiresAt = now + REPLAY_RETENTION_MS
        )

        return EnvelopeVerificationResult.Valid(plaintext, envelope)
    }

    /**
     * Encrypts [plaintext] End-to-End for [destinationDeviceId] using:
     * - Ephemeral P-256 ECDH ([ephPriv] x [destinationIdentityPubKey]) for per-message key isolation
     * - Identity P-256 ECDH ([localIdentityPriv] x [destinationIdentityPubKey]) for sender authentication
     * - HKDF-SHA256 with length-prefixed binding metadata
     * - AES-256-GCM with full immutable envelope AAD
     *
     * Relay nodes forwarding this envelope cannot derive the E2E key or read [plaintext].
     */
    fun encryptEndToEndEnvelope(
        destinationDeviceId: String,
        destinationIdentityPubKey: ByteArray,
        plaintext: ByteArray,
        messageId: String = MessageEnvelope.newMessageId(),
        createdAt: Long = clock(),
        ttl: Int = MessageEnvelope.DEFAULT_TTL
    ): MessageEnvelope {
        require(destinationDeviceId.isNotBlank()) { "Destination deviceId must not be blank" }
        require(destinationDeviceId != identityManager.deviceId) { "Cannot E2E-encrypt to self" }
        require(ttl in 0..MessageEnvelope.MAX_TTL) { "Invalid TTL: $ttl" }
        val destinationEcPub = requireNotNull(decodeEcPublicKey(destinationIdentityPubKey)) {
            "Invalid destination EC public key"
        }

        val ephPair = generateEphemeralEcPair()
        val ephPubBytes = ephPair.public.encoded
        val nonce = ByteArray(MessageEnvelope.NONCE_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val draft = MessageEnvelope.create(
            messageId = messageId,
            senderDeviceId = identityManager.deviceId,
            destinationDeviceId = destinationDeviceId,
            createdAt = createdAt,
            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
            ttl = ttl,
            hopCount = 0,
            encryptionMode = PayloadEncryptionMode.END_TO_END,
            senderEphemeralPubKey = ephPubBytes,
            nonce = nonce,
            ciphertext = ByteArray(0),
            authenticationTag = ByteArray(MessageEnvelope.AUTH_TAG_SIZE_BYTES)
        )

        var dhEphemeral: ByteArray? = null
        var dhStatic: ByteArray? = null
        var e2eKeyBytes: ByteArray? = null
        try {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(ephPair.private)
            ka.doPhase(destinationEcPub, true)
            dhEphemeral = ka.generateSecret()

            dhStatic = requireNotNull(identityManager.performIdentityEcdh(destinationIdentityPubKey)) {
                "Identity ECDH failed for destination key"
            }

            e2eKeyBytes = deriveEndToEndMessageKey(
                dhEphemeral = dhEphemeral,
                dhStatic = dhStatic,
                nonce = nonce,
                envelope = draft
            )
            val e2eKey = SecretKeySpec(e2eKeyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, e2eKey, GCMParameterSpec(128, nonce))
            cipher.updateAAD(draft.buildAdditionalAuthenticatedData())
            val combined = cipher.doFinal(plaintext)

            val splitIndex = combined.size - MessageEnvelope.AUTH_TAG_SIZE_BYTES
            val ciphertext = combined.copyOfRange(0, splitIndex)
            val tag = combined.copyOfRange(splitIndex, combined.size)
            return draft.copy(
                encryptedPayload = draft.encryptedPayload.copy(
                    ciphertext = ciphertext,
                    authenticationTag = tag
                )
            )
        } finally {
            dhEphemeral?.let { java.util.Arrays.fill(it, 0.toByte()) }
            dhStatic?.let { java.util.Arrays.fill(it, 0.toByte()) }
            e2eKeyBytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
            runCatching { ephPair.private.destroy() }
        }
    }

    /**
     * Verifies and decrypts a [PayloadEncryptionMode.END_TO_END] envelope at the final destination,
     * binding against [trustedSenderIdentityPubKey] (pinned/known sender identity) and [replayStore].
     */
    suspend fun decryptAndVerifyE2eEnvelope(
        envelope: MessageEnvelope,
        trustedSenderIdentityPubKey: ByteArray?,
        replayStore: ReplayStore
    ): EnvelopeVerificationResult {
        if (envelope.encryptionMode != PayloadEncryptionMode.END_TO_END) {
            return EnvelopeVerificationResult.InvalidMessage("Expected end-to-end payload mode")
        }
        if (envelope.protocolVersion != MessageEnvelope.CURRENT_PROTOCOL_VERSION) {
            return EnvelopeVerificationResult.InvalidMessage("Unsupported protocol version")
        }
        if (envelope.destinationDeviceId != identityManager.deviceId) {
            return EnvelopeVerificationResult.InvalidMessage("Destination device mismatch")
        }
        if (envelope.senderDeviceId.isBlank() || envelope.senderDeviceId == identityManager.deviceId) {
            return EnvelopeVerificationResult.InvalidMessage("Invalid sender deviceId")
        }
        if (trustedSenderIdentityPubKey == null ||
            trustedSenderIdentityPubKey.size !in MessageEnvelope.MIN_EPHEMERAL_PUB_KEY_BYTES..MessageEnvelope.MAX_EPHEMERAL_PUB_KEY_BYTES
        ) {
            return EnvelopeVerificationResult.InvalidMessage("Missing or untrusted sender identity public key")
        }
        if (envelope.ttl < 0 || envelope.ttl > MessageEnvelope.MAX_TTL ||
            envelope.hopCount < 0 || envelope.hopCount > MessageEnvelope.MAX_HOP_COUNT
        ) {
            return EnvelopeVerificationResult.InvalidMessage("Invalid routing TTL or hopCount")
        }
        if (envelope.nonce.size != MessageEnvelope.NONCE_SIZE_BYTES ||
            envelope.authenticationTag.size != MessageEnvelope.AUTH_TAG_SIZE_BYTES ||
            envelope.senderEphemeralPubKey.size !in
            MessageEnvelope.MIN_EPHEMERAL_PUB_KEY_BYTES..MessageEnvelope.MAX_EPHEMERAL_PUB_KEY_BYTES
        ) {
            return EnvelopeVerificationResult.InvalidMessage("Invalid E2E cryptographic parameters")
        }

        val now = clock()
        if (envelope.createdAt < now - MAX_MESSAGE_AGE_MS) {
            return EnvelopeVerificationResult.Expired(envelope.messageId)
        }
        if (envelope.createdAt > now + MAX_FUTURE_CLOCK_SKEW_MS) {
            return EnvelopeVerificationResult.InvalidMessage("Timestamp skew out of bounds")
        }

        val nonceHex = envelope.nonce.joinToString("") { "%02x".format(it) }
        val scopedNonceKey = "${envelope.senderDeviceId}:$nonceHex"
        if (checkAndPruneE2eNonce(scopedNonceKey, now)) {
            return EnvelopeVerificationResult.ReplayDetected(envelope.messageId)
        }
        if (replayStore.isMessageSeen(envelope.messageId)) {
            return EnvelopeVerificationResult.ReplayDetected(envelope.messageId)
        }

        var dhEphemeral: ByteArray? = null
        var dhStatic: ByteArray? = null
        var e2eKeyBytes: ByteArray? = null
        val plaintext = try {
            dhEphemeral = identityManager.performIdentityEcdh(envelope.senderEphemeralPubKey)
                ?: return EnvelopeVerificationResult.InvalidMessage("Invalid E2E ephemeral sender key")
            dhStatic = identityManager.performIdentityEcdh(trustedSenderIdentityPubKey)
                ?: return EnvelopeVerificationResult.InvalidMessage("Invalid trusted sender identity key")

            e2eKeyBytes = deriveEndToEndMessageKey(
                dhEphemeral = dhEphemeral,
                dhStatic = dhStatic,
                nonce = envelope.nonce,
                envelope = envelope
            )
            val e2eKey = SecretKeySpec(e2eKeyBytes, "AES")

            val combined = ByteArray(envelope.ciphertext.size + envelope.authenticationTag.size)
            System.arraycopy(envelope.ciphertext, 0, combined, 0, envelope.ciphertext.size)
            System.arraycopy(
                envelope.authenticationTag,
                0,
                combined,
                envelope.ciphertext.size,
                envelope.authenticationTag.size
            )

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, e2eKey, GCMParameterSpec(128, envelope.nonce))
            cipher.updateAAD(envelope.buildAdditionalAuthenticatedData())
            cipher.doFinal(combined)
        } catch (e: CancellationException) {
            throw e
        } catch (_: GeneralSecurityException) {
            return EnvelopeVerificationResult.InvalidMessage("End-to-end integrity or decryption verification failed")
        } catch (_: IllegalArgumentException) {
            return EnvelopeVerificationResult.InvalidMessage("End-to-end integrity or decryption verification failed")
        } finally {
            dhEphemeral?.let { java.util.Arrays.fill(it, 0.toByte()) }
            dhStatic?.let { java.util.Arrays.fill(it, 0.toByte()) }
            e2eKeyBytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }

        recordE2eNonce(scopedNonceKey, now)
        replayStore.recordMessageSeen(
            messageId = envelope.messageId,
            senderDeviceId = envelope.senderDeviceId,
            nonceHex = nonceHex,
            seenAt = now,
            expiresAt = now + REPLAY_RETENTION_MS
        )

        return EnvelopeVerificationResult.Valid(plaintext, envelope)
    }

    private fun checkAndPruneE2eNonce(scopedNonceKey: String, now: Long): Boolean {
        synchronized(seenE2eNonces) {
            pruneExpiredE2eNoncesLocked(now)
            val expiresAt = seenE2eNonces[scopedNonceKey] ?: return false
            return expiresAt > now
        }
    }

    private fun recordE2eNonce(scopedNonceKey: String, now: Long) {
        synchronized(seenE2eNonces) {
            pruneExpiredE2eNoncesLocked(now)
            while (seenE2eNonces.size >= maxE2eNonceCacheSize) {
                val eldest = seenE2eNonces.entries.iterator()
                if (!eldest.hasNext()) break
                eldest.next()
                eldest.remove()
            }
            seenE2eNonces[scopedNonceKey] = now + REPLAY_RETENTION_MS
        }
    }

    private fun pruneExpiredE2eNoncesLocked(now: Long) {
        val iterator = seenE2eNonces.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) {
                iterator.remove()
            }
        }
    }

    internal fun e2eNonceCacheSize(): Int = synchronized(seenE2eNonces) { seenE2eNonces.size }

    private fun generateEphemeralEcPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    private fun decodeEcPublicKey(encoded: ByteArray): PublicKey? {
        return try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun deriveDirectionalKeys(
        localEphemeralPrivate: java.security.PrivateKey,
        peerEphemeralPublic: PublicKey,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        initiatorDeviceId: String,
        responderDeviceId: String,
        initiatorEphPub: ByteArray,
        responderEphPub: ByteArray,
        isInitiator: Boolean
    ): Pair<SecretKey, SecretKey>? {
        var sharedSecret: ByteArray? = null
        var prk: ByteArray? = null
        var initiatorToResponder: ByteArray? = null
        var responderToInitiator: ByteArray? = null
        return try {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(localEphemeralPrivate)
            ka.doPhase(peerEphemeralPublic, true)
            sharedSecret = ka.generateSecret()

            val salt = clientNonce + serverNonce
            prk = hmacSha256(salt, sharedSecret)
            val transcriptHash = java.security.MessageDigest.getInstance("SHA-256").apply {
                update(initiatorDeviceId.toByteArray(StandardCharsets.UTF_8))
                update(responderDeviceId.toByteArray(StandardCharsets.UTF_8))
                update(initiatorEphPub)
                update(responderEphPub)
            }.digest()

            initiatorToResponder = hkdfExpandSha256(
                prk,
                "OMNIMESH_I2R_V1".toByteArray(StandardCharsets.UTF_8) + transcriptHash,
                32
            )
            responderToInitiator = hkdfExpandSha256(
                prk,
                "OMNIMESH_R2I_V1".toByteArray(StandardCharsets.UTF_8) + transcriptHash,
                32
            )

            val i2rKey = SecretKeySpec(initiatorToResponder, "AES")
            val r2iKey = SecretKeySpec(responderToInitiator, "AES")
            if (isInitiator) i2rKey to r2iKey else r2iKey to i2rKey
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            sharedSecret?.let { java.util.Arrays.fill(it, 0.toByte()) }
            prk?.let { java.util.Arrays.fill(it, 0.toByte()) }
            initiatorToResponder?.let { java.util.Arrays.fill(it, 0.toByte()) }
            responderToInitiator?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun deriveEndToEndMessageKey(
        dhEphemeral: ByteArray,
        dhStatic: ByteArray,
        nonce: ByteArray,
        envelope: MessageEnvelope
    ): ByteArray {
        val ikm = ByteArray(dhEphemeral.size + dhStatic.size)
        var prk: ByteArray? = null
        return try {
            System.arraycopy(dhEphemeral, 0, ikm, 0, dhEphemeral.size)
            System.arraycopy(dhStatic, 0, ikm, dhEphemeral.size, dhStatic.size)
            prk = hmacSha256(nonce, ikm)
            val info = buildE2eKdfInfo(envelope)
            hkdfExpandSha256(prk, info, 32)
        } finally {
            java.util.Arrays.fill(ikm, 0.toByte())
            prk?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun buildE2eKdfInfo(envelope: MessageEnvelope): ByteArray {
        val label = "OMNIMESH_E2E_MSG_V1".toByteArray(StandardCharsets.UTF_8)
        val msgIdBytes = envelope.messageId.toByteArray(StandardCharsets.UTF_8)
        val senderBytes = envelope.senderDeviceId.toByteArray(StandardCharsets.UTF_8)
        val destBytes = envelope.destinationDeviceId.toByteArray(StandardCharsets.UTF_8)
        val ephPubBytes = envelope.senderEphemeralPubKey
        val total = (4 + label.size) + 4 + 8 +
            (4 + msgIdBytes.size) +
            (4 + senderBytes.size) +
            (4 + destBytes.size) +
            (4 + ephPubBytes.size)
        val buf = ByteBuffer.allocate(total)
        putIntPrefixed(buf, label)
        buf.putInt(envelope.protocolVersion)
        buf.putLong(envelope.createdAt)
        putIntPrefixed(buf, msgIdBytes)
        putIntPrefixed(buf, senderBytes)
        putIntPrefixed(buf, destBytes)
        putIntPrefixed(buf, ephPubBytes)
        return buf.array()
    }

    private fun putIntPrefixed(buf: ByteBuffer, data: ByteArray) {
        buf.putInt(data.size)
        buf.put(data)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpandSha256(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val okm = ByteArray(length)
        var offset = 0
        var currentT = ByteArray(0)
        var counter = 1
        return try {
            while (offset < length) {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(currentT)
                mac.update(info)
                mac.update(counter.toByte())
                val nextT = mac.doFinal()
                java.util.Arrays.fill(currentT, 0.toByte())
                currentT = nextT
                val copyLen = minOf(currentT.size, length - offset)
                System.arraycopy(currentT, 0, okm, offset, copyLen)
                offset += copyLen
                counter++
            }
            okm
        } catch (t: Throwable) {
            java.util.Arrays.fill(okm, 0.toByte())
            throw t
        } finally {
            java.util.Arrays.fill(currentT, 0.toByte())
        }
    }

    private fun buildInitTranscript(
        senderDeviceId: String,
        identityPubKey: ByteArray,
        ephemeralPubKey: ByteArray,
        clientNonce: ByteArray
    ): ByteArray {
        val label = "OMNIMESH_HS_INIT_V1".toByteArray(StandardCharsets.UTF_8)
        val idBytes = senderDeviceId.toByteArray(StandardCharsets.UTF_8)
        return label + idBytes + identityPubKey + ephemeralPubKey + clientNonce
    }

    private fun buildAckTranscript(
        responderDeviceId: String,
        initiatorDeviceId: String,
        responderIdentityPubKey: ByteArray,
        responderEphemeralPubKey: ByteArray,
        initiatorEphemeralPubKey: ByteArray,
        serverNonce: ByteArray,
        clientNonce: ByteArray
    ): ByteArray {
        val label = "OMNIMESH_HS_ACK_V1".toByteArray(StandardCharsets.UTF_8)
        return label +
            responderDeviceId.toByteArray(StandardCharsets.UTF_8) +
            initiatorDeviceId.toByteArray(StandardCharsets.UTF_8) +
            responderIdentityPubKey +
            responderEphemeralPubKey +
            initiatorEphemeralPubKey +
            serverNonce +
            clientNonce
    }

    companion object {
        const val MAX_MESSAGE_AGE_MS = 5 * 60 * 1000L
        const val MAX_FUTURE_CLOCK_SKEW_MS = 60 * 1000L
        const val REPLAY_RETENTION_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_MAX_E2E_NONCE_CACHE_SIZE = 4_096
    }
}
