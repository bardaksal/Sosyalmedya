package com.example.p2p.forwarding

/**
 * Deduplication abstraction for preventing infinite forwarding loops when the same messageId
 * arrives across multiple paths. Designed so a Room/persistent implementation can replace
 * [InMemoryDuplicateMessageCache] in a later phase without changing [ForwardingEngine].
 */
interface DuplicateMessageCache {
    suspend fun isDuplicate(messageId: String): Boolean
    suspend fun markSeen(messageId: String, seenAtMs: Long)
}

/**
 * Bounded in-memory [DuplicateMessageCache] with deterministic TTL expiry and FIFO eviction.
 */
class InMemoryDuplicateMessageCache(
    private val maxEntries: Int = 4096,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : DuplicateMessageCache {

    private val lock = Any()
    private val seenAtById = LinkedHashMap<String, Long>(64, 0.75f, false)

    override suspend fun isDuplicate(messageId: String): Boolean {
        val now = clock()
        synchronized(lock) {
            pruneExpiredLocked(now)
            return seenAtById.containsKey(messageId)
        }
    }

    override suspend fun markSeen(messageId: String, seenAtMs: Long) {
        val now = clock()
        synchronized(lock) {
            pruneExpiredLocked(now)
            if (seenAtById.containsKey(messageId)) return
            while (seenAtById.size >= maxEntries) {
                val oldestKey = seenAtById.keys.firstOrNull() ?: break
                seenAtById.remove(oldestKey)
            }
            seenAtById[messageId] = seenAtMs
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val it = seenAtById.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value > retentionMs) {
                it.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_RETENTION_MS = 10 * 60 * 1000L
    }
}
