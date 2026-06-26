package com.syncchat.app.ui.home

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.syncchat.app.auth.AuthRepository
import com.syncchat.app.data.api.ApiRepository
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.local.dao.ConversationDao
import com.syncchat.app.data.local.dao.UserDao
import com.syncchat.app.data.local.entities.CachedConversation
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
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApiRepo: ApiRepository
    private lateinit var mockAuthRepo: AuthRepository
    private lateinit var mockDb: AppDatabase
    private lateinit var mockConversationDao: ConversationDao
    private lateinit var mockUserDao: UserDao
    private lateinit var mockFirestore: FirebaseFirestore
    private lateinit var mockAuth: FirebaseAuth
    private lateinit var mockFirebaseUser: FirebaseUser

    private val cachedConversationsFlow = MutableStateFlow<List<CachedConversation>>(emptyList())

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
        mockAuthRepo = mockk()
        mockDb = mockk(relaxed = true)
        mockConversationDao = mockk(relaxed = true)
        mockUserDao = mockk(relaxed = true)

        every { mockDb.conversationDao() } returns mockConversationDao
        every { mockDb.userDao() } returns mockUserDao
        every { mockConversationDao.getAllConversationsFlow(any()) } returns cachedConversationsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `conversations loads from database flow and maps to UI state`() = runTest {
        // Prepare mock cached conversations
        val cachedList = listOf(
            CachedConversation(
                id = "conv-1",
                participantUidsString = "test-uid-123,other-uid",
                lastMessageText = "Hello",
                lastMessageSenderId = "other-uid",
                lastMessageTimestamp = Date().time,
                updatedAtTime = Date().time
            )
        )
        
        cachedConversationsFlow.value = cachedList

        // Instantiate HomeViewModel
        val viewModel = HomeViewModel(
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepo,
            database = mockDb
        )

        // Collect conversations to activate stateIn flow
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            viewModel.conversations.collect {}
        }

        advanceUntilIdle()

        // Verify conversation list size and details mapped to Domain
        val resultList = viewModel.conversations.value
        assertEquals(1, resultList.size)
        assertEquals("conv-1", resultList[0].id)
        assertEquals("Hello", resultList[0].lastMessage?.text)
        assertEquals("other-uid", resultList[0].lastMessage?.senderId)

    }
}
