package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.crypto.YektaCryptoEngine
import com.example.data.model.AnalyticsReport
import com.example.data.model.ContentRegistryEntry
import com.example.data.model.EncryptedMessage
import com.example.data.model.FeedPersonalizationResult
import com.example.data.model.P2pHardwareState
import com.example.data.model.PostReply
import com.example.data.model.PublicProfile
import com.example.data.model.SocialPost
import com.example.data.model.UniquenessVerificationResult
import com.example.data.model.UserAccount
import com.example.data.repository.SemanticAnalysisRepository
import com.example.data.repository.YektaRepository
import com.example.network.ProximityP2pManager
import com.example.p2p.OmniMeshEngineState
import com.example.p2p.OmniMeshP2pEngine
import com.example.p2p.crypto.OmniMeshCrypto
import com.example.p2p.data.LocalMessageEntity
import com.example.p2p.data.OmniMeshDatabase
import com.example.p2p.data.OmniMeshLocalRepository
import com.example.p2p.data.PeerEntity
import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.transport.BleTransport
import com.example.p2p.transport.CapabilityDetector
import com.example.p2p.transport.TransportResult
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface VerificationUiState {
    data object Idle : VerificationUiState
    data object Analyzing : VerificationUiState
    data class Approved(val result: UniquenessVerificationResult) : VerificationUiState
    data class Blocked(val result: UniquenessVerificationResult) : VerificationUiState
    data class Error(val message: String) : VerificationUiState
}

sealed interface PersonalizationUiState {
    data object Idle : PersonalizationUiState
    data object Loading : PersonalizationUiState
    data class Ready(val result: FeedPersonalizationResult) : PersonalizationUiState
    data class Error(val message: String) : PersonalizationUiState
}

