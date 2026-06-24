package com.syncchat.app.data.api

interface ApiRepository {
    suspend fun upsertProfile(
        token: String,
        displayName: String,
        email: String,
        photoUrl: String?
    )
}
