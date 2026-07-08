package com.syncchat.app.auth

interface AuthRepository {
    suspend fun signInWithEmail(email: String, password: String): String
    suspend fun signUpWithEmail(displayName: String, email: String, password: String): String
    suspend fun signOut()
    suspend fun getIdToken(forceRefresh: Boolean = false): String?
}
