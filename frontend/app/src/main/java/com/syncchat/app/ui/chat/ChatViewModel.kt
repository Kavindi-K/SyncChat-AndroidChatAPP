package com.syncchat.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.syncchat.app.auth.AuthRepository
import com.syncchat.app.auth.FirebaseAuthRepository
import com.syncchat.app.data.api.ApiRepository
import com.syncchat.app.data.api.RetrofitApiRepository
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.local.entities.CachedMessage
import com.syncchat.app.data.model.Message
import com.syncchat.app.data.model.MessageStatus
import com.syncchat.app.data.workers.MessageSyncWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.syncchat.app.data.signalr.SignalRManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class ChatViewModel(
    private val conversationId: String,
    private val currentUserId: String,
    private val apiRepository: ApiRepository = RetrofitApiRepository(),
    private val authRepository: AuthRepository = FirebaseAuthRepository(),
    private val database: AppDatabase,
    private val context: Context? = null, // Optional context for WorkManager scheduling
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val firestore = FirebaseFirestore.getInstance()

    // UI state flow powered by Room database flow (instant update)
    val messages: StateFlow<List<Message>> = database.messageDao().getMessagesForConversationFlow(conversationId)
        .map { list -> list.map { it.toDomain() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _uploadProgress = MutableStateFlow<String?>(null)
    val uploadProgress: StateFlow<String?> = _uploadProgress.asStateFlow()

    private val _typingUsers = MutableStateFlow<Set<String>>(emptySet())
    val typingUsers: StateFlow<Set<String>> = _typingUsers.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    private val signalRManager = SignalRManager.getInstance()

    private var listenerRegistration: ListenerRegistration? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        listenToMessages()
        setupSignalRListeners()
        scheduleMessageSync()
    }

    private fun setupSignalRListeners() {
        signalRManager.startConnection()
        
        val hubConnection = signalRManager.getHubConnection() ?: return
        
        hubConnection.on("UserTyping", { convId: String, userId: String ->
            if (convId == conversationId && userId != currentUserId) {
                _typingUsers.update { it + userId }
            }
        }, String::class.java, String::class.java)

        hubConnection.on("UserStoppedTyping", { convId: String, userId: String ->
            if (convId == conversationId) {
                _typingUsers.update { it - userId }
            }
        }, String::class.java, String::class.java)
        
        // Listen for real-time NewMessage
        hubConnection.on("NewMessage", { payloadStr: String ->
            try {
                // Since SignalR can't easily parse complex Android objects by default with Gson,
                // we'll use a basic parsing or rely on the fact that payload might be JSON.
                // Assuming payload is JSON string or object. If we pass plain strings:
                val gson = com.google.gson.Gson()
                val msg = gson.fromJson(payloadStr, com.syncchat.app.data.model.Message::class.java)
                
                if (msg.conversationId == conversationId) {
                    viewModelScope.launch(Dispatchers.IO) {
                        database.messageDao().insertMessages(listOf(CachedMessage.fromDomain(msg)))
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error parsing NewMessage", e)
            }
        }, String::class.java)

        // Listen for real-time MessageRead
        hubConnection.on("MessageRead", { convId: String, msgId: String, readerId: String ->
            if (convId == conversationId) {
                viewModelScope.launch(Dispatchers.IO) {
                    val msgFlow = database.messageDao().getMessagesForConversationFlow(convId)
                    val msgList = msgFlow.first()
                    val msg = msgList.find { it.id == msgId }
                    if (msg != null && !msg.readByString.contains(readerId)) {
                        val currentReadBy = if (msg.readByString.isEmpty()) emptyList() else msg.readByString.split(",")
                        if (!currentReadBy.contains(readerId)) {
                            val newReadBy = currentReadBy + readerId
                            database.messageDao().insertMessages(listOf(msg.copy(readByString = newReadBy.joinToString(","))))
                        }
                    }
                }
            }
        }, String::class.java, String::class.java, String::class.java)
    }

    private fun listenToMessages() {
        val query = firestore.collection("conversations")
            .document(conversationId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)

        listenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("ChatViewModel", "Listen messages failed.", error)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val list = mutableListOf<Message>()
                for (doc in snapshot.documents) {
                    try {
                        val id = doc.id
                        val senderId = doc.getString("senderId") ?: ""
                        val text = doc.getString("text") ?: ""
                        val mediaUrl = doc.getString("mediaUrl")
                        val timestamp = doc.getTimestamp("timestamp")?.toDate() ?: java.util.Date()
                        val readBy = doc.get("readBy") as? List<String> ?: emptyList()

                        list.add(Message(id, conversationId, senderId, text, mediaUrl, timestamp, readBy))
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Error parsing message doc ${doc.id}", e)
                    }
                }
                
                // Save messages to Room in background
                viewModelScope.launch(Dispatchers.IO) {
                    val pendingList = database.messageDao().getPendingMessages()
                    for (serverMsg in list) {
                        if (serverMsg.senderId == currentUserId) {
                            val matchedPending = pendingList.find { pending ->
                                pending.text == serverMsg.text &&
                                pending.mediaUrl == serverMsg.mediaUrl &&
                                Math.abs(pending.timestampTime - serverMsg.timestamp.time) < 30000
                            }
                            if (matchedPending != null) {
                                database.messageDao().deleteMessageById(matchedPending.id)
                            }
                        }
                    }
                    val cached = list.map { CachedMessage.fromDomain(it) }
                    database.messageDao().insertMessages(cached)
                }
            }
        }
    }

    /**
     * Offline-first message sending:
     * 1. Immediately insert into Room as PENDING (appears in UI instantly with ⌛)
     * 2. Try to deliver via SignalR or REST API
     * 3. On success → mark as SENT (UI updates to ✓)
     * 4. On failure → stays PENDING (WorkManager will retry when connectivity is restored)
     */
    fun sendMessage(text: String) {
        if (text.trim().isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _isSending.value = true

            // 1. Generate a local ID and insert into Room immediately as PENDING
            val localId = UUID.randomUUID().toString()
            val pendingMessage = CachedMessage(
                id = localId,
                conversationId = conversationId,
                senderId = currentUserId,
                text = text.trim(),
                mediaUrl = null,
                timestampTime = System.currentTimeMillis(),
                readByString = "",
                status = MessageStatus.PENDING.name
            )
            database.messageDao().insertMessage(pendingMessage)

            // 2. Try to deliver via SignalR or REST API
            try {
                val hub = signalRManager.getHubConnection()
                if (hub != null && hub.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
                    hub.invoke("SendMessage", conversationId, text.trim()).blockingAwait()
                } else {
                    val token = authRepository.getIdToken() ?: throw Exception("No auth token")
                    apiRepository.sendMessage(token, conversationId, text.trim(), null)
                }

                // 3. Mark local pending message as SENT (prevents UI flicker while waiting for server snapshot)
                database.messageDao().updateMessageStatus(localId, com.syncchat.app.data.model.MessageStatus.SENT.name)
                stopTyping()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to send, message queued as PENDING", e)
                _errorMessage.value = "Failed to send message. It will be retried automatically."
                // Message stays PENDING — WorkManager will retry when connectivity is restored
                scheduleMessageSync()
            } finally {
                _isSending.value = false
            }
        }
    }

    fun startTyping() {
        viewModelScope.launch(ioDispatcher) {
            try {
                signalRManager.getHubConnection()?.invoke("StartTyping", conversationId)?.blockingAwait()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "StartTyping failed", e)
            }
        }
    }

    fun stopTyping() {
        viewModelScope.launch(ioDispatcher) {
            try {
                signalRManager.getHubConnection()?.invoke("StopTyping", conversationId)?.blockingAwait()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "StopTyping failed", e)
            }
        }
    }

    fun markAsRead(messageId: String) {
        viewModelScope.launch(ioDispatcher) {
            try {
                signalRManager.getHubConnection()?.invoke("MarkRead", conversationId, messageId)?.blockingAwait()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "MarkRead failed", e)
            }
        }
    }

    fun sendImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isSending.value = true
            _uploadProgress.value = "Preparing upload..."
            try {
                val token = authRepository.getIdToken() ?: return@launch

                // 1. Get file details
                val fileDetails = getFileDetails(context, uri)
                val fileName = fileDetails.first
                val contentType = fileDetails.second ?: "application/octet-stream"

                // 2. Request signed URL from backend
                _uploadProgress.value = "Getting upload URL..."
                val uploadResponse = apiRepository.getUploadUrl(token, fileName, contentType)

                // 3. Read file bytes
                _uploadProgress.value = "Reading file..."
                val fileBytes = readFileBytes(context, uri)

                // 4. PUT file directly to GCS via the signed URL
                _uploadProgress.value = "Uploading file..."
                val requestBody = fileBytes.toRequestBody(contentType.toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(uploadResponse.uploadUrl)
                    .put(requestBody)
                    .build()

                val response = withContext(ioDispatcher) {
                    okHttpClient.newCall(request).execute()
                }

                if (!response.isSuccessful) {
                    response.close()
                    throw Exception("GCS Upload failed with status code ${response.code}")
                }
                response.close()

                // 5. Send message with the public mediaUrl and dynamic type placeholder
                _uploadProgress.value = "Finalizing message..."
                val displayText = when {
                    contentType.startsWith("image/") -> "[Image]"
                    contentType.startsWith("video/") -> "[Video]"
                    else -> "[File] $fileName"
                }
                apiRepository.sendMessage(token, conversationId, displayText, uploadResponse.downloadUrl)

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Image send failed", e)
                _errorMessage.value = "Error: ${e.localizedMessage ?: e.message ?: "Upload failed"}"
            } finally {
                _isSending.value = false
                _uploadProgress.value = null
            }
        }
    }

    /**
     * Schedule a WorkManager job to sync any PENDING messages
     * when network connectivity is available.
     */
    private fun scheduleMessageSync() {
        val ctx = context ?: return
        val syncRequest = OneTimeWorkRequestBuilder<MessageSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            MessageSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    private fun getFileDetails(context: Context, uri: Uri): Pair<String, String?> {
        var mimeType = context.contentResolver.getType(uri)
        
        // Query display name first
        var name = ""
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            // Safe fallback if query fails
        }

        // If we couldn't get the name from the query, use a timestamped default
        if (name.isEmpty()) {
            val extension = mimeType?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            name = if (extension != null) {
                "file_${System.currentTimeMillis()}.$extension"
            } else {
                "file_${System.currentTimeMillis()}"
            }
        }

        // If MIME type is still null, resolve it from the filename extension
        if (mimeType == null) {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isNotEmpty()) {
                mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            }
        }

        return Pair(name, mimeType)
    }

    private suspend fun readFileBytes(context: Context, uri: Uri): ByteArray = withContext(ioDispatcher) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw Exception("Failed to open input stream for URI: $uri")
    }

    override fun onCleared() {
        super.onCleared()
        listenerRegistration?.remove()
    }
}
