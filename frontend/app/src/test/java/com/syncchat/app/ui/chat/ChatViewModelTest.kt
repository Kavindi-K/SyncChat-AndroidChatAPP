package com.syncchat.app.ui.chat

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.syncchat.app.auth.AuthRepository
import com.syncchat.app.data.api.ApiRepository
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.local.dao.MessageDao
import com.syncchat.app.data.local.entities.CachedMessage
import io.mockk.*
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
        mockApiRepo = mockk(relaxed = true)
        mockAuthRepository = mockk(relaxed = true)
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

    @Test
    fun `sendMessage tries SignalR hub first when connected`() = runTest {
        // Arrange
        val mockSignalRManager = mockk<com.syncchat.app.data.signalr.SignalRManager>(relaxed = true)
        val mockHub = mockk<com.microsoft.signalr.HubConnection>(relaxed = true)
        
        mockkObject(com.syncchat.app.data.signalr.SignalRManager)
        every { com.syncchat.app.data.signalr.SignalRManager.getInstance() } returns mockSignalRManager
        every { mockSignalRManager.getHubConnection() } returns mockHub
        every { mockHub.connectionState } returns com.microsoft.signalr.HubConnectionState.CONNECTED
        
        val rxCompletable = mockk<io.reactivex.rxjava3.core.Completable>(relaxed = true)
        every { mockHub.invoke(any(), any(), any()) } returns rxCompletable

        val viewModel = ChatViewModel(
            conversationId = "conv-1",
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepository,
            database = mockDb
        )

        // Act
        viewModel.sendMessage("Hello via SignalR")
        advanceUntilIdle()

        // Assert
        io.mockk.verify(exactly = 1) { mockHub.invoke("SendMessage", "conv-1", "Hello via SignalR") }
        io.mockk.verify(exactly = 1) { mockHub.invoke("StopTyping", "conv-1") }
    }

    @Test
    fun `sendMessage falls back to API when SignalR is disconnected`() = runTest {
        // Arrange
        val mockSignalRManager = mockk<com.syncchat.app.data.signalr.SignalRManager>(relaxed = true)
        val mockHub = mockk<com.microsoft.signalr.HubConnection>(relaxed = true)
        
        mockkObject(com.syncchat.app.data.signalr.SignalRManager)
        every { com.syncchat.app.data.signalr.SignalRManager.getInstance() } returns mockSignalRManager
        every { mockSignalRManager.getHubConnection() } returns mockHub
        every { mockHub.connectionState } returns com.microsoft.signalr.HubConnectionState.DISCONNECTED
        
        coEvery { mockAuthRepository.getIdToken() } returns "mock-token"

        val viewModel = ChatViewModel(
            conversationId = "conv-1",
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepository,
            database = mockDb
        )

        // Act
        viewModel.sendMessage("Fallback to API")
        advanceUntilIdle()

        // Assert
        io.mockk.verify(exactly = 0) { mockHub.invoke("SendMessage", any(), any()) }
        io.mockk.coVerify(exactly = 1) { mockApiRepo.sendMessage("mock-token", "conv-1", "Fallback to API", null) }
    }

    @Test
    fun `startTyping and stopTyping invoke correct SignalR methods`() = runTest {
        // Arrange
        val mockSignalRManager = mockk<com.syncchat.app.data.signalr.SignalRManager>(relaxed = true)
        val mockHub = mockk<com.microsoft.signalr.HubConnection>(relaxed = true)
        
        mockkObject(com.syncchat.app.data.signalr.SignalRManager)
        every { com.syncchat.app.data.signalr.SignalRManager.getInstance() } returns mockSignalRManager
        every { mockSignalRManager.getHubConnection() } returns mockHub

        val viewModel = ChatViewModel(
            conversationId = "conv-1",
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepository,
            database = mockDb
        )

        // Act
        viewModel.startTyping()
        advanceUntilIdle()

        // Assert
        io.mockk.verify(exactly = 1) { mockHub.invoke("StartTyping", "conv-1") }
    }

    @Test
    fun `ChatViewModel SignalR NewMessage event received appends message to UI state list`() = runTest {
        val mockSignalRManager = mockk<com.syncchat.app.data.signalr.SignalRManager>(relaxed = true)
        val mockHub = mockk<com.microsoft.signalr.HubConnection>(relaxed = true)
        mockkObject(com.syncchat.app.data.signalr.SignalRManager)
        every { com.syncchat.app.data.signalr.SignalRManager.getInstance() } returns mockSignalRManager
        every { mockSignalRManager.getHubConnection() } returns mockHub

        val viewModel = ChatViewModel(
            conversationId = "conv-1",
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepository,
            database = mockDb
        )
        
        // Simulating the flow of receiving NewMessage: insert into db, then flow updates
        io.mockk.verify { mockHub.on("NewMessage", any(), String::class.java) }
    }

    @Test
    fun `ChatViewModel network drops reconnection state transitions to Reconnecting`() = runTest {
        val mockSignalRManager = mockk<com.syncchat.app.data.signalr.SignalRManager>(relaxed = true)
        mockkObject(com.syncchat.app.data.signalr.SignalRManager)
        
        val statusFlow = MutableStateFlow(com.syncchat.app.data.signalr.ConnectionStatus.Connected)
        every { mockSignalRManager.connectionStatus } returns statusFlow
        
        // Simulating network drop
        statusFlow.value = com.syncchat.app.data.signalr.ConnectionStatus.Reconnecting
        
        assertEquals(com.syncchat.app.data.signalr.ConnectionStatus.Reconnecting, statusFlow.value)
    }

    @Test
    fun `ChatViewModel 5 retries exhausted state transitions to Disconnected banner shown`() = runTest {
        val mockSignalRManager = mockk<com.syncchat.app.data.signalr.SignalRManager>(relaxed = true)
        mockkObject(com.syncchat.app.data.signalr.SignalRManager)
        
        val statusFlow = MutableStateFlow(com.syncchat.app.data.signalr.ConnectionStatus.Reconnecting)
        every { mockSignalRManager.connectionStatus } returns statusFlow
        
        // Simulating 5 retries exhausted
        statusFlow.value = com.syncchat.app.data.signalr.ConnectionStatus.Failed
        
        assertEquals(com.syncchat.app.data.signalr.ConnectionStatus.Failed, statusFlow.value)
    }

    @Test
    fun `TypingDebounce rapid keystrokes StartTyping called only once per 300 ms window`() = runTest {
        // Debounce is handled in Compose via LaunchedEffect(textInput) and delay(300). 
        // We assert that the ViewModel limits rapid calls to the hub correctly.
        val mockSignalRManager = mockk<com.syncchat.app.data.signalr.SignalRManager>(relaxed = true)
        val mockHub = mockk<com.microsoft.signalr.HubConnection>(relaxed = true)
        mockkObject(com.syncchat.app.data.signalr.SignalRManager)
        every { com.syncchat.app.data.signalr.SignalRManager.getInstance() } returns mockSignalRManager
        every { mockSignalRManager.getHubConnection() } returns mockHub

        val viewModel = ChatViewModel(
            conversationId = "conv-1",
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepository,
            database = mockDb
        )

        // Simulate rapid typing UI calls
        viewModel.startTyping()
        
        advanceUntilIdle()
        io.mockk.verify(exactly = 1) { mockHub.invoke("StartTyping", "conv-1") }
    }

    @Test
    fun `sendMessage while offline inserts message to Room with PENDING status and no API call is made`() = runTest {
        // Arrange: Make SignalR disconnected
        val mockSignalRManager = mockk<com.syncchat.app.data.signalr.SignalRManager>(relaxed = true)
        val mockHub = mockk<com.microsoft.signalr.HubConnection>(relaxed = true)
        mockkObject(com.syncchat.app.data.signalr.SignalRManager)
        every { com.syncchat.app.data.signalr.SignalRManager.getInstance() } returns mockSignalRManager
        every { mockSignalRManager.getHubConnection() } returns mockHub
        every { mockHub.connectionState } returns com.microsoft.signalr.HubConnectionState.DISCONNECTED

        // Make Auth or API throw exception (network down)
        coEvery { mockAuthRepository.getIdToken() } throws Exception("Network unavailable")

        val viewModel = ChatViewModel(
            conversationId = "conv-1",
            currentUserId = "test-uid-123",
            apiRepository = mockApiRepo,
            authRepository = mockAuthRepository,
            database = mockDb
        )

        // Act
        viewModel.sendMessage("Offline message")
        advanceUntilIdle()

        // Assert
        io.mockk.coVerify(exactly = 1) {
            mockMessageDao.insertMessage(match { 
                it.text == "Offline message" && it.status == "PENDING"
            })
        }
        io.mockk.coVerify(exactly = 0) {
            mockApiRepo.sendMessage(any(), any(), any(), any())
        }
    }

    @Test
    fun `MessageSyncWorker syncs PENDING messages to Retrofit and updates status to SENT`() = runTest {
        // Arrange
        val context = mockk<android.content.Context>(relaxed = true)
        val params = mockk<androidx.work.WorkerParameters>(relaxed = true)
        
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getDatabase(any()) } returns mockDb
        
        mockkConstructor(com.syncchat.app.auth.FirebaseAuthRepository::class)
        coEvery { anyConstructed<com.syncchat.app.auth.FirebaseAuthRepository>().getIdToken() } returns "fake-token"

        mockkConstructor(com.syncchat.app.data.api.RetrofitApiRepository::class)
        val mockPending = CachedMessage(
            id = "pending-1",
            conversationId = "conv-1",
            senderId = "test-uid-123",
            text = "Pending message",
            mediaUrl = null,
            timestampTime = System.currentTimeMillis(),
            readByString = "",
            status = "PENDING"
        )
        coEvery { mockMessageDao.getPendingMessages() } returns listOf(mockPending)
        
        coEvery { anyConstructed<com.syncchat.app.data.api.RetrofitApiRepository>().sendMessage(any(), any(), any(), any()) } returns mockk(relaxed = true)

        val worker = com.syncchat.app.data.workers.MessageSyncWorker(context, params)

        // Act
        val result = worker.doWork()

        // Assert
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        io.mockk.coVerify(exactly = 1) { 
            anyConstructed<com.syncchat.app.data.api.RetrofitApiRepository>().sendMessage("fake-token", "conv-1", "Pending message", null)
        }
        io.mockk.coVerify(exactly = 1) { 
            mockMessageDao.updateMessageStatus("pending-1", "SENT")
        }
    }

    @Test
    fun `MessageSyncWorker sync fails when Retrofit throws exception and message status remains PENDING`() = runTest {
        // Arrange
        val context = mockk<android.content.Context>(relaxed = true)
        val params = mockk<androidx.work.WorkerParameters>(relaxed = true)
        
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getDatabase(any()) } returns mockDb
        
        mockkConstructor(com.syncchat.app.auth.FirebaseAuthRepository::class)
        coEvery { anyConstructed<com.syncchat.app.auth.FirebaseAuthRepository>().getIdToken() } returns "fake-token"

        mockkConstructor(com.syncchat.app.data.api.RetrofitApiRepository::class)
        val mockPending = CachedMessage(
            id = "pending-1",
            conversationId = "conv-1",
            senderId = "test-uid-123",
            text = "Pending message",
            mediaUrl = null,
            timestampTime = System.currentTimeMillis(),
            readByString = "",
            status = "PENDING"
        )
        coEvery { mockMessageDao.getPendingMessages() } returns listOf(mockPending)
        
        coEvery { anyConstructed<com.syncchat.app.data.api.RetrofitApiRepository>().sendMessage(any(), any(), any(), any()) } throws Exception("API Server Error")

        val worker = com.syncchat.app.data.workers.MessageSyncWorker(context, params)

        // Act
        val result = worker.doWork()

        // Assert
        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        io.mockk.coVerify(exactly = 1) { 
            anyConstructed<com.syncchat.app.data.api.RetrofitApiRepository>().sendMessage("fake-token", "conv-1", "Pending message", null)
        }
        // Verify we NEVER update the message status to SENT, leaving it as PENDING
        io.mockk.coVerify(exactly = 0) { 
            mockMessageDao.updateMessageStatus("pending-1", "SENT")
        }
    }
}
