package com.syncchat.app.data.model

import java.util.Date

data class Conversation(
    val id: String = "",
    val participantUids: List<String> = emptyList(),
    val lastMessage: LastMessageInfo? = null,
    val updatedAt: Date = Date(),
    val isPinned: Boolean = false,
    val isBlocked: Boolean = false,
    val isBlockedByOther: Boolean = false
)

data class LastMessageInfo(
    val text: String = "",
    val senderId: String = "",
    val timestamp: Date = Date()
)
