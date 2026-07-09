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
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.local.entities.CachedConversation
import com.syncchat.app.data.local.entities.CachedUser
import com.syncchat.app.data.model.Conversation
import com.syncchat.app.data.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val currentUserId: String,
    private val apiRepository: ApiRepository = RetrofitApiRepository(),
    private val authRepository: AuthRepository = FirebaseAuthRepository(),
    private val database: AppDatabase
) : ViewModel() {

    private val firestore = FirebaseFirestore.getInstance()

    // UI state flow powered by Room database flow (instant update, automatically updates when Room is updated)
    val conversations: StateFlow<List<Conversation>> = database.conversationDao().getAllConversationsFlow(currentUserId)
        .map { list -> list.map { it.toDomain() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

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

            // Resolve profiles for conversations loaded from Room local cache
            viewModelScope.launch {
                conversations.collect { list ->
                    list.forEach { conv ->
                        val otherUid = conv.participantUids.firstOrNull { it != currentUserId }
                        if (otherUid != null) {
                            listenToUserProfile(otherUid)
                        }
                    }
                }
            }

            // Listen to real-time presence changes
            viewModelScope.launch {
                com.syncchat.app.data.signalr.SignalRManager.getInstance().presenceEvents.collect { (userId, isOnline) ->
                    // Update user profiles map
                    val currentProfiles = _userProfiles.value.toMutableMap()
                    val profile = currentProfiles[userId]
                    if (profile != null) {
                        currentProfiles[userId] = profile.copy(isOnline = isOnline)
                        _userProfiles.value = currentProfiles
                    }

                    // Update search results list if any matches
                    val currentResults = _searchResults.value.toMutableList()
                    val index = currentResults.indexOfFirst { it.uid == userId }
                    if (index != -1) {
                        currentResults[index] = currentResults[index].copy(isOnline = isOnline)
                        _searchResults.value = currentResults
                    }
                }
            }
        }
    }

    private fun listenToConversations() {
        val query = firestore.collection("conversations")
            .whereArrayContains("participantUids", currentUserId)

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

                        // Parse pinning and blocking fields
                        val blockedBy = doc.get("blockedBy") as? List<String> ?: emptyList()
                        val pinnedBy = doc.get("pinnedBy") as? List<String> ?: emptyList()
                        val isPinned = pinnedBy.contains(currentUserId)
                        val isBlocked = blockedBy.contains(currentUserId)
                        val isBlockedByOther = blockedBy.any { it != currentUserId }

                        list.add(Conversation(
                            id = id,
                            participantUids = participantUids,
                            lastMessage = lastMessage,
                            updatedAt = updatedAt,
                            isPinned = isPinned,
                            isBlocked = isBlocked,
                            isBlockedByOther = isBlockedByOther
                        ))

                        // Resolve other participant profiles
                        val otherUid = participantUids.firstOrNull { it != currentUserId }
                        if (otherUid != null) {
                            listenToUserProfile(otherUid)
                        }
                    } catch (e: Exception) {
                        Log.e("HomeViewModel", "Error parsing conversation doc ${doc.id}", e)
                    }
                }
                
                // Sort by pinned, then updatedAt descending
                list.sortWith(compareByDescending<Conversation> { it.isPinned }.thenByDescending { it.updatedAt })
                
                // Save conversations to Room in the background
                viewModelScope.launch(Dispatchers.IO) {
                    val cached = list.map { CachedConversation.fromDomain(it) }
                    database.conversationDao().insertConversations(cached)
                }
            }
        }
    }

    fun pinConversation(conversationId: String, pin: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.conversationDao().updatePinnedStatus(conversationId, pin)
                val docRef = firestore.collection("conversations").document(conversationId)
                if (pin) {
                    docRef.update("pinnedBy", com.google.firebase.firestore.FieldValue.arrayUnion(currentUserId)).await()
                } else {
                    docRef.update("pinnedBy", com.google.firebase.firestore.FieldValue.arrayRemove(currentUserId)).await()
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Pin conversation failed", e)
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.conversationDao().deleteConversationById(conversationId)
                database.messageDao().deleteMessagesForConversation(conversationId)
                val docRef = firestore.collection("conversations").document(conversationId)
                val doc = docRef.get().await()
                if (doc.exists()) {
                    val participants = doc.get("participantUids") as? List<String> ?: emptyList()
                    val newParticipants = participants.filter { it != currentUserId }
                    if (newParticipants.isEmpty()) {
                        docRef.delete().await()
                    } else {
                        docRef.update("participantUids", newParticipants).await()
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Delete conversation failed", e)
            }
        }
    }

    fun clearChat(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.messageDao().deleteMessagesForConversation(conversationId)
                val messagesRef = firestore.collection("conversations")
                    .document(conversationId)
                    .collection("messages")
                val snapshot = messagesRef.get().await()
                firestore.runBatch { batch ->
                    for (doc in snapshot.documents) {
                        batch.delete(doc.reference)
                    }
                }.await()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Clear chat failed", e)
            }
        }
    }

    fun blockUser(conversationId: String, block: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val docRef = firestore.collection("conversations").document(conversationId)
                if (block) {
                    docRef.update("blockedBy", com.google.firebase.firestore.FieldValue.arrayUnion(currentUserId)).await()
                } else {
                    docRef.update("blockedBy", com.google.firebase.firestore.FieldValue.arrayRemove(currentUserId)).await()
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Block user failed", e)
            }
        }
    }

    private val profileListeners = mutableMapOf<String, ListenerRegistration>()

    private fun listenToUserProfile(uid: String) {
        if (uid.isEmpty()) return
        synchronized(profileListeners) {
            if (profileListeners.containsKey(uid)) return

            // 1. Try to load from Room local database first (instant display)
            viewModelScope.launch {
                try {
                    val localUser = withContext(Dispatchers.IO) {
                        database.userDao().getUserById(uid)
                    }
                    if (localUser != null) {
                        val profile = localUser.toDomain()
                        val updatedMap = _userProfiles.value.toMutableMap()
                        if (!updatedMap.containsKey(uid)) {
                            updatedMap[uid] = profile
                            _userProfiles.value = updatedMap
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Failed to load local profile for $uid", e)
                }
            }

            // 2. Setup real-time listener
            val listener = firestore.collection("users").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("HomeViewModel", "Error listening to profile of $uid", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val profile = UserProfile(
                            uid = snapshot.id,
                            displayName = snapshot.getString("displayName") ?: "",
                            email = snapshot.getString("email") ?: "",
                            photoUrl = snapshot.getString("photoUrl"),
                            bio = snapshot.getString("bio"),
                            isOnline = snapshot.getBoolean("isOnline") ?: (_userProfiles.value[uid]?.isOnline ?: false)
                        )

                        // Save to Room cache
                        viewModelScope.launch(Dispatchers.IO) {
                            database.userDao().insertUser(CachedUser.fromDomain(profile))
                        }

                        // Update UI
                        val updatedMap = _userProfiles.value.toMutableMap()
                        updatedMap[uid] = profile
                        _userProfiles.value = updatedMap
                    }
                }
            profileListeners[uid] = listener
        }
    }

    fun searchUsers(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            try {
                val term = query.trim()
                val capitalizedTerm = if (term.isNotEmpty()) term.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() } else ""
                
                // Get all users matching term as prefix in displayName
                val nameQueryTask = if (term.isNotEmpty()) {
                    firestore.collection("users")
                        .orderBy("displayName")
                        .startAt(term)
                        .endAt(term + "\uf8ff")
                        .limit(50)
                        .get()
                } else {
                    firestore.collection("users")
                        .orderBy("displayName")
                        .limit(50)
                        .get()
                }

                val nameCapQueryTask = if (capitalizedTerm.isNotEmpty()) {
                    firestore.collection("users")
                        .orderBy("displayName")
                        .startAt(capitalizedTerm)
                        .endAt(capitalizedTerm + "\uf8ff")
                        .limit(50)
                        .get()
                } else null
                
                val nameSnapshot = nameQueryTask.await()
                val nameCapSnapshot = nameCapQueryTask?.await()
                
                var results = mutableListOf<UserProfile>()
                val seenUids = mutableSetOf<String>()
                
                val allDocs = nameSnapshot.documents + (nameCapSnapshot?.documents ?: emptyList())
                for (doc in allDocs) {
                    val uid = doc.id
                    if (uid != currentUserId && seenUids.add(uid)) {
                        results.add(
                            UserProfile(
                                uid = uid,
                                displayName = doc.getString("displayName") ?: "",
                                email = doc.getString("email") ?: "",
                                photoUrl = doc.getString("photoUrl"),
                                isOnline = doc.getBoolean("isOnline") ?: false
                            )
                        )
                    }
                }
                
                // Sort alphabetically by displayName
                results.sortBy { it.displayName.lowercase(java.util.Locale.getDefault()) }
                
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
        synchronized(profileListeners) {
            profileListeners.values.forEach { it.remove() }
            profileListeners.clear()
        }
    }
}
