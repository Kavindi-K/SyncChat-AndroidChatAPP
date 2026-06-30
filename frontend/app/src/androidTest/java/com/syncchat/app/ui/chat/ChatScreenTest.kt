package com.syncchat.app.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.local.dao.MessageDao
import com.syncchat.app.data.local.entities.CachedMessage
import com.syncchat.app.data.model.UserProfile
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        mockkStatic(FirebaseAuth::class)
        mockkObject(AppDatabase.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun typeMessage_tapSend_bubbleAppears() {
        val messagesFlow = MutableStateFlow<List<CachedMessage>>(emptyList())
        val mockDb = mockk<AppDatabase>(relaxed = true)
        val mockDao = mockk<MessageDao>(relaxed = true)
        every { mockDb.messageDao() } returns mockDao
        every { mockDao.getMessagesForConversationFlow("conv-1") } returns messagesFlow
        every { AppDatabase.getDatabase(any()) } returns mockDb

        // Mock Firebase Auth
        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        val mockUser = mockk<FirebaseUser>(relaxed = true)
        every { mockUser.uid } returns "my-uid"
        every { mockAuth.currentUser } returns mockUser
        every { FirebaseAuth.getInstance() } returns mockAuth

        // Mock Dao insert to simulate DB updating flow
        coEvery { mockDao.insertMessage(any()) } answers {
            val msg = firstArg<CachedMessage>()
            messagesFlow.value = messagesFlow.value + msg
        }

        composeTestRule.setContent {
            ChatScreen(
                conversationId = "conv-1",
                otherUser = UserProfile("other-uid", "Kamal"),
                onBackClick = {}
            )
        }

        // Type message
        composeTestRule.onNodeWithText("Type a message...").performTextInput("Hello from Kamal")

        // Click Send button
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        // Verify the message bubble appears in UI
        composeTestRule.onNodeWithText("Hello from Kamal").assertIsDisplayed()
    }

    @Test
    fun sendWhileOffline_showsPendingIcon() {
        val messagesFlow = MutableStateFlow<List<CachedMessage>>(emptyList())
        val mockDb = mockk<AppDatabase>(relaxed = true)
        val mockDao = mockk<MessageDao>(relaxed = true)
        every { mockDb.messageDao() } returns mockDao
        every { mockDao.getMessagesForConversationFlow("conv-1") } returns messagesFlow
        every { AppDatabase.getDatabase(any()) } returns mockDb

        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        val mockUser = mockk<FirebaseUser>(relaxed = true)
        every { mockUser.uid } returns "my-uid"
        every { mockAuth.currentUser } returns mockUser
        every { FirebaseAuth.getInstance() } returns mockAuth

        // Set up initial state with a PENDING message in the flow
        val pendingMsg = CachedMessage(
            id = "msg-123",
            conversationId = "conv-1",
            senderId = "my-uid",
            text = "Pending message offline",
            mediaUrl = null,
            timestampTime = System.currentTimeMillis(),
            readByString = "",
            status = "PENDING"
        )
        messagesFlow.value = listOf(pendingMsg)

        composeTestRule.setContent {
            ChatScreen(
                conversationId = "conv-1",
                otherUser = UserProfile("other-uid", "Kamal"),
                onBackClick = {}
            )
        }

        // Verify pending text is displayed
        composeTestRule.onNodeWithText("Pending message offline").assertIsDisplayed()
        
        // Verify ⌛ pending indicator icon is displayed next to it
        composeTestRule.onNodeWithText("⌛").assertIsDisplayed()
    }

    @Test
    fun restoreConnectivity_updatesPendingToSent() {
        val messagesFlow = MutableStateFlow<List<CachedMessage>>(emptyList())
        val mockDb = mockk<AppDatabase>(relaxed = true)
        val mockDao = mockk<MessageDao>(relaxed = true)
        every { mockDb.messageDao() } returns mockDao
        every { mockDao.getMessagesForConversationFlow("conv-1") } returns messagesFlow
        every { AppDatabase.getDatabase(any()) } returns mockDb

        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        val mockUser = mockk<FirebaseUser>(relaxed = true)
        every { mockUser.uid } returns "my-uid"
        every { mockAuth.currentUser } returns mockUser
        every { FirebaseAuth.getInstance() } returns mockAuth

        // 1. Initial pending message
        val pendingMsg = CachedMessage(
            id = "msg-123",
            conversationId = "conv-1",
            senderId = "my-uid",
            text = "Pending to Sent test",
            mediaUrl = null,
            timestampTime = System.currentTimeMillis(),
            readByString = "",
            status = "PENDING"
        )
        messagesFlow.value = listOf(pendingMsg)

        composeTestRule.setContent {
            ChatScreen(
                conversationId = "conv-1",
                otherUser = UserProfile("other-uid", "Kamal"),
                onBackClick = {}
            )
        }

        // Verify PENDING ⌛ is shown
        composeTestRule.onNodeWithText("⌛").assertIsDisplayed()

        // 2. Simulate WorkManager successfully syncing: status updates to SENT
        val sentMsg = pendingMsg.copy(status = "SENT")
        messagesFlow.value = listOf(sentMsg)

        // Verify PENDING ⌛ is gone, and SENT checkmark ✓ is shown
        composeTestRule.onNodeWithText("⌛").assertDoesNotExist()
        composeTestRule.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun longMessage_displaysWithoutTruncation() {
        val messagesFlow = MutableStateFlow<List<CachedMessage>>(emptyList())
        val mockDb = mockk<AppDatabase>(relaxed = true)
        val mockDao = mockk<MessageDao>(relaxed = true)
        every { mockDb.messageDao() } returns mockDao
        every { mockDao.getMessagesForConversationFlow("conv-1") } returns messagesFlow
        every { AppDatabase.getDatabase(any()) } returns mockDb

        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        val mockUser = mockk<FirebaseUser>(relaxed = true)
        every { mockUser.uid } returns "my-uid"
        every { mockAuth.currentUser } returns mockUser
        every { FirebaseAuth.getInstance() } returns mockAuth

        // Generate 500-char text message
        val longText = "a".repeat(500)
        val msg = CachedMessage(
            id = "msg-123",
            conversationId = "conv-1",
            senderId = "my-uid",
            text = longText,
            mediaUrl = null,
            timestampTime = System.currentTimeMillis(),
            readByString = "",
            status = "SENT"
        )
        messagesFlow.value = listOf(msg)

        composeTestRule.setContent {
            ChatScreen(
                conversationId = "conv-1",
                otherUser = UserProfile("other-uid", "Kamal"),
                onBackClick = {}
            )
        }

        // Verify long text message is displayed completely
        composeTestRule.onNodeWithText(longText).assertIsDisplayed()
    }
}
