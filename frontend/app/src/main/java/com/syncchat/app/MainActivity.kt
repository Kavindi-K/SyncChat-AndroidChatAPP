package com.syncchat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.syncchat.app.auth.AuthState
import com.syncchat.app.auth.AuthViewModel
import com.syncchat.app.auth.FirebaseAuthRepository
import com.syncchat.app.data.api.RetrofitApiRepository
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.local.entities.CachedUser
import com.syncchat.app.data.model.UserProfile
import com.syncchat.app.ui.auth.LoginScreen
import com.syncchat.app.ui.auth.RegisterScreen
import com.syncchat.app.ui.auth.SplashScreen
import com.syncchat.app.ui.chat.ChatScreen
import com.syncchat.app.ui.home.HomeScreen
import com.syncchat.app.ui.profile.ProfileScreen
import com.syncchat.app.ui.theme.SyncChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    // Exposed at class level so they can be set from deep link intents
    private var activeConversationId by mutableStateOf<String?>(null)
    private var activeConversationUser by mutableStateOf<UserProfile?>(null)
    private var showProfile by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request post notifications permission on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

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

                // Auto-clear navigation when signed out
                LaunchedEffect(authState) {
                    if (authState !is AuthState.LoggedIn) {
                        activeConversationId = null
                        activeConversationUser = null
                    }
                }

                // Retrieve and upload FCM token when logged in
                LaunchedEffect(authState) {
                    if (authState is AuthState.LoggedIn) {
                        try {
                            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val token = task.result
                                        Log.d("MainActivity", "FCM token: $token")
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            try {
                                                val idToken = FirebaseAuthRepository().getIdToken()
                                                if (idToken != null) {
                                                    RetrofitApiRepository().registerFcmToken(idToken, token)
                                                    Log.d("MainActivity", "Token registered with backend successfully")
                                                }
                                            } catch (e: Exception) {
                                                Log.e("MainActivity", "FCM backend registration failed: ${e.message}")
                                            }
                                        }
                                    }
                                }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "FCM token retrieval failed: ${e.message}")
                        }
                    }
                }

                when (authState) {
                    is AuthState.Idle, is AuthState.Loading -> {
                        SplashScreen()
                    }
                    is AuthState.LoggedIn -> {
                        val convId = activeConversationId
                        val otherUser = activeConversationUser
                        when {
                            showProfile -> {
                                androidx.activity.compose.BackHandler { showProfile = false }
                                ProfileScreen(onBackClick = { showProfile = false })
                            }
                            convId != null && otherUser != null -> {
                                androidx.activity.compose.BackHandler {
                                    activeConversationId = null
                                    activeConversationUser = null
                                }
                                ChatScreen(
                                    conversationId = convId,
                                    otherUser = otherUser,
                                    onBackClick = {
                                        activeConversationId = null
                                        activeConversationUser = null
                                    }
                                )
                            }
                            else -> {
                                HomeScreen(
                                    onConversationClick = { id, user ->
                                        activeConversationId = id
                                        activeConversationUser = user
                                    },
                                    onProfileClick = { showProfile = true },
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

        // Process any launch deep link
        handleDeepLinkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val conversationId = intent?.getStringExtra("conversationId") ?: return
        Log.d("MainActivity", "Processing deep link for conversationId: $conversationId")

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@MainActivity)
                var otherUserId: String? = null

                // 1. Check local Room database
                val cachedConv = db.conversationDao().getConversationById(conversationId)
                if (cachedConv != null) {
                    val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    otherUserId = cachedConv.participantUidsString.split(",")
                        .firstOrNull { it != currentUserId }
                }

                // 2. If not found in local db, fetch from Firestore
                if (otherUserId == null) {
                    val task = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("conversations")
                        .document(conversationId)
                        .get()
                    val snapshot = suspendCancellableCoroutine { cont ->
                        task.addOnCompleteListener { t ->
                            if (t.isSuccessful) cont.resume(t.result)
                            else cont.resume(null)
                        }
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val participants = snapshot.get("participantUids") as? List<*>
                        val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                        otherUserId = participants?.mapNotNull { it as? String }
                            ?.firstOrNull { it != currentUserId }
                    }
                }

                if (otherUserId == null) {
                    Log.w("MainActivity", "Could not find recipient user ID for conversation $conversationId")
                    return@launch
                }

                // 3. Get UserProfile for the recipient
                var userProfile: UserProfile? = null
                val cachedUser = db.userDao().getUserById(otherUserId)
                if (cachedUser != null) {
                    userProfile = cachedUser.toDomain()
                } else {
                    // Fetch profile from backend/API
                    val idToken = FirebaseAuthRepository().getIdToken()
                    if (idToken != null) {
                        try {
                            userProfile = RetrofitApiRepository().getUserProfile(idToken, otherUserId)
                            // Save to local cache
                            db.userDao().insertUser(CachedUser.fromDomain(userProfile))
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to fetch user profile: ${e.message}")
                        }
                    }
                }

                if (userProfile != null) {
                    // Update navigation state to view the chat screen
                    activeConversationId = conversationId
                    activeConversationUser = userProfile
                    Log.d("MainActivity", "Deep link navigation successful to conversation $conversationId")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error handling deep link: ${e.message}", e)
            }
        }
    }

}