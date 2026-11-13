package com.example.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.crypto.ContentHasher
import com.example.data.model.ContentRegistryEntry
import com.example.data.model.FeedPersonalizationResult
import com.example.data.model.SocialPost
import com.example.data.model.UniquenessVerificationResult
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class SemanticAnalysisRepository {

    private val uniquenessSchema = Schema.obj(
        properties = mapOf(
            "isUnique" to Schema.boolean(description = "True if the content is semantically original and not a duplicate or paraphrase of existing entries"),
            "similarityPercent" to Schema.integer(description = "Semantic similarity score from 0 to 100 against the closest existing entry"),
            "matchedPostId" to Schema.string(description = "ID of the closest matching existing post if similarityPercent >= 65, otherwise empty string", nullable = true),
            "reason" to Schema.string(description = "Concise user-facing explanation in Turkish without technical jargon"),
            "semanticSummary" to Schema.string(description = "Concise 1-sentence Turkish topic summary of this content"),
            "suggestedTags" to Schema.array(items = Schema.string(), description = "Up to 4 short lowercase Turkish topic tags without #")
        ),
        optionalProperties = listOf("matchedPostId")
    )

    private val personalizationSchema = Schema.obj(
        properties = mapOf(
            "rankedPostIds" to Schema.array(items = Schema.string(), description = "Post IDs ordered by relevance and uniqueness quality"),
            "topicHighlights" to Schema.array(items = Schema.string(), description = "Top 3 short emerging themes in Turkish"),
            "insightSummary" to Schema.string(description = "Actionable 2-sentence Turkish summary for the analytics report")
        )
    )

    private val uniquenessModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
                responseMimeType = "application/json"
                responseSchema = uniquenessSchema
            }
        )
    }

    private val personalizationModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
                responseMimeType = "application/json"
                responseSchema = personalizationSchema
            }
        )
    }

    suspend fun verifyContentUniqueness(
        caption: String,
        format: String,
        bitmap: Bitmap?,
        mediaBytes: ByteArray?,
        existingEntries: List<ContentRegistryEntry>,
        exactHashCheck: suspend (String) -> Boolean
    ): Result<UniquenessVerificationResult> {
        val contentHash = if (mediaBytes != null && mediaBytes.isNotEmpty()) {
            ContentHasher.sha256Hex(mediaBytes + caption.trim().lowercase().toByteArray())
        } else {
            ContentHasher.sha256Hex(caption)
        }
        val perceptualHash = if (bitmap != null) {
            ContentHasher.computeBitmapDHash(bitmap)
        } else {
            ContentHasher.computeTextSimHash(caption)
        }

        // 1. Instant exact cryptographic hash guard
        if (exactHashCheck(contentHash)) {
            return Result.success(
                UniquenessVerificationResult(
                    isUnique = false,
                    similarityPercent = 100,
                    uniquenessScore = 0,
                    matchedPostId = existingEntries.firstOrNull { it.contentHash == contentHash }?.postId,
                    reason = "Bu içeriğin birebir kopyası Yekta ağında zaten mevcut. Her içerik yalnızca 1 kez paylaşılabilir.",
                    semanticSummary = caption.take(120),
                    suggestedTags = emptyList(),
                    contentHash = contentHash,
                    perceptualHash = perceptualHash,
                    blockedByExactHash = true
                )
            )
        }

        // 2. Instant perceptual / SimHash near-duplicate guard
        val nearPerceptualMatch = existingEntries.firstOrNull { entry ->
            entry.format == format &&
                ContentHasher.hammingDistanceHex(perceptualHash, entry.perceptualHash) <= 4
        }
        if (nearPerceptualMatch != null) {
            return Result.success(
                UniquenessVerificationResult(
                    isUnique = false,
                    similarityPercent = 94,
                    uniquenessScore = 6,
                    matchedPostId = nearPerceptualMatch.postId,
                    reason = "Görsel veya metin parmak izi mevcut bir paylaşımla %94 oranında eşleşti. Özgün olmayan kopya engellendi.",
                    semanticSummary = nearPerceptualMatch.semanticSummary,
                    suggestedTags = emptyList(),
                    contentHash = contentHash,
                    perceptualHash = perceptualHash,
                    blockedByExactHash = true
                )
            )
        }

        // 3. Deep Semantic Similarity Verification via Firebase AI Logic (gemini-4-carbon, ThinkingLevel.HIGH)
        val ledgerContext = existingEntries.take(25).joinToString("\n") { entry ->
            "- postId=${entry.postId} | format=${entry.format} | özet=${entry.semanticSummary} | metin=${entry.textPreview}"
        }.ifBlank { "(Henüz kayıtlı gönderi yok)" }

        val promptText = """
            Sen Yekta sosyal ağının 1x Özgünlük Doğrulama değerlendiricisisin.
            Aday içeriği mevcut ağ kayıtlarıyla anlamsal olarak karşılaştır: farklı kelimelerle yeniden yazılmış,
            çevrilmiş veya aynı görsel sahneyi/fikri kopyalayan paylaşımları tespit et.
            - similarityPercent >= 78 ise isUnique = false döndür.
            - Tüm metin alanlarını doğal Türkçe yaz, teknik model veya sistem adlarından bahsetme.
            
            Aday Format: $format
            Aday Metin: $caption
            
            Mevcut Kayıtlar:
            $ledgerContext
        """.trimIndent()

        return try {
            val response = withTimeout(60_000L) {
                uniquenessModel.generateContent(
                    content {
                        text(promptText)
                        if (bitmap != null) {
                            image(bitmap)
                        }
                    }
                )
            }
            val jsonStr = response.text ?: throw IllegalStateException("Doğrulama yanıtı boş döndü.")
            val json = JSONObject(jsonStr)
            val simPercent = json.optInt("similarityPercent", 10).coerceIn(0, 100)
            val isUnique = json.optBoolean("isUnique", simPercent < 78) && simPercent < 78
            val matchedId = json.optString("matchedPostId", "").takeIf { it.isNotBlank() }
            val reason = json.optString("reason", "Özgünlük doğrulaması tamamlandı.")
            val summary = json.optString("semanticSummary", caption.take(100))
            val tagsArr = json.optJSONArray("suggestedTags")
            val tags = buildList {
                if (tagsArr != null) {
                    for (i in 0 until tagsArr.length()) {
                        val tag = tagsArr.optString(i).trim().lowercase()
                        if (tag.isNotBlank()) add(tag)
                    }
                }
            }.take(4)

            Result.success(
                UniquenessVerificationResult(
                    isUnique = isUnique,
                    similarityPercent = simPercent,
                    uniquenessScore = (100 - simPercent).coerceIn(0, 100),
                    matchedPostId = matchedId,
                    reason = reason,
                    semanticSummary = summary,
                    suggestedTags = tags,
                    contentHash = contentHash,
                    perceptualHash = perceptualHash,
                    blockedByExactHash = false
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Uniqueness check timed out", e)
            Result.failure(IllegalStateException("Özgünlük analizi zaman aşımına uğradı. Lütfen tekrar deneyin."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Uniqueness verification failed", e)
            Result.failure(e)
        }
    }

    suspend fun generatePersonalizedFeedAndReport(
        posts: List<SocialPost>,
        preferredTopics: List<String>,
        totalBlockedLocally: Int
    ): Result<FeedPersonalizationResult> {
        if (posts.isEmpty()) {
            return Result.success(
                FeedPersonalizationResult(
                    rankedPostIds = emptyList(),
                    topicHighlights = listOf("Yeni paylaşımlar bekleniyor"),
                    insightSummary = "Ağda henüz analiz edilecek gönderi bulunmuyor. İlk 1x özgün paylaşımınızı oluşturarak raporu başlatabilirsiniz."
                )
            )
        }

        val postLines = posts.take(30).joinToString("\n") { p ->
            "id=${p.id} | format=${p.format} | ozgunluk=${p.uniquenessScore} | begeni=${p.likeCount} | etiket=${p.tags.joinToString(",")} | ozet=${p.semanticSummary}"
        }

        val prompt = """
            Yekta kullanıcısı için akış sıralaması ve kişisel analiz özeti üret.
            Kullanıcı ilgi alanları: ${preferredTopics.joinToString(", ").ifBlank { "genel özgün içerik, teknoloji, kültür" }}
            Engellenen mükerrer içerik sayısı: $totalBlockedLocally
            Kurallar:
            - Tüm metinler doğal, son kullanıcıya yönelik Türkçe olmalı; hiçbir sistem, model veya altyapı adı geçmemeli.
            - rankedPostIds listesine verilen gönderi kimliklerini en yüksek alaka ve özgünlük sırasıyla ekle.
            
            Gönderiler:
            $postLines
        """.trimIndent()

        return try {
            val response = withTimeout(60_000L) {
                personalizationModel.generateContent(prompt)
            }
            val jsonStr = response.text ?: throw IllegalStateException("Öneri yanıtı boş döndü.")
            val json = JSONObject(jsonStr)
            val idsArr = json.optJSONArray("rankedPostIds")
            val rankedIds = buildList {
                if (idsArr != null) {
                    for (i in 0 until idsArr.length()) {
                        val id = idsArr.optString(i)
                        if (id.isNotBlank()) add(id)
                    }
                }
            }
            val topicsArr = json.optJSONArray("topicHighlights")
            val topics = buildList {
                if (topicsArr != null) {
                    for (i in 0 until topicsArr.length()) {
                        val t = topicsArr.optString(i)
                        if (t.isNotBlank()) add(t)
                    }
                }
            }.take(3)
            val summary = json.optString("insightSummary", "Paylaşım akışınız güncellendi.")

            Result.success(
                FeedPersonalizationResult(
                    rankedPostIds = rankedIds,
                    topicHighlights = topics,
                    insightSummary = summary
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Personalization timed out", e)
            Result.failure(IllegalStateException("Kişiselleştirilmiş analiz zaman aşımına uğradı."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Personalization failed", e)
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "SemanticAnalysisRepo"
        const val MODEL_NAME = "gemini-4-carbon"
    }
}
