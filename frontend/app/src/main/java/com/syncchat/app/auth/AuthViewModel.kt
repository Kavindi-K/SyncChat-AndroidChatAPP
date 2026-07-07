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

    init {
        checkCurrentSession()
    }

    private fun checkCurrentSession() {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                // Timeout token fetch after 3 seconds to prevent splash screen lockups
                val token = kotlinx.coroutines.withTimeoutOrNull(3000) {
                    authRepository.getIdToken(forceRefresh = false)
                }
                if (token != null) {
                    idToken = token
                    syncProfileToBackend(token)
                    _authState.value = AuthState.LoggedIn(token)
                } else {
                    _authState.value = AuthState.LoggedOut
                }
            } catch (e: Exception) {
                _authState.value = AuthState.LoggedOut
            }
        }
    }

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
            com.syncchat.app.data.signalr.SignalRManager.getInstance().stopConnection()
            _authState.value = AuthState.LoggedOut
        }
    }

    fun resetState() {
        _authState.value = AuthState.LoggedOut
    }

    /** Call before each API request. Returns cached or refreshed ID token. */
    suspend fun getIdToken(): String? {
        return authRepository.getIdToken(forceRefresh = false)
    }

    /**
     * Syncs the Firebase user profile to the backend Firestore via POST /api/users/me.
     * Non-fatal: login still succeeds even if the backend sync fails.
     */
    private fun syncProfileToBackend(token: String) {
        viewModelScope.launch {
            try {
                val user = FirebaseAuth.getInstance().currentUser ?: return@launch
                // 1. Sync to backend API
                try {
                    apiRepository?.upsertProfile(
                        token = token,
                        displayName = user.displayName ?: "",
                        email = user.email ?: "",
                        photoUrl = user.photoUrl?.toString()
                    )
                } catch (e: Exception) {
                    android.util.Log.e("AuthViewModel", "Backend Profile sync failed", e)
                }

                // 2. Direct Firestore fallback (handles cases where localhost API is unreachable from device)
                try {
                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val data = hashMapOf<String, Any>(
                        "displayName" to (user.displayName ?: ""),
                        "email" to (user.email ?: ""),
                        "photoUrl" to (user.photoUrl?.toString() ?: "")
                    )
                    db.collection("users").document(user.uid)
                        .set(data, com.google.firebase.firestore.SetOptions.merge())
                } catch (e: Exception) {
                    android.util.Log.e("AuthViewModel", "Firestore direct sync failed", e)
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "Profile sync failed completely", e)
            }
        }
    }
}
