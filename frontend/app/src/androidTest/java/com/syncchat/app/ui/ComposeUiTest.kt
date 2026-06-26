package com.syncchat.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syncchat.app.auth.AuthState
import com.syncchat.app.auth.AuthViewModel
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.local.dao.ConversationDao
import com.syncchat.app.data.local.dao.UserDao
import com.syncchat.app.ui.auth.LoginScreen
import com.syncchat.app.ui.home.HomeScreen
import com.syncchat.app.ui.home.HomeViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loginScreen_submitsCredentialsCorrectly() {
        val mockAuthViewModel = mockk<AuthViewModel>(relaxed = true)
        every { mockAuthViewModel.authState } returns MutableStateFlow(AuthState.LoggedOut)

        composeTestRule.setContent {
            LoginScreen(
                viewModel = mockAuthViewModel,
                onLoginSuccess = {},
                onGoogleSignIn = {},
                onNavigateToRegister = {}
            )
        }

        // Fill in credentials
        composeTestRule.onNodeWithText("Email").performTextInput("test@example.com")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")

        // Click Sign In
        composeTestRule.onNodeWithText("Sign In").performClick()

        // Verify signInWithEmail was called on ViewModel
        verify(exactly = 1) {
            mockAuthViewModel.signInWithEmail("test@example.com", "password123")
        }
    }

    @Test
    fun homeScreen_emptyState_showsPlaceholderText() {
        // Mock AppDatabase and Dao returning empty conversation flow
        val mockDb = mockk<AppDatabase>(relaxed = true)
        val mockConversationDao = mockk<ConversationDao>(relaxed = true)
        val mockUserDao = mockk<UserDao>(relaxed = true)
        
        every { mockDb.conversationDao() } returns mockConversationDao
        every { mockDb.userDao() } returns mockUserDao
        every { mockConversationDao.getAllConversationsFlow(any()) } returns MutableStateFlow(emptyList())

        // Set content of HomeScreen (HomeScreen handles its own ViewModel factory using AppDatabase)
        // Since HomeScreen expects AppDatabase.getDatabase(context), we can test the empty state message directly
        composeTestRule.setContent {
            HomeScreen(
                onConversationClick = { _, _ -> },
                onSignOut = {},
                database = mockDb
            )
        }

        // Verify empty state texts are displayed
        composeTestRule.onNodeWithText("No conversations yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tap the + button to start chatting with someone!").assertIsDisplayed()
    }

    @Test
    fun homeScreen_clickFab_opensSearchDialog() {
        val mockDb = mockk<AppDatabase>(relaxed = true)
        val mockConversationDao = mockk<ConversationDao>(relaxed = true)
        val mockUserDao = mockk<UserDao>(relaxed = true)

        every { mockDb.conversationDao() } returns mockConversationDao
        every { mockDb.userDao() } returns mockUserDao
        every { mockConversationDao.getAllConversationsFlow(any()) } returns MutableStateFlow(emptyList())

        composeTestRule.setContent {
            HomeScreen(
                onConversationClick = { _, _ -> },
                onSignOut = {},
                database = mockDb
            )
        }

        // Click on the Floating Action Button (FAB) to open dialog
        composeTestRule.onNodeWithContentDescription("Start New Chat").performClick()

        // Verify that the Dialog opens and displays "New Conversation" title
        composeTestRule.onNodeWithText("New Conversation").assertIsDisplayed()
    }
}