class YektaViewModel(
    private val repository: YektaRepository?,
    private val semanticRepository: SemanticAnalysisRepository,
    val cryptoEngine: YektaCryptoEngine,
    val proximityManager: ProximityP2pManager,
    val omniMeshEngine: OmniMeshP2pEngine,
    val capabilityDetector: CapabilityDetector,
    val currentUserId: String?,
    private val initialDisplayName: String
) : ViewModel() {

    val isCloudSignedIn: Boolean get() = currentUserId != null && repository != null

    private val _verificationState = MutableStateFlow<VerificationUiState>(VerificationUiState.Idle)
    val verificationState: StateFlow<VerificationUiState> = _verificationState.asStateFlow()

    private val _personalizationState = MutableStateFlow<PersonalizationUiState>(PersonalizationUiState.Idle)
    val personalizationState: StateFlow<PersonalizationUiState> = _personalizationState.asStateFlow()

    private val _actionBanner = MutableStateFlow<String?>(null)
    val actionBanner: StateFlow<String?> = _actionBanner.asStateFlow()

    private val _blockedDuplicateCount = MutableStateFlow(0)
    val blockedDuplicateCount: StateFlow<Int> = _blockedDuplicateCount.asStateFlow()

    private val _totalCheckedCount = MutableStateFlow(0)
    val totalCheckedCount: StateFlow<Int> = _totalCheckedCount.asStateFlow()

    private val _sentPlaintextCache = MutableStateFlow<Map<String, String>>(emptyMap())
    val sentPlaintextCache: StateFlow<Map<String, String>> = _sentPlaintextCache.asStateFlow()

    val p2pHardwareState: StateFlow<P2pHardwareState> = proximityManager.state

    // Real OmniMesh Phase 1 Offline P2P states (zero Firebase dependency)
    val omniMeshState: StateFlow<OmniMeshEngineState> = omniMeshEngine.engineState

    val savedP2pPeers: StateFlow<List<PeerEntity>> = omniMeshEngine.savedPeersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val allP2pMessages: StateFlow<List<LocalMessageEntity>> = omniMeshEngine.allMessagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun observeP2pMessagesForPeer(peerDeviceId: String): Flow<List<LocalMessageEntity>> =
        omniMeshEngine.observeMessagesForPeer(peerDeviceId)

    val userAccount: StateFlow<UiState<UserAccount?>> =
        if (repository != null && currentUserId != null) {
            repository.observeUserAccount(currentUserId).asUiState("Hesap bilgileri yüklenemedi")
        } else {
            flowOf<UiState<UserAccount?>>(UiState.Success(null))
                .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Success(null))
        }

    val postsState: StateFlow<UiState<List<SocialPost>>> =
        if (repository != null && currentUserId != null) {
            repository.observePublicPosts(currentUserId).asUiState("Akış yüklenemedi")
        } else {
            flowOf<UiState<List<SocialPost>>>(UiState.Success(emptyList()))
                .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Success(emptyList()))
        }

    val registryState: StateFlow<UiState<List<ContentRegistryEntry>>> =
        if (repository != null && currentUserId != null) {
            repository.observeContentRegistry(currentUserId).asUiState("İçerik kayıtları yüklenemedi")
        } else {
            flowOf<UiState<List<ContentRegistryEntry>>>(UiState.Success(emptyList()))
                .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Success(emptyList()))
        }

    val publicProfilesState: StateFlow<UiState<List<PublicProfile>>> =
        if (repository != null && currentUserId != null) {
            repository.observePublicProfiles(currentUserId).asUiState("Düğüm rehberi yüklenemedi")
        } else {
            flowOf<UiState<List<PublicProfile>>>(UiState.Success(emptyList()))
                .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Success(emptyList()))
        }

    val messagesState: StateFlow<UiState<List<EncryptedMessage>>> =
        if (repository != null && currentUserId != null) {
            repository.observeEncryptedMessages(currentUserId).asUiState("Mesajlar yüklenemedi")
        } else {
            flowOf<UiState<List<EncryptedMessage>>>(UiState.Success(emptyList()))
                .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Success(emptyList()))
        }

    val reportsState: StateFlow<UiState<List<AnalyticsReport>>> =
        if (repository != null && currentUserId != null) {
            repository.observeAnalyticsReports(currentUserId).asUiState("Raporlar yüklenemedi")
        } else {
            flowOf<UiState<List<AnalyticsReport>>>(UiState.Success(emptyList()))
                .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Success(emptyList()))
        }

    init {
        if (repository != null && currentUserId != null) {
            viewModelScope.launch {
                repository.ensureUserInitialized(
                    uid = currentUserId,
                    displayName = initialDisplayName,
                    publicKeyB64 = cryptoEngine.getPublicKeyBase64(),
                    publicKeyFingerprint = cryptoEngine.getPublicKeyFingerprint(),
                    nodeHexId = cryptoEngine.getKademliaNodeHexId()
                )
            }
        }
    }

    // --- Real Offline P2P Controls (OmniMesh Phase 1) ---

    fun refreshP2pCapabilities() {
        omniMeshEngine.refreshCapabilities()
    }

    fun startP2pDiscovery() {
        viewModelScope.launch {
            val res = omniMeshEngine.startDiscovery()
            if (res is TransportResult.Failure) {
                _actionBanner.value = res.reason
            }
        }
    }

    fun stopP2pDiscovery() {
        viewModelScope.launch {
            omniMeshEngine.stopDiscovery()
        }
    }

    fun connectToP2pPeer(address: String) {
        viewModelScope.launch {
            when (val res = omniMeshEngine.connectAndEstablishSession(address)) {
                is TransportResult.Success -> {
                    _actionBanner.value = "Güvenli oturum kuruldu"
                }
                is TransportResult.Failure -> {
                    _actionBanner.value = res.reason
                }
            }
        }
    }

    fun sendDirectP2pMessage(peerDeviceId: String?, peerAddress: String?, plaintext: String) {
        if (plaintext.isBlank()) return
        viewModelScope.launch {
            when (val res = omniMeshEngine.sendMessage(peerDeviceId, peerAddress, plaintext)) {
                is TransportResult.Success -> {
                    _actionBanner.value = "Şifreli mesaj gönderildi"
                }
                is TransportResult.Failure -> {
                    _actionBanner.value = res.reason
                }
            }
        }
    }

    // --- Cloud Social Features (Optional when signed in) ---

    fun clearBanner() {
        _actionBanner.value = null
    }

    fun resetVerification() {
        _verificationState.value = VerificationUiState.Idle
    }

    fun observeReplies(postId: String): Flow<List<PostReply>> {
        val repo = repository ?: return emptyFlow()
        val uid = currentUserId ?: return emptyFlow()
        return repo.observePostReplies(postId, uid)
    }

    fun verifyDraftUniqueness(
        context: Context,
        caption: String,
        format: String,
        mediaUri: Uri?
    ) {
        val repo = repository ?: run {
            _verificationState.value = VerificationUiState.Error("Bu özellik için giriş yapmanız gerekir.")
            return
        }
        if (caption.isBlank()) {
            _verificationState.value = VerificationUiState.Error("Lütfen paylaşım metnini girin.")
            return
        }
        viewModelScope.launch {
            _verificationState.value = VerificationUiState.Analyzing
            _totalCheckedCount.value += 1

            val (bitmap, mediaBytes) = withContext(Dispatchers.IO) {
                if (mediaUri != null && format == "photo") {
                    loadCompressedBitmapAndBytes(context, mediaUri)
                } else if (mediaUri != null) {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(mediaUri)?.use { it.readBytes().take(512_000).toByteArray() }
                    }.getOrNull()
                    null to bytes
                } else {
                    null to null
                }
            }

            val existing = (registryState.value as? UiState.Success)?.data ?: emptyList()
            val result = semanticRepository.verifyContentUniqueness(
                caption = caption,
                format = format,
                bitmap = bitmap,
                mediaBytes = mediaBytes,
                existingEntries = existing,
                exactHashCheck = { hash -> repo.isExactHashTaken(hash) }
            )

            result.onSuccess { res ->
                if (res.isUnique) {
                    _verificationState.value = VerificationUiState.Approved(res)
                } else {
                    _blockedDuplicateCount.value += 1
                    _verificationState.value = VerificationUiState.Blocked(res)
                }
            }.onFailure { err ->
                _verificationState.value = VerificationUiState.Error(
                    err.localizedMessage ?: "Özgünlük analizi tamamlanamadı."
                )
            }
        }
    }

    fun publishVerifiedPost(
        context: Context,
        format: String,
        caption: String,
        mediaUri: Uri?,
        verification: UniquenessVerificationResult,
        customTags: List<String>
    ) {
        val repo = repository ?: return
        val uid = currentUserId ?: return
        viewModelScope.launch {
            val account = (userAccount.value as? UiState.Success)?.data
            val authorName = account?.displayName ?: initialDisplayName.ifBlank { "Yekta Üyesi" }
            val authorHandle = account?.handle ?: "yekta_${uid.take(4)}"

            val mediaDataUri = withContext(Dispatchers.IO) {
                if (mediaUri != null && format == "photo") {
                    encodeBitmapToCompactDataUri(context, mediaUri)
                } else if (mediaUri != null && format == "video") {
                    mediaUri.toString()
                } else {
                    ""
                }
            }

            val finalTags = (customTags + verification.suggestedTags)
                .map { it.trim().lowercase().removePrefix("#") }
                .filter { it.isNotBlank() }
                .distinct()
                .take(6)

            val res = repo.publishUniquePost(
                authorName = authorName,
                authorHandle = authorHandle,
                format = format,
                caption = caption.trim(),
                mediaDataUri = mediaDataUri,
                tags = finalTags,
                contentHash = verification.contentHash,
                perceptualHash = verification.perceptualHash,
                uniquenessScore = verification.uniquenessScore,
                semanticSummary = verification.semanticSummary
            )

            res.onSuccess {
                _verificationState.value = VerificationUiState.Idle
                _actionBanner.value = "1x Özgün paylaşım ağa kaydedildi."
            }.onFailure { err ->
                _verificationState.value = VerificationUiState.Error(
                    err.localizedMessage ?: "Paylaşım yayınlanamadı."
                )
            }
        }
    }

    fun likePost(post: SocialPost) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.incrementLike(post).onFailure {
                _actionBanner.value = "Beğeni kaydedilemedi."
            }
        }
    }

    fun repostPost(post: SocialPost) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.incrementRepost(post).onFailure {
                _actionBanner.value = "Yeniden paylaşım kaydedilemedi."
            }
        }
    }

    fun submitReply(post: SocialPost, text: String) {
        val repo = repository ?: return
        val uid = currentUserId ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            val account = (userAccount.value as? UiState.Success)?.data
            val name = account?.displayName ?: initialDisplayName.ifBlank { "Yekta Üyesi" }
            val handle = account?.handle ?: "yekta_${uid.take(4)}"
            repo.addReply(post, name, handle, text.trim()).onFailure {
                _actionBanner.value = "Yanıt gönderilemedi."
            }
        }
    }

    fun generateAnalyticsAndPersonalization() {
        val repo = repository ?: return
        viewModelScope.launch {
            _personalizationState.value = PersonalizationUiState.Loading
            val posts = (postsState.value as? UiState.Success)?.data.orEmpty()
            val blocked = _blockedDuplicateCount.value
            val checked = (_totalCheckedCount.value + posts.size).coerceAtLeast(posts.size)
            val avgSim = if (posts.isEmpty()) 0 else {
                posts.map { (100L - it.uniquenessScore).coerceIn(0L, 100L) }.average().toInt()
            }

            val res = semanticRepository.generatePersonalizedFeedAndReport(
                posts = posts,
                preferredTopics = posts.flatMap { it.tags }.distinct().take(6),
                totalBlockedLocally = blocked
            )

            res.onSuccess { insight ->
                _personalizationState.value = PersonalizationUiState.Ready(insight)
                repo.saveAnalyticsReport(
                    title = "Yekta Özgünlük & Akış Raporu",
                    totalChecked = checked.toLong(),
                    uniqueApproved = posts.size.toLong(),
                    duplicatesBlocked = blocked.toLong(),
                    avgSimilarityScore = avgSim.toLong(),
                    recommendationsSummary = insight.insightSummary
                )
                _actionBanner.value = "Yeni analiz raporu oluşturuldu ve kaydedildi."
            }.onFailure { err ->
                _personalizationState.value = PersonalizationUiState.Error(
                    err.localizedMessage ?: "Rapor oluşturulamadı."
                )
            }
        }
    }

    fun updatePlanAndWidgets(
        displayName: String,
        bio: String,
        planTier: String,
        widgets: List<String>
    ) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.updateUserProfileAndPlan(displayName, bio, planTier, widgets)
                .onSuccess { _actionBanner.value = "Profil ve panel ayarları güncellendi." }
                .onFailure { _actionBanner.value = it.localizedMessage ?: "Güncelleme başarısız." }
        }
    }

    fun buildExportCsv(): String {
        val posts = (postsState.value as? UiState.Success)?.data.orEmpty()
        val reports = (reportsState.value as? UiState.Success)?.data.orEmpty()
        val sb = StringBuilder()
        sb.appendLine("tur,kimlik,baslik_veya_ozet,ozgunluk_puani,begeni,paylasim,hash")
        for (p in posts) {
            val safeSummary = p.semanticSummary.replace("\"", "'")
            sb.appendLine("gonderi,${p.id},\"$safeSummary\",${p.uniquenessScore},${p.likeCount},${p.repostCount},${p.contentHash}")
        }
        for (r in reports) {
            val safeTitle = r.title.replace("\"", "'")
            sb.appendLine("rapor,${r.id},\"$safeTitle\",${100 - r.avgSimilarityScore},${r.uniqueApproved},${r.duplicatesBlocked},-")
        }
        return sb.toString()
    }

    fun buildExportJson(): String {
        val posts = (postsState.value as? UiState.Success)?.data.orEmpty()
        val reports = (reportsState.value as? UiState.Success)?.data.orEmpty()
        val root = JSONObject()
        root.put("platform", "Yekta")
        root.put("totalPosts", posts.size)
        root.put("blockedDuplicates", _blockedDuplicateCount.value)
        val postsArr = JSONArray()
        posts.forEach { p ->
            postsArr.put(JSONObject().apply {
                put("id", p.id)
                put("format", p.format)
                put("uniquenessScore", p.uniquenessScore)
                put("contentHash", p.contentHash)
                put("summary", p.semanticSummary)
                put("likes", p.likeCount)
            })
        }
        root.put("posts", postsArr)
        val repArr = JSONArray()
        reports.forEach { r ->
            repArr.put(JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("uniqueApproved", r.uniqueApproved)
                put("duplicatesBlocked", r.duplicatesBlocked)
                put("summary", r.recommendationsSummary)
            })
        }
        root.put("reports", repArr)
        return root.toString(2)
    }

    override fun onCleared() {
        super.onCleared()
        proximityManager.stopDiscovery()
        omniMeshEngine.release()
    }

    private fun <T> Flow<T>.asUiState(fallbackError: String): StateFlow<UiState<T>> =
        map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, fallbackError, rootError)
                emit(UiState.Error(rootError.localizedMessage ?: fallbackError))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    private fun loadCompressedBitmapAndBytes(context: Context, uri: Uri): Pair<Bitmap?, ByteArray?> {
        return try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null to null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 900 || opts.outHeight / sample > 900) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            bmp to rawBytes
        } catch (_: Exception) {
            null to null
        }
    }

    private fun encodeBitmapToCompactDataUri(context: Context, uri: Uri): String {
        return try {
            val (bmp, _) = loadCompressedBitmapAndBytes(context, uri)
            if (bmp == null) return ""
            val maxDim = 480
            val scale = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height, 1f)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 70, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        } catch (_: Exception) {
            ""
        }
    }

    class Factory(
        private val context: Context,
        private val currentUserId: String?,
        private val displayName: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            val repository = if (currentUserId != null) {
                val databaseId = appContext.getString(R.string.firestore_database_id)
                YektaRepository(FirebaseFirestore.getInstance(databaseId))
            } else {
                null
            }

            val crypto = YektaCryptoEngine(appContext)
            val p2p = ProximityP2pManager(appContext, crypto.getKademliaNodeHexId())

            val identityManager = DeviceIdentityManager(appContext)
            val capabilityDetector = CapabilityDetector(appContext)
            val bleTransport = BleTransport(
                context = appContext,
                localShortTag = identityManager.getShortAdvertisingTag(),
                capabilityDetector = capabilityDetector
            )
            val roomDb = OmniMeshDatabase.getInstance(appContext)
            val localRepo = OmniMeshLocalRepository(roomDb.omniMeshDao())
            val omniMeshEngine = OmniMeshP2pEngine(
                identityManager = identityManager,
                crypto = OmniMeshCrypto(identityManager),
                primaryTransport = bleTransport,
                localRepository = localRepo,
                capabilityDetector = capabilityDetector
            )

            return YektaViewModel(
                repository = repository,
                semanticRepository = SemanticAnalysisRepository(),
                cryptoEngine = crypto,
                proximityManager = p2p,
                omniMeshEngine = omniMeshEngine,
                capabilityDetector = capabilityDetector,
                currentUserId = currentUserId,
                initialDisplayName = displayName
            ) as T
        }
    }

    private companion object {
        const val TAG = "YektaViewModel"
    }
}
