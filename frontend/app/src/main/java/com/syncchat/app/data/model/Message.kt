package com.syncchat.app.data.model

import java.util.Date

data class Message(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val text: String = "",
    val mediaUrl: String? = null,
    val timestamp: Date = Date(),
    val readBy: List<String> = emptyList()
)
