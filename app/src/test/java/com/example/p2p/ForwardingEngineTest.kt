package com.example.p2p

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.p2p.crypto.EnvelopeVerificationResult
import com.example.p2p.crypto.InMemoryReplayStore
import com.example.p2p.crypto.MessageEnvelope
import com.example.p2p.crypto.OmniMeshCrypto
import com.example.p2p.forwarding.DropReason
import com.example.p2p.forwarding.ForwardingDecision
import com.example.p2p.forwarding.ForwardingEngine
import com.example.p2p.forwarding.InMemoryDuplicateMessageCache
import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.routing.DirectPeerNextHopResolver
import com.example.p2p.routing.NextHopResolution
import com.example.p2p.routing.NextHopResolver
import com.example.p2p.routing.PendingForwardSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ForwardingEngineTest {

    private lateinit var baseContext: Context
    private val nowMs = 1_700_000_000_000L

    private fun scopedContext(prefix: String): Context = object : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return baseContext.getSharedPreferences("${prefix}_$name", mode)
        }
    }

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
    }

    private fun buildTestEnvelope(
        messageId: String = MessageEnvelope.newMessageId(),
        senderDeviceId: String = "device-A",
        destinationDeviceId: String = "device-C",
        ttl: Int = 5,
        hopCount: Int = 0,
        protocolVersion: Int = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
        createdAt: Long = nowMs,
        nonce: ByteArray = ByteArray(12) { 0x11 },
        ciphertext: ByteArray = "opaque-ciphertext".toByteArray(StandardCharsets.UTF_8),
        tag: ByteArray = ByteArray(16) { 0x22 }
    ): MessageEnvelope = MessageEnvelope.create(
        messageId = messageId,
        senderDeviceId = senderDeviceId,
        destinationDeviceId = destinationDeviceId,
        createdAt = createdAt,
        protocolVersion = protocolVersion,
        ttl = ttl,
        hopCount = hopCount,
        nonce = nonce,
        ciphertext = ciphertext,
        authenticationTag = tag
    )

    @Test
    fun test1_localDelivery_deliversWhenDestinationMatchesLocalDevice() = runTest {
        val engine = ForwardingEngine(
            localDeviceId = "device-A",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = DirectPeerNextHopResolver { null },
            clock = { nowMs }
        )

        val env = buildTestEnvelope(senderDeviceId = "device-B", destinationDeviceId = "device-A")
        val decision = engine.processIncoming(env, incomingPeerAddress = "AA:BB:CC:DD:EE:01")

        assertTrue(decision is ForwardingDecision.LocalDelivery)
        assertEquals(env, (decision as ForwardingDecision.LocalDelivery).envelope)
    }

    @Test
    fun test2_forwardDecision_forwardsWithoutReadingPlaintextWhenNotDestination() = runTest {
        val deferredEnvelopes = mutableListOf<MessageEnvelope>()
        val engine = ForwardingEngine(
            localDeviceId = "device-B",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = DirectPeerNextHopResolver { dest ->
                if (dest == "device-C") "AA:BB:CC:DD:EE:03" else null
            },
            pendingForwardSink = object : PendingForwardSink {
                override suspend fun onNoRouteForForward(envelope: MessageEnvelope) {
                    deferredEnvelopes.add(envelope)
                }
            },
            clock = { nowMs }
        )

        val env = buildTestEnvelope(
            senderDeviceId = "device-A",
            destinationDeviceId = "device-C",
            ttl = 5,
            hopCount = 0
        )
        val decision = engine.processIncoming(env, incomingPeerAddress = "AA:BB:CC:DD:EE:01")

        assertTrue(decision is ForwardingDecision.Forward)
        val fwd = decision as ForwardingDecision.Forward
        assertEquals(listOf("AA:BB:CC:DD:EE:03"), fwd.targetAddresses)
        assertArrayEquals(env.ciphertext, fwd.updatedEnvelope.ciphertext)
        assertArrayEquals(env.nonce, fwd.updatedEnvelope.nonce)
        assertArrayEquals(env.authenticationTag, fwd.updatedEnvelope.authenticationTag)
        assertTrue(deferredEnvelopes.isEmpty())
    }

    @Test
    fun test2b_forwardDecision_worksWithNoRouteAndNotifiesPendingSink() = runTest {
        val deferred = mutableListOf<MessageEnvelope>()
        val engine = ForwardingEngine(
            localDeviceId = "device-B",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = object : NextHopResolver {
                override suspend fun resolveNextHops(
                    envelope: MessageEnvelope,
                    incomingPeerAddress: String?
                ): NextHopResolution = NextHopResolution.NoRouteAvailable
            },
            pendingForwardSink = object : PendingForwardSink {
                override suspend fun onNoRouteForForward(envelope: MessageEnvelope) {
                    deferred.add(envelope)
                }
            },
            clock = { nowMs }
        )

        val env = buildTestEnvelope(senderDeviceId = "device-A", destinationDeviceId = "device-C", ttl = 5, hopCount = 0)
        val decision = engine.processIncoming(env)
        assertTrue(decision is ForwardingDecision.Forward)
        val fwd = decision as ForwardingDecision.Forward
        assertTrue(fwd.targetAddresses.isEmpty())
        assertEquals(1, deferred.size)
        assertEquals(4, deferred.first().ttl)
        assertEquals(1, deferred.first().hopCount)
    }

    @Test
    fun test3_ttlZero_mustNotBeForwarded() = runTest {
        val engine = ForwardingEngine(
            localDeviceId = "device-B",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = DirectPeerNextHopResolver { "AA:BB:CC:DD:EE:03" },
            clock = { nowMs }
        )

        val env = buildTestEnvelope(senderDeviceId = "device-A", destinationDeviceId = "device-C", ttl = 0, hopCount = 5)
        val decision = engine.processIncoming(env)

        assertTrue(decision is ForwardingDecision.Drop)
        assertEquals(DropReason.TTL_EXPIRED, (decision as ForwardingDecision.Drop).reason)
    }

    @Test
    fun test4_and_test5_ttlDecrementsAndHopCountIncrementsAcrossMultiHopChain() = runTest {
        val relay1 = ForwardingEngine(
            localDeviceId = "device-B",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = DirectPeerNextHopResolver { "AA:BB:CC:DD:EE:02" },
            clock = { nowMs }
        )
        val relay2 = ForwardingEngine(
            localDeviceId = "device-C",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = DirectPeerNextHopResolver { "AA:BB:CC:DD:EE:03" },
            clock = { nowMs }
        )

        val initial = buildTestEnvelope(senderDeviceId = "device-A", destinationDeviceId = "device-D", ttl = 5, hopCount = 0)
        val d1 = relay1.processIncoming(initial) as ForwardingDecision.Forward
        assertEquals(4, d1.updatedEnvelope.ttl)
        assertEquals(1, d1.updatedEnvelope.hopCount)

        // Wire roundtrip between relay1 -> relay2 preserves routing metadata accurately
        val wireBytes = MessageEnvelope.serialize(d1.updatedEnvelope)
        val decodedAtRelay2 = MessageEnvelope.deserialize(wireBytes)
        assertNotNull(decodedAtRelay2)

        val d2 = relay2.processIncoming(decodedAtRelay2!!) as ForwardingDecision.Forward
        assertEquals(3, d2.updatedEnvelope.ttl)
        assertEquals(2, d2.updatedEnvelope.hopCount)
    }

    @Test
    fun test6_duplicateMessageId_droppedOnSecondArrival() = runTest {
        val relay = ForwardingEngine(
            localDeviceId = "device-B",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = DirectPeerNextHopResolver { "AA:BB:CC:DD:EE:03" },
            clock = { nowMs }
        )

        val sharedId = MessageEnvelope.newMessageId()
        val copy1 = buildTestEnvelope(messageId = sharedId, senderDeviceId = "device-A", destinationDeviceId = "device-C", ttl = 4, hopCount = 1)
        val copy2 = buildTestEnvelope(messageId = sharedId, senderDeviceId = "device-A", destinationDeviceId = "device-C", ttl = 3, hopCount = 2)

        val first = relay.processIncoming(copy1, incomingPeerAddress = "AA:BB:CC:DD:EE:01")
        val second = relay.processIncoming(copy2, incomingPeerAddress = "AA:BB:CC:DD:EE:02")

        assertTrue(first is ForwardingDecision.Forward)
        assertTrue(second is ForwardingDecision.Drop)
        assertEquals(DropReason.DUPLICATE_MESSAGE, (second as ForwardingDecision.Drop).reason)
    }

    @Test
    fun test7_invalidMessage_rejectedCleanly() = runTest {
        val relay = ForwardingEngine(
            localDeviceId = "device-B",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = DirectPeerNextHopResolver { "AA:BB:CC:DD:EE:03" },
            clock = { nowMs }
        )

        // Negative TTL rejected
        val negTtl = buildTestEnvelope(ttl = -1)
        val resNegTtl = relay.processIncoming(negTtl)
        assertTrue(resNegTtl is ForwardingDecision.Drop)
        assertEquals(DropReason.INVALID_MESSAGE, (resNegTtl as ForwardingDecision.Drop).reason)

        // Negative hopCount rejected
        val negHop = buildTestEnvelope(hopCount = -1)
        val resNegHop = relay.processIncoming(negHop)
        assertTrue(resNegHop is ForwardingDecision.Drop)
        assertEquals(DropReason.INVALID_MESSAGE, (resNegHop as ForwardingDecision.Drop).reason)

        // Invalid protocol version rejected
        val badVer = buildTestEnvelope(protocolVersion = 999)
        val resBadVer = relay.processIncoming(badVer)
        assertTrue(resBadVer is ForwardingDecision.Drop)
        assertEquals(DropReason.INVALID_MESSAGE, (resBadVer as ForwardingDecision.Drop).reason)

        // Blank destination rejected
        val blankDest = buildTestEnvelope(destinationDeviceId = "   ")
        val resBlank = relay.processIncoming(blankDest)
        assertTrue(resBlank is ForwardingDecision.Drop)
        assertEquals(DropReason.INVALID_MESSAGE, (resBlank as ForwardingDecision.Drop).reason)

        // Corrupt bytes fail deserialization cleanly
        assertNull(MessageEnvelope.deserialize(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun test8_and_test9_forwardingPreservesIntegrityForValidHops_rejectsTamperAndReplay() = runTest {
        val idA = DeviceIdentityManager(scopedContext("fwd_a"))
        val idC = DeviceIdentityManager(scopedContext("fwd_c"))
        val cryptoA = OmniMeshCrypto(idA, clock = { nowMs })
        val cryptoC = OmniMeshCrypto(idC, clock = { nowMs })

        // Establish end-to-end session between A and C
        val init = cryptoA.createHandshakeInit()
        val (ack, sessionC) = cryptoC.processHandshakeInitAndCreateAck(init.initPacket)!!
        val sessionA = cryptoA.completeClientHandshake(init, ack)!!

        val plaintext = "Merhaba Çok Sekmeli Çekirdek"
        val envFromA = cryptoA.encryptEnvelope(
            session = sessionA,
            plaintext = plaintext.toByteArray(StandardCharsets.UTF_8),
            ttl = 5
        )

        // Relay B forwards without possessing any session key
        val relayB = ForwardingEngine(
            localDeviceId = "relay-B",
            duplicateCache = InMemoryDuplicateMessageCache(clock = { nowMs }),
            nextHopResolver = DirectPeerNextHopResolver { dest ->
                if (dest == idC.deviceId) "AA:BB:CC:DD:EE:CC" else null
            },
            clock = { nowMs }
        )
        val forwardDecision = relayB.processIncoming(envFromA) as ForwardingDecision.Forward
        val envAtC = MessageEnvelope.deserialize(MessageEnvelope.serialize(forwardDecision.updatedEnvelope))!!
        assertEquals(4, envAtC.ttl)
        assertEquals(1, envAtC.hopCount)

        // Destination C receives forwarded envelope
        val destCacheC = InMemoryDuplicateMessageCache(clock = { nowMs })
        val destEngineC = ForwardingEngine(
            localDeviceId = idC.deviceId,
            duplicateCache = destCacheC,
            nextHopResolver = DirectPeerNextHopResolver { null },
            clock = { nowMs }
        )
        val replayStoreC = InMemoryReplayStore()

        // Test 8: Tampered ciphertext at C is rejected by Phase 1 integrity check
        val tamperedEnv = envAtC.copy(
            encryptedPayload = envAtC.encryptedPayload.copy(
                ciphertext = envAtC.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
            )
        )
        val localTampered = destEngineC.processIncoming(tamperedEnv) as ForwardingDecision.LocalDelivery
        val tamperVerify = cryptoC.decryptAndVerifyEnvelope(sessionC, localTampered.envelope, replayStoreC)
        assertTrue(tamperVerify is EnvelopeVerificationResult.InvalidMessage)

        // Untampered forwarded packet succeeds decryption despite TTL/HopCount updates by Relay B!
        val localValid = destEngineC.processIncoming(envAtC) as ForwardingDecision.LocalDelivery
        val validVerify = cryptoC.decryptAndVerifyEnvelope(sessionC, localValid.envelope, replayStoreC)
        assertTrue(validVerify is EnvelopeVerificationResult.Valid)
        assertEquals(plaintext, String((validVerify as EnvelopeVerificationResult.Valid).plaintext, StandardCharsets.UTF_8))
        destEngineC.markLocalDelivered(envAtC.messageId)

        // Test 9: Replay protection rejects same messageId via both duplicate filter and crypto ReplayStore
        val duplicateAtDest = destEngineC.processIncoming(envAtC)
        assertTrue(duplicateAtDest is ForwardingDecision.Drop)
        assertEquals(DropReason.DUPLICATE_MESSAGE, (duplicateAtDest as ForwardingDecision.Drop).reason)

        val cryptoReplay = cryptoC.decryptAndVerifyEnvelope(sessionC, envAtC, replayStoreC)
        assertTrue(cryptoReplay is EnvelopeVerificationResult.ReplayDetected)
    }
}
