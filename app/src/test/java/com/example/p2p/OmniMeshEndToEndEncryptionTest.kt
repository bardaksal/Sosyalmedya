package com.example.p2p

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.p2p.crypto.EnvelopeVerificationResult
import com.example.p2p.crypto.InMemoryReplayStore
import com.example.p2p.crypto.MessageEnvelope
import com.example.p2p.crypto.OmniMeshCrypto
import com.example.p2p.crypto.PayloadEncryptionMode
import com.example.p2p.crypto.SecureSession
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 2.2 End-to-End Message Encryption test suite:
 * - Multi-hop A -> B -> C integration over 20-byte ATT MTU chunked TestMeshTransport
 * - Cryptographic proof relay B cannot read plaintext or derive E2E key
 * - All 9 negative security scenarios + impersonation + mode downgrade protection
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OmniMeshEndToEndEncryptionTest {

    private lateinit var baseContext: Context
    private lateinit var dbA: OmniMeshDatabase
    private lateinit var dbB: OmniMeshDatabase
    private lateinit var dbC: OmniMeshDatabase
    private lateinit var idA: DeviceIdentityManager
    private lateinit var idB: DeviceIdentityManager
    private lateinit var idC: DeviceIdentityManager
    private lateinit var idD: DeviceIdentityManager
    private lateinit var engineA: OmniMeshP2pEngine
    private lateinit var engineB: OmniMeshP2pEngine
    private lateinit var engineC: OmniMeshP2pEngine
    private lateinit var transportA: TestMeshPipeTransport
    private lateinit var transportB: TestMeshPipeTransport
    private lateinit var transportC: TestMeshPipeTransport

    private val addrA = "AA:00:00:00:00:01"
    private val addrB = "BB:00:00:00:00:02"
    private val addrC = "CC:00:00:00:00:03"
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
        dbC = Room.inMemoryDatabaseBuilder(baseContext, OmniMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        idA = DeviceIdentityManager(scopedContext("e2e_phase22_a"))
        idB = DeviceIdentityManager(scopedContext("e2e_phase22_b"))
        idC = DeviceIdentityManager(scopedContext("e2e_phase22_c"))
        idD = DeviceIdentityManager(scopedContext("e2e_phase22_d"))
        val detector = CapabilityDetector(baseContext)

        // Topology: A <-> B <-> C (A and C have NO direct link and NO shared SecureSession)
        transportA = TestMeshPipeTransport(addrA)
        transportB = TestMeshPipeTransport(addrB)
        transportC = TestMeshPipeTransport(addrC)
        transportA.addLink(addrB, transportB)
        transportB.addLink(addrA, transportA)
        transportB.addLink(addrC, transportC)
        transportC.addLink(addrB, transportB)

        val trustedKeys = mapOf(
            idA.deviceId to idA.getPublicKeyBytes(),
            idC.deviceId to idC.getPublicKeyBytes()
        )

        engineA = OmniMeshP2pEngine(
            identityManager = idA,
            crypto = OmniMeshCrypto(idA, clock = { clockNow }),
            primaryTransport = transportA,
            localRepository = OmniMeshLocalRepository(dbA.omniMeshDao(), clock = { clockNow }),
            capabilityDetector = detector,
            clock = { clockNow },
            peerIdentityKeyResolver = { trustedKeys[it] }
        )
        // Relay B intentionally has NO peerIdentityKeyResolver and never needs A or C's keys
        engineB = OmniMeshP2pEngine(
            identityManager = idB,
            crypto = OmniMeshCrypto(idB, clock = { clockNow }),
            primaryTransport = transportB,
            localRepository = OmniMeshLocalRepository(dbB.omniMeshDao(), clock = { clockNow }),
            capabilityDetector = detector,
            clock = { clockNow }
        )
        engineC = OmniMeshP2pEngine(
            identityManager = idC,
            crypto = OmniMeshCrypto(idC, clock = { clockNow }),
            primaryTransport = transportC,
            localRepository = OmniMeshLocalRepository(dbC.omniMeshDao(), clock = { clockNow }),
            capabilityDetector = detector,
            clock = { clockNow },
            peerIdentityKeyResolver = { trustedKeys[it] }
        )
    }

    @After
    fun tearDown() {
        engineA.release()
        engineB.release()
        engineC.release()
        dbA.close()
        dbB.close()
        dbC.close()
    }

    @Test
    fun multiHopAtoBtoC_endToEndEncrypted_relayForwardsWithoutPlaintextAndCDecrypts() = runBlocking {
        // Establish hop-by-hop link sessions: A <-> B and B <-> C (NOT A <-> C)
        val hsAB = engineA.connectAndEstablishSession(addrB)
        val hsBC = engineB.connectAndEstablishSession(addrC)
        assertTrue(hsAB is TransportResult.Success)
        assertTrue(hsBC is TransportResult.Success)
        val sessionBtoA = (hsAB as TransportResult.Success).value
        val sessionBtoC = (hsBC as TransportResult.Success).value

        // Observe every envelope arriving at B and forwarded by B
        val seenAtB = mutableListOf<MessageEnvelope>()
        val forwardedByB = mutableListOf<MessageEnvelope>()
        transportB.incomingInterceptor = { _, frame ->
            if (frame.isNotEmpty() && frame[0] == OmniMeshP2pEngine.FRAME_DATA_ENVELOPE) {
                MessageEnvelope.deserialize(frame.copyOfRange(1, frame.size))?.let { seenAtB.add(it) }
            }
            frame
        }
        transportB.outgoingInterceptor = { _, frame ->
            if (frame.isNotEmpty() && frame[0] == OmniMeshP2pEngine.FRAME_DATA_ENVELOPE) {
                MessageEnvelope.deserialize(frame.copyOfRange(1, frame.size))?.let { forwardedByB.add(it) }
            }
            frame
        }

        val secretMessage = "Çok gizli uçtan uca mesaj A -> B -> C"
        val sendRes = engineA.sendEndToEndMessage(
            destinationDeviceId = idC.deviceId,
            plaintext = secretMessage,
            nextHopAddress = addrB,
            ttl = 5
        )
        assertTrue("E2E send should succeed: $sendRes", sendRes is TransportResult.Success)

        waitUntil {
            engineC.allMessagesFlow.first().any { it.plaintext == secretMessage }
        }

        // Verify C received and decrypted the exact plaintext in Room
        val msgsC = engineC.allMessagesFlow.first()
        assertEquals(1, msgsC.size)
        assertEquals(secretMessage, msgsC.first().plaintext)
        assertEquals(idA.deviceId, msgsC.first().senderDeviceId)
        assertEquals(idC.deviceId, msgsC.first().receiverDeviceId)
        assertEquals(MessageDirection.INCOMING.name, msgsC.first().direction)

        // Verify relay B stored ZERO incoming plaintext messages
        assertTrue("Relay B must never store relayed plaintext", engineB.allMessagesFlow.first().isEmpty())

        // Verify B inspected metadata only and updated TTL=4, hopCount=1 without touching ciphertext
        assertEquals(1, seenAtB.size)
        assertEquals(1, forwardedByB.size)
        val envAtB = seenAtB.single()
        val envAfterB = forwardedByB.single()
        assertEquals(5, envAtB.ttl)
        assertEquals(0, envAtB.hopCount)
        assertEquals(4, envAfterB.ttl)
        assertEquals(1, envAfterB.hopCount)
        assertEquals(PayloadEncryptionMode.END_TO_END, envAtB.encryptionMode)
        assertArrayEquals(envAtB.ciphertext, envAfterB.ciphertext)
        assertArrayEquals(envAtB.authenticationTag, envAfterB.authenticationTag)
        assertArrayEquals(envAtB.nonce, envAfterB.nonce)
        assertArrayEquals(envAtB.senderEphemeralPubKey, envAfterB.senderEphemeralPubKey)

        // Verify plaintext UTF-8 bytes do NOT appear in serialized wire payload
        val wireBytes = MessageEnvelope.serialize(envAtB)
        assertFalse(wireBytes.containsSubarray(secretMessage.toByteArray(StandardCharsets.UTF_8)))

        // Cryptographic proof: Relay B CANNOT decrypt envAtB with B's crypto, session(A<->B), or session(B<->C)
        val cryptoB = OmniMeshCrypto(idB, clock = { clockNow })
        val bE2eAttempt = cryptoB.decryptAndVerifyE2eEnvelope(envAtB, idA.getPublicKeyBytes(), InMemoryReplayStore())
        assertTrue(bE2eAttempt is EnvelopeVerificationResult.InvalidMessage)

        val bSessionAttempt = cryptoB.decryptAndVerifyEnvelope(sessionBtoA, envAtB, InMemoryReplayStore())
        assertTrue(bSessionAttempt is EnvelopeVerificationResult.InvalidMessage)

        assertRawAesGcmFails(sessionBtoA.txKey, envAtB)
        assertRawAesGcmFails(sessionBtoA.rxKey, envAtB)
        assertRawAesGcmFails(sessionBtoC.txKey, envAtB)
        assertRawAesGcmFails(sessionBtoC.rxKey, envAtB)
    }

    @Test
    fun relayTampersCiphertextBeforeForwarding_destinationCRejectsAndStoresNothing() = runBlocking {
        assertTrue(engineA.connectAndEstablishSession(addrB) is TransportResult.Success)
        assertTrue(engineB.connectAndEstablishSession(addrC) is TransportResult.Success)

        // Negative Test 1: Malicious relay B flips a byte in ciphertext before forwarding to C
        transportB.outgoingInterceptor = { _, frame ->
            if (frame.isNotEmpty() && frame[0] == OmniMeshP2pEngine.FRAME_DATA_ENVELOPE) {
                val env = MessageEnvelope.deserialize(frame.copyOfRange(1, frame.size))!!
                val tamperedCipher = env.ciphertext.copyOf().apply {
                    this[0] = (this[0].toInt() xor 0xFF).toByte()
                }
                val tamperedEnv = env.copy(
                    encryptedPayload = env.encryptedPayload.copy(ciphertext = tamperedCipher)
                )
                byteArrayOf(OmniMeshP2pEngine.FRAME_DATA_ENVELOPE) + MessageEnvelope.serialize(tamperedEnv)
            } else {
                frame
            }
        }

        val sendRes = engineA.sendEndToEndMessage(
            destinationDeviceId = idC.deviceId,
            plaintext = "Bozulacak E2E mesajı",
            nextHopAddress = addrB
        )
        assertTrue(sendRes is TransportResult.Success)

        waitUntil {
            engineC.engineState.value.lastSecurityNotice?.contains("INVALID_MESSAGE") == true
        }
        assertTrue("Tampered message must not be saved at C", engineC.allMessagesFlow.first().isEmpty())
    }

    @Test
    fun relayModifiesDestinationId_destinationOrRelayRejectsCleanly() = runBlocking {
        assertTrue(engineA.connectAndEstablishSession(addrB) is TransportResult.Success)
        assertTrue(engineB.connectAndEstablishSession(addrC) is TransportResult.Success)

        // Even if someone swaps destinationDeviceId to an arbitrary wrong target, GCM AAD rejects at C
        val cryptoA = OmniMeshCrypto(idA, clock = { clockNow })
        val cryptoC = OmniMeshCrypto(idC, clock = { clockNow })
        val env = cryptoA.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = "Hedef bağlama testi".toByteArray(StandardCharsets.UTF_8)
        )
        val wrongDest = env.copy(
            routingMetadata = env.routingMetadata.copy(destinationDeviceId = idD.deviceId)
        )
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(wrongDest, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )
    }

    @Test
    fun allNineNegativeSecurityTests_andImpersonationAndModeDowngradeRejected() = runTest {
        val cryptoA = OmniMeshCrypto(idA, clock = { clockNow })
        val cryptoB = OmniMeshCrypto(idB, clock = { clockNow })
        val cryptoC = OmniMeshCrypto(idC, clock = { clockNow })
        val cryptoD = OmniMeshCrypto(idD, clock = { clockNow })
        val replayStoreC = InMemoryReplayStore()

        // Establish a real transport session B <-> A so test #8 uses real session key from B
        val initBA = cryptoB.createHandshakeInit()
        val (ackAB, sessionAtoB) = cryptoA.processHandshakeInitAndCreateAck(initBA.initPacket)!!
        val sessionBtoA: SecureSession = cryptoB.completeClientHandshake(initBA, ackAB)!!

        val plaintext = "Doğrulanmış E2E Gizli Yük"
        val env = cryptoA.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = plaintext.toByteArray(StandardCharsets.UTF_8),
            ttl = 5
        )
        // Simulate relay hop B advancing TTL/hopCount before arriving at C
        val forwardedEnv = env.advanceForForward()
        assertEquals(4, forwardedEnv.ttl)
        assertEquals(1, forwardedEnv.hopCount)

        // 1. Relay tampers ciphertext
        val relayTamperedCipher = forwardedEnv.copy(
            encryptedPayload = forwardedEnv.encryptedPayload.copy(
                ciphertext = forwardedEnv.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
            )
        )
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(relayTamperedCipher, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )

        // 2. Single bit flip in ciphertext
        val bitFlipCipher = forwardedEnv.copy(
            encryptedPayload = forwardedEnv.encryptedPayload.copy(
                ciphertext = forwardedEnv.ciphertext.copyOf().apply {
                    this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte()
                }
            )
        )
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(bitFlipCipher, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )

        // 3. Nonce modified
        val badNonce = forwardedEnv.copy(
            encryptedPayload = forwardedEnv.encryptedPayload.copy(
                nonce = forwardedEnv.nonce.copyOf().apply { this[0] = (this[0].toInt() xor 0x40).toByte() }
            )
        )
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(badNonce, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )

        // 4. Auth tag modified
        val badTag = forwardedEnv.copy(
            encryptedPayload = forwardedEnv.encryptedPayload.copy(
                authenticationTag = forwardedEnv.authenticationTag.copyOf().apply { this[0] = (this[0].toInt() xor 0x02).toByte() }
            )
        )
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(badTag, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )

        // 5. destinationDeviceId modified
        val badDest = forwardedEnv.copy(
            routingMetadata = forwardedEnv.routingMetadata.copy(destinationDeviceId = idB.deviceId)
        )
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(badDest, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )

        // 6. senderDeviceId modified
        val badSender = forwardedEnv.copy(
            routingMetadata = forwardedEnv.routingMetadata.copy(senderDeviceId = idB.deviceId)
        )
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(badSender, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )

        // Valid decryption works FIRST, then:
        val valid = cryptoC.decryptAndVerifyE2eEnvelope(forwardedEnv, idA.getPublicKeyBytes(), replayStoreC)
        assertTrue(valid is EnvelopeVerificationResult.Valid)
        assertEquals(plaintext, String((valid as EnvelopeVerificationResult.Valid).plaintext, StandardCharsets.UTF_8))

        // 7. Replay same message rejected
        val replay = cryptoC.decryptAndVerifyE2eEnvelope(forwardedEnv, idA.getPublicKeyBytes(), replayStoreC)
        assertTrue(replay is EnvelopeVerificationResult.ReplayDetected)

        // 8. Relay B tries to decrypt with its own transport session key (A <-> B) -> fails
        assertTrue(
            cryptoB.decryptAndVerifyEnvelope(sessionBtoA, forwardedEnv, InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )
        assertTrue(
            cryptoB.decryptAndVerifyEnvelope(sessionAtoB, forwardedEnv, InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )
        assertRawAesGcmFails(sessionBtoA.rxKey, forwardedEnv)
        assertRawAesGcmFails(sessionBtoA.txKey, forwardedEnv)
        assertRelayEcdhAlsoFails(idB, idA.getPublicKeyBytes(), forwardedEnv)

        // 9. Wrong destination device D tries to decrypt C's E2E envelope -> fails
        assertTrue(
            cryptoD.decryptAndVerifyE2eEnvelope(forwardedEnv, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )
        val coercedToD = forwardedEnv.copy(
            routingMetadata = forwardedEnv.routingMetadata.copy(destinationDeviceId = idD.deviceId)
        )
        assertTrue(
            cryptoD.decryptAndVerifyE2eEnvelope(coercedToD, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )

        // Bonus A: Attacker D impersonating A to C fails because D lacks A's private identity key
        val forgedAsA = cryptoD.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = "Sahte A mesajı".toByteArray(StandardCharsets.UTF_8)
        ).let { e -> e.copy(routingMetadata = e.routingMetadata.copy(senderDeviceId = idA.deviceId)) }
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(forgedAsA, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )

        // Bonus B: Wire serialization roundtrip preserves END_TO_END + ephemeral key,
        // and mode downgrade tampering fails
        val serialized = MessageEnvelope.serialize(forwardedEnv)
        val deserialized = MessageEnvelope.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(forwardedEnv, deserialized)

        val downgradedMode = forwardedEnv.copy(
            encryptedPayload = forwardedEnv.encryptedPayload.copy(
                encryptionMode = PayloadEncryptionMode.TRANSPORT_SESSION
            )
        )
        assertTrue(
            cryptoC.decryptAndVerifyE2eEnvelope(downgradedMode, idA.getPublicKeyBytes(), InMemoryReplayStore())
                is EnvelopeVerificationResult.InvalidMessage
        )
    }

    @Test
    fun perMessageKeyIsolation_samePlaintextProducesDistinctEphemeralKeysAndCiphertexts() {
        val cryptoA = OmniMeshCrypto(idA, clock = { clockNow })
        val msg = "Aynı mesaj iki kez".toByteArray(StandardCharsets.UTF_8)

        val e1 = cryptoA.encryptEndToEndEnvelope(idC.deviceId, idC.getPublicKeyBytes(), msg)
        val e2 = cryptoA.encryptEndToEndEnvelope(idC.deviceId, idC.getPublicKeyBytes(), msg)

        assertNotEquals(e1.messageId, e2.messageId)
        assertFalse(e1.senderEphemeralPubKey.contentEquals(e2.senderEphemeralPubKey))
        assertFalse(e1.nonce.contentEquals(e2.nonce))
        assertFalse(e1.ciphertext.contentEquals(e2.ciphertext))
    }

    private fun assertRawAesGcmFails(key: javax.crypto.SecretKey, envelope: MessageEnvelope) {
        try {
            val combined = ByteArray(envelope.ciphertext.size + envelope.authenticationTag.size)
            System.arraycopy(envelope.ciphertext, 0, combined, 0, envelope.ciphertext.size)
            System.arraycopy(
                envelope.authenticationTag,
                0,
                combined,
                envelope.ciphertext.size,
                envelope.authenticationTag.size
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.nonce))
            cipher.updateAAD(envelope.buildAdditionalAuthenticatedData())
            cipher.doFinal(combined)
            fail("Relay key must NOT decrypt E2E ciphertext")
        } catch (_: AEADBadTagException) {
            // Expected cryptographic rejection
        }
    }

    private fun assertRelayEcdhAlsoFails(
        relayIdentity: DeviceIdentityManager,
        senderPubKey: ByteArray,
        envelope: MessageEnvelope
    ) {
        val relayDhEph = relayIdentity.performIdentityEcdh(envelope.senderEphemeralPubKey)!!
        val relayDhStatic = relayIdentity.performIdentityEcdh(senderPubKey)!!
        val ikm = relayDhEph + relayDhStatic
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(envelope.nonce, "HmacSHA256"))
        }
        val prk = mac.doFinal(ikm)
        val infoLabel = "OMNIMESH_E2E_MSG_V1".toByteArray(StandardCharsets.UTF_8)
        val msgBytes = envelope.messageId.toByteArray(StandardCharsets.UTF_8)
        val senderBytes = envelope.senderDeviceId.toByteArray(StandardCharsets.UTF_8)
        val destBytes = envelope.destinationDeviceId.toByteArray(StandardCharsets.UTF_8)
        val infoBuf = ByteBuffer.allocate(
            4 + infoLabel.size + 4 + 8 +
                4 + msgBytes.size + 4 + senderBytes.size + 4 + destBytes.size + 4 + envelope.senderEphemeralPubKey.size
        ).apply {
            putInt(infoLabel.size); put(infoLabel)
            putInt(envelope.protocolVersion)
            putLong(envelope.createdAt)
            putInt(msgBytes.size); put(msgBytes)
            putInt(senderBytes.size); put(senderBytes)
            putInt(destBytes.size); put(destBytes)
            putInt(envelope.senderEphemeralPubKey.size); put(envelope.senderEphemeralPubKey)
        }.array()
        val expandMac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(infoBuf)
            update(1.toByte())
        }
        val wrongKey = SecretKeySpec(expandMac.doFinal(), "AES")
        assertRawAesGcmFails(wrongKey, envelope)
    }

    private fun ByteArray.containsSubarray(sub: ByteArray): Boolean {
        if (sub.isEmpty() || sub.size > size) return false
        outer@ for (i in 0..(size - sub.size)) {
            for (j in sub.indices) {
                if (this[i + j] != sub[j]) continue@outer
            }
            return true
        }
        return false
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
     * Multi-link test transport emulating BLE 20-byte ATT MTU chunking across multiple peers (e.g. A <-> B <-> C).
     */
    private class TestMeshPipeTransport(
        private val localAddress: String
    ) : P2pTransport {
        override val type: TransportType = TransportType.BLE
        private val _events = MutableSharedFlow<TransportEvent>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val events: Flow<TransportEvent> = _events.asSharedFlow()
        private val _incoming = MutableSharedFlow<IncomingTransportFrame>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        private val links = ConcurrentHashMap<String, TestMeshPipeTransport>()
        private val connectedPeers = ConcurrentHashMap.newKeySet<String>()
        private val reassembler = BleFrameReassembler()

        var incomingInterceptor: ((String, ByteArray) -> ByteArray)? = null
        var outgoingInterceptor: ((String, ByteArray) -> ByteArray)? = null

        fun addLink(peerAddress: String, peerTransport: TestMeshPipeTransport) {
            links[peerAddress.uppercase()] = peerTransport
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
            val addr = peer.address.uppercase()
            val remote = links[addr] ?: return TransportResult.Failure("Link yok: $addr")
            connectedPeers.add(addr)
            remote.connectedPeers.add(localAddress.uppercase())
            _events.tryEmit(TransportEvent.ConnectionStateChanged(addr, PeerConnectionStatus.CONNECTED))
            return TransportResult.Success(Unit)
        }

        override suspend fun disconnect(peerAddress: String) {
            val addr = peerAddress.uppercase()
            connectedPeers.remove(addr)
            links[addr]?.connectedPeers?.remove(localAddress.uppercase())
        }

        override suspend fun send(peerAddress: String, payload: ByteArray): TransportResult<Unit> {
            val addr = peerAddress.uppercase()
            val remote = links[addr] ?: return TransportResult.Failure("Uzak uç yok: $addr")
            if (!connectedPeers.contains(addr)) {
                return TransportResult.Failure("Aktif bağlantı yok: $addr")
            }
            val effectiveOut = outgoingInterceptor?.invoke(addr, payload) ?: payload
            val chunks = BleFrameCodec.chunk(effectiveOut, BleFrameCodec.MIN_ATT_PAYLOAD)
            for (chunk in chunks) {
                val reassembled = remote.reassembler.onChunkReceived(localAddress.uppercase(), chunk)
                if (reassembled != null) {
                    val effectiveIn = remote.incomingInterceptor?.invoke(localAddress.uppercase(), reassembled) ?: reassembled
                    remote._incoming.tryEmit(
                        IncomingTransportFrame(
                            peerAddress = localAddress.uppercase(),
                            transportType = TransportType.BLE,
                            payload = effectiveIn
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
