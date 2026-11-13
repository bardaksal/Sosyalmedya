package com.example.p2p.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.p2p.crypto.ReplayStore
import kotlinx.coroutines.flow.Flow

@Dao
interface OmniMeshDao {
    @Query("SELECT * FROM p2p_peers ORDER BY lastSeenAt DESC")
    fun observePeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM p2p_peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeerByDeviceId(deviceId: String): PeerEntity?

    @Query("SELECT * FROM p2p_peers WHERE bleAddress = :bleAddress LIMIT 1")
    suspend fun getPeerByAddress(bleAddress: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("SELECT * FROM p2p_messages ORDER BY timestamp ASC")
    fun observeAllMessages(): Flow<List<LocalMessageEntity>>

    @Query("SELECT * FROM p2p_messages WHERE peerDeviceId = :peerDeviceId ORDER BY timestamp ASC")
    fun observeMessagesForPeer(peerDeviceId: String): Flow<List<LocalMessageEntity>>

    @Query("SELECT * FROM p2p_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): LocalMessageEntity?

    @Query("SELECT * FROM p2p_messages WHERE peerDeviceId = :peerDeviceId AND direction = 'OUTGOING' AND state IN ('CREATED', 'FAILED') ORDER BY timestamp ASC")
    suspend fun getPendingOutgoingForPeer(peerDeviceId: String): List<LocalMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: LocalMessageEntity)

    @Query("UPDATE p2p_messages SET state = :state, errorReason = :errorReason, updatedAt = :updatedAt WHERE messageId = :messageId")
    suspend fun updateMessageState(
        messageId: String,
        state: String,
        errorReason: String?,
        updatedAt: Long
    )

    @Query("SELECT EXISTS(SELECT 1 FROM p2p_seen_messages WHERE messageId = :messageId)")
    suspend fun isMessageSeen(messageId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeenMessage(entry: SeenMessageEntity)

    @Query("DELETE FROM p2p_seen_messages WHERE expiresAt < :now")
    suspend fun pruneExpiredSeenMessages(now: Long)
}

@Database(
    entities = [PeerEntity::class, LocalMessageEntity::class, SeenMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class OmniMeshDatabase : RoomDatabase() {
    abstract fun omniMeshDao(): OmniMeshDao

    companion object {
        @Volatile
        private var INSTANCE: OmniMeshDatabase? = null

        fun getInstance(context: Context): OmniMeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniMeshDatabase::class.java,
                    "omnimesh_p2p.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class OmniMeshLocalRepository(
    private val dao: OmniMeshDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ReplayStore {

    val peers: Flow<List<PeerEntity>> = dao.observePeers()
    val allMessages: Flow<List<LocalMessageEntity>> = dao.observeAllMessages()

    fun observeMessagesForPeer(peerDeviceId: String): Flow<List<LocalMessageEntity>> =
        dao.observeMessagesForPeer(peerDeviceId)

    suspend fun getPeerByDeviceId(deviceId: String): PeerEntity? =
        dao.getPeerByDeviceId(deviceId)

    suspend fun getPeerByAddress(bleAddress: String): PeerEntity? =
        dao.getPeerByAddress(bleAddress)

    suspend fun saveVerifiedPeer(peer: PeerEntity) {
        dao.upsertPeer(peer)
    }

    suspend fun createOutgoingMessage(
        messageId: String,
        peerDeviceId: String,
        senderDeviceId: String,
        plaintext: String,
        timestamp: Long = clock()
    ): LocalMessageEntity {
        val entity = LocalMessageEntity(
            messageId = messageId,
            peerDeviceId = peerDeviceId,
            senderDeviceId = senderDeviceId,
            receiverDeviceId = peerDeviceId,
            direction = MessageDirection.OUTGOING.name,
            plaintext = plaintext,
            state = MessageDeliveryState.CREATED.name,
            errorReason = null,
            timestamp = timestamp,
            updatedAt = timestamp
        )
        dao.upsertMessage(entity)
        return entity
    }

    suspend fun saveIncomingMessage(
        messageId: String,
        senderDeviceId: String,
        receiverDeviceId: String,
        plaintext: String,
        timestamp: Long
    ) {
        val entity = LocalMessageEntity(
            messageId = messageId,
            peerDeviceId = senderDeviceId,
            senderDeviceId = senderDeviceId,
            receiverDeviceId = receiverDeviceId,
            direction = MessageDirection.INCOMING.name,
            plaintext = plaintext,
            state = MessageDeliveryState.DELIVERED.name,
            errorReason = null,
            timestamp = timestamp,
            updatedAt = clock()
        )
        dao.upsertMessage(entity)
    }

    suspend fun updateMessageState(
        messageId: String,
        state: MessageDeliveryState,
        errorReason: String? = null
    ) {
        dao.updateMessageState(
            messageId = messageId,
            state = state.name,
            errorReason = errorReason,
            updatedAt = clock()
        )
    }

    suspend fun getPendingOutgoingForPeer(peerDeviceId: String): List<LocalMessageEntity> =
        dao.getPendingOutgoingForPeer(peerDeviceId)

    override suspend fun isMessageSeen(messageId: String): Boolean =
        dao.isMessageSeen(messageId)

    override suspend fun recordMessageSeen(
        messageId: String,
        senderDeviceId: String,
        nonceHex: String,
        seenAt: Long,
        expiresAt: Long
    ) {
        dao.insertSeenMessage(
            SeenMessageEntity(
                messageId = messageId,
                senderDeviceId = senderDeviceId,
                nonceHex = nonceHex,
                seenAt = seenAt,
                expiresAt = expiresAt
            )
        )
        dao.pruneExpiredSeenMessages(seenAt)
    }
}
