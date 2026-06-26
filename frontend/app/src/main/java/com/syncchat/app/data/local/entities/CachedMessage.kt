package com.syncchat.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.syncchat.app.data.model.Message
import java.util.Date

@Entity(tableName = "cached_messages")
data class CachedMessage(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val text: String,
    val mediaUrl: String?,
    val timestampTime: Long,
    val readByString: String // Comma-separated Uids
) {
    fun toDomain(): Message {
        val readByList = if (readByString.isEmpty()) {
            emptyList()
        } else {
            readByString.split(",")
        }

        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            text = text,
            mediaUrl = mediaUrl,
            timestamp = Date(timestampTime),
            readBy = readByList
        )
    }

    companion object {
        fun fromDomain(msg: Message): CachedMessage {
            return CachedMessage(
                id = msg.id,
                conversationId = msg.conversationId,
                senderId = msg.senderId,
                text = msg.text,
                mediaUrl = msg.mediaUrl,
                timestampTime = msg.timestamp.time,
                readByString = msg.readBy.joinToString(",")
            )
        }
    }
}
