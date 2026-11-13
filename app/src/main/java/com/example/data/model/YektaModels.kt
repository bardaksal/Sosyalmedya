package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserAccount(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val handle: String = "",
    val bio: String = "",
    val planTier: String = "ucretsiz",
    val publicKeyFingerprint: String = "",
    val dashboardWidgets: List<String> = listOf(
        "uniqueness_rate",
        "similarity_chart",
        "network_nodes",
        "cipher_messages"
    ),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PublicProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val handle: String = "",
    val publicKeyB64: String = "",
    val publicKeyFingerprint: String = "",
    val nodeHexId: String = "",
    val visibility: String = "public",
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SocialPost(
    @DocumentId val id: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorHandle: String = "",
    val format: String = "text", // "photo" | "video" | "text"
    val caption: String = "",
    val mediaDataUri: String = "",
    val tags: List<String> = emptyList(),
    val contentHash: String = "",
    val perceptualHash: String = "",
    val uniquenessScore: Long = 100L,
    val semanticSummary: String = "",
    val visibility: String = "public",
    val likeCount: Long = 0L,
    val repostCount: Long = 0L,
    val replyCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PostReply(
    @DocumentId val id: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorHandle: String = "",
    val text: String = "",
    val visibility: String = "public",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ContentRegistryEntry(
    @DocumentId val id: String = "",
    val ownerId: String = "",
    val postId: String = "",
    val contentHash: String = "",
    val perceptualHash: String = "",
    val format: String = "text",
    val semanticSummary: String = "",
    val textPreview: String = "",
    val visibility: String = "public",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class EncryptedMessage(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val recipientId: String = "",
    val participantIds: List<String> = emptyList(),
    val senderName: String = "",
    val ciphertext: String = "",
    val nonce: String = "",
    val ephemeralPubKey: String = "",
    val onionHopsCount: Long = 3L,
    val transportMode: String = "encrypted_relay", // "ble_p2p" | "wifi_direct" | "encrypted_relay"
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AnalyticsReport(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val totalChecked: Long = 0L,
    val uniqueApproved: Long = 0L,
    val duplicatesBlocked: Long = 0L,
    val avgSimilarityScore: Long = 0L,
    val recommendationsSummary: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class UniquenessVerificationResult(
    val isUnique: Boolean,
    val similarityPercent: Int,
    val uniquenessScore: Int,
    val matchedPostId: String?,
    val reason: String,
    val semanticSummary: String,
    val suggestedTags: List<String>,
    val contentHash: String,
    val perceptualHash: String,
    val blockedByExactHash: Boolean = false
)

data class FeedPersonalizationResult(
    val rankedPostIds: List<String>,
    val topicHighlights: List<String>,
    val insightSummary: String
)

data class NearbyPeerNode(
    val id: String,
    val label: String,
    val transport: String, // "Bluetooth LE" | "Wi-Fi Direct"
    val signalStrengthDbm: Int,
    val nodeHexId: String,
    val xorDistanceRank: Int
)

data class P2pHardwareState(
    val bluetoothSupported: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val wifiP2pSupported: Boolean = false,
    val permissionsGranted: Boolean = false,
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val statusText: String = "Hazır",
    val discoveredPeers: List<NearbyPeerNode> = emptyList()
)
