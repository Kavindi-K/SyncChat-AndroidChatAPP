package com.syncchat.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.syncchat.app.data.local.dao.ConversationDao
import com.syncchat.app.data.local.dao.MessageDao
import com.syncchat.app.data.local.dao.UserDao
import com.syncchat.app.data.local.entities.CachedConversation
import com.syncchat.app.data.local.entities.CachedMessage
import com.syncchat.app.data.local.entities.CachedUser

@Database(
    entities = [CachedUser::class, CachedConversation::class, CachedMessage::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "syncchat_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
