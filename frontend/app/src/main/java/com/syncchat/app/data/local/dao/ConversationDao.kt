package com.syncchat.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.syncchat.app.data.local.entities.CachedConversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM cached_conversations WHERE participantUidsString LIKE '%' || :userId || '%' ORDER BY updatedAtTime DESC")
    fun getAllConversationsFlow(userId: String): Flow<List<CachedConversation>>

    @Query("SELECT * FROM cached_conversations WHERE id = :id")
    suspend fun getConversationById(id: String): CachedConversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: CachedConversation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<CachedConversation>)
}
