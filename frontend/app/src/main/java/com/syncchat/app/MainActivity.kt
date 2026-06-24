package com.syncchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.syncchat.app.auth.AuthState
import com.syncchat.app.auth.AuthViewModel
import com.syncchat.app.ui.auth.LoginScreen
import com.syncchat.app.ui.home.HomeScreen
import com.syncchat.app.ui.theme.SyncChatTheme

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncChatTheme {
                val authState by authViewModel.authState.collectAsState()

                when (authState) {
                    is AuthState.LoggedIn -> {
                        HomeScreen(
                            onSignOut = { authViewModel.signOut() }
                        )
                    }
                    else -> {
                        LoginScreen(
                            viewModel = authViewModel,
                            onLoginSuccess = { /* Handled by state observation */ },
                            onGoogleSignIn = { launchGoogleSignIn() }
                        )
                    }
                }
            }
        }
    }

    private fun launchGoogleSignIn() {
        // TODO Phase 2: Implement Credential Manager Google Sign-In flow
        // val credentialManager = CredentialManager.create(this)
        // val request = GetCredentialRequest(listOf(GetGoogleIdOption(...)))
        // Pass googleIdToken to authViewModel.signInWithGoogle(googleIdToken)
    }
}