package com.example.p2p.routing

import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.transport.TransportType

sealed interface RoutingInsertOutcome {
    data class Inserted(val bucketIndex: Int, val entry: RoutingNodeEntry) : RoutingInsertOutcome
    data class Updated(val bucketIndex: Int, val entry: RoutingNodeEntry) : RoutingInsertOutcome
    data class ReplacedStale(
        val bucketIndex: Int,
        val evicted: RoutingNodeEntry,
        val inserted: RoutingNodeEntry
    ) : RoutingInsertOutcome
    data class BucketFull(
        val bucketIndex: Int,
        val evictionCandidate: RoutingNodeEntry,
        val challenger: RoutingNodeEntry
    ) : RoutingInsertOutcome
    data class IdentityBindingConflict(val reason: String) : RoutingInsertOutcome
    data object SelfNodeRejected : RoutingInsertOutcome
    data class InvalidNode(val reason: String) : RoutingInsertOutcome
}

/**
 * Thread-safe Kademlia routing table indexing peers across [KademliaConfig.idBitLength] k-buckets
 * using `bucketIndex = floor(log2(XOR(localNodeId, remoteNodeId)))`.
 *
 * Enforces `nodeId <-> deviceId <-> identityFingerprint` consistency so conflicting
 * identity bindings cannot silently overwrite existing routing entries.
 */
