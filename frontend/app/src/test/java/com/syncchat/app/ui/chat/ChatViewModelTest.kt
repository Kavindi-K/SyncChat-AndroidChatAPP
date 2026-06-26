package com.syncchat.app.ui.chat

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.syncchat.app.auth.AuthRepository
import com.syncchat.app.data.api.ApiRepository
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.local.dao.MessageDao
import com.syncchat.app.data.local.entities.CachedMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApiRepo: ApiRepository
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var mockDb: AppDatabase
    private lateinit var mockMessageDao: MessageDao
    private lateinit var mockFirestore: FirebaseFirestore
    private lateinit var mockAuth: FirebaseAuth
    private lateinit var mockFirebaseUser: FirebaseUser

    private val cachedMessagesFlow = MutableStateFlow<List<CachedMessage>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock Firebase statics
        mockkStatic(FirebaseFirestore::class)
        mockkStatic(FirebaseAuth::class)

        mockFirestore = mockk(relaxed = true)
        mockAuth = mockk(relaxed = true)
        mockFirebaseUser = mockk(relaxed = true)

        every { FirebaseFirestore.getInstance() } returns mockFirestore
        every { FirebaseAuth.getInstance() } returns mockAuth
        every { mockAuth.currentUser } returns mockFirebaseUser
        every { mockFirebaseUser.uid } returns "test-uid-123"

        // Mock repositories and database
        mockApiRepo = mockk()
        mockAuthRepository = mockk()
        mockDb = mockk(relaxed = true)
        mockMessageDao = mockk(relaxed = true)

        every { mockDb.messageDao() } returns mockMessageDao
        every { mockMessageDao.getMessagesForConversationFlow("conv-1") } returns cachedMessagesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `messages loads from database flow and maps to UI state`() = runTest {
        // Prepare mock cached messages
        val cachedList = listOf(
            CachedMessage(
                id = "msg-1",
                conversationId = "conv-1",
                senderId = "other-uid",
                text = "Hello from Room",
                mediaUrl = null,
                timestampTime = Date().time,
                readByString = "other-uid"
            )
        )
        
        cachedMessagesFlow.value = cachedList

        // Instantiate ChatViewModel
        val viewModel = ChatViewModel(
            conversationId = "conv-1",
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepository,
            database = mockDb
        )

        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect {}
        }

        advanceUntilIdle()

        // Verify messages list size and details mapped to Domain
        val resultList = viewModel.messages.value
        assertEquals(1, resultList.size)
        assertEquals("msg-1", resultList[0].id)
        assertEquals("Hello from Room", resultList[0].text)
        assertEquals("other-uid", resultList[0].senderId)
    }

    @Test
    fun `sendMessage fails - ViewModel handles error and cached data remains active`() = runTest {
        coEvery { mockAuthRepository.getIdToken() } returns "fake-token"
        coEvery { mockApiRepo.sendMessage(any(), any(), any(), any()) } throws Exception("Network connection failed")

        // Prepare mock cached messages
        val cachedList = listOf(
            CachedMessage(
                id = "msg-1",
                conversationId = "conv-1",
                senderId = "other-uid",
                text = "Hello from Room",
                mediaUrl = null,
                timestampTime = Date().time,
                readByString = ""
            )
        )
        cachedMessagesFlow.value = cachedList

        // Instantiate ChatViewModel
        val viewModel = ChatViewModel(
            conversationId = "conv-1",
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepository,
            database = mockDb
        )

        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect {}
        }

        advanceUntilIdle()

        // Call sendMessage (will trigger network call that throws exception)
        viewModel.sendMessage("New message")
        advanceUntilIdle()

        // Verify that the exception didn't crash the ViewModel and local messages are still loaded
        val resultList = viewModel.messages.value
        assertEquals(1, resultList.size)
        assertEquals("Hello from Room", resultList[0].text)
    }
}
