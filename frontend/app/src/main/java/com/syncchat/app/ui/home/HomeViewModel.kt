package com.syncchat.app.ui.home

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
import com.syncchat.app.data.model.Conversation
import com.syncchat.app.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class HomeViewModel(
    private val apiRepository: ApiRepository = RetrofitApiRepository(),
    private val authRepository: AuthRepository = FirebaseAuthRepository()
) : ViewModel() {

    private val firestore = FirebaseFirestore.getInstance()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _userProfiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    val userProfiles: StateFlow<Map<String, UserProfile>> = _userProfiles.asStateFlow()

    private val _searchResults = MutableStateFlow<List<UserProfile>>(emptyList())
    val searchResults: StateFlow<List<UserProfile>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var listenerRegistration: ListenerRegistration? = null

    init {
        if (currentUserId.isNotEmpty()) {
            listenToConversations()
        }
    }

    private fun listenToConversations() {
        val query = firestore.collection("conversations")
            .whereArrayContains("participantUids", currentUserId)
            .orderBy("updatedAt", Query.Direction.DESCENDING)

        listenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("HomeViewModel", "Listen failed.", error)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val list = mutableListOf<Conversation>()
                for (doc in snapshot.documents) {
                    try {
                        val id = doc.id
                        val participantUids = doc.get("participantUids") as? List<String> ?: emptyList()
                        
                        // Parse lastMessage
                        val lastMsgMap = doc.get("lastMessage") as? Map<String, Any>
                        val lastMessage = lastMsgMap?.let {
                            com.syncchat.app.data.model.LastMessageInfo(
                                text = it["text"] as? String ?: "",
                                senderId = it["senderId"] as? String ?: "",
                                timestamp = (it["timestamp"] as? com.google.firebase.Timestamp)?.toDate() ?: java.util.Date()
                            )
                        }

                        val updatedAt = doc.getTimestamp("updatedAt")?.toDate() ?: java.util.Date()

                        list.add(Conversation(id, participantUids, lastMessage, updatedAt))

                        // Resolve other participant profiles
                        val otherUid = participantUids.firstOrNull { it != currentUserId }
                        if (otherUid != null && !_userProfiles.value.containsKey(otherUid)) {
                            fetchUserProfile(otherUid)
                        }
                    } catch (e: Exception) {
                        Log.e("HomeViewModel", "Error parsing conversation doc ${doc.id}", e)
                    }
                }
                _conversations.value = list
            }
        }
    }

    private fun fetchUserProfile(uid: String) {
        viewModelScope.launch {
            try {
                val doc = firestore.collection("users").document(uid).get().await()
                if (doc.exists()) {
                    val profile = UserProfile(
                        uid = doc.id,
                        displayName = doc.getString("displayName") ?: "",
                        email = doc.getString("email") ?: "",
                        photoUrl = doc.getString("photoUrl")
                    )
                    val updatedMap = _userProfiles.value.toMutableMap()
                    updatedMap[uid] = profile
                    _userProfiles.value = updatedMap
                } else {
                    // Try to fall back to API using token
                    val token = authRepository.getIdToken() ?: return@launch
                    val profile = apiRepository.getUserProfile(token, uid)
                    val updatedMap = _userProfiles.value.toMutableMap()
                    updatedMap[uid] = profile
                    _userProfiles.value = updatedMap
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error fetching user profile for $uid", e)
            }
        }
    }

    fun searchUsers(query: String) {
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isSearching.value = true
            try {
                val term = query.trim()
                
                // Get all users matching term as prefix in displayName or email
                val nameQueryTask = firestore.collection("users")
                    .orderBy("displayName")
                    .startAt(term)
                    .endAt(term + "\uf8ff")
                    .limit(20)
                    .get()
                
                val emailQueryTask = firestore.collection("users")
                    .orderBy("email")
                    .startAt(term)
                    .endAt(term + "\uf8ff")
                    .limit(20)
                    .get()
                
                val nameSnapshot = nameQueryTask.await()
                val emailSnapshot = emailQueryTask.await()
                
                val results = mutableListOf<UserProfile>()
                val seenUids = mutableSetOf<String>()
                
                for (doc in nameSnapshot.documents) {
                    val uid = doc.id
                    if (uid != currentUserId && seenUids.add(uid)) {
                        results.add(
                            UserProfile(
                                uid = uid,
                                displayName = doc.getString("displayName") ?: "",
                                email = doc.getString("email") ?: "",
                                photoUrl = doc.getString("photoUrl")
                            )
                        )
                    }
                }
                
                for (doc in emailSnapshot.documents) {
                    val uid = doc.id
                    if (uid != currentUserId && seenUids.add(uid)) {
                        results.add(
                            UserProfile(
                                uid = uid,
                                displayName = doc.getString("displayName") ?: "",
                                email = doc.getString("email") ?: "",
                                photoUrl = doc.getString("photoUrl")
                            )
                        )
                    }
                }
                
                _searchResults.value = results
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Search failed", e)
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun startConversation(targetUserId: String, onConversationStarted: (String) -> Unit) {
        viewModelScope.launch {
            try {
                // Check if a conversation between these two users already exists
                val querySnapshot = firestore.collection("conversations")
                    .whereArrayContains("participantUids", currentUserId)
                    .get()
                    .await()
                
                var existingConversationId: String? = null
                for (doc in querySnapshot.documents) {
                    val participants = doc.get("participantUids") as? List<String> ?: emptyList()
                    if (participants.contains(targetUserId)) {
                        existingConversationId = doc.id
                        break
                    }
                }
                
                if (existingConversationId != null) {
                    onConversationStarted(existingConversationId)
                } else {
                    // Create a new conversation document
                    val newDocRef = firestore.collection("conversations").document()
                    val conversationData = hashMapOf(
                        "participantUids" to listOf(currentUserId, targetUserId),
                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                    newDocRef.set(conversationData).await()
                    onConversationStarted(newDocRef.id)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to start conversation", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        listenerRegistration?.remove()
    }
}
