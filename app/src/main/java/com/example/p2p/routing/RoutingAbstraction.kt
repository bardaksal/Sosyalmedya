package com.example.p2p.routing

import com.example.p2p.crypto.MessageEnvelope

/**
 * Result of resolving transport-level next-hop peer addresses for a forwarded [MessageEnvelope].
 */
sealed interface NextHopResolution {
    data class Resolved(val peerAddresses: List<String>) : NextHopResolution
    data object NoRouteAvailable : NextHopResolution
}

/**
 * Transport-independent routing abstraction.
 * Phase 2.1 uses direct peer lookup; Phase 2.2+ can plug in a real routing table / flooding policy
 * without changing [com.example.p2p.forwarding.ForwardingEngine] or transport implementations.
 */
interface NextHopResolver {
    suspend fun resolveNextHops(
        envelope: MessageEnvelope,
        incomingPeerAddress: String?
    ): NextHopResolution
}

/**
 * Hook for connecting Forwarding Core -> Persistent Store-and-Forward Queue -> Retry in a future phase.
 * Phase 2.1 keeps this as a no-op because persistent store-and-forward is intentionally deferred.
 */
interface PendingForwardSink {
    suspend fun onNoRouteForForward(envelope: MessageEnvelope)
}

object NoOpPendingForwardSink : PendingForwardSink {
    override suspend fun onNoRouteForForward(envelope: MessageEnvelope) = Unit
}

/**
 * Minimal Phase 2.1 resolver that checks whether a known direct transport address exists for
 * [MessageEnvelope.destinationDeviceId] (never echoing back to [incomingPeerAddress]).
 */
class DirectPeerNextHopResolver(
    private val lookupDirectAddress: (destinationDeviceId: String) -> String?
) : NextHopResolver {
    override suspend fun resolveNextHops(
        envelope: MessageEnvelope,
        incomingPeerAddress: String?
    ): NextHopResolution {
        val targetAddr = lookupDirectAddress(envelope.destinationDeviceId)?.uppercase()
            ?: return NextHopResolution.NoRouteAvailable
        if (incomingPeerAddress != null && targetAddr == incomingPeerAddress.uppercase()) {
            return NextHopResolution.NoRouteAvailable
        }
        return NextHopResolution.Resolved(listOf(targetAddr))
    }
}
