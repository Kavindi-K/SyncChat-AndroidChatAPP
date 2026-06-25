package com.syncchat.app.auth

sealed class AuthException(message: String) : Exception(message) {
    class WrongPassword(message: String = "Wrong password. Please try again.") : AuthException(message)
    class NetworkError(message: String = "Network error. Please check your connection.") : AuthException(message)
    class EmailAlreadyExists(message: String = "An account with this email already exists.") : AuthException(message)
    class Unknown(message: String = "Authentication failed. Please try again.") : AuthException(message)
}
