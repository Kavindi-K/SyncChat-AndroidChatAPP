package com.syncchat.app.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.tasks.await
import java.io.IOException

class FirebaseAuthRepository : AuthRepository {

    private val firebaseAuth = FirebaseAuth.getInstance()

    override suspend fun signInWithEmail(email: String, password: String): String {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            result.user?.getIdToken(false)?.await()?.token
                ?: throw AuthException.Unknown("Failed to retrieve ID token")
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            throw AuthException.WrongPassword()
        } catch (e: IOException) {
            throw AuthException.NetworkError()
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException.Unknown(e.message ?: "Sign in failed")
        }
    }

    override suspend fun signUpWithEmail(displayName: String, email: String, password: String): String {
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()

            // Set the display name on the new Firebase user profile
            val profileUpdate = UserProfileChangeRequest.Builder()
                .setDisplayName(displayName)
                .build()
            result.user?.updateProfile(profileUpdate)?.await()

            // Firebase auto-signs in the user after creation — get the ID token
            result.user?.getIdToken(false)?.await()?.token
                ?: throw AuthException.Unknown("Failed to retrieve ID token after registration")
        } catch (e: FirebaseAuthUserCollisionException) {
            throw AuthException.EmailAlreadyExists()
        } catch (e: IOException) {
            throw AuthException.NetworkError()
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException.Unknown(e.message ?: "Registration failed")
        }
    }

    override suspend fun signOut() {
        firebaseAuth.signOut()
    }

    override suspend fun getIdToken(forceRefresh: Boolean): String? {
        return firebaseAuth.currentUser?.getIdToken(forceRefresh)?.await()?.token
    }
}
