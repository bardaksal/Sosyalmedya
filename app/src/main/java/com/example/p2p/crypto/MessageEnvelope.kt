package com.example.p2p.crypto

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Hop-by-hop and end-to-end routing metadata separated cleanly from the encrypted payload.
 * Relay nodes may inspect routing metadata and update [ttl] / [hopCount] without decrypting payload.
 */
data class RoutingMetadata(
    val messageId: String,
    val protocolVersion: Int,
    val senderDeviceId: String,
    val destinationDeviceId: String,
    val createdAt: Long,
    val ttl: Int,
    val hopCount: Int
) {
    fun advanceForForward(): RoutingMetadata {
        require(ttl > 0) { "Cannot forward packet with non-positive TTL: $ttl" }
        require(hopCount >= 0) { "Cannot forward packet with negative hopCount: $hopCount" }
        return copy(
            ttl = ttl - 1,
            hopCount = hopCount + 1
        )
    }
}

/**
 * Distinguishes hop-by-hop link encryption ([TRANSPORT_SESSION]) from multi-hop
 * end-to-end encryption ([END_TO_END]) readable only by [RoutingMetadata.destinationDeviceId].
 */
enum class PayloadEncryptionMode(val code: Byte) {
    TRANSPORT_SESSION(1),
    END_TO_END(2);

    companion object {
        fun fromCode(code: Byte): PayloadEncryptionMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * AES-256-GCM encrypted payload bytes and authentication primitives.
 * For [PayloadEncryptionMode.END_TO_END], [senderEphemeralPubKey] carries the sender's
 * per-message X.509 P-256 ephemeral public key needed by the destination to derive the E2E key.
 */
data class EncryptedPayload(
    val encryptionMode: PayloadEncryptionMode = PayloadEncryptionMode.TRANSPORT_SESSION,
    val senderEphemeralPubKey: ByteArray = ByteArray(0),
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val authenticationTag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedPayload) return false
        return encryptionMode == other.encryptionMode &&
            senderEphemeralPubKey.contentEquals(other.senderEphemeralPubKey) &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            authenticationTag.contentEquals(other.authenticationTag)
    }

    override fun hashCode(): Int {
        var result = encryptionMode.hashCode()
        result = 31 * result + senderEphemeralPubKey.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + authenticationTag.contentHashCode()
        return result
    }
}

/**
 * Wire representation of an authenticated, encrypted OmniMesh message with explicit
 * separation between [routingMetadata] and [encryptedPayload].
 *
 * Uses classical AES-256-GCM with an explicit 16-byte authentication tag and 12-byte nonce.
 */
