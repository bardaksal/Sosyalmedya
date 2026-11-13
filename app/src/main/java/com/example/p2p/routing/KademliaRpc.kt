package com.example.p2p.routing

import com.example.p2p.crypto.MessageEnvelope
import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.transport.TransportType
import java.util.UUID

enum class KademliaRpcType {
    PING,
    FIND_NODE
}

/**
 * Public peer descriptor exchanged in Kademlia PING / FIND_NODE RPC messages.
 * Never carries private keys or session secrets.
 */
data class FindNodeNodeDescriptor(
    val nodeId: RoutingNodeId,
    val deviceId: String,
    val identityFingerprint: String,
    val identityPublicKey: ByteArray? = null,
    val lastSeenAt: Long,
    val transportCapabilities: Set<TransportType> = emptySet(),
    val reachableTransports: Set<TransportType> = emptySet(),
    val endpointAddress: String? = null
) {
    fun toRoutingEntry(
        trustState: RoutingTrustState = RoutingTrustState.KNOWN_UNVERIFIED,
        isDirectlyReachable: Boolean = false
    ): RoutingNodeEntry = RoutingNodeEntry(
        nodeId = nodeId,
        deviceId = deviceId,
        identityFingerprint = identityFingerprint,
        identityPublicKey = identityPublicKey,
        lastSeenAt = lastSeenAt,
        transportCapabilities = transportCapabilities,
        reachableTransports = reachableTransports,
        endpointAddress = endpointAddress,
        isDirectlyReachable = isDirectlyReachable,
        consecutiveFailures = 0,
        trustState = trustState
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FindNodeNodeDescriptor) return false
        return nodeId == other.nodeId &&
            deviceId == other.deviceId &&
            identityFingerprint == other.identityFingerprint &&
            ((identityPublicKey == null && other.identityPublicKey == null) ||
                (identityPublicKey != null && other.identityPublicKey != null &&
                    identityPublicKey.contentEquals(other.identityPublicKey))) &&
            lastSeenAt == other.lastSeenAt &&
            transportCapabilities == other.transportCapabilities &&
            reachableTransports == other.reachableTransports &&
            endpointAddress == other.endpointAddress
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + identityFingerprint.hashCode()
        result = 31 * result + (identityPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + lastSeenAt.hashCode()
        result = 31 * result + transportCapabilities.hashCode()
        result = 31 * result + reachableTransports.hashCode()
        result = 31 * result + (endpointAddress?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun fromEntry(entry: RoutingNodeEntry): FindNodeNodeDescriptor = FindNodeNodeDescriptor(
            nodeId = entry.nodeId,
            deviceId = entry.deviceId,
            identityFingerprint = entry.identityFingerprint,
            identityPublicKey = entry.identityPublicKey,
            lastSeenAt = entry.lastSeenAt,
            transportCapabilities = entry.transportCapabilities,
            reachableTransports = entry.reachableTransports,
            endpointAddress = entry.endpointAddress
        )
    }
}

data class PingRequest(
    val protocolVersion: Int = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
    val requestId: String = newRequestId(),
    val senderNodeId: RoutingNodeId,
    val targetNodeId: RoutingNodeId,
    val senderDescriptor: FindNodeNodeDescriptor? = null
) {
    val type: KademliaRpcType get() = KademliaRpcType.PING
}

data class PingResponse(
    val protocolVersion: Int = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
    val requestId: String,
    val responderNodeId: RoutingNodeId,
    val responderDescriptor: FindNodeNodeDescriptor? = null,
    val acknowledged: Boolean = true
)

data class FindNodeRequest(
    val protocolVersion: Int = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
    val requestId: String = newRequestId(),
    val senderNodeId: RoutingNodeId,
    val targetNodeId: RoutingNodeId,
    val senderDescriptor: FindNodeNodeDescriptor? = null
) {
    val type: KademliaRpcType get() = KademliaRpcType.FIND_NODE
}

data class FindNodeResponse(
    val protocolVersion: Int = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
    val requestId: String,
    val responderNodeId: RoutingNodeId,
    val targetNodeId: RoutingNodeId,
    val nodes: List<FindNodeNodeDescriptor>
)

private fun newRequestId(): String = UUID.randomUUID().toString()

/**
 * Transport-agnostic Kademlia RPC abstraction.
 * Phase 3.1 decouples routing discovery from BLE/physical transport implementations.
 */
interface RoutingRpcTransport {
    suspend fun ping(target: RoutingNodeEntry, request: PingRequest): PingResponse?
    suspend fun findNode(target: RoutingNodeEntry, request: FindNodeRequest): FindNodeResponse?
}

/**
 * Local responder handling incoming PING and FIND_NODE RPCs against a node's [KademliaRoutingTable].
 */
class KademliaRpcHandler(
    private val routingTable: KademliaRoutingTable,
    private val localDescriptorProvider: () -> FindNodeNodeDescriptor? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun handlePing(request: PingRequest): PingResponse? {
        if (request.protocolVersion != MessageEnvelope.CURRENT_PROTOCOL_VERSION) return null
        if (request.senderNodeId.bitLength != routingTable.config.idBitLength) return null
        if (request.targetNodeId != routingTable.localNodeId) return null

        recordSenderIfValid(request.senderNodeId, request.senderDescriptor)
        return PingResponse(
            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
            requestId = request.requestId,
            responderNodeId = routingTable.localNodeId,
            responderDescriptor = localDescriptorProvider(),
            acknowledged = true
        )
    }

    fun handleFindNode(request: FindNodeRequest): FindNodeResponse? {
        if (request.protocolVersion != MessageEnvelope.CURRENT_PROTOCOL_VERSION) return null
        if (request.senderNodeId.bitLength != routingTable.config.idBitLength) return null
        if (request.targetNodeId.bitLength != routingTable.config.idBitLength) return null

        recordSenderIfValid(request.senderNodeId, request.senderDescriptor)

        val closest = routingTable.closestNodes(
            targetNodeId = request.targetNodeId,
            count = routingTable.config.bucketSize,
            excludeNodeIds = setOf(request.senderNodeId)
        ).map { FindNodeNodeDescriptor.fromEntry(it) }.toMutableList()

        // If queried for our own nodeId and localDescriptor is available, include it cleanly
        if (request.targetNodeId == routingTable.localNodeId) {
            localDescriptorProvider()?.let { localDesc ->
                if (closest.none { it.nodeId == localDesc.nodeId }) {
                    closest.add(0, localDesc)
                }
            }
        }

        val sortedClosest = closest
            .distinctBy { it.nodeId }
            .sortedBy { request.targetNodeId.xorDistanceTo(it.nodeId) }
            .take(routingTable.config.bucketSize)

        return FindNodeResponse(
            protocolVersion = MessageEnvelope.CURRENT_PROTOCOL_VERSION,
            requestId = request.requestId,
            responderNodeId = routingTable.localNodeId,
            targetNodeId = request.targetNodeId,
            nodes = sortedClosest
        )
    }

    private fun recordSenderIfValid(
        senderNodeId: RoutingNodeId,
        descriptor: FindNodeNodeDescriptor?
    ) {
        if (descriptor != null && descriptor.nodeId == senderNodeId) {
            routingTable.upsertNode(
                descriptor.toRoutingEntry(
                    trustState = if (descriptor.identityPublicKey != null &&
                        DeviceIdentityManager.fingerprintOf(descriptor.identityPublicKey)
                            .equals(descriptor.identityFingerprint, ignoreCase = true)
                    ) {
                        RoutingTrustState.KNOWN_UNVERIFIED
                    } else {
                        RoutingTrustState.KNOWN_UNVERIFIED
                    }
                ).copy(lastSeenAt = maxOf(descriptor.lastSeenAt, clock()))
            )
        } else {
            routingTable.markNodeSeen(senderNodeId, timestamp = clock())
        }
    }
}
