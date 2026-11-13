package com.example.p2p

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.p2p.crypto.OmniMeshCrypto
import com.example.p2p.crypto.SessionState
import com.example.p2p.data.MessageDeliveryState
import com.example.p2p.data.MessageDirection
import com.example.p2p.data.OmniMeshDatabase
import com.example.p2p.data.OmniMeshLocalRepository
import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.transport.BleFrameCodec
import com.example.p2p.transport.BleFrameReassembler
import com.example.p2p.transport.CapabilityDetector
import com.example.p2p.transport.CapabilityStatus
import com.example.p2p.transport.IncomingTransportFrame
import com.example.p2p.transport.P2pTransport
import com.example.p2p.transport.PeerConnectionStatus
import com.example.p2p.transport.TransportEvent
import com.example.p2p.transport.TransportPeer
import com.example.p2p.transport.TransportResult
import com.example.p2p.transport.TransportType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end two-node P2P integration and security test suite (test-only byte-pipe transport
 * emulating BLE ATT 20-byte chunked frames between two completely independent device instances).
 * Firebase is never initialized or used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OmniMeshTwoNodeIntegrationTest {

    private lateinit var baseContext: Context
    private lateinit var dbA: OmniMeshDatabase
    private lateinit var dbB: OmniMeshDatabase
    private lateinit var engineA: OmniMeshP2pEngine
    private lateinit var engineB: OmniMeshP2pEngine
    private lateinit var transportA: TestPipeTransport
    private lateinit var transportB: TestPipeTransport

    private val addressA = "11:22:33:44:55:01"
    private val addressB = "11:22:33:44:55:02"
    private var clockNow = 1_700_000_000_000L

    private fun scopedContext(prefix: String): Context = object : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return baseContext.getSharedPreferences("${prefix}_$name", mode)
        }
    }

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
        dbA = Room.inMemoryDatabaseBuilder(baseContext, OmniMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dbB = Room.inMemoryDatabaseBuilder(baseContext, OmniMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val idA = DeviceIdentityManager(scopedContext("e2e_a"))
        val idB = DeviceIdentityManager(scopedContext("e2e_b"))
        val detector = CapabilityDetector(baseContext)

        transportA = TestPipeTransport(localAddress = addressA)
        transportB = TestPipeTransport(localAddress = addressB)
        transportA.wirePeer(transportB)
        transportB.wirePeer(transportA)

        engineA = OmniMeshP2pEngine(
            identityManager = idA,
            crypto = OmniMeshCrypto(idA, clock = { clockNow }),
            primaryTransport = transportA,
            localRepository = OmniMeshLocalRepository(dbA.omniMeshDao(), clock = { clockNow }),
            capabilityDetector = detector,
            clock = { clockNow }
        )
        engineB = OmniMeshP2pEngine(
            identityManager = idB,
            crypto = OmniMeshCrypto(idB, clock = { clockNow }),
            primaryTransport = transportB,
            localRepository = OmniMeshLocalRepository(dbB.omniMeshDao(), clock = { clockNow }),
            capabilityDetector = detector,
            clock = { clockNow }
        )
    }

    @After
    fun tearDown() {
        engineA.release()
        engineB.release()
        dbA.close()
        dbB.close()
    }

    @Test
    fun twoDevices_discoverHandshakeSendEncryptedMessageAndAckWithoutFirebase() = runBlocking {
        engineA.startDiscovery()
        engineB.startDiscovery()

        val peerB = TransportPeer(
            address = addressB,
            displayName = "Device-B",
            shortTagHex = "BB02",
            rssi = -48,
            transportType = TransportType.BLE
        )
        transportA.emitDiscovered(peerB)

        val hsResult = engineA.connectAndEstablishSession(addressB)
        assertTrue("Handshake should succeed: $hsResult", hsResult is TransportResult.Success)
        assertEquals(
            SessionState.ESTABLISHED,
            engineA.getSessionStateForPeer(addressB, null)
        )

        val sendResult = engineA.sendMessage(
            peerDeviceId = null,
            peerAddress = addressB,
            plaintext = "Merhaba OmniMesh"
        )
        assertTrue("Encrypted send should succeed", sendResult is TransportResult.Success)

        // Wait briefly for asynchronous Room persistence + ACK roundtrip
        waitUntil {
            val msgsA = engineA.allMessagesFlow.first()
            val msgsB = engineB.allMessagesFlow.first()
            msgsA.any { it.state == MessageDeliveryState.DELIVERED.name } &&
                msgsB.any { it.plaintext == "Merhaba OmniMesh" }
        }

        val msgsA = engineA.allMessagesFlow.first()
        val msgsB = engineB.allMessagesFlow.first()
        assertEquals(1, msgsA.size)
        assertEquals(MessageDirection.OUTGOING.name, msgsA.first().direction)
        assertEquals(MessageDeliveryState.DELIVERED.name, msgsA.first().state)

        assertEquals(1, msgsB.size)
        assertEquals(MessageDirection.INCOMING.name, msgsB.first().direction)
        assertEquals("Merhaba OmniMesh", msgsB.first().plaintext)
        assertEquals(MessageDeliveryState.DELIVERED.name, msgsB.first().state)
    }

    @Test
    fun securityMitm_tamperedCiphertextNonceReplayAndWrongReceiverRejected() = runBlocking {
        engineA.startDiscovery()
        engineB.startDiscovery()
        assertTrue(engineA.connectAndEstablishSession(addressB) is TransportResult.Success)

        // Capture a valid wire DATA_ENVELOPE frame from A -> B and test MitM mutations
        val captured = mutableListOf<ByteArray>()
        transportA.interceptor = { frame ->
            if (frame.isNotEmpty() && frame[0] == OmniMeshP2pEngine.FRAME_DATA_ENVELOPE) {
                captured.add(frame.copyOf())
            }
            frame
        }

        // First send valid message so we capture authentic encrypted wire frame
        assertTrue(engineA.sendMessage(null, addressB, "Orijinal Mesaj") is TransportResult.Success)
        waitUntil { engineB.allMessagesFlow.first().size == 1 }
        val validWireFrame = captured.single()

        // 1. Replay the exact captured wire frame -> B must reject with REPLAY_DETECTED
        transportA.injectRawFrameToPeer(validWireFrame)
        waitUntil {
            engineB.engineState.value.lastSecurityNotice?.contains("REPLAY_DETECTED") == true
        }
        assertEquals(1, engineB.allMessagesFlow.first().size)

        // 2. Tamper ciphertext byte on next send -> B must reject with INVALID_MESSAGE
        transportA.interceptor = { frame ->
            if (frame.isNotEmpty() && frame[0] == OmniMeshP2pEngine.FRAME_DATA_ENVELOPE) {
                frame.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte() }
            } else {
                frame
            }
        }
        assertTrue(engineA.sendMessage(null, addressB, "Kurcalanan Mesaj") is TransportResult.Success)
        waitUntil {
            engineB.engineState.value.lastSecurityNotice?.contains("INVALID_MESSAGE") == true
        }
        assertEquals(1, engineB.allMessagesFlow.first().size)

        // 3. Tamper nonce byte (nonce sits inside serialized envelope after header) -> rejected
        transportA.interceptor = { frame ->
            if (frame.isNotEmpty() && frame[0] == OmniMeshP2pEngine.FRAME_DATA_ENVELOPE) {
                val mutated = frame.copyOf()
                // Flip a byte near the tail of the envelope (tag/ciphertext/nonce area)
                val idx = (mutated.size - 20).coerceAtLeast(1)
                mutated[idx] = (mutated[idx].toInt() xor 0xAA).toByte()
                mutated
            } else {
                frame
            }
        }
        assertTrue(engineA.sendMessage(null, addressB, "Nonce Bozulan Mesaj") is TransportResult.Success)
        delay(120)
        assertEquals(1, engineB.allMessagesFlow.first().size)
    }

    @Test
    fun connectionDropAndReconnect_recoversQueuedMessageWithoutCrashing() = runBlocking {
        engineA.startDiscovery()
        engineB.startDiscovery()
        val hs = engineA.connectAndEstablishSession(addressB) as TransportResult.Success
        val peerBDeviceId = hs.value.peerDeviceId

        // Simulate link drop before sending
        transportA.simulateConnectionDrop(addressB)
        waitUntil {
            engineA.getSessionStateForPeer(addressB, peerBDeviceId) != SessionState.ESTABLISHED
        }

        // Send fails safely while disconnected
        transportA.failNextSend = true
        val failedSend = engineA.sendMessage(peerBDeviceId, addressB, "Kesinti Sonrası İleti")
        assertTrue(failedSend is TransportResult.Failure)
        val storedAfterFail = engineA.allMessagesFlow.first()
        assertEquals(1, storedAfterFail.size)
        assertEquals(MessageDeliveryState.FAILED.name, storedAfterFail.first().state)

        // Reconnect -> handshake re-established -> automatic pending flush delivers queued message!
        transportA.failNextSend = false
        val reconnect = engineA.connectAndEstablishSession(addressB)
        assertTrue(reconnect is TransportResult.Success)

        waitUntil {
            val msgsA = engineA.allMessagesFlow.first()
            val msgsB = engineB.allMessagesFlow.first()
            msgsA.any { it.state == MessageDeliveryState.DELIVERED.name } &&
                msgsB.any { it.plaintext == "Kesinti Sonrası İleti" }
        }

        assertEquals(
            MessageDeliveryState.DELIVERED.name,
            engineA.allMessagesFlow.first().first().state
        )
        assertEquals("Kesinti Sonrası İleti", engineB.allMessagesFlow.first().first().plaintext)
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000L, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(25)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", predicate())
    }

    /**
     * Test-only byte-pipe transport that runs payloads through the real BleFrameCodec
     * at minimum 20-byte ATT MTU so chunking & reassembly are exercised end-to-end.
     */
    private class TestPipeTransport(
        private val localAddress: String
    ) : P2pTransport {
        override val type: TransportType = TransportType.BLE
        private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val events: Flow<TransportEvent> = _events.asSharedFlow()
        private val _incoming = MutableSharedFlow<IncomingTransportFrame>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        private val reassembler = BleFrameReassembler()
        private var remote: TestPipeTransport? = null
        private val connectedPeers = mutableSetOf<String>()

        var failNextSend: Boolean = false
        var interceptor: ((ByteArray) -> ByteArray)? = null

        fun wirePeer(peer: TestPipeTransport) {
            remote = peer
        }

        fun emitDiscovered(peer: TransportPeer) {
            _events.tryEmit(TransportEvent.PeerDiscovered(peer))
        }

        fun simulateConnectionDrop(peerAddress: String) {
            connectedPeers.remove(peerAddress.uppercase())
            remote?.connectedPeers?.remove(localAddress.uppercase())
            _events.tryEmit(TransportEvent.ConnectionStateChanged(peerAddress.uppercase(), PeerConnectionStatus.DISCONNECTED))
            remote?._events?.tryEmit(TransportEvent.ConnectionStateChanged(localAddress.uppercase(), PeerConnectionStatus.DISCONNECTED))
        }

        fun injectRawFrameToPeer(rawFrame: ByteArray) {
            val target = remote ?: return
            val chunks = BleFrameCodec.chunk(rawFrame, BleFrameCodec.MIN_ATT_PAYLOAD)
            chunks.forEach { chunk ->
                val full = target.reassembler.onChunkReceived(localAddress, chunk)
                if (full != null) {
                    target._incoming.tryEmit(IncomingTransportFrame(localAddress, TransportType.BLE, full))
                }
            }
        }

        override fun getCapabilities(): CapabilityStatus = CapabilityStatus.SUPPORTED

        override suspend fun startDiscovery(): TransportResult<Unit> {
            _events.tryEmit(TransportEvent.DiscoveryStateChanged(TransportType.BLE, true, true, "Aktif"))
            return TransportResult.Success(Unit)
        }

        override suspend fun stopDiscovery() {
            _events.tryEmit(TransportEvent.DiscoveryStateChanged(TransportType.BLE, false, false, "Durduruldu"))
        }

        override suspend fun connect(peer: TransportPeer): TransportResult<Unit> {
            if (failNextSend) return TransportResult.Failure("Bağlantı simülasyon hatası")
            connectedPeers.add(peer.address.uppercase())
            remote?.connectedPeers?.add(localAddress.uppercase())
            _events.tryEmit(TransportEvent.ConnectionStateChanged(peer.address.uppercase(), PeerConnectionStatus.CONNECTED))
            return TransportResult.Success(Unit)
        }

        override suspend fun disconnect(peerAddress: String) {
            simulateConnectionDrop(peerAddress)
        }

        override suspend fun send(peerAddress: String, payload: ByteArray): TransportResult<Unit> {
            if (failNextSend) return TransportResult.Failure("İletim bağlantısı koptu")
            val target = remote ?: return TransportResult.Failure("Uzak uç yok")
            if (!connectedPeers.contains(peerAddress.uppercase())) {
                return TransportResult.Failure("Aktif bağlantı yok")
            }
            val effectivePayload = interceptor?.invoke(payload) ?: payload
            val chunks = BleFrameCodec.chunk(effectivePayload, BleFrameCodec.MIN_ATT_PAYLOAD)
            for (chunk in chunks) {
                val reassembled = target.reassembler.onChunkReceived(localAddress.uppercase(), chunk)
                if (reassembled != null) {
                    target._incoming.tryEmit(
                        IncomingTransportFrame(
                            peerAddress = localAddress.uppercase(),
                            transportType = TransportType.BLE,
                            payload = reassembled
                        )
                    )
                }
            }
            return TransportResult.Success(Unit)
        }

        override fun receive(): Flow<IncomingTransportFrame> = _incoming.asSharedFlow()

        override fun release() {
            connectedPeers.clear()
            reassembler.clearAll()
        }
    }
}
