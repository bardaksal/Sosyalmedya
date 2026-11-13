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
import com.example.p2p.data.OmniMeshDatabase
import com.example.p2p.data.OmniMeshLocalRepository
import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.transport.BleFrameCodec
import com.example.p2p.transport.BleFrameReassembler
import com.example.p2p.transport.CapabilityDetector
import com.example.p2p.transport.CapabilityStatus
import com.example.p2p.transport.WifiAwareTransport
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class OmniMeshProtocolAndSecurityTest {

    private lateinit var baseContext: Context
    private lateinit var dbA: OmniMeshDatabase
    private lateinit var dbB: OmniMeshDatabase
    private var currentTimeMs = 1_700_000_000_000L

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
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
    }

    @Test
    fun messageIdGeneration_producesUniqueIds() {
        val ids = (1..500).map { MessageEnvelope.newMessageId() }.toSet()
        assertEquals(500, ids.size)
    }

    @Test
    fun deviceIdentity_persistsAcrossManagerInstances() {
        val ctx = scopedContext("persist_test")
        val first = DeviceIdentityManager(ctx)
        val second = DeviceIdentityManager(ctx)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(first.getPublicKeyBase64(), second.getPublicKeyBase64())
        assertEquals(first.getPublicKeyFingerprint(), second.getPublicKeyFingerprint())
    }

    @Test
    fun envelopeSerialization_roundtripsAndRejectsCorruptBytes() {
        val env = MessageEnvelope.create(
            messageId = MessageEnvelope.newMessageId(),
            senderDeviceId = "omn-sender-1",
            destinationDeviceId = "omn-receiver-2",
            createdAt = 1_700_000_000_000L,
            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
            ttl = 5,
            hopCount = 0,
            nonce = ByteArray(12) { it.toByte() },
            ciphertext = "encrypted-payload".toByteArray(StandardCharsets.UTF_8),
            authenticationTag = ByteArray(16) { (it + 10).toByte() }
        )

        val serialized = MessageEnvelope.serialize(env)
        val decoded = MessageEnvelope.deserialize(serialized)
        assertEquals(env, decoded)

        // Truncated or malformed bytes must safely return null
        assertNull(MessageEnvelope.deserialize(serialized.copyOf(10)))
        assertNull(MessageEnvelope.deserialize(serialized + byteArrayOf(0x01)))
    }

    @Test
    fun handshakeAndSecurityChecks_rejectTamperingReplayWrongReceiverAndExpiry() = runTest {
        val idA = DeviceIdentityManager(scopedContext("node_a"))
        val idB = DeviceIdentityManager(scopedContext("node_b"))
        val cryptoA = OmniMeshCrypto(idA, clock = { currentTimeMs })
        val cryptoB = OmniMeshCrypto(idB, clock = { currentTimeMs })
        val replayStoreB = OmniMeshLocalRepository(dbB.omniMeshDao(), clock = { currentTimeMs })

        val pending = cryptoA.createHandshakeInit()
        val ackAndSessionB = cryptoB.processHandshakeInitAndCreateAck(pending.initPacket)
        assertNotNull(ackAndSessionB)
        val (ackPacket, sessionB) = ackAndSessionB!!
        val sessionA = cryptoA.completeClientHandshake(pending, ackPacket)
        assertNotNull(sessionA)
        sessionA!!

        val plaintext = "Merhaba OmniMesh"
        val envelope = cryptoA.encryptEnvelope(sessionA, plaintext.toByteArray(StandardCharsets.UTF_8))

        // 1. Valid decryption succeeds
        val valid = cryptoB.decryptAndVerifyEnvelope(sessionB, envelope, replayStoreB)
        assertTrue(valid is EnvelopeVerificationResult.Valid)
        assertEquals(
            plaintext,
            String((valid as EnvelopeVerificationResult.Valid).plaintext, StandardCharsets.UTF_8)
        )

        // 2. Replaying exact same messageId + nonce is rejected
        val replay = cryptoB.decryptAndVerifyEnvelope(sessionB, envelope, replayStoreB)
        assertTrue(replay is EnvelopeVerificationResult.ReplayDetected)

        // 3. Replaying same messageId with a fresh nonce across a brand-new session is STILL rejected by Room ReplayStore
        val pending2 = cryptoA.createHandshakeInit()
        val (ack2, sessionB2) = cryptoB.processHandshakeInitAndCreateAck(pending2.initPacket)!!
        val sessionA2 = cryptoA.completeClientHandshake(pending2, ack2)!!
        val replayedMsgIdEnv = cryptoA.encryptEnvelope(
            sessionA2,
            plaintext.toByteArray(StandardCharsets.UTF_8),
            messageId = envelope.messageId
        )
        val persistentReplay = cryptoB.decryptAndVerifyEnvelope(sessionB2, replayedMsgIdEnv, replayStoreB)
        assertTrue(persistentReplay is EnvelopeVerificationResult.ReplayDetected)

        // 4. Tampered ciphertext fails integrity
        val freshEnv = cryptoA.encryptEnvelope(sessionA2, plaintext.toByteArray(StandardCharsets.UTF_8))
        val badCipher = freshEnv.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        val tamperedCipherRes = cryptoB.decryptAndVerifyEnvelope(
            sessionB2,
            freshEnv.copy(encryptedPayload = freshEnv.encryptedPayload.copy(ciphertext = badCipher)),
            InMemoryReplayStore()
        )
        assertTrue(tamperedCipherRes is EnvelopeVerificationResult.InvalidMessage)

        // 5. Tampered authentication tag fails integrity
        val badTag = freshEnv.authenticationTag.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val tamperedTagRes = cryptoB.decryptAndVerifyEnvelope(
            sessionB2,
            freshEnv.copy(encryptedPayload = freshEnv.encryptedPayload.copy(authenticationTag = badTag)),
            InMemoryReplayStore()
        )
        assertTrue(tamperedTagRes is EnvelopeVerificationResult.InvalidMessage)

        // 6. Tampered nonce fails integrity
        val badNonce = freshEnv.nonce.copyOf().apply { this[0] = (this[0].toInt() xor 0x55).toByte() }
        val tamperedNonceRes = cryptoB.decryptAndVerifyEnvelope(
            sessionB2,
            freshEnv.copy(encryptedPayload = freshEnv.encryptedPayload.copy(nonce = badNonce)),
            InMemoryReplayStore()
        )
        assertTrue(tamperedNonceRes is EnvelopeVerificationResult.InvalidMessage)

        // 7. Wrong destinationDeviceId rejected
        val wrongReceiverRes = cryptoB.decryptAndVerifyEnvelope(
            sessionB2,
            freshEnv.copy(routingMetadata = freshEnv.routingMetadata.copy(destinationDeviceId = "omn-wrong-device")),
            InMemoryReplayStore()
        )
        assertTrue(wrongReceiverRes is EnvelopeVerificationResult.InvalidMessage)

        // 8. Unsupported protocol version rejected
        val wrongVersionRes = cryptoB.decryptAndVerifyEnvelope(
            sessionB2,
            freshEnv.copy(routingMetadata = freshEnv.routingMetadata.copy(protocolVersion = 99)),
            InMemoryReplayStore()
        )
        assertTrue(wrongVersionRes is EnvelopeVerificationResult.InvalidMessage)

        // 9. Expired timestamp rejected
        val expiredEnv = cryptoA.encryptEnvelope(
            sessionA2,
            plaintext.toByteArray(StandardCharsets.UTF_8),
            createdAt = currentTimeMs - OmniMeshCrypto.MAX_MESSAGE_AGE_MS - 1_000L
        )
        val expiredRes = cryptoB.decryptAndVerifyEnvelope(sessionB2, expiredEnv, InMemoryReplayStore())
        assertTrue(expiredRes is EnvelopeVerificationResult.Expired)
    }

    @Test
    fun handshake_rejectsForgedSignatureAndPinnedKeyMismatch() {
        val idA = DeviceIdentityManager(scopedContext("hs_a"))
        val idB = DeviceIdentityManager(scopedContext("hs_b"))
        val idAttacker = DeviceIdentityManager(scopedContext("hs_attacker"))
        val cryptoA = OmniMeshCrypto(idA)
        val cryptoB = OmniMeshCrypto(idB)

        val pending = cryptoA.createHandshakeInit()
        val forgedInit = pending.initPacket.copy(
            signature = pending.initPacket.signature.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        )
        assertNull(cryptoB.processHandshakeInitAndCreateAck(forgedInit))

        // Key pinning rejects impersonator with different key for same deviceId
        val pinnedPub = idA.getPublicKeyBytes()
        assertNull(
            cryptoB.processHandshakeInitAndCreateAck(
                pending.initPacket.copy(identityPubKey = idAttacker.getPublicKeyBytes()),
                expectedPinnedPublicKey = pinnedPub
            )
        )
    }

    @Test
    fun bleFrameCodec_chunksAndReassemblesAcrossMinAttMtuAndOutOfOrder() {
        val payload = ("OmniMesh BLE chunking verification " + "X".repeat(250)).toByteArray(StandardCharsets.UTF_8)
        val chunks = BleFrameCodec.chunk(payload, maxAttPayloadBytes = BleFrameCodec.MIN_ATT_PAYLOAD)
        assertTrue("Expected multi-chunk split at 20-byte ATT MTU", chunks.size > 10)
        assertTrue(chunks.all { it.size <= BleFrameCodec.MIN_ATT_PAYLOAD })

        val reassembler = BleFrameReassembler()
        var reassembled: ByteArray? = null
        // Feed in reverse order to verify out-of-order reassembly
        for (chunk in chunks.reversed()) {
            reassembled = reassembler.onChunkReceived("AA:BB:CC:DD:EE:FF", chunk)
        }
        assertNotNull(reassembled)
        assertArrayEquals(payload, reassembled)

        // Corrupt chunk is rejected cleanly
        assertNull(reassembler.onChunkReceived("AA:BB:CC:DD:EE:FF", byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun capabilityDetector_reportsUnsupportedWifiAwareHonestly() {
        val shadowPm = org.robolectric.Shadows.shadowOf(baseContext.packageManager)
        shadowPm.setSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_AWARE, false)

        val detector = CapabilityDetector(baseContext)
        val wifiAwareTransport = WifiAwareTransport(detector)
        assertEquals(CapabilityStatus.UNSUPPORTED, wifiAwareTransport.getCapabilities())
        assertEquals(CapabilityStatus.UNSUPPORTED, detector.detectWifiAwareCapability())
    }
}
