package com.example.p2p.crypto

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Wire representation of an authenticated, encrypted OmniMesh direct message.
 * Uses classical AES-256-GCM with an explicit 16-byte authentication tag and 12-byte nonce.
 */
data class MessageEnvelope(
    val messageId: String,
    val senderDeviceId: String,
    val receiverDeviceId: String,
    val timestamp: Long,
    val protocolVersion: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val authenticationTag: ByteArray
) {
    fun buildAdditionalAuthenticatedData(): ByteArray {
        val msgIdBytes = messageId.toByteArray(StandardCharsets.UTF_8)
        val senderBytes = senderDeviceId.toByteArray(StandardCharsets.UTF_8)
        val receiverBytes = receiverDeviceId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(
            4 + 8 +
                4 + msgIdBytes.size +
                4 + senderBytes.size +
                4 + receiverBytes.size
        )
        buffer.putInt(protocolVersion)
        buffer.putLong(timestamp)
        buffer.putInt(msgIdBytes.size)
        buffer.put(msgIdBytes)
        buffer.putInt(senderBytes.size)
        buffer.put(senderBytes)
        buffer.putInt(receiverBytes.size)
        buffer.put(receiverBytes)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEnvelope) return false
        return messageId == other.messageId &&
            senderDeviceId == other.senderDeviceId &&
            receiverDeviceId == other.receiverDeviceId &&
            timestamp == other.timestamp &&
            protocolVersion == other.protocolVersion &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            authenticationTag.contentEquals(other.authenticationTag)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + senderDeviceId.hashCode()
        result = 31 * result + receiverDeviceId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + authenticationTag.contentHashCode()
        return result
    }

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1
        const val NONCE_SIZE_BYTES = 12
        const val AUTH_TAG_SIZE_BYTES = 16
        private const val MAX_STRING_BYTES = 256
        private const val MAX_CIPHERTEXT_BYTES = 65_536

        fun newMessageId(): String = UUID.randomUUID().toString()

        fun serialize(envelope: MessageEnvelope): ByteArray {
            val msgIdBytes = envelope.messageId.toByteArray(StandardCharsets.UTF_8)
            val senderBytes = envelope.senderDeviceId.toByteArray(StandardCharsets.UTF_8)
            val receiverBytes = envelope.receiverDeviceId.toByteArray(StandardCharsets.UTF_8)
            require(msgIdBytes.size in 1..MAX_STRING_BYTES) { "Invalid messageId length" }
            require(senderBytes.size in 1..MAX_STRING_BYTES) { "Invalid senderDeviceId length" }
            require(receiverBytes.size in 1..MAX_STRING_BYTES) { "Invalid receiverDeviceId length" }
            require(envelope.nonce.size == NONCE_SIZE_BYTES) { "Nonce must be 12 bytes" }
            require(envelope.authenticationTag.size == AUTH_TAG_SIZE_BYTES) { "Auth tag must be 16 bytes" }
            require(envelope.ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "Ciphertext exceeds max size" }

            val totalSize = 4 + // protocolVersion
                8 + // timestamp
                2 + msgIdBytes.size +
                2 + senderBytes.size +
                2 + receiverBytes.size +
                2 + envelope.nonce.size +
                2 + envelope.authenticationTag.size +
                4 + envelope.ciphertext.size

            val buffer = ByteBuffer.allocate(totalSize)
            buffer.putInt(envelope.protocolVersion)
            buffer.putLong(envelope.timestamp)
            putShortPrefixed(buffer, msgIdBytes)
            putShortPrefixed(buffer, senderBytes)
            putShortPrefixed(buffer, receiverBytes)
            putShortPrefixed(buffer, envelope.nonce)
            putShortPrefixed(buffer, envelope.authenticationTag)
            buffer.putInt(envelope.ciphertext.size)
            buffer.put(envelope.ciphertext)
            return buffer.array()
        }

        fun deserialize(bytes: ByteArray): MessageEnvelope? {
            return try {
                if (bytes.size < 26) return null
                val buffer = ByteBuffer.wrap(bytes)
                val version = buffer.int
                val timestamp = buffer.long
                val msgIdBytes = readShortPrefixed(buffer, 1..MAX_STRING_BYTES) ?: return null
                val senderBytes = readShortPrefixed(buffer, 1..MAX_STRING_BYTES) ?: return null
                val receiverBytes = readShortPrefixed(buffer, 1..MAX_STRING_BYTES) ?: return null
                val nonce = readShortPrefixed(buffer, NONCE_SIZE_BYTES..NONCE_SIZE_BYTES) ?: return null
                val tag = readShortPrefixed(buffer, AUTH_TAG_SIZE_BYTES..AUTH_TAG_SIZE_BYTES) ?: return null
                if (buffer.remaining() < 4) return null
                val cipherLen = buffer.int
                if (cipherLen < 0 || cipherLen > MAX_CIPHERTEXT_BYTES || buffer.remaining() != cipherLen) {
                    return null
                }
                val ciphertext = ByteArray(cipherLen)
                buffer.get(ciphertext)
                MessageEnvelope(
                    messageId = String(msgIdBytes, StandardCharsets.UTF_8),
                    senderDeviceId = String(senderBytes, StandardCharsets.UTF_8),
                    receiverDeviceId = String(receiverBytes, StandardCharsets.UTF_8),
                    timestamp = timestamp,
                    protocolVersion = version,
                    nonce = nonce,
                    ciphertext = ciphertext,
                    authenticationTag = tag
                )
            } catch (_: Exception) {
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
