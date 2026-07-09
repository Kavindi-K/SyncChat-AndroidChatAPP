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
            // Local backend URL for USB debugging/emulation via adb reverse
            .baseUrl("http://localhost:5228/")
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
        val email = FirebaseAuth.getInstance().currentUser?.email ?: ""
        // Update via backend API
        try {
            service.upsertProfile(
                bearerToken = "Bearer $idToken",
                request = UpsertProfileRequest(
                    displayName = displayName,
                    email = email,
                    photoUrl = photoUrl,
                    bio = bio
                )
            )
        } catch (e: Exception) {
            android.util.Log.w("RetrofitApiRepository", "Backend profile update failed, falling back to Firestore: ${e.message}")
        }

        // Always also save to Firestore directly so bio + photoUrl persist even if backend is slow
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val data = hashMapOf<String, Any>(
                "displayName" to displayName,
                "email" to email,
                "bio" to bio,
                "photoUrl" to photoUrl
            )
            db.collection("users").document(uid)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener { e ->
                    android.util.Log.e("RetrofitApiRepository", "Firestore update failed: ${e.message}")
                }
        } catch (e: Exception) {
            android.util.Log.e("RetrofitApiRepository", "Firestore update error: ${e.message}")
        }
    }
}
