package com.syncchat.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.syncchat.app.auth.AuthState
import com.syncchat.app.auth.AuthViewModel
import com.syncchat.app.auth.FirebaseAuthRepository
import com.syncchat.app.data.api.RetrofitApiRepository
import com.syncchat.app.ui.auth.LoginScreen
import com.syncchat.app.ui.auth.RegisterScreen
import com.syncchat.app.ui.auth.SplashScreen
import com.syncchat.app.ui.home.HomeScreen
import com.syncchat.app.ui.theme.SyncChatTheme
import com.syncchat.app.ui.chat.ChatScreen
import com.syncchat.app.data.model.UserProfile
import com.syncchat.app.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by lazy {
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AuthViewModel(
                        authRepository = FirebaseAuthRepository(),
                        apiRepository = RetrofitApiRepository()
                    ) as T
                }
            }
        )[AuthViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable Firestore offline persistence
        try {
            val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            com.google.firebase.firestore.FirebaseFirestore.getInstance().firestoreSettings = settings
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set Firestore settings: ${e.message}")
        }

        enableEdgeToEdge()
        setContent {
            SyncChatTheme {
                val authState by authViewModel.authState.collectAsState()
                var showRegister by remember { mutableStateOf(false) }

                var activeConversationId by remember { mutableStateOf<String?>(null) }
                var activeConversationUser by remember { mutableStateOf<UserProfile?>(null) }

                // Auto-clear navigation when signed out
                LaunchedEffect(authState) {
                    if (authState !is AuthState.LoggedIn) {
                        activeConversationId = null
                        activeConversationUser = null
                    }
                }

                when (authState) {
                    is AuthState.Idle, is AuthState.Loading -> {
                        SplashScreen()
                    }
                    is AuthState.LoggedIn -> {
                        val convId = activeConversationId
                        val otherUser = activeConversationUser
                        if (convId != null && otherUser != null) {
                            ChatScreen(
                                conversationId = convId,
                                otherUser = otherUser,
                                onBackClick = {
                                    activeConversationId = null
                                    activeConversationUser = null
                                }
                            )
                        } else {
                            HomeScreen(
                                onConversationClick = { id, user ->
                                    activeConversationId = id
                                    activeConversationUser = user
                                },
                                onSignOut = {
                                    authViewModel.signOut()
                                    val db = AppDatabase.getDatabase(this@MainActivity)
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        db.clearAllTables()
                                    }
                                }
                            )
                        }
                    }
                    else -> {
                        if (showRegister) {
                            RegisterScreen(
                                viewModel = authViewModel,
                                onNavigateToLogin = {
                                    authViewModel.resetState()
                                    showRegister = false
                                }
                            )
                            LaunchedEffect(authState) {
                                if (authState is AuthState.LoggedIn) showRegister = false
                            }
                        } else {
                            LoginScreen(
                                viewModel = authViewModel,
                                onLoginSuccess = { },
                                onGoogleSignIn = { launchGoogleSignIn() },
                                onNavigateToRegister = {
                                    authViewModel.resetState()
                                    showRegister = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchGoogleSignIn() {
        val credentialManager = CredentialManager.create(this)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(getString(R.string.default_web_client_id))
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(this@MainActivity, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    authViewModel.signInWithGoogle(googleIdTokenCredential.idToken)
                }
            } catch (e: GetCredentialException) {
                Log.e("MainActivity", "Google Sign-In failed: ${e.message}")
            }
        }
    }
}