class KademliaRoutingTable(
    val localNodeId: RoutingNodeId,
    val config: KademliaConfig = KademliaConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(localNodeId.bitLength == config.idBitLength) {
            "localNodeId bitLength (${localNodeId.bitLength}) must match config.idBitLength (${config.idBitLength})"
        }
    }

    private data class IdentityBinding(
        val deviceId: String,
        val identityFingerprint: String
    )

    private val lock = Any()
    private val buckets = Array(config.idBitLength) { index ->
        KBucket(
            index = index,
            capacity = config.bucketSize,
            maxConsecutiveFailures = config.maxConsecutiveFailures
        )
    }
    private val bindingsByNodeId = HashMap<RoutingNodeId, IdentityBinding>()
    private val nodeIdByDeviceId = HashMap<String, RoutingNodeId>()

    fun bucketIndexFor(remoteNodeId: RoutingNodeId): Int? {
        if (remoteNodeId.bitLength != config.idBitLength) return null
        if (remoteNodeId == localNodeId) return null
        return localNodeId.xorDistanceTo(remoteNodeId).bucketIndex().takeIf { it >= 0 }
    }

    fun upsertNode(entry: RoutingNodeEntry): RoutingInsertOutcome {
        if (entry.nodeId.bitLength != config.idBitLength) {
            return RoutingInsertOutcome.InvalidNode(
                "Node bitLength (${entry.nodeId.bitLength}) != ${config.idBitLength}"
            )
        }
        if (entry.nodeId == localNodeId) {
            return RoutingInsertOutcome.SelfNodeRejected
        }
        if (entry.deviceId.isBlank() || entry.identityFingerprint.isBlank()) {
            return RoutingInsertOutcome.InvalidNode("deviceId and identityFingerprint must not be blank")
        }

        // If a raw public key is supplied, verify its fingerprint and deterministic NodeId derivation
        entry.identityPublicKey?.let { pubKey ->
            val actualFp = DeviceIdentityManager.fingerprintOf(pubKey)
            if (!actualFp.equals(entry.identityFingerprint, ignoreCase = true)) {
                return RoutingInsertOutcome.IdentityBindingConflict(
                    "Public key fingerprint mismatch for ${entry.deviceId}"
                )
            }
            val expectedNodeId = RoutingNodeId.deriveFromDeviceIdentity(
                deviceId = entry.deviceId,
                publicKeyBytes = pubKey,
                idBitLength = config.idBitLength
            )
            if (expectedNodeId != entry.nodeId) {
                return RoutingInsertOutcome.IdentityBindingConflict(
                    "RoutingNodeId does not match deviceId + publicKey binding"
                )
            }
        }

        val bucketIdx = bucketIndexFor(entry.nodeId)
            ?: return RoutingInsertOutcome.InvalidNode("Invalid distance to localNodeId")

        synchronized(lock) {
            val existingBinding = bindingsByNodeId[entry.nodeId]
            if (existingBinding != null &&
                (existingBinding.deviceId != entry.deviceId ||
                    !existingBinding.identityFingerprint.equals(entry.identityFingerprint, ignoreCase = true))
            ) {
                return RoutingInsertOutcome.IdentityBindingConflict(
                    "Conflicting identity binding for nodeId=${entry.nodeId.hex}"
                )
            }

            val existingNodeForDevice = nodeIdByDeviceId[entry.deviceId]
            if (existingNodeForDevice != null && existingNodeForDevice != entry.nodeId) {
                return RoutingInsertOutcome.IdentityBindingConflict(
                    "Conflicting nodeId already bound to deviceId=${entry.deviceId}"
                )
            }

            return when (val res = buckets[bucketIdx].upsert(entry)) {
                is BucketInsertResult.Inserted -> {
                    bindingsByNodeId[entry.nodeId] = IdentityBinding(entry.deviceId, entry.identityFingerprint)
                    nodeIdByDeviceId[entry.deviceId] = entry.nodeId
                    RoutingInsertOutcome.Inserted(bucketIdx, res.entry)
                }
                is BucketInsertResult.Updated -> {
                    RoutingInsertOutcome.Updated(bucketIdx, res.entry)
                }
                is BucketInsertResult.ReplacedStale -> {
                    if (nodeIdByDeviceId[res.evicted.deviceId] == res.evicted.nodeId) {
                        nodeIdByDeviceId.remove(res.evicted.deviceId)
                    }
                    bindingsByNodeId[entry.nodeId] = IdentityBinding(entry.deviceId, entry.identityFingerprint)
                    nodeIdByDeviceId[entry.deviceId] = entry.nodeId
                    RoutingInsertOutcome.ReplacedStale(bucketIdx, res.evicted, res.inserted)
                }
                is BucketInsertResult.BucketFullPendingEviction -> {
                    RoutingInsertOutcome.BucketFull(
                        bucketIndex = bucketIdx,
                        evictionCandidate = res.evictionCandidate,
                        challenger = res.rejectedChallenger
                    )
                }
                is BucketInsertResult.IdentityConflict -> {
                    RoutingInsertOutcome.IdentityBindingConflict(
                        "Bucket conflict for nodeId=${entry.nodeId.hex}"
                    )
                }
            }
        }
    }

    fun markNodeSeen(
        nodeId: RoutingNodeId,
        timestamp: Long = clock(),
        endpointAddress: String? = null,
        reachableTransports: Set<TransportType>? = null,
        isDirectlyReachable: Boolean? = null
    ): RoutingNodeEntry? {
        val bucketIdx = bucketIndexFor(nodeId) ?: return null
        synchronized(lock) {
            return buckets[bucketIdx].markSeen(
                nodeId = nodeId,
                timestamp = timestamp,
                endpointAddress = endpointAddress,
                reachableTransports = reachableTransports,
                isDirectlyReachable = isDirectlyReachable
            )
        }
    }

    fun markNodeFailure(nodeId: RoutingNodeId): RoutingNodeEntry? {
        val bucketIdx = bucketIndexFor(nodeId) ?: return null
        synchronized(lock) {
            return buckets[bucketIdx].markFailure(nodeId)
        }
    }

    fun markDeviceDisconnected(deviceId: String): RoutingNodeEntry? {
        synchronized(lock) {
            val nodeId = nodeIdByDeviceId[deviceId] ?: return null
            val bucketIdx = bucketIndexFor(nodeId) ?: return null
            val current = buckets[bucketIdx].get(nodeId) ?: return null
            val updated = current.copy(isDirectlyReachable = false)
            buckets[bucketIdx].remove(nodeId)
            buckets[bucketIdx].upsert(updated)
            return updated
        }
    }

    fun getNode(nodeId: RoutingNodeId): RoutingNodeEntry? {
        val bucketIdx = bucketIndexFor(nodeId) ?: return null
        synchronized(lock) {
            return buckets[bucketIdx].get(nodeId)
        }
    }

    fun findByDeviceId(deviceId: String): RoutingNodeEntry? {
        synchronized(lock) {
            val nodeId = nodeIdByDeviceId[deviceId] ?: return null
            val bucketIdx = bucketIndexFor(nodeId) ?: return null
            return buckets[bucketIdx].get(nodeId)
        }
    }

    fun bucketEntries(bucketIndex: Int): List<RoutingNodeEntry> {
        require(bucketIndex in 0 until config.idBitLength) {
            "bucketIndex out of range: $bucketIndex"
        }
        synchronized(lock) {
            return buckets[bucketIndex].snapshot()
        }
    }

    fun closestNodes(
        targetNodeId: RoutingNodeId,
        count: Int = config.bucketSize,
        excludeNodeIds: Set<RoutingNodeId> = emptySet()
    ): List<RoutingNodeEntry> {
        if (targetNodeId.bitLength != config.idBitLength || count <= 0) return emptyList()
        synchronized(lock) {
            val all = ArrayList<RoutingNodeEntry>()
            for (bucket in buckets) {
                if (!bucket.isEmpty()) {
                    for (entry in bucket.snapshot()) {
                        if (entry.nodeId !in excludeNodeIds) {
                            all.add(entry)
                        }
                    }
                }
            }
            return all.sortedWith(
                Comparator<RoutingNodeEntry> { a, b ->
                    val distCmp = targetNodeId.xorDistanceTo(a.nodeId)
                        .compareTo(targetNodeId.xorDistanceTo(b.nodeId))
                    if (distCmp != 0) distCmp else b.lastSeenAt.compareTo(a.lastSeenAt)
                }
            ).take(count)
        }
    }

    fun totalNodeCount(): Int = synchronized(lock) {
        buckets.sumOf { it.size() }
    }

    fun snapshot(): List<RoutingNodeEntry> = synchronized(lock) {
        buckets.flatMap { it.snapshot() }
    }
}
