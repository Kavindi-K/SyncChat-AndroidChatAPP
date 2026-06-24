package com.syncchat.app.data.api

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class UpsertProfileRequest(
    val displayName: String,
    val email: String,
    val photoUrl: String?,
    val fcmTokens: List<String> = emptyList()
)

interface SyncChatApiService {
    @POST("api/users/me")
    suspend fun upsertProfile(
        @Header("Authorization") bearerToken: String,
        @Body request: UpsertProfileRequest
    )
}
