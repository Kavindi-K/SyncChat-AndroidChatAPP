package com.syncchat.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.syncchat.app.data.local.entities.CachedUser
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM cached_users WHERE uid = :uid")
    suspend fun getUserById(uid: String): CachedUser?

    @Query("SELECT * FROM cached_users WHERE uid = :uid")
    fun getUserByIdFlow(uid: String): Flow<CachedUser?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: CachedUser)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<CachedUser>)
}
