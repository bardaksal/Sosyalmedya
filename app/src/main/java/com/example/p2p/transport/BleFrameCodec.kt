package com.example.p2p.transport

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Splits arbitrary protocol payloads into MTU-sized BLE ATT chunks and reassembles them
 * reliably on the receiving side.
 *
 * Chunk header format (9 bytes):
 * - magic: 1 byte (0x4F)
 * - frameId: 2 bytes (unsigned short)
 * - chunkIndex: 2 bytes (unsigned short)
 * - totalChunks: 2 bytes (unsigned short)
 * - payloadLength: 2 bytes (unsigned short)
 */
object BleFrameCodec {

    const val MAGIC_BYTE: Byte = 0x4F
    const val HEADER_SIZE_BYTES = 9
    const val MIN_ATT_PAYLOAD = 20
    const val MAX_FRAME_PAYLOAD_BYTES = 128 * 1024
    private const val MAX_CHUNKS = 4096

    private val frameSeq = AtomicInteger(1)

    fun nextFrameId(): Int = frameSeq.getAndIncrement() and 0xFFFF

    fun chunk(payload: ByteArray, maxAttPayloadBytes: Int, frameId: Int = nextFrameId()): List<ByteArray> {
        require(payload.size <= MAX_FRAME_PAYLOAD_BYTES) { "Payload exceeds maximum frame size" }
        val effectiveAtt = maxAttPayloadBytes.coerceAtLeast(MIN_ATT_PAYLOAD)
        val maxChunkData = (effectiveAtt - HEADER_SIZE_BYTES).coerceAtLeast(1)

        if (payload.isEmpty()) {
            return listOf(buildChunk(frameId, 0, 1, ByteArray(0)))
        }

        val totalChunks = (payload.size + maxChunkData - 1) / maxChunkData
        require(totalChunks <= MAX_CHUNKS) { "Too many chunks: $totalChunks" }

        val chunks = ArrayList<ByteArray>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * maxChunkData
            val end = minOf(start + maxChunkData, payload.size)
            val slice = payload.copyOfRange(start, end)
            chunks.add(buildChunk(frameId, i, totalChunks, slice))
        }
        return chunks
    }

    private fun buildChunk(
        frameId: Int,
        chunkIndex: Int,
        totalChunks: Int,
        data: ByteArray
    ): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE_BYTES + data.size)
        buf.put(MAGIC_BYTE)
        buf.putShort(frameId.toShort())
        buf.putShort(chunkIndex.toShort())
        buf.putShort(totalChunks.toShort())
        buf.putShort(data.size.toShort())
        buf.put(data)
        return buf.array()
    }

    data class DecodedChunk(
        val frameId: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: ByteArray
    )

    fun decodeChunk(packet: ByteArray): DecodedChunk? {
        if (packet.size < HEADER_SIZE_BYTES) return null
        val buf = ByteBuffer.wrap(packet)
        if (buf.get() != MAGIC_BYTE) return null
        val frameId = buf.short.toInt() and 0xFFFF
        val chunkIndex = buf.short.toInt() and 0xFFFF
        val totalChunks = buf.short.toInt() and 0xFFFF
        val payloadLen = buf.short.toInt() and 0xFFFF

        if (totalChunks !in 1..MAX_CHUNKS) return null
        if (chunkIndex >= totalChunks) return null
        if (buf.remaining() != payloadLen) return null

        val data = ByteArray(payloadLen)
        buf.get(data)
        return DecodedChunk(frameId, chunkIndex, totalChunks, data)
    }
}

/**
 * Thread-safe per-peer chunk reassembler with bounded buffer limits.
 */
class BleFrameReassembler(
    private val maxPendingFramesPerPeer: Int = 16,
    private val frameTimeoutMs: Long = 30_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private data class AssemblyKey(val peerAddress: String, val frameId: Int)
    private class PendingFrame(
        val totalChunks: Int,
        val createdAt: Long,
        val parts: Array<ByteArray?> = arrayOfNulls(totalChunks),
        var receivedCount: Int = 0,
        var totalBytes: Int = 0
    )

    private val lock = Any()
    private val pending = LinkedHashMap<AssemblyKey, PendingFrame>()

    fun onChunkReceived(peerAddress: String, rawChunk: ByteArray): ByteArray? {
        val decoded = BleFrameCodec.decodeChunk(rawChunk) ?: return null
        val now = clock()
        synchronized(lock) {
            pruneExpiredLocked(now)
            val key = AssemblyKey(peerAddress, decoded.frameId)
            val entry = pending.getOrPut(key) {
                evictOldestIfPeerFullLocked(peerAddress)
                PendingFrame(decoded.totalChunks, now)
            }
            if (entry.totalChunks != decoded.totalChunks) {
                pending.remove(key)
                return null
            }
            if (decoded.chunkIndex >= entry.totalChunks) return null
            if (entry.parts[decoded.chunkIndex] == null) {
                if (entry.totalBytes + decoded.data.size > BleFrameCodec.MAX_FRAME_PAYLOAD_BYTES) {
                    pending.remove(key)
                    return null
                }
                entry.parts[decoded.chunkIndex] = decoded.data
                entry.receivedCount++
                entry.totalBytes += decoded.data.size
            }
            if (entry.receivedCount == entry.totalChunks) {
                pending.remove(key)
                val combined = ByteArray(entry.totalBytes)
                var offset = 0
                for (part in entry.parts) {
                    val p = part ?: return null
                    System.arraycopy(p, 0, combined, offset, p.size)
                    offset += p.size
                }
                return combined
            }
            return null
        }
    }

    fun clearPeer(peerAddress: String) {
        synchronized(lock) {
            pending.keys.removeAll { it.peerAddress == peerAddress }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            pending.clear()
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdAt > frameTimeoutMs) {
                it.remove()
            }
        }
    }

    private fun evictOldestIfPeerFullLocked(peerAddress: String) {
        var count = 0
        var oldestKey: AssemblyKey? = null
        for ((k, _) in pending) {
            if (k.peerAddress == peerAddress) {
                if (oldestKey == null) oldestKey = k
                count++
            }
        }
        if (count >= maxPendingFramesPerPeer && oldestKey != null) {
            pending.remove(oldestKey)
        }
    }
}
