package com.example.p2p

import com.example.p2p.crypto.EnvelopeVerificationResult
import com.example.p2p.crypto.HandshakeAckPacket
import com.example.p2p.crypto.HandshakeInitPacket
import com.example.p2p.crypto.MessageEnvelope
import com.example.p2p.crypto.OmniMeshCrypto
import com.example.p2p.crypto.PendingClientHandshake
import com.example.p2p.crypto.SecureSession
import com.example.p2p.crypto.SessionState
import com.example.p2p.data.LocalMessageEntity
import com.example.p2p.data.MessageDeliveryState
import com.example.p2p.data.OmniMeshLocalRepository
import com.example.p2p.data.PeerEntity
import com.example.p2p.forwarding.DropReason
import com.example.p2p.forwarding.DuplicateMessageCache
import com.example.p2p.forwarding.ForwardingDecision
import com.example.p2p.forwarding.ForwardingEngine
import com.example.p2p.forwarding.InMemoryDuplicateMessageCache
import com.example.p2p.identity.DeviceIdentity
import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.routing.DirectPeerNextHopResolver
import com.example.p2p.routing.NextHopResolver
import com.example.p2p.transport.CapabilityDetector
import com.example.p2p.transport.CapabilityStatus
import com.example.p2p.transport.DeviceTransportCapabilities
import com.example.p2p.transport.P2pTransport
import com.example.p2p.transport.PeerConnectionStatus
import com.example.p2p.transport.TransportEvent
import com.example.p2p.transport.TransportPeer
import com.example.p2p.transport.TransportResult
import com.example.p2p.transport.TransportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

data class PeerUiModel(
    val address: String,
    val deviceId: String?,
    val displayName: String,
    val fingerprint: String?,
    val rssi: Int,
    val transportType: TransportType,
    val connectionStatus: PeerConnectionStatus,
    val sessionState: SessionState,
    val statusDetail: String? = null
)

data class OmniMeshEngineState(
    val localIdentity: DeviceIdentity,
    val capabilities: DeviceTransportCapabilities,
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val statusMessage: String = "Hazır",
    val peers: List<PeerUiModel> = emptyList(),
    val lastSecurityNotice: String? = null
)

/**
 * Core OmniMesh Phase 1 P2P orchestrator.
 * Operates completely offline over P2pTransport (BLE in production) + Room local database.
 * Zero Firebase or server dependency.
 */
