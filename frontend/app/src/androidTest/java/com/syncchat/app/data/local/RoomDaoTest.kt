package com.syncchat.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syncchat.app.data.local.entities.CachedMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Date

@RunWith(AndroidJUnit4::class)
class RoomDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertMessage_QueryByConvId_ReturnsCorrect() = runBlocking {
        val message = CachedMessage(
            id = "msg1",
            conversationId = "conv1",
            senderId = "user1",
            text = "Hello World",
            mediaUrl = null,
            timestampTime = Date().time,
            readByString = "user1,user2"
        )
        db.messageDao().insertMessage(message)

        val messagesList = db.messageDao().getMessagesForConversationFlow("conv1").first()
        assertEquals(1, messagesList.size)
        assertEquals("msg1", messagesList[0].id)
        assertEquals("Hello World", messagesList[0].text)
    }

    @Test
    fun insertDuplicateMsgId_ReplacesExisting() = runBlocking {
        val message1 = CachedMessage(
            id = "msg1",
            conversationId = "conv1",
            senderId = "user1",
            text = "Original message",
            mediaUrl = null,
            timestampTime = Date().time,
            readByString = ""
        )
        db.messageDao().insertMessage(message1)

        val message2 = CachedMessage(
            id = "msg1",
            conversationId = "conv1",
            senderId = "user1",
            text = "Edited message",
            mediaUrl = null,
            timestampTime = Date().time,
            readByString = ""
        )
        db.messageDao().insertMessage(message2)

        val messagesList = db.messageDao().getMessagesForConversationFlow("conv1").first()
        assertEquals(1, messagesList.size)
        assertEquals("Edited message", messagesList[0].text)
    }
}
