package com.example.p2p.routing

import com.example.p2p.transport.TransportType
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Configuration parameters for the Kademlia routing core.
 * Avoids hardcoded magic numbers across buckets and iterative lookups.
 */
data class KademliaConfig(
    val idBitLength: Int = RoutingNodeId.DEFAULT_ID_BITS,
    val bucketSize: Int = DEFAULT_BUCKET_SIZE,
    val alpha: Int = DEFAULT_ALPHA,
    val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES
) {
    init {
        require(idBitLength in 8..256 && idBitLength % 8 == 0) {
            "idBitLength must be a multiple of 8 between 8 and 256: $idBitLength"
        }
        require(bucketSize > 0) { "bucketSize must be positive: $bucketSize" }
        require(alpha > 0) { "alpha must be positive: $alpha" }
        require(maxConsecutiveFailures > 0) {
            "maxConsecutiveFailures must be positive: $maxConsecutiveFailures"
        }
    }

    val idByteLength: Int get() = idBitLength / 8

    companion object {
        const val DEFAULT_BUCKET_SIZE = 20
        const val DEFAULT_ALPHA = 3
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 3
    }
}

/**
 * Fixed-width binary Kademlia routing identifier.
 *
 * Distinct from both User Identity (optional cloud account) and [DeviceIdentity]
 * (`deviceId` + P-256 identity keypair), though deterministically derived from device identity.
 */
