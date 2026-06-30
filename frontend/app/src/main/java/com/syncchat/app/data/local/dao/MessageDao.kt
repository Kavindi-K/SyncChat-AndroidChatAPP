package com.syncchat.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.syncchat.app.data.local.entities.CachedMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId ORDER BY timestampTime ASC")
    fun getMessagesForConversationFlow(conversationId: String): Flow<List<CachedMessage>>

    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId ORDER BY timestampTime DESC LIMIT :limit")
    suspend fun getMessagesForConversation(conversationId: String, limit: Int): List<CachedMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: CachedMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<CachedMessage>)

    @Query("DELETE FROM cached_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    // Offline queue queries
    @Query("SELECT * FROM cached_messages WHERE status = 'PENDING' ORDER BY timestampTime ASC")
    suspend fun getPendingMessages(): List<CachedMessage>

    @Query("UPDATE cached_messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)
}