data class MessageEnvelope(
    val routingMetadata: RoutingMetadata,
    val encryptedPayload: EncryptedPayload
) {
    val messageId: String get() = routingMetadata.messageId
    val protocolVersion: Int get() = routingMetadata.protocolVersion
    val senderDeviceId: String get() = routingMetadata.senderDeviceId
    val destinationDeviceId: String get() = routingMetadata.destinationDeviceId
    val createdAt: Long get() = routingMetadata.createdAt
    val ttl: Int get() = routingMetadata.ttl
    val hopCount: Int get() = routingMetadata.hopCount

    val encryptionMode: PayloadEncryptionMode get() = encryptedPayload.encryptionMode
    val senderEphemeralPubKey: ByteArray get() = encryptedPayload.senderEphemeralPubKey
    val nonce: ByteArray get() = encryptedPayload.nonce
    val ciphertext: ByteArray get() = encryptedPayload.ciphertext
    val authenticationTag: ByteArray get() = encryptedPayload.authenticationTag

    fun advanceForForward(): MessageEnvelope =
        copy(routingMetadata = routingMetadata.advanceForForward())

    /**
     * Binds immutable end-to-end envelope fields into AES-GCM Additional Authenticated Data.
     * Mutable hop-by-hop fields ([ttl], [hopCount]) are intentionally excluded so relay nodes
     * can update them without invalidating end-to-end AEAD authentication at [destinationDeviceId].
     * (Accordingly, [ttl] and [hopCount] are not cryptographically authenticated across relays in Phase 2.2.)
     */
    fun buildAdditionalAuthenticatedData(): ByteArray {
        val msgIdBytes = messageId.toByteArray(StandardCharsets.UTF_8)
        val senderBytes = senderDeviceId.toByteArray(StandardCharsets.UTF_8)
        val destinationBytes = destinationDeviceId.toByteArray(StandardCharsets.UTF_8)
        val ephKeyBytes = encryptedPayload.senderEphemeralPubKey
        val buffer = ByteBuffer.allocate(
            4 + 8 + 1 +
                4 + ephKeyBytes.size +
                4 + msgIdBytes.size +
                4 + senderBytes.size +
                4 + destinationBytes.size
        )
        buffer.putInt(protocolVersion)
        buffer.putLong(createdAt)
        buffer.put(encryptedPayload.encryptionMode.code)
        buffer.putInt(ephKeyBytes.size)
        buffer.put(ephKeyBytes)
        buffer.putInt(msgIdBytes.size)
        buffer.put(msgIdBytes)
        buffer.putInt(senderBytes.size)
        buffer.put(senderBytes)
        buffer.putInt(destinationBytes.size)
        buffer.put(destinationBytes)
        return buffer.array()
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 2
        const val DEFAULT_TTL = 5
        const val MAX_TTL = 32
        const val MAX_HOP_COUNT = 32
        const val NONCE_SIZE_BYTES = 12
        const val AUTH_TAG_SIZE_BYTES = 16
        const val MIN_EPHEMERAL_PUB_KEY_BYTES = 32
        const val MAX_EPHEMERAL_PUB_KEY_BYTES = 512
        private const val MAX_STRING_BYTES = 256
        private const val MAX_CIPHERTEXT_BYTES = 65_536

        fun newMessageId(): String = UUID.randomUUID().toString()

        fun create(
            messageId: String,
            senderDeviceId: String,
            destinationDeviceId: String,
            createdAt: Long,
            protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
            ttl: Int = DEFAULT_TTL,
            hopCount: Int = 0,
            encryptionMode: PayloadEncryptionMode = PayloadEncryptionMode.TRANSPORT_SESSION,
            senderEphemeralPubKey: ByteArray = ByteArray(0),
            nonce: ByteArray,
            ciphertext: ByteArray,
            authenticationTag: ByteArray
        ): MessageEnvelope {
            return MessageEnvelope(
                routingMetadata = RoutingMetadata(
                    messageId = messageId,
                    protocolVersion = protocolVersion,
                    senderDeviceId = senderDeviceId,
                    destinationDeviceId = destinationDeviceId,
                    createdAt = createdAt,
                    ttl = ttl,
                    hopCount = hopCount
                ),
                encryptedPayload = EncryptedPayload(
                    encryptionMode = encryptionMode,
                    senderEphemeralPubKey = senderEphemeralPubKey,
                    nonce = nonce,
                    ciphertext = ciphertext,
                    authenticationTag = authenticationTag
                )
            )
        }

        fun serialize(envelope: MessageEnvelope): ByteArray {
            val msgIdBytes = envelope.messageId.toByteArray(StandardCharsets.UTF_8)
            val senderBytes = envelope.senderDeviceId.toByteArray(StandardCharsets.UTF_8)
            val destBytes = envelope.destinationDeviceId.toByteArray(StandardCharsets.UTF_8)
            val ephKeyBytes = envelope.senderEphemeralPubKey
            require(msgIdBytes.size in 1..MAX_STRING_BYTES) { "Invalid messageId length" }
            require(senderBytes.size in 1..MAX_STRING_BYTES) { "Invalid senderDeviceId length" }
            require(destBytes.size in 1..MAX_STRING_BYTES) { "Invalid destinationDeviceId length" }
            when (envelope.encryptionMode) {
                PayloadEncryptionMode.TRANSPORT_SESSION ->
                    require(ephKeyBytes.isEmpty()) { "TRANSPORT_SESSION must not carry E2E ephemeral key" }
                PayloadEncryptionMode.END_TO_END ->
                    require(ephKeyBytes.size in MIN_EPHEMERAL_PUB_KEY_BYTES..MAX_EPHEMERAL_PUB_KEY_BYTES) {
                        "Invalid E2E ephemeral public key length"
                    }
            }
            require(envelope.nonce.size == NONCE_SIZE_BYTES) { "Nonce must be 12 bytes" }
            require(envelope.authenticationTag.size == AUTH_TAG_SIZE_BYTES) { "Auth tag must be 16 bytes" }
            require(envelope.ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "Ciphertext exceeds max size" }

            val totalSize = 4 + // protocolVersion
                8 + // createdAt
                4 + // ttl
                4 + // hopCount
                1 + // encryptionMode
                2 + ephKeyBytes.size +
                2 + msgIdBytes.size +
                2 + senderBytes.size +
                2 + destBytes.size +
                2 + envelope.nonce.size +
                2 + envelope.authenticationTag.size +
                4 + envelope.ciphertext.size

            val buffer = ByteBuffer.allocate(totalSize)
            buffer.putInt(envelope.protocolVersion)
            buffer.putLong(envelope.createdAt)
            buffer.putInt(envelope.ttl)
            buffer.putInt(envelope.hopCount)
            buffer.put(envelope.encryptionMode.code)
            putShortPrefixed(buffer, ephKeyBytes)
            putShortPrefixed(buffer, msgIdBytes)
            putShortPrefixed(buffer, senderBytes)
            putShortPrefixed(buffer, destBytes)
            putShortPrefixed(buffer, envelope.nonce)
            putShortPrefixed(buffer, envelope.authenticationTag)
            buffer.putInt(envelope.ciphertext.size)
            buffer.put(envelope.ciphertext)
            return buffer.array()
        }

        fun deserialize(bytes: ByteArray): MessageEnvelope? {
            return try {
                if (bytes.size < 37) return null
                val buffer = ByteBuffer.wrap(bytes)
                val version = buffer.int
                val createdAt = buffer.long
                val ttl = buffer.int
                val hopCount = buffer.int
                if (buffer.remaining() < 1) return null
                val encryptionMode = PayloadEncryptionMode.fromCode(buffer.get()) ?: return null
                val ephKeyBytes = readShortPrefixed(buffer, 0..MAX_EPHEMERAL_PUB_KEY_BYTES) ?: return null
                when (encryptionMode) {
                    PayloadEncryptionMode.TRANSPORT_SESSION -> if (ephKeyBytes.isNotEmpty()) return null
                    PayloadEncryptionMode.END_TO_END ->
                        if (ephKeyBytes.size !in MIN_EPHEMERAL_PUB_KEY_BYTES..MAX_EPHEMERAL_PUB_KEY_BYTES) return null
                }
                val msgIdBytes = readShortPrefixed(buffer, 1..MAX_STRING_BYTES) ?: return null
                val senderBytes = readShortPrefixed(buffer, 1..MAX_STRING_BYTES) ?: return null
                val destBytes = readShortPrefixed(buffer, 1..MAX_STRING_BYTES) ?: return null
                val nonce = readShortPrefixed(buffer, NONCE_SIZE_BYTES..NONCE_SIZE_BYTES) ?: return null
                val tag = readShortPrefixed(buffer, AUTH_TAG_SIZE_BYTES..AUTH_TAG_SIZE_BYTES) ?: return null
                if (buffer.remaining() < 4) return null
                val cipherLen = buffer.int
                if (cipherLen < 0 || cipherLen > MAX_CIPHERTEXT_BYTES || buffer.remaining() != cipherLen) {
                    return null
                }
                val ciphertext = ByteArray(cipherLen)
                buffer.get(ciphertext)
                val messageId = decodeStrictUtf8(msgIdBytes) ?: return null
                val senderDeviceId = decodeStrictUtf8(senderBytes) ?: return null
                val destinationDeviceId = decodeStrictUtf8(destBytes) ?: return null
                if (messageId.isBlank() || senderDeviceId.isBlank() || destinationDeviceId.isBlank()) {
                    return null
                }
                create(
                    messageId = messageId,
                    senderDeviceId = senderDeviceId,
                    destinationDeviceId = destinationDeviceId,
                    createdAt = createdAt,
                    protocolVersion = version,
                    ttl = ttl,
                    hopCount = hopCount,
                    encryptionMode = encryptionMode,
                    senderEphemeralPubKey = ephKeyBytes,
                    nonce = nonce,
                    ciphertext = ciphertext,
                    authenticationTag = tag
                )
            } catch (_: BufferUnderflowException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: CharacterCodingException) {
                null
            }
        }

        private fun decodeStrictUtf8(bytes: ByteArray): String? {
            return try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }

        private fun putShortPrefixed(buffer: ByteBuffer, data: ByteArray) {
            buffer.putShort(data.size.toShort())
            buffer.put(data)
        }

        private fun readShortPrefixed(buffer: ByteBuffer, allowedRange: IntRange): ByteArray? {
            if (buffer.remaining() < 2) return null
            val len = buffer.short.toInt() and 0xFFFF
            if (len !in allowedRange || buffer.remaining() < len) return null
            return ByteArray(len).also { buffer.get(it) }
        }
    }
}
