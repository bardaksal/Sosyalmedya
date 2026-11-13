package com.example.p2p.routing

import com.example.p2p.crypto.MessageEnvelope
import com.example.p2p.transport.TransportType

/**
 * Result of resolving transport-level next-hop peer addresses for a forwarded [MessageEnvelope].
 */
sealed interface NextHopResolution {
    data class Resolved(val peerAddresses: List<String>) : NextHopResolution
    data object NoRouteAvailable : NextHopResolution
}

/**
 * Rich routing-level resolution distinguishing direct reachability, greedy Kademlia next-hop,
 * known-but-unreachable peers, and completely unknown destinations.
 */
sealed interface RoutingNextHopOutcome {
    data class Direct(
        val destinationEntry: RoutingNodeEntry,
        val peerAddress: String,
        val transport: TransportType?
    ) : RoutingNextHopOutcome

    data class NextHop(
        val nextHopEntry: RoutingNodeEntry,
        val peerAddress: String,
        val destinationNodeId: RoutingNodeId,
        val transport: TransportType?
    ) : RoutingNextHopOutcome

    data class Unreachable(
        val destinationNodeId: RoutingNodeId?,
        val reason: String
    ) : RoutingNextHopOutcome

    data object Unknown : RoutingNextHopOutcome
}

/**
 * Transport-independent routing abstraction.
 * Integrates [KademliaNextHopResolver] or [DirectPeerNextHopResolver] without coupling
 * [com.example.p2p.forwarding.ForwardingEngine] to specific transport implementations.
 */
interface NextHopResolver {
    suspend fun resolveNextHops(
        envelope: MessageEnvelope,
        incomingPeerAddress: String?
    ): NextHopResolution
}

/**
 * Hook for connecting Forwarding Core -> Persistent Store-and-Forward Queue -> Retry in a future phase.
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

/**
 * Real Kademlia routing-table-backed [NextHopResolver].
 *
 * Separates "destination known in routing table" from "destination physically reachable":
 * - Returns [RoutingNextHopOutcome.Direct] only if the destination is known AND currently reachable.
 * - Returns [RoutingNextHopOutcome.NextHop] using the closest reachable Kademlia neighbor strictly
 *   closer to `targetNodeId` than [KademliaRoutingTable.localNodeId] (never falling back to random/first peers).
 * - Returns [RoutingNextHopOutcome.Unreachable] if known in table / closer peers exist but none are reachable.
 * - Returns [RoutingNextHopOutcome.Unknown] if no routing state exists for the destination.
 */
class KademliaNextHopResolver(
    private val routingTable: KademliaRoutingTable,
    private val resolveDestinationNodeId: (destinationDeviceId: String) -> RoutingNodeId? = { deviceId ->
        routingTable.findByDeviceId(deviceId)?.nodeId
    },
    private val isEntryReachable: (RoutingNodeEntry) -> Boolean = { entry ->
        entry.isDirectlyReachable &&
            !entry.endpointAddress.isNullOrBlank() &&
            entry.reachableTransports.isNotEmpty()
    },
    private val fallbackDirectAddressLookup: ((destinationDeviceId: String) -> String?)? = null
) : NextHopResolver {

    fun resolveRoutingOutcome(
        destinationDeviceId: String,
        incomingPeerAddress: String? = null
    ): RoutingNextHopOutcome {
        val normalizedIncoming = incomingPeerAddress?.uppercase()
        val destEntry = routingTable.findByDeviceId(destinationDeviceId)
        val targetNodeId = destEntry?.nodeId ?: resolveDestinationNodeId(destinationDeviceId)

        if (destEntry != null && isEntryReachable(destEntry)) {
            val addr = destEntry.endpointAddress?.uppercase()
            if (addr != null && addr != normalizedIncoming) {
                return RoutingNextHopOutcome.Direct(
                    destinationEntry = destEntry,
                    peerAddress = addr,
                    transport = destEntry.reachableTransports.firstOrNull()
                )
            }
        }

        if (targetNodeId == null) {
            return RoutingNextHopOutcome.Unknown
        }
        if (targetNodeId == routingTable.localNodeId) {
            return RoutingNextHopOutcome.Unreachable(targetNodeId, "Destination matches local node")
        }

        val localDistanceToTarget = routingTable.localNodeId.xorDistanceTo(targetNodeId)
        val candidates = routingTable.closestNodes(targetNodeId, count = routingTable.config.bucketSize)
        val closerCandidates = candidates.filter { candidate ->
            targetNodeId.xorDistanceTo(candidate.nodeId) < localDistanceToTarget
        }

        val reachableHop = closerCandidates.firstOrNull { candidate ->
            val addr = candidate.endpointAddress?.uppercase()
            isEntryReachable(candidate) && addr != null && addr != normalizedIncoming
        }

        if (reachableHop != null) {
            return RoutingNextHopOutcome.NextHop(
                nextHopEntry = reachableHop,
                peerAddress = reachableHop.endpointAddress!!.uppercase(),
                destinationNodeId = targetNodeId,
                transport = reachableHop.reachableTransports.firstOrNull()
            )
        }

        return if (destEntry != null || closerCandidates.isNotEmpty()) {
            RoutingNextHopOutcome.Unreachable(
                destinationNodeId = targetNodeId,
                reason = "Destination or closer Kademlia peers known in routing table but currently unreachable"
            )
        } else {
            RoutingNextHopOutcome.Unknown
        }
    }

    override suspend fun resolveNextHops(
        envelope: MessageEnvelope,
        incomingPeerAddress: String?
    ): NextHopResolution {
        return when (val outcome = resolveRoutingOutcome(envelope.destinationDeviceId, incomingPeerAddress)) {
            is RoutingNextHopOutcome.Direct -> NextHopResolution.Resolved(listOf(outcome.peerAddress))
            is RoutingNextHopOutcome.NextHop -> NextHopResolution.Resolved(listOf(outcome.peerAddress))
            is RoutingNextHopOutcome.Unreachable,
            RoutingNextHopOutcome.Unknown -> {
                val fallbackAddr = fallbackDirectAddressLookup?.invoke(envelope.destinationDeviceId)?.uppercase()
                if (fallbackAddr != null && fallbackAddr != incomingPeerAddress?.uppercase()) {
                    NextHopResolution.Resolved(listOf(fallbackAddr))
                } else {
                    NextHopResolution.NoRouteAvailable
                }
            }
        }
    }
}
