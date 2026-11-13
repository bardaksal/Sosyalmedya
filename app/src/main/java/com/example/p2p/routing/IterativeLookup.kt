package com.example.p2p.routing

import com.example.p2p.crypto.MessageEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class IterativeLookupResult(
    val targetNodeId: RoutingNodeId,
    val targetFound: Boolean,
    val targetResponded: Boolean,
    val targetEntry: RoutingNodeEntry?,
    val closestNodes: List<RoutingNodeEntry>,
    val queriedNodeIds: Set<RoutingNodeId>,
    val respondedNodeIds: Set<RoutingNodeId>,
    val failedNodeIds: Set<RoutingNodeId>,
    val rejectedConflictNodeIds: Set<RoutingNodeId>,
    val rounds: Int
)

/**
 * Kademlia iterative FIND_NODE lookup engine with configurable `alpha` concurrency and `k` bucket size.
 *
 * Tracks `queried`, `pending`, `discovered`, `responded`, and `failed` sets cleanly:
 * - Never sends a duplicate FIND_NODE query to the same node.
 * - Tolerates unresponsive/failed peers without crashing the lookup.
 * - Rejects identity-conflicting referrals.
 * - Propagates coroutine [CancellationException] cleanly.
 */
class IterativeNodeLookup(
    private val routingTable: KademliaRoutingTable,
    private val rpcTransport: RoutingRpcTransport,
    private val localDescriptorProvider: () -> FindNodeNodeDescriptor? = { null },
    private val perRpcTimeoutMs: Long = DEFAULT_RPC_TIMEOUT_MS,
    private val persistDiscoveredInRoutingTable: Boolean = true,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun findNode(targetNodeId: RoutingNodeId): IterativeLookupResult {
        require(targetNodeId.bitLength == routingTable.config.idBitLength) {
            "targetNodeId bitLength (${targetNodeId.bitLength}) != ${routingTable.config.idBitLength}"
        }

        val k = routingTable.config.bucketSize
        val alpha = routingTable.config.alpha

        if (targetNodeId == routingTable.localNodeId) {
            val selfEntry = localDescriptorProvider()?.toRoutingEntry(
                trustState = RoutingTrustState.VERIFIED_IDENTITY,
                isDirectlyReachable = true
            )
            return IterativeLookupResult(
                targetNodeId = targetNodeId,
                targetFound = true,
                targetResponded = true,
                targetEntry = selfEntry,
                closestNodes = routingTable.closestNodes(targetNodeId, count = k),
                queriedNodeIds = emptySet(),
                respondedNodeIds = emptySet(),
                failedNodeIds = emptySet(),
                rejectedConflictNodeIds = emptySet(),
                rounds = 0
            )
        }

        val queried = LinkedHashSet<RoutingNodeId>()
        val pending = LinkedHashSet<RoutingNodeId>()
        val responded = LinkedHashSet<RoutingNodeId>()
        val failed = LinkedHashSet<RoutingNodeId>()
        val rejectedConflicts = LinkedHashSet<RoutingNodeId>()
        val discovered = LinkedHashMap<RoutingNodeId, RoutingNodeEntry>()

        for (seed in routingTable.closestNodes(targetNodeId, count = k)) {
            if (seed.nodeId != routingTable.localNodeId) {
                discovered[seed.nodeId] = seed
            }
        }

        var rounds = 0

        while (true) {
            val sortedDiscovered = discovered.values.sortedWith(
                Comparator<RoutingNodeEntry> { a, b ->
                    targetNodeId.xorDistanceTo(a.nodeId)
                        .compareTo(targetNodeId.xorDistanceTo(b.nodeId))
                }
            )

            // Determine kth-best responded distance if we already have k responses
            val kthRespondedDistance = if (responded.size >= k) {
                val respondedSorted = responded.mapNotNull { discovered[it] }
                    .sortedBy { targetNodeId.xorDistanceTo(it.nodeId) }
                if (respondedSorted.size >= k) {
                    targetNodeId.xorDistanceTo(respondedSorted[k - 1].nodeId)
                } else {
                    null
                }
            } else {
                null
            }

            val batch = sortedDiscovered
                .asSequence()
                .filter { entry ->
                    entry.nodeId != routingTable.localNodeId &&
                        entry.nodeId !in queried &&
                        entry.nodeId !in pending
                }
                .filter { entry ->
                    kthRespondedDistance == null ||
                        targetNodeId.xorDistanceTo(entry.nodeId) < kthRespondedDistance
                }
                .take(alpha)
                .toList()

            if (batch.isEmpty()) {
                break
            }

            rounds++
            // Mark queried & pending BEFORE launching RPCs so duplicate queries cannot occur
            for (candidate in batch) {
                queried.add(candidate.nodeId)
                pending.add(candidate.nodeId)
            }

            val senderDescriptor = localDescriptorProvider()
            val roundResponses: List<Pair<RoutingNodeEntry, FindNodeResponse?>> = coroutineScope {
                batch.map { targetEntry ->
                    async {
                        val req = FindNodeRequest(
                            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
                            senderNodeId = routingTable.localNodeId,
                            targetNodeId = targetNodeId,
                            senderDescriptor = senderDescriptor
                        )
                        val response = try {
                            withTimeoutOrNull(perRpcTimeoutMs) {
                                rpcTransport.findNode(targetEntry, req)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        targetEntry to response
                    }
                }.awaitAll()
            }

            for ((queriedEntry, response) in roundResponses) {
                pending.remove(queriedEntry.nodeId)
                if (response == null ||
                    response.protocolVersion != MessageEnvelope.CURRENT_PROTOCOL_VERSION ||
                    response.responderNodeId != queriedEntry.nodeId
                ) {
                    failed.add(queriedEntry.nodeId)
                    routingTable.markNodeFailure(queriedEntry.nodeId)
                    continue
                }

                responded.add(queriedEntry.nodeId)
                routingTable.markNodeSeen(queriedEntry.nodeId, timestamp = clock())

                for (desc in response.nodes) {
                    if (desc.nodeId == routingTable.localNodeId) continue
                    if (desc.nodeId.bitLength != routingTable.config.idBitLength) continue
                    if (desc.deviceId.isBlank() || desc.identityFingerprint.isBlank()) continue

                    val candidateEntry = desc.toRoutingEntry(
                        trustState = RoutingTrustState.KNOWN_UNVERIFIED,
                        isDirectlyReachable = false
                    )

                    if (persistDiscoveredInRoutingTable) {
                        when (routingTable.upsertNode(candidateEntry)) {
                            is RoutingInsertOutcome.IdentityBindingConflict -> {
                                rejectedConflicts.add(desc.nodeId)
                                continue
                            }
                            is RoutingInsertOutcome.InvalidNode,
                            RoutingInsertOutcome.SelfNodeRejected -> continue
                            is RoutingInsertOutcome.Inserted,
                            is RoutingInsertOutcome.Updated,
                            is RoutingInsertOutcome.ReplacedStale,
                            is RoutingInsertOutcome.BucketFull -> Unit
                        }
                    }

                    val existing = discovered[desc.nodeId]
                    if (existing != null &&
                        (existing.deviceId != desc.deviceId ||
                            !existing.identityFingerprint.equals(desc.identityFingerprint, ignoreCase = true))
                    ) {
                        rejectedConflicts.add(desc.nodeId)
                        continue
                    }

                    if (existing == null) {
                        discovered[desc.nodeId] = routingTable.getNode(desc.nodeId) ?: candidateEntry
                    } else if (desc.lastSeenAt > existing.lastSeenAt) {
                        discovered[desc.nodeId] = existing.copy(
                            lastSeenAt = desc.lastSeenAt,
                            transportCapabilities = existing.transportCapabilities + desc.transportCapabilities
                        )
                    }
                }
            }

            // Fast-path: if exact target was queried and responded, lookup reached the target
            if (targetNodeId in responded) {
                break
            }
        }

        val finalClosest = discovered.values
            .sortedBy { targetNodeId.xorDistanceTo(it.nodeId) }
            .take(k)

        val targetEntry = discovered[targetNodeId]
        return IterativeLookupResult(
            targetNodeId = targetNodeId,
            targetFound = targetEntry != null,
            targetResponded = targetNodeId in responded,
            targetEntry = targetEntry,
            closestNodes = finalClosest,
            queriedNodeIds = queried.toSet(),
            respondedNodeIds = responded.toSet(),
            failedNodeIds = failed.toSet(),
            rejectedConflictNodeIds = rejectedConflicts.toSet(),
            rounds = rounds
        )
    }

    companion object {
        const val DEFAULT_RPC_TIMEOUT_MS = 2_500L
    }
}
