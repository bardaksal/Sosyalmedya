package com.example.p2p.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageDeliveryState {
    CREATED,
    CONNECTING,
    SENT,
    DELIVERED,
    FAILED,
    EXPIRED
}

enum class MessageDirection {
    OUTGOING,
    INCOMING
}

@Entity(tableName = "p2p_peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val identityPublicKeyB64: String,
    val publicKeyFingerprint: String,
    val bleAddress: String,
    val lastSeenAt: Long,
    val lastRssi: Int
)

@Entity(
    tableName = "p2p_messages",
    indices = [
        Index(value = ["peerDeviceId", "timestamp"]),
        Index(value = ["state"])
    ]
)
data class LocalMessageEntity(
    @PrimaryKey val messageId: String,
    val peerDeviceId: String,
    val senderDeviceId: String,
    val receiverDeviceId: String,
    val direction: String, // MessageDirection.name
    val plaintext: String,
    val state: String, // MessageDeliveryState.name
    val errorReason: String? = null,
    val timestamp: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "p2p_seen_messages",
    indices = [Index(value = ["expiresAt"])]
)
data class SeenMessageEntity(
    @PrimaryKey val messageId: String,
    val senderDeviceId: String,
    val nonceHex: String,
    val seenAt: Long,
    val expiresAt: Long
)
