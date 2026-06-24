package com.syncchat.app.data.api

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
            .build()

        Retrofit.Builder()
            // 10.0.2.2 is the host machine's localhost from Android Emulator
            .baseUrl("http://10.0.2.2:8081/")
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
}
