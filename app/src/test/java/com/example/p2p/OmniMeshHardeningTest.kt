package com.example.p2p

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.p2p.crypto.EnvelopeVerificationResult
import com.example.p2p.crypto.InMemoryReplayStore
import com.example.p2p.crypto.MessageEnvelope
import com.example.p2p.crypto.OmniMeshCrypto
import com.example.p2p.crypto.PayloadEncryptionMode
import com.example.p2p.crypto.ReplayStore
import com.example.p2p.forwarding.DropReason
import com.example.p2p.forwarding.ForwardingDecision
import com.example.p2p.forwarding.ForwardingEngine
import com.example.p2p.forwarding.InMemoryDuplicateMessageCache
import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.routing.DirectPeerNextHopResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OmniMeshHardeningTest {

    private lateinit var baseContext: Context
    private lateinit var idA: DeviceIdentityManager
    private lateinit var idB: DeviceIdentityManager
    private lateinit var idC: DeviceIdentityManager
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
        idA = DeviceIdentityManager(scopedContext("hardening_a"))
        idB = DeviceIdentityManager(scopedContext("hardening_b"))
        idC = DeviceIdentityManager(scopedContext("hardening_c"))
    }

    // ------------------------------------------------------------------------
    // 1. E2E Nonce Replay Cache Lifecycle Tests
    // ------------------------------------------------------------------------

    @Test
    fun nonceCache_acceptsFirst_rejectsReplay_evictsExpired_andCapsSize() = runTest {
        val cryptoA = OmniMeshCrypto(idA, clock = { clockNow })
        val cryptoC = OmniMeshCrypto(idC, clock = { clockNow }, maxE2eNonceCacheSize = 3)
        val replayStoreC = InMemoryReplayStore()

        // 1. First message is accepted and cached
        val env1 = cryptoA.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = "İlk mesaj".toByteArray(StandardCharsets.UTF_8),
            createdAt = clockNow
        )
        val res1 = cryptoC.decryptAndVerifyE2eEnvelope(env1, idA.getPublicKeyBytes(), replayStoreC)
        assertTrue(res1 is EnvelopeVerificationResult.Valid)
        assertEquals(1, cryptoC.e2eNonceCacheSize())

        // 2. Replaying the same nonce is rejected cleanly
        val replayRes = cryptoC.decryptAndVerifyE2eEnvelope(env1, idA.getPublicKeyBytes(), replayStoreC)
        assertTrue(replayRes is EnvelopeVerificationResult.ReplayDetected)

        // 3. Advance clock past REPLAY_RETENTION_MS -> expired nonce entry is evicted instead of staying forever
        clockNow += OmniMeshCrypto.REPLAY_RETENTION_MS + 1_000L
        val envAfterExpiry = cryptoA.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = "Süre sonrası taze mesaj".toByteArray(StandardCharsets.UTF_8),
            createdAt = clockNow
        )
        val resAfterExpiry = cryptoC.decryptAndVerifyE2eEnvelope(envAfterExpiry, idA.getPublicKeyBytes(), replayStoreC)
        assertTrue(resAfterExpiry is EnvelopeVerificationResult.Valid)
        assertEquals(
            "Expired nonce cache entry should have been pruned",
            1,
            cryptoC.e2eNonceCacheSize()
        )

        // 4. Bounded cache size prevents unbounded memory growth when many fresh nonces arrive
        repeat(5) { idx ->
            val e = cryptoA.encryptEndToEndEnvelope(
                destinationDeviceId = idC.deviceId,
                destinationIdentityPubKey = idC.getPublicKeyBytes(),
                plaintext = "Kapasite testi $idx".toByteArray(StandardCharsets.UTF_8),
                createdAt = clockNow
            )
            assertTrue(
                cryptoC.decryptAndVerifyE2eEnvelope(e, idA.getPublicKeyBytes(), replayStoreC)
                    is EnvelopeVerificationResult.Valid
            )
        }
        assertEquals(3, cryptoC.e2eNonceCacheSize())
    }

    @Test
    fun nonceCache_sameNonceFromDifferentSendersDoesNotCollide() = runTest {
        val cryptoC = OmniMeshCrypto(idC, clock = { clockNow })
        val replayStoreC = InMemoryReplayStore()

        val sharedNonce = ByteArray(12) { (0xA0 + it).toByte() }
        val envFromA = buildManualValidE2eEnvelope(
            senderIdentity = idA,
            destinationDeviceId = idC.deviceId,
            destinationPubKey = idC.getPublicKeyBytes(),
            nonce = sharedNonce,
            plaintext = "Gönderici A mesajı".toByteArray(StandardCharsets.UTF_8),
            createdAt = clockNow
        )
        val envFromB = buildManualValidE2eEnvelope(
            senderIdentity = idB,
            destinationDeviceId = idC.deviceId,
            destinationPubKey = idC.getPublicKeyBytes(),
            nonce = sharedNonce,
            plaintext = "Gönderici B mesajı".toByteArray(StandardCharsets.UTF_8),
            createdAt = clockNow
        )

        val resA = cryptoC.decryptAndVerifyE2eEnvelope(envFromA, idA.getPublicKeyBytes(), replayStoreC)
        val resB = cryptoC.decryptAndVerifyE2eEnvelope(envFromB, idB.getPublicKeyBytes(), replayStoreC)

        assertTrue("Sender A should succeed", resA is EnvelopeVerificationResult.Valid)
        assertTrue(
            "Sender B with identical nonce must NOT collide with Sender A",
            resB is EnvelopeVerificationResult.Valid
        )
        assertEquals("Gönderici A mesajı", String((resA as EnvelopeVerificationResult.Valid).plaintext, StandardCharsets.UTF_8))
        assertEquals("Gönderici B mesajı", String((resB as EnvelopeVerificationResult.Valid).plaintext, StandardCharsets.UTF_8))
        assertEquals(2, cryptoC.e2eNonceCacheSize())
    }

    // ------------------------------------------------------------------------
    // 2. HKDF Key Derivation Behavioral Regression (No Wire/Output Change)
    // ------------------------------------------------------------------------

    @Test
    fun hkdfKeyDerivation_preservesExactOutputAndPublicWireFields() = runTest {
        val cryptoA = OmniMeshCrypto(idA, clock = { clockNow })
        val cryptoC = OmniMeshCrypto(idC, clock = { clockNow })
        val replayStoreC = InMemoryReplayStore()

        val plaintext = "HKDF regresyon ve genel metadata testi".toByteArray(StandardCharsets.UTF_8)
        val envelope = cryptoA.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = plaintext,
            createdAt = clockNow
        )

        // Public metadata must remain intact (not wiped!)
        assertEquals(PayloadEncryptionMode.END_TO_END, envelope.encryptionMode)
        assertEquals(MessageEnvelope.NONCE_SIZE_BYTES, envelope.nonce.size)
        assertEquals(MessageEnvelope.AUTH_TAG_SIZE_BYTES, envelope.authenticationTag.size)
        assertTrue(envelope.senderEphemeralPubKey.size >= MessageEnvelope.MIN_EPHEMERAL_PUB_KEY_BYTES)
        assertFalse(envelope.nonce.all { it == 0.toByte() })

        // Cross-check OmniMeshCrypto output against independent RFC 5869 HKDF-SHA256 derivation at C
        val independentEcdhEph = idC.performIdentityEcdh(envelope.senderEphemeralPubKey)!!
        val independentEcdhStatic = idC.performIdentityEcdh(idA.getPublicKeyBytes())!!
        val expectedKey = independentHkdfSha256(
            salt = envelope.nonce,
            ikm = independentEcdhEph + independentEcdhStatic,
            info = buildExpectedE2eInfo(envelope),
            length = 32
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(expectedKey, "AES"), GCMParameterSpec(128, envelope.nonce))
        cipher.updateAAD(envelope.buildAdditionalAuthenticatedData())
        val independentPlaintext = cipher.doFinal(envelope.ciphertext + envelope.authenticationTag)
        assertArrayEquals(plaintext, independentPlaintext)

        val verified = cryptoC.decryptAndVerifyE2eEnvelope(envelope, idA.getPublicKeyBytes(), replayStoreC)
        assertTrue(verified is EnvelopeVerificationResult.Valid)
        assertArrayEquals(plaintext, (verified as EnvelopeVerificationResult.Valid).plaintext)
    }

    // ------------------------------------------------------------------------
    // 3. Coroutine Cancellation & Cryptographic InvalidMessage Separation
    // ------------------------------------------------------------------------

    @Test
    fun decryption_returnsInvalidMessageOnCryptoError_andPropagatesCoroutineCancellation() = runTest {
        val cryptoA = OmniMeshCrypto(idA, clock = { clockNow })
        val cryptoC = OmniMeshCrypto(idC, clock = { clockNow })

        // A. Invalid crypto payload still yields EnvelopeVerificationResult.InvalidMessage
        val validEnv = cryptoA.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = "Bozuk zarf testi".toByteArray(StandardCharsets.UTF_8),
            createdAt = clockNow
        )
        val tamperedEnv = validEnv.copy(
            encryptedPayload = validEnv.encryptedPayload.copy(
                ciphertext = validEnv.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
            )
        )
        val cryptoErr = cryptoC.decryptAndVerifyE2eEnvelope(tamperedEnv, idA.getPublicKeyBytes(), InMemoryReplayStore())
        assertTrue(cryptoErr is EnvelopeVerificationResult.InvalidMessage)

        // B. Coroutine cancellation during E2E decrypt propagates CancellationException (never swallowed)
        val gate = CompletableDeferred<Unit>()
        val suspendingReplayStore = object : ReplayStore {
            override suspend fun isMessageSeen(messageId: String): Boolean {
                gate.complete(Unit)
                CompletableDeferred<Boolean>().await() // suspends until cancelled
                return false
            }

            override suspend fun recordMessageSeen(
                messageId: String,
                senderDeviceId: String,
                nonceHex: String,
                seenAt: Long,
                expiresAt: Long
            ) = Unit
        }

        var caughtCancellationInE2e: Throwable? = null
        val jobE2e = launch {
            try {
                cryptoC.decryptAndVerifyE2eEnvelope(validEnv, idA.getPublicKeyBytes(), suspendingReplayStore)
            } catch (t: Throwable) {
                caughtCancellationInE2e = t
                throw t
            }
        }
        gate.await()
        jobE2e.cancel()
        jobE2e.join()
        assertTrue(
            "E2E decrypt must propagate CancellationException",
            caughtCancellationInE2e is CancellationException
        )

        // C. Coroutine cancellation thrown inside transport decrypt also propagates CancellationException
        val init = cryptoA.createHandshakeInit()
        val (ack, sessionC) = cryptoC.processHandshakeInitAndCreateAck(init.initPacket)!!
        val sessionA = cryptoA.completeClientHandshake(init, ack)!!
        val transportEnv = cryptoA.encryptEnvelope(sessionA, "Transport iptal testi".toByteArray(StandardCharsets.UTF_8))

        val gateTransport = CompletableDeferred<Unit>()
        val cancellingStore = object : ReplayStore {
            override suspend fun isMessageSeen(messageId: String): Boolean = false
            override suspend fun recordMessageSeen(
                messageId: String,
                senderDeviceId: String,
                nonceHex: String,
                seenAt: Long,
                expiresAt: Long
            ) {
                gateTransport.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }

        var caughtCancellationInTransport: Throwable? = null
        val jobTransport = launch {
            try {
                cryptoC.decryptAndVerifyEnvelope(sessionC, transportEnv, cancellingStore)
            } catch (t: Throwable) {
                caughtCancellationInTransport = t
                throw t
            }
        }
        gateTransport.await()
        jobTransport.cancel()
        jobTransport.join()
        assertTrue(
            "Transport decrypt must propagate CancellationException",
            caughtCancellationInTransport is CancellationException
        )
    }

    // ------------------------------------------------------------------------
    // 4. MessageEnvelope Wire Parser Hardening Tests
    // ------------------------------------------------------------------------

    @Test
    fun wireParser_rejectsTruncatedInvalidLengthOversizedMalformedUtf8BadModeBadKeysAndBadSizes() {
        val cryptoA = OmniMeshCrypto(idA, clock = { clockNow })
        val validE2e = cryptoA.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = "Parser testi".toByteArray(StandardCharsets.UTF_8),
            createdAt = clockNow
        )
        val validWire = MessageEnvelope.serialize(validE2e)
        assertNotNull(MessageEnvelope.deserialize(validWire))

        // 1. Truncated envelope at various positions
        assertNull(MessageEnvelope.deserialize(ByteArray(0)))
        assertNull(MessageEnvelope.deserialize(validWire.copyOf(10)))
        assertNull(MessageEnvelope.deserialize(validWire.copyOf(36)))
        assertNull(MessageEnvelope.deserialize(validWire.copyOf(validWire.size - 1)))

        // 2. Trailing byte(s) rejected deterministically
        assertNull(MessageEnvelope.deserialize(validWire + byteArrayOf(0x00)))

        // 3. Invalid encryption mode
        val badModeBytes = validWire.copyOf().apply {
            this[20] = 99.toByte() // offset: 4(ver) + 8(createdAt) + 4(ttl) + 4(hop) = 20
        }
        assertNull(MessageEnvelope.deserialize(badModeBytes))

        // 4. Invalid ephemeral key length for END_TO_END (0 bytes, 16 bytes < 32, and > 512 bytes)
        assertNull(
            MessageEnvelope.deserialize(
                buildRawWireBytes(
                    mode = PayloadEncryptionMode.END_TO_END.code,
                    ephPubKey = ByteArray(0)
                )
            )
        )
        assertNull(
            MessageEnvelope.deserialize(
                buildRawWireBytes(
                    mode = PayloadEncryptionMode.END_TO_END.code,
                    ephPubKey = ByteArray(16) { 0x11 }
                )
            )
        )
        assertNull(
            MessageEnvelope.deserialize(
                buildRawWireBytes(
                    mode = PayloadEncryptionMode.END_TO_END.code,
                    ephPubKey = ByteArray(513) { 0x11 }
                )
            )
        )
        // Non-empty ephemeral key on TRANSPORT_SESSION rejected
        assertNull(
            MessageEnvelope.deserialize(
                buildRawWireBytes(
                    mode = PayloadEncryptionMode.TRANSPORT_SESSION.code,
                    ephPubKey = ByteArray(64) { 0x11 }
                )
            )
        )

        // 5. Invalid nonce length (11 and 13 bytes) and invalid auth tag length (15 and 17 bytes)
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(nonce = ByteArray(11))))
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(nonce = ByteArray(13))))
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(tag = ByteArray(15))))
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(tag = ByteArray(17))))

        // 6. Oversized string field (> 256 bytes) & oversized ciphertext (> 65536 bytes) & negative cipherLen
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(messageIdBytes = ByteArray(257) { 'a'.code.toByte() })))
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(ciphertext = ByteArray(65_537) { 0x22 })))
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(overrideCipherLen = -1)))

        // 7. Malformed UTF-8 bytes in messageId, senderDeviceId, destinationDeviceId must be rejected strictly
        val badUtf8 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0x28.toByte())
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(messageIdBytes = badUtf8)))
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(senderBytes = badUtf8)))
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(destBytes = badUtf8)))

        // Whitespace-only IDs also rejected
        assertNull(MessageEnvelope.deserialize(buildRawWireBytes(destBytes = "   ".toByteArray(StandardCharsets.UTF_8))))
    }

    // ------------------------------------------------------------------------
    // 5. TTL / HopCount Validation & Bounds Preservation
    // ------------------------------------------------------------------------

    @Test
    fun ttlAndHopCount_boundsAndRelayForwardUpdatesRemainPreserved() = runTest {
        val cryptoA = OmniMeshCrypto(idA, clock = { clockNow })
        val cryptoC = OmniMeshCrypto(idC, clock = { clockNow })
        val relayB = ForwardingEngine(
            localDeviceId = idB.deviceId,
            duplicateCache = InMemoryDuplicateMessageCache(clock = { clockNow }),
            nextHopResolver = DirectPeerNextHopResolver { "CC:CC:CC:CC:CC:CC" },
            clock = { clockNow }
        )

        val envMaxTtl = cryptoA.encryptEndToEndEnvelope(
            destinationDeviceId = idC.deviceId,
            destinationIdentityPubKey = idC.getPublicKeyBytes(),
            plaintext = "MAX_TTL kontrolü".toByteArray(StandardCharsets.UTF_8),
            ttl = MessageEnvelope.MAX_TTL
        )
        val fwd = relayB.processIncoming(envMaxTtl) as ForwardingDecision.Forward
        assertEquals(MessageEnvelope.MAX_TTL - 1, fwd.updatedEnvelope.ttl)
        assertEquals(1, fwd.updatedEnvelope.hopCount)

        // Destination C still decrypts cleanly because ttl/hopCount intentionally remain outside AAD
        val decoded = cryptoC.decryptAndVerifyE2eEnvelope(
            fwd.updatedEnvelope,
            idA.getPublicKeyBytes(),
            InMemoryReplayStore()
        )
        assertTrue(decoded is EnvelopeVerificationResult.Valid)

        // Over-limit TTL (> MAX_TTL) and hopCount (> MAX_HOP_COUNT) are dropped by ForwardingEngine
        val overTtl = envMaxTtl.copy(routingMetadata = envMaxTtl.routingMetadata.copy(ttl = MessageEnvelope.MAX_TTL + 1))
        val overHop = envMaxTtl.copy(routingMetadata = envMaxTtl.routingMetadata.copy(hopCount = MessageEnvelope.MAX_HOP_COUNT + 1))
        assertEquals(DropReason.INVALID_MESSAGE, (relayB.processIncoming(overTtl) as ForwardingDecision.Drop).reason)
        assertEquals(DropReason.INVALID_MESSAGE, (relayB.processIncoming(overHop) as ForwardingDecision.Drop).reason)
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private fun buildManualValidE2eEnvelope(
        senderIdentity: DeviceIdentityManager,
        destinationDeviceId: String,
        destinationPubKey: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        createdAt: Long
    ): MessageEnvelope {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val ephPair = kpg.generateKeyPair()

        val destEcPub = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(destinationPubKey))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephPair.private)
        ka.doPhase(destEcPub, true)
        val dhEph = ka.generateSecret()
        val dhStatic = senderIdentity.performIdentityEcdh(destinationPubKey)!!

        val draft = MessageEnvelope.create(
            messageId = MessageEnvelope.newMessageId(),
            senderDeviceId = senderIdentity.deviceId,
            destinationDeviceId = destinationDeviceId,
            createdAt = createdAt,
            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
            ttl = MessageEnvelope.DEFAULT_TTL,
            hopCount = 0,
            encryptionMode = PayloadEncryptionMode.END_TO_END,
            senderEphemeralPubKey = ephPair.public.encoded,
            nonce = nonce.copyOf(),
            ciphertext = ByteArray(0),
            authenticationTag = ByteArray(MessageEnvelope.AUTH_TAG_SIZE_BYTES)
        )
        val keyBytes = independentHkdfSha256(
            salt = nonce,
            ikm = dhEph + dhStatic,
            info = buildExpectedE2eInfo(draft),
            length = 32
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(draft.buildAdditionalAuthenticatedData())
        val combined = cipher.doFinal(plaintext)
        val split = combined.size - MessageEnvelope.AUTH_TAG_SIZE_BYTES
        return draft.copy(
            encryptedPayload = draft.encryptedPayload.copy(
                ciphertext = combined.copyOfRange(0, split),
                authenticationTag = combined.copyOfRange(split, combined.size)
            )
        )
    }

    private fun independentHkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }
        val prk = extractMac.doFinal(ikm)
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val expandMac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(prk, "HmacSHA256"))
                update(t)
                update(info)
                update(counter.toByte())
            }
            t = expandMac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            counter++
        }
        return okm
    }

    private fun buildExpectedE2eInfo(envelope: MessageEnvelope): ByteArray {
        val label = "OMNIMESH_E2E_MSG_V1".toByteArray(StandardCharsets.UTF_8)
        val msgBytes = envelope.messageId.toByteArray(StandardCharsets.UTF_8)
        val senderBytes = envelope.senderDeviceId.toByteArray(StandardCharsets.UTF_8)
        val destBytes = envelope.destinationDeviceId.toByteArray(StandardCharsets.UTF_8)
        val ephBytes = envelope.senderEphemeralPubKey
        return ByteBuffer.allocate(
            (4 + label.size) + 4 + 8 +
                (4 + msgBytes.size) + (4 + senderBytes.size) + (4 + destBytes.size) + (4 + ephBytes.size)
        ).apply {
            putInt(label.size); put(label)
            putInt(envelope.protocolVersion)
            putLong(envelope.createdAt)
            putInt(msgBytes.size); put(msgBytes)
            putInt(senderBytes.size); put(senderBytes)
            putInt(destBytes.size); put(destBytes)
            putInt(ephBytes.size); put(ephBytes)
        }.array()
    }

    private fun buildRawWireBytes(
        version: Int = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
        createdAt: Long = clockNow,
        ttl: Int = 5,
        hopCount: Int = 0,
        mode: Byte = PayloadEncryptionMode.TRANSPORT_SESSION.code,
        ephPubKey: ByteArray = ByteArray(0),
        messageIdBytes: ByteArray = "msg-1".toByteArray(StandardCharsets.UTF_8),
        senderBytes: ByteArray = "sender-A".toByteArray(StandardCharsets.UTF_8),
        destBytes: ByteArray = "dest-C".toByteArray(StandardCharsets.UTF_8),
        nonce: ByteArray = ByteArray(12) { 0x11 },
        tag: ByteArray = ByteArray(16) { 0x22 },
        ciphertext: ByteArray = "cipher".toByteArray(StandardCharsets.UTF_8),
        overrideCipherLen: Int? = null
    ): ByteArray {
        val total = 4 + 8 + 4 + 4 + 1 +
            2 + ephPubKey.size +
            2 + messageIdBytes.size +
            2 + senderBytes.size +
            2 + destBytes.size +
            2 + nonce.size +
            2 + tag.size +
            4 + ciphertext.size
        val buf = ByteBuffer.allocate(total)
        buf.putInt(version)
        buf.putLong(createdAt)
        buf.putInt(ttl)
        buf.putInt(hopCount)
        buf.put(mode)
        buf.putShort(ephPubKey.size.toShort()); buf.put(ephPubKey)
        buf.putShort(messageIdBytes.size.toShort()); buf.put(messageIdBytes)
        buf.putShort(senderBytes.size.toShort()); buf.put(senderBytes)
        buf.putShort(destBytes.size.toShort()); buf.put(destBytes)
        buf.putShort(nonce.size.toShort()); buf.put(nonce)
        buf.putShort(tag.size.toShort()); buf.put(tag)
        buf.putInt(overrideCipherLen ?: ciphertext.size)
        buf.put(ciphertext)
        return buf.array()
    }
}
