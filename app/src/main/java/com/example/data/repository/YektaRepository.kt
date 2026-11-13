package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AnalyticsReport
import com.example.data.model.ContentRegistryEntry
import com.example.data.model.EncryptedMessage
import com.example.data.model.PostReply
import com.example.data.model.PublicProfile
import com.example.data.model.SocialPost
import com.example.data.model.UserAccount
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class YektaRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Devam etmek için Google hesabınızla oturum açmalısınız.")

    suspend fun ensureUserInitialized(
        uid: String,
        displayName: String,
        publicKeyB64: String,
        publicKeyFingerprint: String,
        nodeHexId: String
    ): Result<Unit> = runCatching {
        val cleanName = displayName.ifBlank { "Yekta Üyesi" }.take(60)
        val cleanHandle = cleanName.lowercase()
            .replace(Regex("[^a-z0-9_]"), "")
            .ifBlank { "yekta" }
            .take(20) + "_" + uid.take(4)

        val userRef = db.collection("users").document(uid)
        val existingUser = userRef.get().await()
        if (!existingUser.exists()) {
            val payload = mapOf(
                "userId" to uid,
                "displayName" to cleanName,
                "handle" to cleanHandle,
                "bio" to "Özgün içerik üreticisi ve gizlilik odaklı ağ üyesi.",
                "planTier" to "ucretsiz",
                "publicKeyFingerprint" to publicKeyFingerprint,
                "dashboardWidgets" to listOf(
                    "uniqueness_rate",
                    "similarity_chart",
                    "network_nodes",
                    "cipher_messages"
                ),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            userRef.set(payload).await()
        }

        val publicRef = db.collection("public_profiles").document(uid)
        val publicPayload = mapOf(
            "userId" to uid,
            "displayName" to cleanName,
            "handle" to cleanHandle,
            "publicKeyB64" to publicKeyB64,
            "publicKeyFingerprint" to publicKeyFingerprint,
            "nodeHexId" to nodeHexId,
            "visibility" to "public",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        publicRef.set(publicPayload).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.WRITE, "users/$uid")
    }

    fun observeUserAccount(uid: String): Flow<UserAccount?> = flow {
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(
                        UserAccount::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    handleFlowError(error, OperationType.GET, path, uid)
                    throw error
                }
        )
    }

    fun observePublicPosts(currentUserId: String): Flow<List<SocialPost>> = flow {
        val path = "posts"
        emitAll(
            db.collection(path)
                .whereEqualTo("visibility", "public")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        SocialPost::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, currentUserId)
                    throw error
                }
        )
    }

    fun observeContentRegistry(currentUserId: String): Flow<List<ContentRegistryEntry>> = flow {
        val path = "content_registry"
        emitAll(
            db.collection(path)
                .whereEqualTo("visibility", "public")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ContentRegistryEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, currentUserId)
                    throw error
                }
        )
    }

    fun observePublicProfiles(currentUserId: String): Flow<List<PublicProfile>> = flow {
        val path = "public_profiles"
        emitAll(
            db.collection(path)
                .whereEqualTo("visibility", "public")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PublicProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, currentUserId)
                    throw error
                }
        )
    }

    fun observeEncryptedMessages(uid: String): Flow<List<EncryptedMessage>> = flow {
        val path = "encrypted_messages"
        emitAll(
            db.collection(path)
                .whereArrayContains("participantIds", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        EncryptedMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observeAnalyticsReports(uid: String): Flow<List<AnalyticsReport>> = flow {
        val path = "users/$uid/analytics_reports"
        emitAll(
            db.collection("users").document(uid)
                .collection("analytics_reports")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AnalyticsReport::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, uid)
                    throw error
                }
        )
    }

    fun observePostReplies(postId: String, currentUserId: String): Flow<List<PostReply>> = flow {
        val path = "posts/$postId/replies"
        emitAll(
            db.collection("posts").document(postId)
                .collection("replies")
                .whereEqualTo("visibility", "public")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PostReply::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    handleFlowError(error, OperationType.LIST, path, currentUserId)
                    throw error
                }
        )
    }

    suspend fun isExactHashTaken(contentHash: String): Boolean {
        return try {
            db.collection("content_registry").document(contentHash).get().await().exists()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "content_registry/$contentHash")
            false
        }
    }

    suspend fun publishUniquePost(
        authorName: String,
        authorHandle: String,
        format: String,
        caption: String,
        mediaDataUri: String,
        tags: List<String>,
        contentHash: String,
        perceptualHash: String,
        uniquenessScore: Int,
        semanticSummary: String
    ): Result<String> {
        val uid = requireUserId()
        val postId = "post_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val postRef = db.collection("posts").document(postId)
        val registryRef = db.collection("content_registry").document(contentHash)

        return try {
            val existing = registryRef.get().await()
            if (existing.exists()) {
                return Result.failure(
                    IllegalStateException("Bu içerik ağda zaten 1x olarak kayıtlı; mükerrer paylaşım engellendi.")
                )
            }

            val batch = db.batch()
            val postPayload = mutableMapOf<String, Any>(
                "authorId" to uid,
                "authorName" to authorName.take(60),
                "authorHandle" to authorHandle.take(30),
                "format" to format,
                "caption" to caption.take(1000),
                "tags" to tags.take(8),
                "contentHash" to contentHash,
                "perceptualHash" to perceptualHash,
                "uniquenessScore" to uniquenessScore.coerceIn(0, 100),
                "semanticSummary" to semanticSummary.ifBlank { caption.take(120) }.take(300),
                "visibility" to "public",
                "likeCount" to 0L,
                "repostCount" to 0L,
                "replyCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (mediaDataUri.isNotBlank()) {
                postPayload["mediaDataUri"] = mediaDataUri.take(340_000)
            }

            val registryPayload = mapOf(
                "ownerId" to uid,
                "postId" to postId,
                "contentHash" to contentHash,
                "perceptualHash" to perceptualHash,
                "format" to format,
                "semanticSummary" to semanticSummary.ifBlank { caption.take(120) }.take(300),
                "textPreview" to caption.take(200),
                "visibility" to "public",
                "createdAt" to FieldValue.serverTimestamp()
            )

            batch.set(postRef, postPayload)
            batch.set(registryRef, registryPayload)
            batch.commit().await()
            Result.success(postId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, postRef.path)
            Result.failure(e)
        }
    }

    suspend fun incrementLike(post: SocialPost): Result<Unit> =
        incrementPostCounter(post.id, "likeCount", post.likeCount)

    suspend fun incrementRepost(post: SocialPost): Result<Unit> =
        incrementPostCounter(post.id, "repostCount", post.repostCount)

    private suspend fun incrementPostCounter(
        postId: String,
        field: String,
        currentValue: Long
    ): Result<Unit> {
        requireUserId()
        val docRef = db.collection("posts").document(postId)
        return try {
            docRef.update(
                mapOf(
                    field to (currentValue + 1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun addReply(
        post: SocialPost,
        authorName: String,
        authorHandle: String,
        text: String
    ): Result<String> {
        val uid = requireUserId()
        val replyId = "rep_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val replyRef = db.collection("posts").document(post.id)
            .collection("replies").document(replyId)
        val postRef = db.collection("posts").document(post.id)

        return try {
            val batch = db.batch()
            batch.set(
                replyRef,
                mapOf(
                    "authorId" to uid,
                    "authorName" to authorName.take(60),
                    "authorHandle" to authorHandle.take(30),
                    "text" to text.take(500),
                    "visibility" to "public",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                postRef,
                mapOf(
                    "replyCount" to (post.replyCount + 1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(replyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, replyRef.path)
            Result.failure(e)
        }
    }

    suspend fun sendEncryptedMessage(
        recipientId: String,
        senderName: String,
        ciphertext: String,
        nonce: String,
        ephemeralPubKey: String,
        onionHopsCount: Int,
        transportMode: String
    ): Result<String> {
        val uid = requireUserId()
        val msgId = "msg_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val msgRef = db.collection("encrypted_messages").document(msgId)
        val payload = mapOf(
            "senderId" to uid,
            "recipientId" to recipientId,
            "participantIds" to listOf(uid, recipientId).distinct().let {
                if (it.size == 1) listOf(uid, uid) else it
            },
            "senderName" to senderName.take(60),
            "ciphertext" to ciphertext,
            "nonce" to nonce,
            "ephemeralPubKey" to ephemeralPubKey,
            "onionHopsCount" to onionHopsCount.toLong().coerceIn(1L, 8L),
            "transportMode" to transportMode,
            "createdAt" to FieldValue.serverTimestamp()
        )
        return try {
            msgRef.set(payload).await()
            Result.success(msgId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun saveAnalyticsReport(
        title: String,
        totalChecked: Long,
        uniqueApproved: Long,
        duplicatesBlocked: Long,
        avgSimilarityScore: Long,
        recommendationsSummary: String
    ): Result<String> {
        val uid = requireUserId()
        val reportId = "rpt_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val ref = db.collection("users").document(uid)
            .collection("analytics_reports").document(reportId)
        val payload = mapOf(
            "userId" to uid,
            "title" to title.take(120),
            "totalChecked" to totalChecked,
            "uniqueApproved" to uniqueApproved,
            "duplicatesBlocked" to duplicatesBlocked,
            "avgSimilarityScore" to avgSimilarityScore.coerceIn(0L, 100L),
            "recommendationsSummary" to recommendationsSummary.take(1500),
            "createdAt" to FieldValue.serverTimestamp()
        )
        return try {
            ref.set(payload).await()
            Result.success(reportId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun updateUserProfileAndPlan(
        displayName: String,
        bio: String,
        planTier: String,
        dashboardWidgets: List<String>
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            docRef.update(
                mapOf(
                    "displayName" to displayName.take(60),
                    "bio" to bio.take(240),
                    "planTier" to planTier,
                    "dashboardWidgets" to dashboardWidgets.take(8),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    private fun handleFlowError(
        error: Throwable,
        operationType: OperationType,
        path: String,
        uid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, operationType, path)
        }
    }
}
