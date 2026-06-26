package com.syncchat.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.syncchat.app.data.model.UserProfile
import java.util.Date

@Entity(tableName = "cached_users")
data class CachedUser(
    @PrimaryKey val uid: String,
    val displayName: String,
    val email: String,
    val photoUrl: String?,
    val createdAtTime: Long
) {
    fun toDomain(): UserProfile {
        return UserProfile(
            uid = uid,
            displayName = displayName,
            email = email,
            photoUrl = photoUrl,
            createdAt = Date(createdAtTime)
        )
    }

    companion object {
        fun fromDomain(user: UserProfile): CachedUser {
            return CachedUser(
                uid = user.uid,
                displayName = user.displayName,
                email = user.email,
                photoUrl = user.photoUrl,
                createdAtTime = user.createdAt.time
            )
        }
    }
}
