package com.syncchat.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ChatViewModel(
    private val conversationId: String,
    private val currentUserId: String,
    private val apiRepository: ApiRepository = RetrofitApiRepository(),
    private val authRepository: AuthRepository = FirebaseAuthRepository(),
    private val database: AppDatabase
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

    private var listenerRegistration: ListenerRegistration? = null
    private val okHttpClient = OkHttpClient()

    init {
        listenToMessages()
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
                    val cached = list.map { CachedMessage.fromDomain(it) }
                    database.messageDao().insertMessages(cached)
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.trim().isEmpty()) return

        viewModelScope.launch {
            _isSending.value = true
            try {
                val token = authRepository.getIdToken() ?: return@launch
                apiRepository.sendMessage(token, conversationId, text, null)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to send message", e)
            } finally {
                _isSending.value = false
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
                val contentType = fileDetails.second ?: "image/jpeg"

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

                val response = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute()
                }

                if (!response.isSuccessful) {
                    throw Exception("GCS Upload failed with status code ${response.code}")
                }

                // 5. Send message with the public mediaUrl
                _uploadProgress.value = "Finalizing message..."
                apiRepository.sendMessage(token, conversationId, "[Image]", uploadResponse.downloadUrl)

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Image send failed", e)
            } finally {
                _isSending.value = false
                _uploadProgress.value = null
            }
        }
    }

    private fun getFileDetails(context: Context, uri: Uri): Pair<String, String?> {
        var name = "image_${System.currentTimeMillis()}.jpg"
        val mimeType = context.contentResolver.getType(uri)
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return Pair(name, mimeType)
    }

    private suspend fun readFileBytes(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw Exception("Failed to open input stream for URI: $uri")
    }

    override fun onCleared() {
        super.onCleared()
        listenerRegistration?.remove()
    }
}
