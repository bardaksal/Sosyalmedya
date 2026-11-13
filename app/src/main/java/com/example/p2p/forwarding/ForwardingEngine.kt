package com.example.p2p.forwarding

import com.example.p2p.crypto.MessageEnvelope
import com.example.p2p.crypto.OmniMeshCrypto
import com.example.p2p.routing.NextHopResolution
import com.example.p2p.routing.NextHopResolver
import com.example.p2p.routing.NoOpPendingForwardSink
import com.example.p2p.routing.PendingForwardSink

enum class DropReason {
    INVALID_MESSAGE,
    EXPIRED_TIMESTAMP,
    DUPLICATE_MESSAGE,
    TTL_EXPIRED
}

sealed interface ForwardingDecision {
    data class LocalDelivery(val envelope: MessageEnvelope) : ForwardingDecision
    data class Forward(
        val updatedEnvelope: MessageEnvelope,
        val targetAddresses: List<String>
    ) : ForwardingDecision
    data class Drop(val reason: DropReason, val detail: String) : ForwardingDecision
}

/**
 * Transport-independent forwarding core.
 *
 * Pipeline:
 * Incoming Envelope -> Validate -> Duplicate? -> Destination? ->
 *   - Destination == localDeviceId -> [ForwardingDecision.LocalDelivery]
 *   - Destination != localDeviceId -> Check TTL > 0 -> Decrement TTL & Increment HopCount ->
 *     Resolve Next Hop -> [ForwardingDecision.Forward]
 *
 * Does NOT decrypt end-to-end payloads when acting as a relay node.
 */
class ForwardingEngine(
    private val localDeviceId: String,
    private val duplicateCache: DuplicateMessageCache,
    private val nextHopResolver: NextHopResolver,
    private val pendingForwardSink: PendingForwardSink = NoOpPendingForwardSink,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun processIncoming(
        envelope: MessageEnvelope,
        incomingPeerAddress: String? = null
    ): ForwardingDecision {
        val validationError = validateEnvelope(envelope)
        if (validationError != null) {
            return validationError
        }

        if (duplicateCache.isDuplicate(envelope.messageId)) {
            return ForwardingDecision.Drop(
                reason = DropReason.DUPLICATE_MESSAGE,
                detail = "Duplicate messageId: ${envelope.messageId}"
            )
        }

        if (envelope.destinationDeviceId == localDeviceId) {
            // Note: local delivery marks duplicateCache only after crypto/integrity verification succeeds.
            return ForwardingDecision.LocalDelivery(envelope)
        }

        if (envelope.ttl <= 0) {
            return ForwardingDecision.Drop(
                reason = DropReason.TTL_EXPIRED,
                detail = "TTL exhausted (ttl=${envelope.ttl}) for ${envelope.messageId}"
            )
        }

        duplicateCache.markSeen(envelope.messageId, clock())
        val forwardedEnvelope = envelope.advanceForForward()

        return when (val route = nextHopResolver.resolveNextHops(forwardedEnvelope, incomingPeerAddress)) {
            is NextHopResolution.Resolved -> {
                ForwardingDecision.Forward(
                    updatedEnvelope = forwardedEnvelope,
                    targetAddresses = route.peerAddresses
                )
            }
            is NextHopResolution.NoRouteAvailable -> {
                pendingForwardSink.onNoRouteForForward(forwardedEnvelope)
                ForwardingDecision.Forward(
                    updatedEnvelope = forwardedEnvelope,
                    targetAddresses = emptyList()
                )
            }
        }
    }

    /**
     * Marks a locally delivered [messageId] as seen after end-to-end authentication & decryption succeed.
     */
    suspend fun markLocalDelivered(messageId: String) {
        duplicateCache.markSeen(messageId, clock())
    }

    private fun validateEnvelope(envelope: MessageEnvelope): ForwardingDecision.Drop? {
        if (envelope.protocolVersion != MessageEnvelope.CURRENT_PROTOCOL_VERSION) {
            return ForwardingDecision.Drop(
                DropReason.INVALID_MESSAGE,
                "Unsupported protocolVersion: ${envelope.protocolVersion}"
            )
        }
        if (envelope.messageId.isBlank() ||
            envelope.senderDeviceId.isBlank() ||
            envelope.destinationDeviceId.isBlank()
        ) {
            return ForwardingDecision.Drop(
                DropReason.INVALID_MESSAGE,
                "Routing identifiers must not be blank"
            )
        }
        if (envelope.ttl < 0 || envelope.ttl > MessageEnvelope.MAX_TTL) {
            return ForwardingDecision.Drop(
                DropReason.INVALID_MESSAGE,
                "Invalid TTL: ${envelope.ttl}"
            )
        }
        if (envelope.hopCount < 0 || envelope.hopCount > MessageEnvelope.MAX_HOP_COUNT) {
            return ForwardingDecision.Drop(
                DropReason.INVALID_MESSAGE,
                "Invalid hopCount: ${envelope.hopCount}"
            )
        }
        if (envelope.nonce.size != MessageEnvelope.NONCE_SIZE_BYTES ||
            envelope.authenticationTag.size != MessageEnvelope.AUTH_TAG_SIZE_BYTES
        ) {
            return ForwardingDecision.Drop(
                DropReason.INVALID_MESSAGE,
                "Invalid cryptographic nonce or authentication tag length"
            )
        }

        val now = clock()
        if (envelope.createdAt < now - OmniMeshCrypto.MAX_MESSAGE_AGE_MS) {
            return ForwardingDecision.Drop(
                DropReason.EXPIRED_TIMESTAMP,
                "Message timestamp expired"
            )
        }
        if (envelope.createdAt > now + OmniMeshCrypto.MAX_FUTURE_CLOCK_SKEW_MS) {
            return ForwardingDecision.Drop(
                DropReason.INVALID_MESSAGE,
                "Message timestamp skew out of bounds"
            )
        }
        return null
    }
}
