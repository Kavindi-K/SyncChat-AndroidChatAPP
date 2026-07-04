package com.syncchat.app.data.api

import com.syncchat.app.data.model.Conversation
import com.syncchat.app.data.model.UserProfile

interface ApiRepository {
    suspend fun upsertProfile(
        token: String,
        displayName: String,
        email: String,
        photoUrl: String?
    )

    suspend fun getUserProfile(
        token: String,
        uid: String
    ): UserProfile

    suspend fun searchUsers(
        token: String,
        query: String
    ): List<UserProfile>

    suspend fun createConversation(
        token: String,
        targetUserId: String
    ): Conversation

    suspend fun sendMessage(
        token: String,
        conversationId: String,
        text: String,
        mediaUrl: String?
    ): MessageResponse

    suspend fun getMessages(
        token: String,
        conversationId: String,
        cursor: String?,
        limit: Int
    ): List<MessageResponse>

    suspend fun getUploadUrl(
        token: String,
        fileName: String,
        contentType: String
    ): UploadResponse

    suspend fun registerFcmToken(token: String, fcmToken: String)
}
