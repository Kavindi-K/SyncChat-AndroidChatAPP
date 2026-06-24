package com.syncchat.app.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.GoogleAuthProvider
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

    override suspend fun signInWithGoogle(googleIdToken: String): String {
        return try {
            val credential = GoogleAuthProvider.getCredential(googleIdToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()
            result.user?.getIdToken(false)?.await()?.token
                ?: throw AuthException.Unknown("Failed to retrieve ID token")
        } catch (e: IOException) {
            throw AuthException.NetworkError()
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException.Unknown(e.message ?: "Google sign in failed")
        }
    }

    override suspend fun signOut() {
        firebaseAuth.signOut()
    }

    override suspend fun getIdToken(forceRefresh: Boolean): String? {
        return firebaseAuth.currentUser?.getIdToken(forceRefresh)?.await()?.token
    }
}
