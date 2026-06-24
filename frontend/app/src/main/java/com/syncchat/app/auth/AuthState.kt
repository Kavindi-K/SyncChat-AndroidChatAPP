package com.syncchat.app.auth

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class LoggedIn(val idToken: String) : AuthState()
    object LoggedOut : AuthState()
    data class Error(val message: String) : AuthState()
}
