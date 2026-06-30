package com.syncchat.app.data.model

import java.util.Date

data class Message(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val text: String = "",
    val mediaUrl: String? = null,
    val timestamp: Date = Date(),
    val readBy: List<String> = emptyList(),
    val status: String = MessageStatus.SENT.name
)

enum class MessageStatus {
    PENDING,   // Stored locally, not yet sent to server
    SENT,      // Successfully delivered to backend
    FAILED     // Delivery attempted but failed (will retry)
}
