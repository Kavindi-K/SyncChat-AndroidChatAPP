package com.syncchat.app.data.api

import com.syncchat.app.data.model.Conversation
import com.syncchat.app.data.model.UserProfile
import retrofit2.http.*

data class UpsertProfileRequest(
    val displayName: String,
    val email: String,
    val photoUrl: String?,
    val fcmTokens: List<String> = emptyList()
)

data class CreateConversationRequest(
    val targetUserId: String
)

data class SendMessageRequest(
    val text: String,
    val mediaUrl: String? = null
)

data class MessageResponse(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val text: String,
    val mediaUrl: String?,
    val timestamp: String,
    val readBy: List<String>
)

interface SyncChatApiService {
    @POST("api/users/me")
    suspend fun upsertProfile(
        @Header("Authorization") bearerToken: String,
        @Body request: UpsertProfileRequest
    )

    @GET("api/users/{uid}")
    suspend fun getUserProfile(
        @Header("Authorization") bearerToken: String,
        @Path("uid") uid: String
    ): UserProfile

    @GET("api/users/search")
    suspend fun searchUsers(
        @Header("Authorization") bearerToken: String,
        @Query("q") query: String
    ): List<UserProfile>

    @POST("api/conversations")
    suspend fun createConversation(
        @Header("Authorization") bearerToken: String,
        @Body request: CreateConversationRequest
    ): Conversation

    @POST("api/conversations/{conversationId}/messages")
    suspend fun sendMessage(
        @Header("Authorization") bearerToken: String,
        @Path("conversationId") conversationId: String,
        @Body request: SendMessageRequest
    ): MessageResponse

    @GET("api/conversations/{conversationId}/messages")
    suspend fun getMessages(
        @Header("Authorization") bearerToken: String,
        @Path("conversationId") conversationId: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int
    ): List<MessageResponse>

    @POST("api/upload")
    suspend fun getUploadUrl(
        @Header("Authorization") bearerToken: String,
        @Body request: UploadRequest
    ): UploadResponse

    @POST("api/users/me/fcm-token")
    suspend fun registerFcmToken(
        @Header("Authorization") bearerToken: String,
        @Body request: FcmTokenRequestDto
    )
}

data class UploadRequest(val fileName: String, val contentType: String)
data class UploadResponse(val uploadUrl: String, val downloadUrl: String)
data class FcmTokenRequestDto(val token: String)
