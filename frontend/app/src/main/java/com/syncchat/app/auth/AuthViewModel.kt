package com.syncchat.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.syncchat.app.data.api.ApiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository = FirebaseAuthRepository(),
    private val apiRepository: ApiRepository? = null  // null in unit tests
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // ID token stored in memory — cleared on sign-out
    var idToken: String? = null
        private set

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                idToken = authRepository.signInWithEmail(email, password)
                syncProfileToBackend(idToken!!)
                _authState.value = AuthState.LoggedIn(idToken!!)
            } catch (e: AuthException.WrongPassword) {
                _authState.value = AuthState.Error(e.message ?: "Wrong password")
            } catch (e: AuthException.NetworkError) {
                _authState.value = AuthState.Error(e.message ?: "Network error")
            } catch (e: AuthException) {
                _authState.value = AuthState.Error(e.message ?: "Sign in failed")
            }
        }
    }

    fun signUp(displayName: String, email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                idToken = authRepository.signUpWithEmail(displayName, email, password)
                syncProfileToBackend(idToken!!)
                _authState.value = AuthState.LoggedIn(idToken!!)
            } catch (e: AuthException.EmailAlreadyExists) {
                _authState.value = AuthState.Error(e.message ?: "Email already in use")
            } catch (e: AuthException.NetworkError) {
                _authState.value = AuthState.Error(e.message ?: "Network error")
            } catch (e: AuthException) {
                _authState.value = AuthState.Error(e.message ?: "Registration failed")
            }
        }
    }

    fun signInWithGoogle(googleIdToken: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                idToken = authRepository.signInWithGoogle(googleIdToken)
                syncProfileToBackend(idToken!!)
                _authState.value = AuthState.LoggedIn(idToken!!)
            } catch (e: AuthException.NetworkError) {
                _authState.value = AuthState.Error(e.message ?: "Network error")
            } catch (e: AuthException) {
                _authState.value = AuthState.Error(e.message ?: "Google sign in failed")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            idToken = null
            _authState.value = AuthState.LoggedOut
        }
    }

    fun resetState() {
        _authState.value = AuthState.Idle
    }

    /** Call before each API request. Returns cached or refreshed ID token. */
    suspend fun getIdToken(): String? {
        return authRepository.getIdToken(forceRefresh = false)
    }

    /**
     * Syncs the Firebase user profile to the backend Firestore via POST /api/users/me.
     * Non-fatal: login still succeeds even if the backend sync fails.
     */
    private suspend fun syncProfileToBackend(token: String) {
        try {
            val user = FirebaseAuth.getInstance().currentUser ?: return
            apiRepository?.upsertProfile(
                token = token,
                displayName = user.displayName ?: "",
                email = user.email ?: "",
                photoUrl = user.photoUrl?.toString()
            )
        } catch (e: Exception) {
            // Profile sync failure must not block login or registration
        }
    }
}
