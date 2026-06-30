package com.syncchat.app.data.model

/**
 * A generic sealed class that represents the result of an operation.
 * It can be either Success (with data), Error (with message/exception), or Loading.
 */
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val message: String, val exception: Exception? = null) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}
