package com.example.p2p.routing

sealed interface BucketInsertResult {
    data class Inserted(val entry: RoutingNodeEntry) : BucketInsertResult
    data class Updated(val entry: RoutingNodeEntry) : BucketInsertResult
    data class ReplacedStale(val evicted: RoutingNodeEntry, val inserted: RoutingNodeEntry) : BucketInsertResult
    data class BucketFullPendingEviction(
        val evictionCandidate: RoutingNodeEntry,
        val rejectedChallenger: RoutingNodeEntry
    ) : BucketInsertResult
    data class IdentityConflict(
        val existing: RoutingNodeEntry,
        val incoming: RoutingNodeEntry
    ) : BucketInsertResult
}

/**
 * Kademlia k-bucket storing [RoutingNodeEntry] items ordered from
 * least-recently-seen (head, index 0) to most-recently-seen (tail, last index).
 *
 * When full, live entries at the head are preserved and explicitly surfaced as
 * [BucketInsertResult.BucketFullPendingEviction]; already stale heads
 * (`consecutiveFailures >= maxConsecutiveFailures`) are replaced via LRU eviction.
 */
class KBucket(
    val index: Int,
    private val capacity: Int,
    private val maxConsecutiveFailures: Int = KademliaConfig.DEFAULT_MAX_CONSECUTIVE_FAILURES
) {
    init {
        require(index >= 0) { "Bucket index must be non-negative: $index" }
        require(capacity > 0) { "Bucket capacity must be positive: $capacity" }
    }

    private val entries = ArrayList<RoutingNodeEntry>(capacity)

    fun size(): Int = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun isFull(): Boolean = entries.size >= capacity

    fun leastRecentlySeen(): RoutingNodeEntry? = entries.firstOrNull()

    fun mostRecentlySeen(): RoutingNodeEntry? = entries.lastOrNull()

    fun get(nodeId: RoutingNodeId): RoutingNodeEntry? = entries.firstOrNull { it.nodeId == nodeId }

    fun snapshot(): List<RoutingNodeEntry> = entries.toList()

    fun upsert(incoming: RoutingNodeEntry): BucketInsertResult {
        val existingIndex = entries.indexOfFirst { it.nodeId == incoming.nodeId }
        if (existingIndex >= 0) {
            val existing = entries[existingIndex]
            if (existing.identityFingerprint != incoming.identityFingerprint ||
                existing.deviceId != incoming.deviceId
            ) {
                return BucketInsertResult.IdentityConflict(existing, incoming)
            }
            val merged = mergeExisting(existing, incoming)
            entries.removeAt(existingIndex)
            entries.add(merged) // Promote to tail (most-recently-seen)
            return BucketInsertResult.Updated(merged)
        }

        if (entries.size < capacity) {
            entries.add(incoming)
            return BucketInsertResult.Inserted(incoming)
        }

        val lruHead = entries.first()
        if (lruHead.consecutiveFailures >= maxConsecutiveFailures) {
            entries.removeAt(0)
            entries.add(incoming)
            return BucketInsertResult.ReplacedStale(evicted = lruHead, inserted = incoming)
        }

        // Preserve existing live entries; surface LRU head as explicit eviction candidate
        return BucketInsertResult.BucketFullPendingEviction(
            evictionCandidate = lruHead,
            rejectedChallenger = incoming
        )
    }

    fun markSeen(
        nodeId: RoutingNodeId,
        timestamp: Long,
        endpointAddress: String? = null,
        reachableTransports: Set<com.example.p2p.transport.TransportType>? = null,
        isDirectlyReachable: Boolean? = null
    ): RoutingNodeEntry? {
        val idx = entries.indexOfFirst { it.nodeId == nodeId }
        if (idx < 0) return null
        val current = entries.removeAt(idx)
        val updated = current.copy(
            lastSeenAt = maxOf(current.lastSeenAt, timestamp),
            endpointAddress = endpointAddress ?: current.endpointAddress,
            reachableTransports = reachableTransports ?: current.reachableTransports,
            isDirectlyReachable = isDirectlyReachable ?: current.isDirectlyReachable,
            consecutiveFailures = 0
        )
        entries.add(updated)
        return updated
    }

    fun markFailure(nodeId: RoutingNodeId): RoutingNodeEntry? {
        val idx = entries.indexOfFirst { it.nodeId == nodeId }
        if (idx < 0) return null
        val current = entries[idx]
        val updated = current.copy(
            consecutiveFailures = current.consecutiveFailures + 1,
            isDirectlyReachable = false
        )
        entries[idx] = updated
        return updated
    }

    fun remove(nodeId: RoutingNodeId): Boolean {
        val idx = entries.indexOfFirst { it.nodeId == nodeId }
        if (idx < 0) return false
        entries.removeAt(idx)
        return true
    }

    private fun mergeExisting(existing: RoutingNodeEntry, incoming: RoutingNodeEntry): RoutingNodeEntry {
        val mergedTrust = if (existing.trustState == RoutingTrustState.VERIFIED_IDENTITY ||
            incoming.trustState == RoutingTrustState.VERIFIED_IDENTITY
        ) {
            RoutingTrustState.VERIFIED_IDENTITY
        } else {
            RoutingTrustState.KNOWN_UNVERIFIED
        }
        val mergedPubKey = existing.identityPublicKey ?: incoming.identityPublicKey
        return existing.copy(
            identityPublicKey = mergedPubKey,
            lastSeenAt = maxOf(existing.lastSeenAt, incoming.lastSeenAt),
            transportCapabilities = existing.transportCapabilities + incoming.transportCapabilities,
            reachableTransports = if (incoming.reachableTransports.isNotEmpty()) {
                incoming.reachableTransports
            } else {
                existing.reachableTransports
            },
            endpointAddress = incoming.endpointAddress ?: existing.endpointAddress,
            isDirectlyReachable = incoming.isDirectlyReachable || existing.isDirectlyReachable,
            consecutiveFailures = if (incoming.lastSeenAt >= existing.lastSeenAt && incoming.consecutiveFailures == 0) {
                0
            } else {
                existing.consecutiveFailures
            },
            trustState = mergedTrust
        )
    }
}
