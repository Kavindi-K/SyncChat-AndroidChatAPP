package com.syncchat.app.data.api

import com.google.firebase.auth.FirebaseAuth
import com.syncchat.app.data.model.Conversation
import com.syncchat.app.data.model.UserProfile
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class RetrofitApiRepository : ApiRepository {

    private val service: SyncChatApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            // Production Railway backend URL
            .baseUrl("https://syncchat-androidchatapp-production.up.railway.app/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SyncChatApiService::class.java)
    }

    override suspend fun upsertProfile(
        token: String,
        displayName: String,
        email: String,
        photoUrl: String?
    ) {
        service.upsertProfile(
            bearerToken = "Bearer $token",
            request = UpsertProfileRequest(
                displayName = displayName,
                email = email,
                photoUrl = photoUrl
            )
        )
    }

    override suspend fun getUserProfile(token: String, uid: String): UserProfile {
        return service.getUserProfile("Bearer $token", uid)
    }

    override suspend fun searchUsers(token: String, query: String): List<UserProfile> {
        return service.searchUsers("Bearer $token", query)
    }

    override suspend fun createConversation(token: String, targetUserId: String): Conversation {
        return service.createConversation("Bearer $token", CreateConversationRequest(targetUserId))
    }

    override suspend fun sendMessage(
        token: String,
        conversationId: String,
        text: String,
        mediaUrl: String?
    ): MessageResponse {
        return service.sendMessage(
            bearerToken = "Bearer $token",
            conversationId = conversationId,
            request = SendMessageRequest(text, mediaUrl)
        )
    }

    override suspend fun getMessages(
        token: String,
        conversationId: String,
        cursor: String?,
        limit: Int
    ): List<MessageResponse> {
        return service.getMessages("Bearer $token", conversationId, cursor, limit)
    }

    override suspend fun getUploadUrl(
        token: String,
        fileName: String,
        contentType: String
    ): UploadResponse {
        return service.getUploadUrl("Bearer $token", UploadRequest(fileName, contentType))
    }

    override suspend fun registerFcmToken(token: String, fcmToken: String) {
        service.registerFcmToken("Bearer $token", FcmTokenRequestDto(fcmToken))
    }

    override suspend fun updateUserProfile(
        idToken: String,
        displayName: String,
        bio: String,
        photoUrl: String
    ) {
        // Re-use upsertProfile which updates the profile on the backend
        service.upsertProfile(
            bearerToken = "Bearer $idToken",
            request = UpsertProfileRequest(
                displayName = displayName,
                email = FirebaseAuth.getInstance().currentUser?.email ?: "",
                photoUrl = photoUrl.ifEmpty { null }
            )
        )
    }
}
