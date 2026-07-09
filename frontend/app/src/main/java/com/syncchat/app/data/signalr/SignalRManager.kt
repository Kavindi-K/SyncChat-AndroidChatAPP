package com.syncchat.app.data.signalr

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class ConnectionStatus {
    Disconnected,
    Reconnecting,
    Connected,
    Failed
}

class SignalRManager {
    private var hubConnection: HubConnection? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _presenceEvents = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)
    val presenceEvents: SharedFlow<Pair<String, Boolean>> = _presenceEvents.asSharedFlow()

    private var retryCount = 0
    private val MAX_RETRIES = 5
    private val RETRY_DELAYS = listOf(2000L, 4000L, 8000L, 16000L, 30000L)

    companion object {
        // Local backend Hub URL for USB debugging/emulation via adb reverse
        private const val HUB_URL = "http://localhost:5228/hubs/chat"
        private const val TAG = "SignalRManager"
        
        @Volatile
        private var instance: SignalRManager? = null
        
        fun getInstance(): SignalRManager {
            return instance ?: synchronized(this) {
                instance ?: SignalRManager().also { instance = it }
            }
        }
    }

    fun startConnection() {
        if (hubConnection?.connectionState == HubConnectionState.CONNECTED) return
        
        if (hubConnection == null) {
            buildConnection()
        }
        
        connectWithRetry()
    }

    private fun buildConnection() {
        hubConnection = HubConnectionBuilder.create(HUB_URL)
            .withAccessTokenProvider(Single.defer {
                Single.fromCallable {
                    val user = FirebaseAuth.getInstance().currentUser
                    if (user != null) {
                        try {
                            val task = user.getIdToken(false)
                            val token = com.google.android.gms.tasks.Tasks.await(task).token ?: ""
                            Log.e("FIREBASE_TOKEN", "COPY THIS TOKEN: $token")
                            return@fromCallable token

                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to get token", e)
                            ""
                        }
                    } else {
                        ""
                    }
                }
            })
            .build()

        hubConnection?.on("UserPresenceChanged", { userId: String, isOnline: Boolean ->
            Log.d(TAG, "Presence changed: User $userId isOnline=$isOnline")
            scope.launch {
                _presenceEvents.emit(Pair(userId, isOnline))
            }
        }, String::class.java, java.lang.Boolean::class.java)

        hubConnection?.onClosed { exception ->
            Log.w(TAG, "Connection closed. Exception: \${exception?.message}")
            if (exception?.message?.contains("401") == true || exception?.message?.contains("Unauthorized") == true) {
                // Token expired mid-session
                Log.d(TAG, "401 received, forcing token refresh")
                forceTokenRefresh()
            }
            
            if (_connectionStatus.value != ConnectionStatus.Disconnected) {
                connectWithRetry()
            }
        }
    }

    private fun forceTokenRefresh() {
        scope.launch {
            try {
                FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
            } catch (e: Exception) {
                Log.e(TAG, "Force token refresh failed", e)
            }
        }
    }

    private fun connectWithRetry() {
        if (_connectionStatus.value == ConnectionStatus.Connected) return
        
        scope.launch {
            _connectionStatus.value = ConnectionStatus.Reconnecting
            
            while (retryCount < MAX_RETRIES) {
                try {
                    Log.d(TAG, "Attempting to connect to SignalR hub... (Attempt \${retryCount + 1})")
                    hubConnection?.start()?.blockingAwait()
                    
                    Log.d(TAG, "Successfully connected to SignalR hub!")
                    _connectionStatus.value = ConnectionStatus.Connected
                    retryCount = 0
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to SignalR hub: \${e.message}")
                    val delayMs = RETRY_DELAYS.getOrElse(retryCount) { 30000L }
                    retryCount++
                    
                    if (retryCount >= MAX_RETRIES) {
                        Log.e(TAG, "Max retries reached. Connection failed.")
                        _connectionStatus.value = ConnectionStatus.Failed
                        return@launch
                    }
                    
                    Log.d(TAG, "Waiting \${delayMs}ms before next retry...")
                    delay(delayMs)
                }
            }
        }
    }

    fun stopConnection() {
        _connectionStatus.value = ConnectionStatus.Disconnected
        retryCount = 0
        try {
            hubConnection?.stop()?.blockingAwait()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping connection", e)
        } finally {
            hubConnection = null
        }
    }
    
    // Allows sending messages directly via hub if needed
    fun getHubConnection(): HubConnection? = hubConnection
}