class RoutingNodeId private constructor(
    private val bytes: ByteArray
) : Comparable<RoutingNodeId> {

    val byteLength: Int get() = bytes.size
    val bitLength: Int get() = bytes.size * 8

    val hex: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        bytes.joinToString("") { "%02x".format(it) }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    fun xorDistanceTo(other: RoutingNodeId): XorDistance {
        require(bitLength == other.bitLength) {
            "Cannot compute XOR distance across mismatched bit lengths: $bitLength vs ${other.bitLength}"
        }
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor other.bytes[i].toInt()).toByte()
        }
        return XorDistance(out)
    }

    override fun compareTo(other: RoutingNodeId): Int {
        require(bitLength == other.bitLength) {
            "Cannot compare RoutingNodeIds with different bit lengths: $bitLength vs ${other.bitLength}"
        }
        for (i in bytes.indices) {
            val diff = (bytes[i].toInt() and 0xFF) - (other.bytes[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoutingNodeId) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "RoutingNodeId($bitLength-bit:${hex.take(12)}...)"

    companion object {
        const val DEFAULT_ID_BITS = 160
        const val DEFAULT_ID_BYTES = DEFAULT_ID_BITS / 8

        fun fromBytes(bytes: ByteArray, expectedBitLength: Int? = null): RoutingNodeId? {
            if (bytes.isEmpty() || bytes.size > 32) return null
            if (expectedBitLength != null && bytes.size * 8 != expectedBitLength) return null
            return RoutingNodeId(bytes.copyOf())
        }

        fun fromHex(hex: String, expectedBitLength: Int? = null): RoutingNodeId? {
            val cleaned = hex.trim()
            if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
            val byteLen = cleaned.length / 2
            if (byteLen > 32) return null
            if (expectedBitLength != null && byteLen * 8 != expectedBitLength) return null
            val parsed = ByteArray(byteLen)
            for (i in 0 until byteLen) {
                val hi = Character.digit(cleaned[i * 2], 16)
                val lo = Character.digit(cleaned[i * 2 + 1], 16)
                if (hi == -1 || lo == -1) return null
                parsed[i] = ((hi shl 4) or lo).toByte()
            }
            return RoutingNodeId(parsed)
        }

        /**
         * Deterministically derives a device's Kademlia [RoutingNodeId] from its persistent
         * [deviceId] and X.509 [publicKeyBytes] using SHA-256 with domain separation.
         * Never derived from any user/cloud account ID.
         */
        fun deriveFromDeviceIdentity(
            deviceId: String,
            publicKeyBytes: ByteArray,
            idBitLength: Int = DEFAULT_ID_BITS
        ): RoutingNodeId {
            require(deviceId.isNotBlank()) { "deviceId must not be blank" }
            require(publicKeyBytes.isNotEmpty()) { "publicKeyBytes must not be empty" }
            require(idBitLength in 8..256 && idBitLength % 8 == 0) {
                "Unsupported idBitLength: $idBitLength"
            }

            val domain = "OMNIMESH_ROUTING_NODE_V1".toByteArray(StandardCharsets.UTF_8)
            val idBytes = deviceId.toByteArray(StandardCharsets.UTF_8)
            val buffer = ByteBuffer.allocate(4 + domain.size + 4 + idBytes.size + 4 + publicKeyBytes.size)
            buffer.putInt(domain.size)
            buffer.put(domain)
            buffer.putInt(idBytes.size)
            buffer.put(idBytes)
            buffer.putInt(publicKeyBytes.size)
            buffer.put(publicKeyBytes)

            val digest = MessageDigest.getInstance("SHA-256").digest(buffer.array())
            return RoutingNodeId(digest.copyOfRange(0, idBitLength / 8))
        }
    }
}

/**
 * Unsigned binary XOR distance metric between two [RoutingNodeId]s of equal bit length.
 */
class XorDistance internal constructor(
    private val bytes: ByteArray
) : Comparable<XorDistance> {

    val bitLength: Int get() = bytes.size * 8

    val isZero: Boolean
        get() = bytes.all { it == 0.toByte() }

    fun toByteArray(): ByteArray = bytes.copyOf()

    /**
     * Computes Kademlia 0-indexed bucket index: `floor(log2(distance))` in `0 until bitLength`.
     * Returns `-1` when distance is zero (identical node IDs).
     */
    fun bucketIndex(): Int {
        for (byteIdx in bytes.indices) {
            val unsigned = bytes[byteIdx].toInt() and 0xFF
            if (unsigned != 0) {
                val highestBitInByte = 31 - Integer.numberOfLeadingZeros(unsigned)
                val remainingBytes = bytes.size - 1 - byteIdx
                return remainingBytes * 8 + highestBitInByte
            }
        }
        return -1
    }

    override fun compareTo(other: XorDistance): Int {
        require(bitLength == other.bitLength) {
            "Cannot compare XOR distances of different bit lengths: $bitLength vs ${other.bitLength}"
        }
        for (i in bytes.indices) {
            val diff = (bytes[i].toInt() and 0xFF) - (other.bytes[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is XorDistance) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * Indicates whether a routing entry's identity binding has been cryptographically verified
 * via direct authenticated session/public key check, or only learned via unverified referrals.
 */
enum class RoutingTrustState {
    VERIFIED_IDENTITY,
    KNOWN_UNVERIFIED
}

/**
 * Public routing metadata stored in a [KBucket] and [KademliaRoutingTable].
 * Never contains private keys or session secrets.
 */
data class RoutingNodeEntry(
    val nodeId: RoutingNodeId,
    val deviceId: String,
    val identityFingerprint: String,
    val identityPublicKey: ByteArray? = null,
    val lastSeenAt: Long,
    val transportCapabilities: Set<TransportType> = emptySet(),
    val reachableTransports: Set<TransportType> = emptySet(),
    val endpointAddress: String? = null,
    val isDirectlyReachable: Boolean = false,
    val consecutiveFailures: Int = 0,
    val trustState: RoutingTrustState = RoutingTrustState.KNOWN_UNVERIFIED
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoutingNodeEntry) return false
        return nodeId == other.nodeId &&
            deviceId == other.deviceId &&
            identityFingerprint == other.identityFingerprint &&
            ((identityPublicKey == null && other.identityPublicKey == null) ||
                (identityPublicKey != null && other.identityPublicKey != null &&
                    identityPublicKey.contentEquals(other.identityPublicKey))) &&
            lastSeenAt == other.lastSeenAt &&
            transportCapabilities == other.transportCapabilities &&
            reachableTransports == other.reachableTransports &&
            endpointAddress == other.endpointAddress &&
            isDirectlyReachable == other.isDirectlyReachable &&
            consecutiveFailures == other.consecutiveFailures &&
            trustState == other.trustState
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
        result = 31 * result + isDirectlyReachable.hashCode()
        result = 31 * result + consecutiveFailures
        result = 31 * result + trustState.hashCode()
        return result
    }
}

/**
 * Persistence abstraction for routing table state (Firebase-independent).
 */
interface RoutingStateStore {
    suspend fun loadEntries(): List<RoutingNodeEntry>
    suspend fun saveEntries(entries: List<RoutingNodeEntry>)
}

class InMemoryRoutingStateStore : RoutingStateStore {
    private var entries: List<RoutingNodeEntry> = emptyList()

    override suspend fun loadEntries(): List<RoutingNodeEntry> = synchronized(this) { entries }

    override suspend fun saveEntries(entries: List<RoutingNodeEntry>) {
        synchronized(this) {
            this.entries = entries.toList()
        }
    }
}
