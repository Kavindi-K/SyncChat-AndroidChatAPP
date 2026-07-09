package com.syncchat.app.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.model.Conversation
import com.syncchat.app.data.model.UserProfile
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onConversationClick: (String, UserProfile) -> Unit,
    onSignOut: () -> Unit,
    onProfileClick: () -> Unit,
    database: AppDatabase = AppDatabase.getDatabase(LocalContext.current)
) {
    val currentUser = FirebaseAuth.getInstance().currentUser
    val currentUserId = currentUser?.uid ?: ""
    val currentUserEmail = currentUser?.email ?: ""

    var currentUserProfile by remember { mutableStateOf<UserProfile?>(null) }
    DisposableEffect(currentUserId) {
        if (currentUserId.isNotEmpty()) {
            val docRef = FirebaseFirestore.getInstance().collection("users").document(currentUserId)
            val listener = docRef.addSnapshotListener { snapshot, error ->
                if (snapshot != null && snapshot.exists()) {
                    currentUserProfile = UserProfile(
                        uid = snapshot.id,
                        displayName = snapshot.getString("displayName") ?: "",
                        email = snapshot.getString("email") ?: "",
                        photoUrl = snapshot.getString("photoUrl"),
                        bio = snapshot.getString("bio"),
                        isOnline = snapshot.getBoolean("isOnline") ?: false
                    )
                }
            }
            onDispose {
                listener.remove()
            }
        } else {
            onDispose {}
        }
    }

    val currentUserName = currentUserProfile?.displayName ?: currentUser?.displayName ?: "Me"
    val currentUserPhoto = currentUserProfile?.photoUrl ?: currentUser?.photoUrl?.toString() ?: ""

    val homeViewModel: HomeViewModel = viewModel(
        key = currentUserId,
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(currentUserId = currentUserId, database = database) as T
            }
        }
    )

    val conversations by homeViewModel.conversations.collectAsState()
    
    // Only show conversations that actually have a message sent
    val activeConversations = conversations.filter { val lm = it.lastMessage; lm != null && lm.text.isNotEmpty() }
    
    val profiles by homeViewModel.userProfiles.collectAsState()
    val searchResults by homeViewModel.searchResults.collectAsState()
    val isSearching by homeViewModel.isSearching.collectAsState()

    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    var selectedConversationIds by remember { mutableStateOf(setOf<String>()) }
    var isInSelectionMode by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var conversationToDelete by remember { mutableStateOf<String?>(null) }
    var showLongPressMenu by remember { mutableStateOf(false) }
    var longPressedConversation by remember { mutableStateOf<Conversation?>(null) }

    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            if (isInSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedConversationIds.size} selected",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectedConversationIds = emptySet()
                            isInSelectionMode = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Selection",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        val anyUnpinned = activeConversations
                            .filter { selectedConversationIds.contains(it.id) }
                            .any { !it.isPinned }

                        IconButton(onClick = {
                            selectedConversationIds.forEach { id ->
                                homeViewModel.pinConversation(id, anyUnpinned)
                            }
                            selectedConversationIds = emptySet()
                            isInSelectionMode = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = if (anyUnpinned) "Pin" else "Unpin",
                                tint = if (anyUnpinned) Color.White else Color(0xFFFFD700)
                            )
                        }

                        IconButton(onClick = {
                            showDeleteConfirmDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = Color.Red
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1E1E2E)
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Profile photo avatar
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        androidx.compose.ui.graphics.Brush.linearGradient(
                                            listOf(Color(0xFF6C63FF), Color(0xFF3F3D56))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentUserPhoto.isNotEmpty()) {
                                    AsyncImage(
                                        model = currentUserPhoto,
                                        contentDescription = "Profile Photo",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        text = (currentUserName.firstOrNull()?.uppercaseChar() ?: "?").toString(),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "SyncChat",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = currentUserName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier
                                    .background(Color(0xFF1A1A2E))
                                    .widthIn(min = 160.dp)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Profile",
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Person, null, tint = Color(0xFF6C63FF))
                                    },
                                    onClick = {
                                        showMenu = false
                                        onProfileClick()
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = Color.White,
                                        leadingIconColor = Color(0xFF6C63FF)
                                    )
                                )
                                HorizontalDivider(color = Color(0xFF2E2E3E), thickness = 0.5.dp)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Logout",
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.ExitToApp, null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = {
                                        showMenu = false
                                        onSignOut()
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.error,
                                        leadingIconColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0F0F1A)
                    )
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    searchQuery = ""
                    homeViewModel.searchUsers("")
                    showSearchDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Start New Chat",
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        containerColor = Color(0xFF0F0F1A)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (activeConversations.isEmpty()) {
                // Empty state view
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the + button to start chatting with someone!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(activeConversations) { conversation ->
                        val otherUid = conversation.participantUids.firstOrNull { it != currentUserId }
                        val otherProfile = profiles[otherUid] ?: UserProfile(
                            uid = otherUid ?: "",
                            displayName = "Loading...",
                            email = ""
                        )

                        val isSelected = selectedConversationIds.contains(conversation.id)
                        ConversationItem(
                            conversation = conversation,
                            otherProfile = otherProfile,
                            currentUserId = currentUserId,
                            isSelected = isSelected,
                            isInSelectionMode = isInSelectionMode,
                            onClick = {
                                if (isInSelectionMode) {
                                    val nextSelected = selectedConversationIds.toMutableSet()
                                    if (nextSelected.contains(conversation.id)) {
                                        nextSelected.remove(conversation.id)
                                    } else {
                                        nextSelected.add(conversation.id)
                                    }
                                    selectedConversationIds = nextSelected
                                    if (nextSelected.isEmpty()) {
                                        isInSelectionMode = false
                                    }
                                } else {
                                    onConversationClick(conversation.id, otherProfile)
                                }
                            },
                            onLongClick = {
                                if (!isInSelectionMode) {
                                    longPressedConversation = conversation
                                    showLongPressMenu = true
                                }
                            }
                        )
                    }
                }
            }

            // Start New Chat Dialog / Bottom Sheet
            if (showSearchDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showSearchDialog = false
                        searchQuery = ""
                        homeViewModel.searchUsers("")
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = {
                            showSearchDialog = false
                            searchQuery = ""
                            homeViewModel.searchUsers("")
                        }) {
                            Text("Close", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    title = {
                        Text(
                            text = "New Conversation",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    homeViewModel.searchUsers(it)
                                },
                                placeholder = { Text("Search by name...") },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = {
                                            searchQuery = ""
                                            homeViewModel.searchUsers("")
                                        }) {
                                            Icon(imageVector = Icons.Default.Close, contentDescription = null)
                                        }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    keyboardController?.hide()
                                }),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color.DarkGray,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            if (searchQuery.isNotEmpty() && !isSearching && searchResults.isNotEmpty()) {
                                Text(
                                    text = "Found ${searchResults.size} user(s)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }

                            if (isSearching) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            } else if (searchResults.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No users found",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(searchResults) { user ->
                                        UserSearchResultItem(
                                            user = user,
                                            onClick = {
                                                homeViewModel.startConversation(user.uid) { conversationId ->
                                                    showSearchDialog = false
                                                    searchQuery = ""
                                                    homeViewModel.searchUsers("")
                                                    onConversationClick(conversationId, user)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    containerColor = Color(0xFF1E1E2C),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }

    // Long Press Actions Overlay
    if (showLongPressMenu && longPressedConversation != null) {
        val conv = longPressedConversation!!
        val otherUid = conv.participantUids.firstOrNull { it != currentUserId }
        val otherProfile = profiles[otherUid]
        val displayName = otherProfile?.displayName ?: "Chat"
        
        AlertDialog(
            onDismissRequest = { showLongPressMenu = false },
            containerColor = Color(0xFF1E1E2E),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = { Text(text = displayName, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showLongPressMenu = false
                                homeViewModel.pinConversation(conv.id, !conv.isPinned)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Pin",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(if (conv.isPinned) "Unpin Chat" else "Pin Chat", color = Color.White)
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showLongPressMenu = false
                                isInSelectionMode = true
                                selectedConversationIds = setOf(conv.id)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Select",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Select Chat", color = Color.White)
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showLongPressMenu = false
                                conversationToDelete = conv.id
                                showDeleteConfirmDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.Red
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Delete Chat", color = Color.Red)
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = Color(0xFF1E1E2E),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = { Text("Delete Chat") },
            text = { Text("Are you sure you want to delete the selected chat(s)? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        if (isInSelectionMode) {
                            selectedConversationIds.forEach { id ->
                                homeViewModel.deleteConversation(id)
                            }
                            selectedConversationIds = emptySet()
                            isInSelectionMode = false
                        } else {
                            conversationToDelete?.let { id ->
                                homeViewModel.deleteConversation(id)
                            }
                        }
                        showDeleteConfirmDialog = false
                        conversationToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("OK", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    conversationToDelete = null
                }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun ConversationItem(
    conversation: Conversation,
    otherProfile: UserProfile,
    currentUserId: String,
    isSelected: Boolean = false,
    isInSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val backgroundColor = when {
        isSelected -> Color(0xFF2E2E4E)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Initials Avatar with Selection Badge check
        val initials = if (otherProfile.displayName.isNotEmpty()) {
            otherProfile.displayName.split(" ")
                .mapNotNull { it.firstOrNull() }
                .take(2)
                .joinToString("")
                .uppercase()
        } else "?"

        Box {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            Brush.linearGradient(colors = listOf(Color(0xFF4CAF50), Color(0xFF2E7D32)))
                        } else {
                            Brush.linearGradient(colors = listOf(Color(0xFF6C63FF), Color(0xFF3F3D56)))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (!otherProfile.photoUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = otherProfile.photoUrl,
                        contentDescription = "Profile Photo",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = initials,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }
            if (otherProfile.isOnline && !isSelected) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                        .align(Alignment.BottomEnd)
                        .border(1.5.dp, Color(0xFF0F0F1A), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = otherProfile.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Pinned Indicator & Timestamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Pinned",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(16.dp).padding(end = 4.dp)
                        )
                    }
                    
                    val timeStr = conversation.lastMessage?.timestamp?.let {
                        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
                        format.format(it)
                    } ?: ""

                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Last message body
            val lastMsgText = run {
                val lm = conversation.lastMessage
                when {
                    lm == null -> "No messages yet"
                    lm.senderId == currentUserId -> "You: ${lm.text}"
                    else -> lm.text
                }
            }

            Text(
                text = lastMsgText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun UserSearchResultItem(
    user: UserProfile,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(Color(0xFF2E2E3E))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val initials = if (user.displayName.isNotEmpty()) {
            user.displayName.split(" ")
                .mapNotNull { it.firstOrNull() }
                .take(2)
                .joinToString("")
                .uppercase()
        } else "?"

        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6C63FF)),
                contentAlignment = Alignment.Center
            ) {
                if (!user.photoUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = user.photoUrl,
                        contentDescription = "Profile Photo",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = initials,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
            if (user.isOnline) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                        .align(Alignment.BottomEnd)
                        .border(1.5.dp, Color(0xFF2E2E3E), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = user.displayName,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 15.sp
            )
            Text(
                text = user.email,
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}
