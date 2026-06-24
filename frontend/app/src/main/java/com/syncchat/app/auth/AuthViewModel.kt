package com.syncchat.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository = FirebaseAuthRepository()
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

    fun signInWithGoogle(googleIdToken: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                idToken = authRepository.signInWithGoogle(googleIdToken)
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

    /** Call before each API request. Returns cached or refreshed ID token. */
    suspend fun getIdToken(): String? {
        return authRepository.getIdToken(forceRefresh = false)
    }
}
