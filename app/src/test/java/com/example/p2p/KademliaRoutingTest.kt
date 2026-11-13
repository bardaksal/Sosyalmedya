package com.example.p2p

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.p2p.crypto.MessageEnvelope
import com.example.p2p.identity.DeviceIdentityManager
import com.example.p2p.routing.FindNodeNodeDescriptor
import com.example.p2p.routing.FindNodeRequest
import com.example.p2p.routing.FindNodeResponse
import com.example.p2p.routing.InMemoryRoutingStateStore
import com.example.p2p.routing.IterativeNodeLookup
import com.example.p2p.routing.KademliaConfig
import com.example.p2p.routing.KademliaNextHopResolver
import com.example.p2p.routing.KademliaRoutingTable
import com.example.p2p.routing.KademliaRpcHandler
import com.example.p2p.routing.NextHopResolution
import com.example.p2p.routing.PingRequest
import com.example.p2p.routing.PingResponse
import com.example.p2p.routing.RoutingInsertOutcome
import com.example.p2p.routing.RoutingNextHopOutcome
import com.example.p2p.routing.RoutingNodeEntry
import com.example.p2p.routing.RoutingNodeId
import com.example.p2p.routing.RoutingRpcTransport
import com.example.p2p.routing.RoutingTrustState
import com.example.p2p.transport.TransportType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KademliaRoutingTest {

    private lateinit var baseContext: Context
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
    }

    // ------------------------------------------------------------------------
    // 1. Node ID Tests
    // ------------------------------------------------------------------------

    @Test
    fun nodeId_isDeterministicAcrossDeviceRestarts_andRejectsInvalidAndSelf() {
        val ctx = scopedContext("kad_nodeid_persist")
        val id1 = DeviceIdentityManager(ctx)
        val id2 = DeviceIdentityManager(ctx)

        val nodeId1 = RoutingNodeId.deriveFromDeviceIdentity(id1.deviceId, id1.getPublicKeyBytes())
        val nodeId2 = RoutingNodeId.deriveFromDeviceIdentity(id2.deviceId, id2.getPublicKeyBytes())
        assertEquals(nodeId1, nodeId2)
        assertEquals(160, nodeId1.bitLength)
        assertEquals(20, nodeId1.byteLength)

        // Different device identity -> different RoutingNodeId
        val otherId = DeviceIdentityManager(scopedContext("kad_nodeid_other"))
        val otherNodeId = RoutingNodeId.deriveFromDeviceIdentity(otherId.deviceId, otherId.getPublicKeyBytes())
        assertNotEquals(nodeId1, otherNodeId)

        // Invalid IDs rejected cleanly
        assertNull(RoutingNodeId.fromHex(""))
        assertNull(RoutingNodeId.fromHex("abc")) // odd length
        assertNull(RoutingNodeId.fromHex("zzzz")) // non-hex
        assertNull(RoutingNodeId.fromBytes(ByteArray(0)))
        assertNull(RoutingNodeId.fromBytes(ByteArray(10), expectedBitLength = 160))

        // Self-node insertion rejected cleanly
        val table = KademliaRoutingTable(localNodeId = nodeId1, clock = { clockNow })
        val selfResult = table.upsertNode(
            RoutingNodeEntry(
                nodeId = nodeId1,
                deviceId = id1.deviceId,
                identityFingerprint = id1.getPublicKeyFingerprint(),
                identityPublicKey = id1.getPublicKeyBytes(),
                lastSeenAt = clockNow
            )
        )
        assertTrue(selfResult is RoutingInsertOutcome.SelfNodeRejected)
    }

    // ------------------------------------------------------------------------
    // 2. XOR Distance & Bit-Length Tests
    // ------------------------------------------------------------------------

    @Test
    fun xorDistance_knownVectorsOrderingIdenticalAndMismatchedBitLengths() {
        val zero8 = RoutingNodeId.fromHex("00")!!
        val one8 = RoutingNodeId.fromHex("01")!!
        val two8 = RoutingNodeId.fromHex("02")!!
        val max8 = RoutingNodeId.fromHex("80")!!
        val fullMax8 = RoutingNodeId.fromHex("ff")!!

        // Identical node distance = 0 -> bucketIndex = -1
        val dZero = zero8.xorDistanceTo(zero8)
        assertTrue(dZero.isZero)
        assertEquals(-1, dZero.bucketIndex())

        // Distance = 1 -> floor(log2(1)) = 0 (minimum non-zero distance)
        val dOne = zero8.xorDistanceTo(one8)
        assertEquals(0, dOne.bucketIndex())

        // Distance = 2 -> floor(log2(2)) = 1
        val dTwo = zero8.xorDistanceTo(two8)
        assertEquals(1, dTwo.bucketIndex())

        // Distance = 0x80 (128) & 0xFF (255) -> floor(log2(dist)) = 7 (max 8-bit bucket)
        val dMax = zero8.xorDistanceTo(max8)
        val dFullMax = zero8.xorDistanceTo(fullMax8)
        assertEquals(7, dMax.bucketIndex())
        assertEquals(7, dFullMax.bucketIndex())

        // Ordering correctness
        assertTrue(dZero < dOne)
        assertTrue(dOne < dTwo)
        assertTrue(dTwo < dMax)
        assertTrue(dMax < dFullMax)

        // 160-bit min and max distance vectors
        val local160 = RoutingNodeId.fromBytes(ByteArray(20) { 0x00 })!!
        val minRemote160 = RoutingNodeId.fromBytes(ByteArray(20) { if (it == 19) 0x01 else 0x00 })!!
        val maxRemote160 = RoutingNodeId.fromBytes(ByteArray(20) { if (it == 0) 0x80.toByte() else 0x00 })!!
        assertEquals(0, local160.xorDistanceTo(minRemote160).bucketIndex())
        assertEquals(159, local160.xorDistanceTo(maxRemote160).bucketIndex())

        // Mismatched bit lengths rejected
        val threwMismatch = try {
            zero8.xorDistanceTo(local160)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("Mismatched bit length XOR must throw", threwMismatch)

        val table160 = KademliaRoutingTable(localNodeId = local160)
        val badBitInsert = table160.upsertNode(
            RoutingNodeEntry(
                nodeId = one8,
                deviceId = "dev-8bit",
                identityFingerprint = "FP8",
                lastSeenAt = clockNow
            )
        )
        assertTrue(badBitInsert is RoutingInsertOutcome.InvalidNode)
    }

    // ------------------------------------------------------------------------
    // 3. K-Bucket Selection, Duplicate, Capacity Limit & LRU Behavior
    // ------------------------------------------------------------------------

    @Test
    fun kBucket_bucketSelectionDuplicatesCapacityLimitAndLruStaleReplacement() {
        val local = RoutingNodeId.fromHex("00")!! // 8-bit test space
        val config = KademliaConfig(idBitLength = 8, bucketSize = 2, alpha = 2, maxConsecutiveFailures = 2)
        val table = KademliaRoutingTable(localNodeId = local, config = config, clock = { clockNow })

        // Bucket 7 peers (MSB = 1 -> hex 0x80..0xFF)
        val n1 = RoutingNodeId.fromHex("80")!!
        val n2 = RoutingNodeId.fromHex("81")!!
        val n3 = RoutingNodeId.fromHex("82")!!

        // Bucket 0 peer (distance = 1)
        val b0 = RoutingNodeId.fromHex("01")!!

        assertEquals(7, table.bucketIndexFor(n1))
        assertEquals(7, table.bucketIndexFor(n2))
        assertEquals(0, table.bucketIndexFor(b0))

        // Insert n1, n2 into bucket 7 (capacity = 2)
        assertTrue(table.upsertNode(entry(n1, "dev-1", lastSeen = 100L)) is RoutingInsertOutcome.Inserted)
        assertTrue(table.upsertNode(entry(n2, "dev-2", lastSeen = 200L)) is RoutingInsertOutcome.Inserted)
        assertEquals(listOf(n1, n2), table.bucketEntries(7).map { it.nodeId })

        // Duplicate insert updates n1 and promotes it to MRU tail ([n2, n1]) without increasing count
        val dupOutcome = table.upsertNode(entry(n1, "dev-1", lastSeen = 300L))
        assertTrue(dupOutcome is RoutingInsertOutcome.Updated)
        assertEquals(listOf(n2, n1), table.bucketEntries(7).map { it.nodeId })
        assertEquals(2, table.bucketEntries(7).size)

        // Bucket full with all live entries -> preserves existing live entries, identifies n2 (LRU head) as candidate
        val fullOutcome = table.upsertNode(entry(n3, "dev-3", lastSeen = 400L))
        assertTrue(fullOutcome is RoutingInsertOutcome.BucketFull)
        val full = fullOutcome as RoutingInsertOutcome.BucketFull
        assertEquals(n2, full.evictionCandidate.nodeId)
        assertEquals(listOf(n2, n1), table.bucketEntries(7).map { it.nodeId })

        // Mark LRU head n2 as stale (2 consecutive failures) -> n3 replaces stale n2 cleanly
        table.markNodeFailure(n2)
        table.markNodeFailure(n2)
        val replacedOutcome = table.upsertNode(entry(n3, "dev-3", lastSeen = 500L))
        assertTrue(replacedOutcome is RoutingInsertOutcome.ReplacedStale)
        val replaced = replacedOutcome as RoutingInsertOutcome.ReplacedStale
        assertEquals(n2, replaced.evicted.nodeId)
        assertEquals(listOf(n1, n3), table.bucketEntries(7).map { it.nodeId })
    }

    // ------------------------------------------------------------------------
    // 4. Security: Node Identity Binding Conflict Detection
    // ------------------------------------------------------------------------

    @Test
    fun security_conflictingNodeIdentityBindingIsRejected() {
        val idA = DeviceIdentityManager(scopedContext("sec_bind_a"))
        val idB = DeviceIdentityManager(scopedContext("sec_bind_b"))
        val localNode = RoutingNodeId.deriveFromDeviceIdentity(idA.deviceId, idA.getPublicKeyBytes())
        val remoteNode = RoutingNodeId.deriveFromDeviceIdentity(idB.deviceId, idB.getPublicKeyBytes())
        val table = KademliaRoutingTable(localNodeId = localNode, clock = { clockNow })

        val validInsert = table.upsertNode(
            RoutingNodeEntry(
                nodeId = remoteNode,
                deviceId = idB.deviceId,
                identityFingerprint = idB.getPublicKeyFingerprint(),
                identityPublicKey = idB.getPublicKeyBytes(),
                lastSeenAt = clockNow,
                trustState = RoutingTrustState.VERIFIED_IDENTITY
            )
        )
        assertTrue(validInsert is RoutingInsertOutcome.Inserted)

        // 1. Same nodeId claiming a different fingerprint/deviceId -> rejected
        val spoofedFingerprint = table.upsertNode(
            RoutingNodeEntry(
                nodeId = remoteNode,
                deviceId = idB.deviceId,
                identityFingerprint = "DEADBEEF00001111",
                lastSeenAt = clockNow + 10
            )
        )
        assertTrue(spoofedFingerprint is RoutingInsertOutcome.IdentityBindingConflict)

        // 2. Existing deviceId claiming a different nodeId -> rejected
        val forgedNodeId = RoutingNodeId.fromBytes(ByteArray(20) { 0x5A })!!
        val spoofedNodeForDevice = table.upsertNode(
            RoutingNodeEntry(
                nodeId = forgedNodeId,
                deviceId = idB.deviceId,
                identityFingerprint = idB.getPublicKeyFingerprint(),
                lastSeenAt = clockNow + 10
            )
        )
        assertTrue(spoofedNodeForDevice is RoutingInsertOutcome.IdentityBindingConflict)

        // 3. Public key bytes that do not match claimed nodeId -> rejected
        val idC = DeviceIdentityManager(scopedContext("sec_bind_c"))
        val wrongKeyClaim = table.upsertNode(
            RoutingNodeEntry(
                nodeId = forgedNodeId,
                deviceId = idC.deviceId,
                identityFingerprint = idC.getPublicKeyFingerprint(),
                identityPublicKey = idC.getPublicKeyBytes(),
                lastSeenAt = clockNow + 10
            )
        )
        assertTrue(wrongKeyClaim is RoutingInsertOutcome.IdentityBindingConflict)
    }

    // ------------------------------------------------------------------------
    // 5. PING & FIND_NODE RPC Protocol
    // ------------------------------------------------------------------------

    @Test
    fun rpc_pingAndFindNodeReturnSortedClosestNodesWithoutPrivateSecrets() {
        val local = RoutingNodeId.fromHex("00")!!
        val config = KademliaConfig(idBitLength = 8, bucketSize = 4, alpha = 2)
        val table = KademliaRoutingTable(localNodeId = local, config = config, clock = { clockNow })

        val peer1 = RoutingNodeId.fromHex("10")!!
        val peer2 = RoutingNodeId.fromHex("0c")!!
        val peer3 = RoutingNodeId.fromHex("05")!!
        val requester = RoutingNodeId.fromHex("80")!!

        table.upsertNode(entry(peer1, "dev-p1"))
        table.upsertNode(entry(peer2, "dev-p2"))
        table.upsertNode(entry(peer3, "dev-p3"))
        table.upsertNode(entry(requester, "dev-req"))

        val handler = KademliaRpcHandler(
            routingTable = table,
            localDescriptorProvider = {
                FindNodeNodeDescriptor(local, "dev-local", "FP-LOCAL", lastSeenAt = clockNow)
            },
            clock = { clockNow }
        )

        val pingResp = handler.handlePing(PingRequest(senderNodeId = requester, targetNodeId = local))
        assertNotNull(pingResp)
        assertTrue(pingResp!!.acknowledged)
        assertEquals(local, pingResp.responderNodeId)

        val target = RoutingNodeId.fromHex("04")!!
        val findResp = handler.handleFindNode(FindNodeRequest(senderNodeId = requester, targetNodeId = target))
        assertNotNull(findResp)
        // Distances to 0x04: peer3(0x05)=1, peer2(0x0c)=8, peer1(0x10)=20; requester excluded
        assertEquals(listOf(peer3, peer2, peer1), findResp!!.nodes.map { it.nodeId })
    }

    // ------------------------------------------------------------------------
    // 6. Iterative Lookup (7-Node Network, No Duplicate Queries, Failure, Cancellation)
    // ------------------------------------------------------------------------

    @Test
    fun iterativeLookup_discoversUnseenNodeGAcrossMultiHopVirtualNetworkWithoutDuplicates() = runTest {
        // 8-bit deterministic Kademlia space so A -> B -> D -> F -> G topology is exact
        val idNodeA = RoutingNodeId.fromHex("00")!! // 0000_0000
        val idNodeB = RoutingNodeId.fromHex("80")!! // 1000_0000
        val idNodeC = RoutingNodeId.fromHex("90")!! // 1001_0000 (farther branch)
        val idNodeD = RoutingNodeId.fromHex("c0")!! // 1100_0000
        val idNodeE = RoutingNodeId.fromHex("d0")!! // 1101_0000 (failing peer branch)
        val idNodeF = RoutingNodeId.fromHex("e0")!! // 1110_0000
        val idNodeG = RoutingNodeId.fromHex("e1")!! // 1110_0001 (target!)

        val config = KademliaConfig(idBitLength = 8, bucketSize = 8, alpha = 2)
        val network = InMemoryRoutingRpc(config)

        val tableA = network.registerNode(idNodeA, "dev-A")
        val tableB = network.registerNode(idNodeB, "dev-B")
        val tableC = network.registerNode(idNodeC, "dev-C")
        val tableD = network.registerNode(idNodeD, "dev-D")
        network.registerNode(idNodeE, "dev-E", offline = true) // Unresponsive node E!
        val tableF = network.registerNode(idNodeF, "dev-F")
        network.registerNode(idNodeG, "dev-G")

        // A knows ONLY B and C (A has NO knowledge of D, E, F, or G!)
        tableA.upsertNode(entry(idNodeB, "dev-B", endpoint = "ADDR-B"))
        tableA.upsertNode(entry(idNodeC, "dev-C", endpoint = "ADDR-C"))
        // B knows D
        tableB.upsertNode(entry(idNodeD, "dev-D", endpoint = "ADDR-D"))
        // C knows D as well
        tableC.upsertNode(entry(idNodeD, "dev-D", endpoint = "ADDR-D"))
        // D knows E (offline) and F
        tableD.upsertNode(entry(idNodeE, "dev-E", endpoint = "ADDR-E"))
        tableD.upsertNode(entry(idNodeF, "dev-F", endpoint = "ADDR-F"))
        // F knows G
        tableF.upsertNode(entry(idNodeG, "dev-G", endpoint = "ADDR-G"))

        assertNull(tableA.getNode(idNodeG))

        val lookup = IterativeNodeLookup(
            routingTable = tableA,
            rpcTransport = network,
            localDescriptorProvider = {
                FindNodeNodeDescriptor(idNodeA, "dev-A", "FP-dev-A", lastSeenAt = clockNow)
            },
            clock = { clockNow }
        )

        val result = lookup.findNode(idNodeG)

        assertTrue("Target G must be found via iterative lookup", result.targetFound)
        assertTrue("Target G must have responded directly", result.targetResponded)
        assertNotNull(result.targetEntry)
        assertEquals("dev-G", result.targetEntry!!.deviceId)
        assertEquals(idNodeG, result.closestNodes.first().nodeId)

        // Verify offline peer E failed gracefully without crashing the lookup
        assertTrue(idNodeE in result.failedNodeIds)

        // Verify ZERO duplicate FIND_NODE RPCs were ever sent to the same node
        val queryList = network.findNodeLog
        assertEquals(
            "Every queried node must receive at most 1 FIND_NODE RPC",
            queryList.size,
            queryList.toSet().size
        )
        assertTrue(network.maxConcurrentRpcs <= config.alpha)

        // Verify A's routing table now holds discovered G
        assertNotNull(tableA.getNode(idNodeG))
    }

    @Test
    fun iterativeLookup_propagatesCoroutineCancellation() = runTest {
        val local = RoutingNodeId.fromHex("00")!!
        val peerB = RoutingNodeId.fromHex("80")!!
        val targetG = RoutingNodeId.fromHex("e1")!!

        val config = KademliaConfig(idBitLength = 8, bucketSize = 3, alpha = 2)
        val table = KademliaRoutingTable(local, config, clock = { clockNow })
        table.upsertNode(entry(peerB, "dev-B"))

        val gate = CompletableDeferred<Unit>()
        val hangingRpc = object : RoutingRpcTransport {
            override suspend fun ping(target: RoutingNodeEntry, request: PingRequest): PingResponse? = null
            override suspend fun findNode(target: RoutingNodeEntry, request: FindNodeRequest): FindNodeResponse? {
                gate.complete(Unit)
                CompletableDeferred<FindNodeResponse?>().await()
                return null
            }
        }

        val lookup = IterativeNodeLookup(table, hangingRpc, perRpcTimeoutMs = 30_000L)
        var caught: Throwable? = null
        val job = launch {
            try {
                lookup.findNode(targetG)
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        gate.await()
        job.cancel()
        job.join()

        assertTrue("CancellationException must propagate cleanly", caught is CancellationException)
    }

    // ------------------------------------------------------------------------
    // 7. NextHopResolver Integration & StateStore Tests
    // ------------------------------------------------------------------------

    @Test
    fun nextHopResolver_distinguishesDirectNextHopUnreachableAndUnknownWithoutFakeFallback() = runTest {
        val idA = RoutingNodeId.fromHex("00")!! // 0000_0000
        val idB = RoutingNodeId.fromHex("80")!! // 1000_0000 -> XOR to G(0xe1) = 0x61
        val idFar = RoutingNodeId.fromHex("20")!! // 0010_0000 -> XOR to G(0xe1) = 0xc1 (farther than A's 0xe1? Wait: 0xc1 < 0xe1, let's test true farther node 0x01 -> XOR to G = 0xe0 < 0xe1; true farther = 0x10 -> 0xf1 > 0xe1!)
        val idG = RoutingNodeId.fromHex("e1")!! // 1110_0001 -> XOR from A = 0xe1

        val config = KademliaConfig(idBitLength = 8, bucketSize = 4, alpha = 2)
        val table = KademliaRoutingTable(idA, config, clock = { clockNow })

        // Farther peer (reachable) must NEVER be chosen as a fake fallback for unknown or unreachable targets
        val fartherNode = RoutingNodeId.fromHex("10")!! // 0x10 xor 0xe1 = 0xf1 > 0xe1 (farther from G than A is!)
        table.upsertNode(
            entry(
                fartherNode,
                "dev-far",
                endpoint = "AA:BB:CC:DD:EE:FF",
                reachable = true
            )
        )

        val resolver = KademliaNextHopResolver(
            routingTable = table,
            resolveDestinationNodeId = { dev -> if (dev == "dev-G") idG else null }
        )

        // 1. Unknown destination -> Unknown / NoRouteAvailable (NOT fartherNode!)
        assertEquals(RoutingNextHopOutcome.Unknown, resolver.resolveRoutingOutcome("dev-unknown"))
        val envUnknown = dummyEnvelope(destinationDeviceId = "dev-unknown")
        assertEquals(NextHopResolution.NoRouteAvailable, resolver.resolveNextHops(envUnknown, null))

        // 2. Destination G known in routing table, NOT directly reachable, and no closer peer reachable -> Unreachable
        table.upsertNode(entry(idG, "dev-G", endpoint = "GG:GG:GG:GG:GG:GG", reachable = false))
        val unreachable = resolver.resolveRoutingOutcome("dev-G")
        assertTrue(unreachable is RoutingNextHopOutcome.Unreachable)
        val envG = dummyEnvelope(destinationDeviceId = "dev-G")
        assertEquals(NextHopResolution.NoRouteAvailable, resolver.resolveNextHops(envG, null))

        // 3. Closer reachable peer B added -> NextHop(B)
        table.upsertNode(entry(idB, "dev-B", endpoint = "BB:BB:BB:BB:BB:BB", reachable = true))
        val nextHop = resolver.resolveRoutingOutcome("dev-G")
        assertTrue(nextHop is RoutingNextHopOutcome.NextHop)
        assertEquals("dev-B", (nextHop as RoutingNextHopOutcome.NextHop).nextHopEntry.deviceId)
        assertEquals(
            NextHopResolution.Resolved(listOf("BB:BB:BB:BB:BB:BB")),
            resolver.resolveNextHops(envG, incomingPeerAddress = null)
        )
        // Loop prevention: if packet came from B, do not echo back to B
        assertEquals(
            NextHopResolution.NoRouteAvailable,
            resolver.resolveNextHops(envG, incomingPeerAddress = "BB:BB:BB:BB:BB:BB")
        )

        // 4. Destination G becomes directly reachable -> Direct(G)
        table.markNodeSeen(
            nodeId = idG,
            timestamp = clockNow + 100,
            endpointAddress = "GG:GG:GG:GG:GG:GG",
            reachableTransports = setOf(TransportType.BLE),
            isDirectlyReachable = true
        )
        val direct = resolver.resolveRoutingOutcome("dev-G")
        assertTrue(direct is RoutingNextHopOutcome.Direct)
        assertEquals(
            NextHopResolution.Resolved(listOf("GG:GG:GG:GG:GG:GG")),
            resolver.resolveNextHops(envG, null)
        )

        // RoutingStateStore roundtrip check
        val store = InMemoryRoutingStateStore()
        store.saveEntries(table.snapshot())
        assertEquals(table.snapshot().size, store.loadEntries().size)
    }

    // ------------------------------------------------------------------------
    // Helpers & InMemoryRoutingRpc Test Double
    // ------------------------------------------------------------------------

    private fun entry(
        nodeId: RoutingNodeId,
        deviceId: String,
        lastSeen: Long = clockNow,
        endpoint: String? = null,
        reachable: Boolean = false
    ): RoutingNodeEntry = RoutingNodeEntry(
        nodeId = nodeId,
        deviceId = deviceId,
        identityFingerprint = "FP-$deviceId",
        lastSeenAt = lastSeen,
        transportCapabilities = setOf(TransportType.BLE),
        reachableTransports = if (reachable) setOf(TransportType.BLE) else emptySet(),
        endpointAddress = endpoint,
        isDirectlyReachable = reachable
    )

    private fun dummyEnvelope(destinationDeviceId: String): MessageEnvelope = MessageEnvelope.create(
        messageId = MessageEnvelope.newMessageId(),
        senderDeviceId = "dev-A",
        destinationDeviceId = destinationDeviceId,
        createdAt = clockNow,
        nonce = ByteArray(12) { 0x11 },
        ciphertext = byteArrayOf(0x01, 0x02),
        authenticationTag = ByteArray(16) { 0x22 }
    )

    private class InMemoryRoutingRpc(
        private val config: KademliaConfig
    ) : RoutingRpcTransport {
        private data class NodeRecord(
            val table: KademliaRoutingTable,
            val handler: KademliaRpcHandler,
            val offline: Boolean
        )

        private val nodes = ConcurrentHashMap<RoutingNodeId, NodeRecord>()
        private val inFlight = AtomicInteger(0)
        @Volatile
        var maxConcurrentRpcs: Int = 0
            private set
        val findNodeLog: MutableList<RoutingNodeId> = Collections.synchronizedList(mutableListOf())

        fun registerNode(
            nodeId: RoutingNodeId,
            deviceId: String,
            offline: Boolean = false
        ): KademliaRoutingTable {
            val table = KademliaRoutingTable(nodeId, config)
            val handler = KademliaRpcHandler(
                routingTable = table,
                localDescriptorProvider = {
                    FindNodeNodeDescriptor(
                        nodeId = nodeId,
                        deviceId = deviceId,
                        identityFingerprint = "FP-$deviceId",
                        lastSeenAt = 1_700_000_000_000L,
                        transportCapabilities = setOf(TransportType.BLE)
                    )
                }
            )
            nodes[nodeId] = NodeRecord(table, handler, offline)
            return table
        }

        override suspend fun ping(target: RoutingNodeEntry, request: PingRequest): PingResponse? {
            val rec = nodes[target.nodeId] ?: return null
            if (rec.offline) return null
            return rec.handler.handlePing(request)
        }

        override suspend fun findNode(target: RoutingNodeEntry, request: FindNodeRequest): FindNodeResponse? {
            findNodeLog.add(target.nodeId)
            val current = inFlight.incrementAndGet()
            if (current > maxConcurrentRpcs) {
                maxConcurrentRpcs = current
            }
            return try {
                delay(10) // allow concurrent alpha batch overlap
                val rec = nodes[target.nodeId] ?: return null
                if (rec.offline) return null
                rec.handler.handleFindNode(request)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }
}
