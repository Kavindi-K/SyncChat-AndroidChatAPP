package com.syncchat.app.auth

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher
    private lateinit var mockRepo: AuthRepository
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk()
        viewModel = AuthViewModel(mockRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `successful login - state transitions to LoggedIn`() = runTest {
        coEvery { mockRepo.signInWithEmail(any(), any()) } returns "fake-id-token"

        viewModel.signInWithEmail("test@test.com", "password123")
        advanceUntilIdle()

        val state = viewModel.authState.value
        assertTrue("Expected LoggedIn but got $state", state is AuthState.LoggedIn)
        assertEquals("fake-id-token", (state as AuthState.LoggedIn).idToken)
        assertEquals("fake-id-token", viewModel.idToken)
    }

    @Test
    fun `wrong password - state transitions to Error with message`() = runTest {
        coEvery { mockRepo.signInWithEmail(any(), any()) } throws AuthException.WrongPassword()

        viewModel.signInWithEmail("test@test.com", "wrongpass")
        advanceUntilIdle()

        val state = viewModel.authState.value
        assertTrue("Expected Error but got $state", state is AuthState.Error)
        val error = state as AuthState.Error
        assertTrue(
            "Error message should mention password, got: ${error.message}",
            error.message.contains("password", ignoreCase = true)
        )
    }

    @Test
    fun `network error - state transitions to Error with network message`() = runTest {
        coEvery { mockRepo.signInWithEmail(any(), any()) } throws AuthException.NetworkError()

        viewModel.signInWithEmail("test@test.com", "password123")
        advanceUntilIdle()

        val state = viewModel.authState.value
        assertTrue("Expected Error but got $state", state is AuthState.Error)
        val error = state as AuthState.Error
        assertTrue(
            "Error message should mention network, got: ${error.message}",
            error.message.contains("network", ignoreCase = true)
        )
    }

    @Test
    fun `sign-out - state transitions to LoggedOut and token cleared`() = runTest {
        // First log in
        coEvery { mockRepo.signInWithEmail(any(), any()) } returns "fake-id-token"
        coEvery { mockRepo.signOut() } just Runs

        viewModel.signInWithEmail("test@test.com", "password123")
        advanceUntilIdle()
        assertTrue(viewModel.authState.value is AuthState.LoggedIn)

        // Then sign out
        viewModel.signOut()
        advanceUntilIdle()

        val state = viewModel.authState.value
        assertTrue("Expected LoggedOut but got $state", state is AuthState.LoggedOut)
        assertNull("Token should be cleared after sign-out", viewModel.idToken)
        coVerify(exactly = 1) { mockRepo.signOut() }
    }
}
