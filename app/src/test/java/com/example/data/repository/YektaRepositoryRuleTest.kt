package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.crypto.ContentHasher
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class YektaRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun publishUniquePost_createsPostAndBlocksDuplicateHash() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = YektaRepository(firestore, auth)

        val caption = "Özgün test paylaşımı ${UUID.randomUUID()}"
        val contentHash = ContentHasher.sha256Hex(caption)
        val pHash = ContentHasher.computeTextSimHash(caption)

        val postResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.publishUniquePost(
                authorName = "Alice Test",
                authorHandle = "alice_test",
                format = "text",
                caption = caption,
                mediaDataUri = "",
                tags = listOf("ozgun", "test"),
                contentHash = contentHash,
                perceptualHash = pHash,
                uniquenessScore = 97,
                semanticSummary = "Özgün test paylaşımı özeti"
            )
        }
        assertTrue("Expected publish to succeed: ${postResult.exceptionOrNull()}", postResult.isSuccess)
        val postId = postResult.getOrThrow()

        val posts = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observePublicPosts(aliceUid).first { list -> list.any { it.id == postId } }
        }
        assertTrue(posts.any { it.id == postId })

        // Second user tries to publish identical hash -> must fail
        signInTestUser(BOB_EMAIL)
        val bobRepo = YektaRepository(firestore, auth)
        val dupResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.publishUniquePost(
                authorName = "Bob Test",
                authorHandle = "bob_test",
                format = "text",
                caption = caption,
                mediaDataUri = "",
                tags = listOf("kopya"),
                contentHash = contentHash,
                perceptualHash = pHash,
                uniquenessScore = 10,
                semanticSummary = "Kopya içerik"
            )
        }
        assertTrue("Duplicate 1x hash must be rejected", dupResult.isFailure)
    }

    @Test
    fun analyticsReport_isolatedPerUser() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = YektaRepository(firestore, auth)

        val saveRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.saveAnalyticsReport(
                title = "Rapor ${UUID.randomUUID().toString().take(6)}",
                totalChecked = 10L,
                uniqueApproved = 9L,
                duplicatesBlocked = 1L,
                avgSimilarityScore = 14L,
                recommendationsSummary = "Özgünlük oranı yüksek."
            )
        }
        assertTrue(saveRes.isSuccess)
        val reportId = saveRes.getOrThrow()

        val reports = withTimeout(FLOW_TIMEOUT_MS) {
            aliceRepo.observeAnalyticsReports(aliceUid).first { list -> list.any { it.id == reportId } }
        }
        assertTrue(reports.any { it.id == reportId })

        // Unauthenticated read must fail with PERMISSION_DENIED
        auth.signOut()
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                aliceRepo.observeAnalyticsReports(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED when unauthenticated")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice_yekta@test.com"
        const val BOB_EMAIL = "bob_yekta@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
