package com.syncchat.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.syncchat.app.data.model.Conversation
import com.syncchat.app.data.model.LastMessageInfo
import java.util.Date

@Entity(tableName = "cached_conversations")
data class CachedConversation(
    @PrimaryKey val id: String,
    val participantUidsString: String, // Comma-separated Uids
    val lastMessageText: String?,
    val lastMessageSenderId: String?,
    val lastMessageTimestamp: Long?,
    val updatedAtTime: Long
) {
    fun toDomain(): Conversation {
        val participants = if (participantUidsString.isEmpty()) {
            emptyList()
        } else {
            participantUidsString.split(",")
        }

        val lastMsg = if (lastMessageText != null && lastMessageSenderId != null && lastMessageTimestamp != null) {
            LastMessageInfo(
                text = lastMessageText,
                senderId = lastMessageSenderId,
                timestamp = Date(lastMessageTimestamp)
            )
        } else null

        return Conversation(
            id = id,
            participantUids = participants,
            lastMessage = lastMsg,
            updatedAt = Date(updatedAtTime)
        )
    }

    companion object {
        fun fromDomain(conv: Conversation): CachedConversation {
            return CachedConversation(
                id = conv.id,
                participantUidsString = conv.participantUids.joinToString(","),
                lastMessageText = conv.lastMessage?.text,
                lastMessageSenderId = conv.lastMessage?.senderId,
                lastMessageTimestamp = conv.lastMessage?.timestamp?.time,
                updatedAtTime = conv.updatedAt.time
            )
        }
    }
}
