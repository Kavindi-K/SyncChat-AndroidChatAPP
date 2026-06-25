package com.syncchat.app.data.model

import java.util.Date

data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val createdAt: Date = Date()
)