class OmniMeshP2pEngine(
    private val identityManager: DeviceIdentityManager,
    private val crypto: OmniMeshCrypto,
    private val primaryTransport: P2pTransport,
    private val localRepository: OmniMeshLocalRepository,
    private val capabilityDetector: CapabilityDetector,
    private val clock: () -> Long = { System.currentTimeMillis() },
    duplicateCache: DuplicateMessageCache? = null,
    nextHopResolver: NextHopResolver? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handshakeMutex = Mutex()

    private val _engineState = MutableStateFlow(
        OmniMeshEngineState(
            localIdentity = identityManager.getDeviceIdentity(),
            capabilities = capabilityDetector.detectAll()
        )
    )
    val engineState: StateFlow<OmniMeshEngineState> = _engineState.asStateFlow()

    val savedPeersFlow: Flow<List<PeerEntity>> = localRepository.peers
    val allMessagesFlow: Flow<List<LocalMessageEntity>> = localRepository.allMessages

    fun observeMessagesForPeer(peerDeviceId: String): Flow<List<LocalMessageEntity>> =
        localRepository.observeMessagesForPeer(peerDeviceId)

    private val discoveredByAddress = ConcurrentHashMap<String, TransportPeer>()
    private val addressToDeviceId = ConcurrentHashMap<String, String>()
    private val deviceIdToAddress = ConcurrentHashMap<String, String>()
    private val sessionsByDeviceId = ConcurrentHashMap<String, SecureSession>()
    private val sessionStatesByAddress = ConcurrentHashMap<String, SessionState>()
    private val pendingHandshakes = ConcurrentHashMap<String, PendingClientHandshake>()
    private val handshakeWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    val forwardingEngine: ForwardingEngine = ForwardingEngine(
        localDeviceId = identityManager.deviceId,
        duplicateCache = duplicateCache ?: InMemoryDuplicateMessageCache(clock = clock),
        nextHopResolver = nextHopResolver ?: DirectPeerNextHopResolver { destDeviceId ->
            deviceIdToAddress[destDeviceId]
        },
        clock = clock
    )

    init {
        scope.launch {
            primaryTransport.events.collect { event ->
                handleTransportEvent(event)
            }
        }
        scope.launch {
            primaryTransport.receive().collect { frame ->
                handleIncomingFrame(frame.peerAddress.uppercase(), frame.payload)
            }
        }
        scope.launch {
            localRepository.peers.collect { saved ->
                saved.forEach { entity ->
                    if (entity.bleAddress.isNotBlank()) {
                        addressToDeviceId.putIfAbsent(entity.bleAddress.uppercase(), entity.deviceId)
                        deviceIdToAddress.putIfAbsent(entity.deviceId, entity.bleAddress.uppercase())
                    }
                }
                rebuildPeerList()
            }
        }
    }

    fun refreshCapabilities() {
        _engineState.update {
            it.copy(capabilities = capabilityDetector.detectAll())
        }
    }

    suspend fun startDiscovery(): TransportResult<Unit> {
        refreshCapabilities()
        val result = primaryTransport.startDiscovery()
        if (result is TransportResult.Failure) {
            _engineState.update {
                it.copy(
                    isScanning = false,
                    isAdvertising = false,
                    statusMessage = result.reason
                )
            }
        }
        return result
    }

    suspend fun stopDiscovery() {
        primaryTransport.stopDiscovery()
        _engineState.update {
            it.copy(
                isScanning = false,
                isAdvertising = false,
                statusMessage = "Tarama durduruldu"
            )
        }
    }

    suspend fun connectAndEstablishSession(address: String): TransportResult<SecureSession> {
        val normalized = address.uppercase()
        val existingDeviceId = addressToDeviceId[normalized]
        if (existingDeviceId != null) {
            sessionsByDeviceId[existingDeviceId]?.let { active ->
                return TransportResult.Success(active)
            }
        }

        val peer = discoveredByAddress[normalized] ?: TransportPeer(
            address = normalized,
            displayName = "OmniMesh Cihazı",
            shortTagHex = normalized.replace(":", ""),
            rssi = -65,
            transportType = primaryTransport.type
        )

        return handshakeMutex.withLock {
            // Re-check inside lock
            addressToDeviceId[normalized]?.let { devId ->
                sessionsByDeviceId[devId]?.let { return@withLock TransportResult.Success(it) }
            }

            sessionStatesByAddress[normalized] = SessionState.HANDSHAKING
            updatePeerStatus(normalized, PeerConnectionStatus.CONNECTING, SessionState.HANDSHAKING, "Bağlanıyor")

            when (val connRes = primaryTransport.connect(peer)) {
                is TransportResult.Failure -> {
                    sessionStatesByAddress[normalized] = SessionState.FAILED
                    updatePeerStatus(normalized, PeerConnectionStatus.FAILED, SessionState.FAILED, connRes.reason)
                    return@withLock connRes
                }
                is TransportResult.Success -> Unit
            }

            val pending = crypto.createHandshakeInit()
            val waiter = CompletableDeferred<Boolean>()
            pendingHandshakes[normalized] = pending
            handshakeWaiters[normalized] = waiter

            val wireInit = byteArrayOf(FRAME_HANDSHAKE_INIT) + pending.initPacket.serialize()
            when (val sendRes = primaryTransport.send(normalized, wireInit)) {
                is TransportResult.Failure -> {
                    pendingHandshakes.remove(normalized)
                    handshakeWaiters.remove(normalized)
                    sessionStatesByAddress[normalized] = SessionState.FAILED
                    updatePeerStatus(normalized, PeerConnectionStatus.FAILED, SessionState.FAILED, sendRes.reason)
                    return@withLock sendRes
                }
                is TransportResult.Success -> Unit
            }

            val completed = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { waiter.await() } == true
            handshakeWaiters.remove(normalized)
            if (!completed) {
                pendingHandshakes.remove(normalized)
                sessionStatesByAddress[normalized] = SessionState.FAILED
                updatePeerStatus(normalized, PeerConnectionStatus.FAILED, SessionState.FAILED, "El sıkışma zaman aşımı")
                return@withLock TransportResult.Failure("Güvenli oturum el sıkışması tamamlanamadı.")
            }

            val devId = addressToDeviceId[normalized]
            val session = devId?.let { sessionsByDeviceId[it] }
            if (session != null) {
                TransportResult.Success(session)
            } else {
                TransportResult.Failure("Oturum oluşturulamadı.")
            }
        }
    }

    /**
     * Persists a message locally in Room first (state = CREATED), ensures a secure session
     * with the target device, encrypts into MessageEnvelope, transmits over P2pTransport,
     * and awaits authenticated DeliveryAck from receiver.
     */
    suspend fun sendMessage(
        peerDeviceId: String?,
        peerAddress: String?,
        plaintext: String
    ): TransportResult<String> {
        val trimmed = plaintext.trim()
        if (trimmed.isEmpty()) {
            return TransportResult.Failure("Boş mesaj gönderilemez.")
        }

        val resolvedAddress = (peerAddress ?: peerDeviceId?.let { deviceIdToAddress[it] })?.uppercase()
        val initialDeviceId = peerDeviceId ?: resolvedAddress?.let { addressToDeviceId[it] } ?: resolvedAddress
        if (initialDeviceId == null) {
            return TransportResult.Failure("Hedef cihaz adresi veya kimliği bulunamadı.")
        }

        val messageId = MessageEnvelope.newMessageId()
        localRepository.createOutgoingMessage(
            messageId = messageId,
            peerDeviceId = initialDeviceId,
            senderDeviceId = identityManager.deviceId,
            plaintext = trimmed
        )

        // Ensure active session
        var session = sessionsByDeviceId[initialDeviceId]
        var targetAddress = resolvedAddress ?: deviceIdToAddress[initialDeviceId]
        var targetDeviceId = initialDeviceId

        if (session == null) {
            if (targetAddress == null) {
                localRepository.updateMessageState(
                    messageId,
                    MessageDeliveryState.FAILED,
                    "Cihaz yakın alanda bulunamadı"
                )
                return TransportResult.Failure("Cihaz yakın alanda bulunamadı.")
            }

            localRepository.updateMessageState(messageId, MessageDeliveryState.CONNECTING)
            when (val hsRes = connectAndEstablishSession(targetAddress)) {
                is TransportResult.Failure -> {
                    localRepository.updateMessageState(
                        messageId,
                        MessageDeliveryState.FAILED,
                        hsRes.reason
                    )
                    return hsRes
                }
                is TransportResult.Success -> {
                    session = hsRes.value
                    targetDeviceId = session.peerDeviceId
                    // If initial message was keyed by BLE address before handshake learned deviceId, migrate record
                    if (targetDeviceId != initialDeviceId) {
                        localRepository.createOutgoingMessage(
                            messageId = messageId,
                            peerDeviceId = targetDeviceId,
                            senderDeviceId = identityManager.deviceId,
                            plaintext = trimmed
                        )
                    }
                }
            }
        }

        val activeAddress = targetAddress ?: deviceIdToAddress[targetDeviceId]
        if (activeAddress == null) {
            localRepository.updateMessageState(messageId, MessageDeliveryState.FAILED, "Bağlantı adresi yok")
            return TransportResult.Failure("Bağlantı adresi bulunamadı.")
        }

        return transmitEnvelope(activeAddress, session, messageId, trimmed)
    }

    suspend fun retryPendingMessagesForPeer(peerDeviceId: String) {
        val session = sessionsByDeviceId[peerDeviceId] ?: return
        val address = deviceIdToAddress[peerDeviceId] ?: return
        val pending = localRepository.getPendingOutgoingForPeer(peerDeviceId)
        for (msg in pending) {
            transmitEnvelope(address, session, msg.messageId, msg.plaintext)
        }
    }

    private suspend fun transmitEnvelope(
        address: String,
        session: SecureSession,
        messageId: String,
        plaintext: String
    ): TransportResult<String> {
        val envelope = crypto.encryptEnvelope(
            session = session,
            plaintext = plaintext.toByteArray(StandardCharsets.UTF_8),
            messageId = messageId,
            createdAt = clock()
        )
        val wireFrame = byteArrayOf(FRAME_DATA_ENVELOPE) + MessageEnvelope.serialize(envelope)
        return when (val res = primaryTransport.send(address, wireFrame)) {
            is TransportResult.Success -> {
                localRepository.updateMessageState(messageId, MessageDeliveryState.SENT)
                _engineState.update { it.copy(statusMessage = "Şifreli mesaj gönderildi") }
                TransportResult.Success(messageId)
            }
            is TransportResult.Failure -> {
                localRepository.updateMessageState(messageId, MessageDeliveryState.FAILED, res.reason)
                TransportResult.Failure(res.reason)
            }
        }
    }

    private suspend fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.DiscoveryStateChanged -> {
                _engineState.update {
                    it.copy(
                        isScanning = event.isScanning,
                        isAdvertising = event.isAdvertising,
                        statusMessage = event.message
                    )
                }
            }
            is TransportEvent.PeerDiscovered -> {
                val peer = event.peer.copy(address = event.peer.address.uppercase())
                discoveredByAddress[peer.address] = peer
                rebuildPeerList()
            }
            is TransportEvent.ConnectionStateChanged -> {
                val addr = event.peerAddress.uppercase()
                if (event.status == PeerConnectionStatus.DISCONNECTED || event.status == PeerConnectionStatus.FAILED) {
                    val devId = addressToDeviceId[addr]
                    if (devId != null) {
                        sessionsByDeviceId.remove(devId)
                    }
                    sessionStatesByAddress[addr] =
                        if (event.status == PeerConnectionStatus.FAILED) SessionState.FAILED else SessionState.CLOSED
                    handshakeWaiters.remove(addr)?.complete(false)
                }
                val currentSessionState = sessionStatesByAddress[addr] ?: SessionState.UNINITIALIZED
                val effectiveStatus = if (currentSessionState == SessionState.ESTABLISHED) {
                    PeerConnectionStatus.SECURE_SESSION_ESTABLISHED
                } else {
                    event.status
                }
                updatePeerStatus(addr, effectiveStatus, currentSessionState, event.reason)
            }
            is TransportEvent.TransportError -> {
                _engineState.update { it.copy(statusMessage = event.reason) }
            }
        }
    }

    private suspend fun handleIncomingFrame(peerAddress: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        val frameType = payload[0]
        val body = payload.copyOfRange(1, payload.size)

        when (frameType) {
            FRAME_HANDSHAKE_INIT -> handleIncomingHandshakeInit(peerAddress, body)
            FRAME_HANDSHAKE_ACK -> handleIncomingHandshakeAck(peerAddress, body)
            FRAME_DATA_ENVELOPE -> handleIncomingDataEnvelope(peerAddress, body)
            FRAME_ACK_ENVELOPE -> handleIncomingAckEnvelope(peerAddress, body)
            else -> {
                _engineState.update {
                    it.copy(lastSecurityNotice = "Bilinmeyen protokol çerçevesi reddedildi")
                }
            }
        }
    }

    private suspend fun handleIncomingHandshakeInit(peerAddress: String, body: ByteArray) {
        val initPacket = HandshakeInitPacket.deserialize(body) ?: run {
            _engineState.update { it.copy(lastSecurityNotice = "Geçersiz el sıkışma paketi reddedildi") }
            return
        }

        val knownPeer = localRepository.getPeerByDeviceId(initPacket.senderDeviceId)
        val pinnedKey = knownPeer?.identityPublicKeyB64?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
        }

        val ackAndSession = crypto.processHandshakeInitAndCreateAck(initPacket, pinnedKey)
        if (ackAndSession == null) {
            sessionStatesByAddress[peerAddress] = SessionState.FAILED
            updatePeerStatus(
                peerAddress,
                PeerConnectionStatus.FAILED,
                SessionState.FAILED,
                "Kimlik doğrulaması başarısız"
            )
            _engineState.update { it.copy(lastSecurityNotice = "Sahte veya hatalı kimlik el sıkışması reddedildi") }
            return
        }

        val (ackPacket, session) = ackAndSession
        bindVerifiedPeerSession(peerAddress, session)

        val wireAck = byteArrayOf(FRAME_HANDSHAKE_ACK) + ackPacket.serialize()
        val sendRes = primaryTransport.send(peerAddress, wireAck)
        if (sendRes is TransportResult.Failure) {
            sessionStatesByAddress[peerAddress] = SessionState.FAILED
            sessionsByDeviceId.remove(session.peerDeviceId)
            updatePeerStatus(peerAddress, PeerConnectionStatus.FAILED, SessionState.FAILED, sendRes.reason)
            return
        }

        updatePeerStatus(
            peerAddress,
            PeerConnectionStatus.SECURE_SESSION_ESTABLISHED,
            SessionState.ESTABLISHED,
            "Güvenli oturum kuruldu"
        )
        retryPendingMessagesForPeer(session.peerDeviceId)
    }

    private suspend fun handleIncomingHandshakeAck(peerAddress: String, body: ByteArray) {
        val ackPacket = HandshakeAckPacket.deserialize(body) ?: run {
            handshakeWaiters.remove(peerAddress)?.complete(false)
            return
        }
        val pending = pendingHandshakes.remove(peerAddress) ?: run {
            handshakeWaiters.remove(peerAddress)?.complete(false)
            return
        }

        val knownPeer = localRepository.getPeerByDeviceId(ackPacket.responderDeviceId)
        val pinnedKey = knownPeer?.identityPublicKeyB64?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
        }

        val session = crypto.completeClientHandshake(pending, ackPacket, pinnedKey)
        if (session == null) {
            sessionStatesByAddress[peerAddress] = SessionState.FAILED
            updatePeerStatus(
                peerAddress,
                PeerConnectionStatus.FAILED,
                SessionState.FAILED,
                "El sıkışma doğrulaması başarısız"
            )
            _engineState.update { it.copy(lastSecurityNotice = "Sunucu el sıkışma imzası doğrulanamadı") }
            handshakeWaiters.remove(peerAddress)?.complete(false)
            return
        }

        bindVerifiedPeerSession(peerAddress, session)
        updatePeerStatus(
            peerAddress,
            PeerConnectionStatus.SECURE_SESSION_ESTABLISHED,
            SessionState.ESTABLISHED,
            "Güvenli oturum kuruldu"
        )
        handshakeWaiters.remove(peerAddress)?.complete(true)
        retryPendingMessagesForPeer(session.peerDeviceId)
    }

    private suspend fun bindVerifiedPeerSession(peerAddress: String, session: SecureSession) {
        addressToDeviceId[peerAddress] = session.peerDeviceId
        deviceIdToAddress[session.peerDeviceId] = peerAddress
        sessionsByDeviceId[session.peerDeviceId] = session
        sessionStatesByAddress[peerAddress] = SessionState.ESTABLISHED

        val existingDiscovered = discoveredByAddress[peerAddress]
        val pubB64 = android.util.Base64.encodeToString(session.peerIdentityPubKey, android.util.Base64.NO_WRAP)
        val label = existingDiscovered?.displayName ?: "OmniMesh-${session.peerFingerprint.take(6)}"
        localRepository.saveVerifiedPeer(
            PeerEntity(
                deviceId = session.peerDeviceId,
                displayName = label,
                identityPublicKeyB64 = pubB64,
                publicKeyFingerprint = session.peerFingerprint,
                bleAddress = peerAddress,
                lastSeenAt = clock(),
                lastRssi = existingDiscovered?.rssi ?: -60
            )
        )
    }

    private suspend fun handleIncomingDataEnvelope(peerAddress: String, body: ByteArray) {
        val envelope = MessageEnvelope.deserialize(body) ?: run {
            _engineState.update { it.copy(lastSecurityNotice = "INVALID_MESSAGE: Bozuk paket formatı") }
            return
        }

        when (val decision = forwardingEngine.processIncoming(envelope, incomingPeerAddress = peerAddress)) {
            is ForwardingDecision.LocalDelivery -> {
                val devId = addressToDeviceId[peerAddress]
                val session = sessionsByDeviceId[decision.envelope.senderDeviceId]
                    ?: devId?.let { sessionsByDeviceId[it] }
                    ?: run {
                        _engineState.update {
                            it.copy(lastSecurityNotice = "INVALID_MESSAGE: Aktif güvenli oturum yok")
                        }
                        return
                    }

                when (val result = crypto.decryptAndVerifyEnvelope(session, decision.envelope, localRepository)) {
                    is EnvelopeVerificationResult.Valid -> {
                        forwardingEngine.markLocalDelivered(decision.envelope.messageId)
                        val text = String(result.plaintext, StandardCharsets.UTF_8)
                        localRepository.saveIncomingMessage(
                            messageId = decision.envelope.messageId,
                            senderDeviceId = decision.envelope.senderDeviceId,
                            receiverDeviceId = decision.envelope.destinationDeviceId,
                            plaintext = text,
                            timestamp = decision.envelope.createdAt
                        )
                        sendEncryptedDeliveryAck(peerAddress, session, decision.envelope.messageId)
                        _engineState.update { it.copy(statusMessage = "Yeni şifreli mesaj alındı") }
                    }
                    is EnvelopeVerificationResult.InvalidMessage -> {
                        _engineState.update {
                            it.copy(lastSecurityNotice = "INVALID_MESSAGE: ${result.reason}")
                        }
                    }
                    is EnvelopeVerificationResult.ReplayDetected -> {
                        _engineState.update {
                            it.copy(lastSecurityNotice = "REPLAY_DETECTED: Tekrarlanan mesaj reddedildi")
                        }
                    }
                    is EnvelopeVerificationResult.Expired -> {
                        localRepository.updateMessageState(result.messageId, MessageDeliveryState.EXPIRED, "Zaman aşımı")
                        _engineState.update {
                            it.copy(lastSecurityNotice = "EXPIRED: Süresi dolmuş paket reddedildi")
                        }
                    }
                }
            }
            is ForwardingDecision.Forward -> {
                if (decision.targetAddresses.isNotEmpty()) {
                    val wireForward = byteArrayOf(FRAME_DATA_ENVELOPE) + MessageEnvelope.serialize(decision.updatedEnvelope)
                    for (targetAddr in decision.targetAddresses) {
                        primaryTransport.send(targetAddr, wireForward)
                    }
                }
            }
            is ForwardingDecision.Drop -> {
                val notice = when (decision.reason) {
                    DropReason.INVALID_MESSAGE -> "INVALID_MESSAGE: ${decision.detail}"
                    DropReason.EXPIRED_TIMESTAMP -> "EXPIRED: ${decision.detail}"
                    DropReason.DUPLICATE_MESSAGE -> "REPLAY_DETECTED: ${decision.detail}"
                    DropReason.TTL_EXPIRED -> "TTL_EXPIRED: ${decision.detail}"
                }
                _engineState.update { it.copy(lastSecurityNotice = notice) }
            }
        }
    }

    private suspend fun sendEncryptedDeliveryAck(
        peerAddress: String,
        session: SecureSession,
        ackedMessageId: String
    ) {
        val ackEnvelope = crypto.encryptEnvelope(
            session = session,
            plaintext = ackedMessageId.toByteArray(StandardCharsets.UTF_8),
            createdAt = clock()
        )
        val wireAck = byteArrayOf(FRAME_ACK_ENVELOPE) + MessageEnvelope.serialize(ackEnvelope)
        primaryTransport.send(peerAddress, wireAck)
    }

    private suspend fun handleIncomingAckEnvelope(peerAddress: String, body: ByteArray) {
        val envelope = MessageEnvelope.deserialize(body) ?: return
        val devId = addressToDeviceId[peerAddress] ?: return
        val session = sessionsByDeviceId[devId] ?: return

        when (val result = crypto.decryptAndVerifyEnvelope(session, envelope, localRepository)) {
            is EnvelopeVerificationResult.Valid -> {
                val ackedMsgId = String(result.plaintext, StandardCharsets.UTF_8)
                localRepository.updateMessageState(ackedMsgId, MessageDeliveryState.DELIVERED)
                _engineState.update { it.copy(statusMessage = "İletildi") }
            }
            else -> {
                _engineState.update {
                    it.copy(lastSecurityNotice = "Geçersiz teslimat onayı reddedildi")
                }
            }
        }
    }

    private fun updatePeerStatus(
        address: String,
        status: PeerConnectionStatus,
        sessionState: SessionState,
        detail: String?
    ) {
        discoveredByAddress.computeIfPresent(address) { _, p -> p.copy(status = status) }
        sessionStatesByAddress[address] = sessionState
        rebuildPeerList(statusOverrideAddress = address, statusOverride = status, detailOverride = detail)
    }

    private fun rebuildPeerList(
        statusOverrideAddress: String? = null,
        statusOverride: PeerConnectionStatus? = null,
        detailOverride: String? = null
    ) {
        val combined = linkedMapOf<String, PeerUiModel>()

        for ((addr, discovered) in discoveredByAddress) {
            val devId = addressToDeviceId[addr]
            val sessState = sessionStatesByAddress[addr] ?: SessionState.UNINITIALIZED
            val status = if (addr == statusOverrideAddress && statusOverride != null) {
                statusOverride
            } else if (sessState == SessionState.ESTABLISHED) {
                PeerConnectionStatus.SECURE_SESSION_ESTABLISHED
            } else {
                discovered.status
            }
            val detail = if (addr == statusOverrideAddress && detailOverride != null) {
                detailOverride
            } else {
                statusToTurkish(status)
            }
            combined[addr] = PeerUiModel(
                address = addr,
                deviceId = devId,
                displayName = discovered.displayName,
                fingerprint = devId?.let { sessionsByDeviceId[it]?.peerFingerprint },
                rssi = discovered.rssi,
                transportType = discovered.transportType,
                connectionStatus = status,
                sessionState = sessState,
                statusDetail = detail
            )
        }

        _engineState.update { state ->
            state.copy(peers = combined.values.sortedByDescending { it.rssi })
        }
    }

    fun getSessionStateForPeer(address: String?, deviceId: String?): SessionState {
        val resolvedAddr = address?.uppercase() ?: deviceId?.let { deviceIdToAddress[it] }
        return if (deviceId != null && sessionsByDeviceId.containsKey(deviceId)) {
            SessionState.ESTABLISHED
        } else if (resolvedAddr != null) {
            sessionStatesByAddress[resolvedAddr] ?: SessionState.UNINITIALIZED
        } else {
            SessionState.UNINITIALIZED
        }
    }

    fun release() {
        primaryTransport.release()
        scope.cancel()
    }

    companion object {
        const val FRAME_HANDSHAKE_INIT: Byte = 0x01
        const val FRAME_HANDSHAKE_ACK: Byte = 0x02
        const val FRAME_DATA_ENVELOPE: Byte = 0x03
        const val FRAME_ACK_ENVELOPE: Byte = 0x04

        private const val HANDSHAKE_TIMEOUT_MS = 10_000L

        fun statusToTurkish(status: PeerConnectionStatus): String = when (status) {
            PeerConnectionStatus.DISCOVERED -> "Cihaz bulundu"
            PeerConnectionStatus.CONNECTING -> "Bağlanıyor"
            PeerConnectionStatus.CONNECTED -> "Bağlandı"
            PeerConnectionStatus.SECURE_SESSION_ESTABLISHED -> "Güvenli oturum kuruldu"
            PeerConnectionStatus.DISCONNECTED -> "Bağlantı kesildi"
            PeerConnectionStatus.FAILED -> "Başarısız"
        }

        fun capabilityToTurkish(status: CapabilityStatus): String = when (status) {
            CapabilityStatus.SUPPORTED -> "Hazır"
            CapabilityStatus.UNSUPPORTED -> "Desteklenmiyor"
            CapabilityStatus.DISABLED -> "Kapalı"
            CapabilityStatus.PERMISSION_REQUIRED -> "İzin Gerekli"
        }
    }
}
