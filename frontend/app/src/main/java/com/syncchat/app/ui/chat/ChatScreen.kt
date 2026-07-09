package com.syncchat.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.syncchat.app.data.model.Message
import com.syncchat.app.data.model.UserProfile
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    otherUser: UserProfile,
    onBackClick: () -> Unit
) {
    // Set active conversation ID for FCM foreground check
    DisposableEffect(conversationId) {
        com.syncchat.app.SyncChatMessagingService.activeChatConversationId = conversationId
        onDispose {
            if (com.syncchat.app.SyncChatMessagingService.activeChatConversationId == conversationId) {
                com.syncchat.app.SyncChatMessagingService.activeChatConversationId = null
            }
        }
    }

    val context = LocalContext.current
    val currentUserId = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    val database = remember { com.syncchat.app.data.local.AppDatabase.getDatabase(context) }

    // Inject ChatViewModel with conversationId, currentUserId, recipientUserId, initialRecipient and Room Database
    val chatViewModel: ChatViewModel = viewModel(
        key = "${conversationId}_${currentUserId}",
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(
                    conversationId = conversationId,
                    currentUserId = currentUserId,
                    recipientUserId = otherUser.uid,
                    initialRecipient = otherUser,
                    database = database,
                    context = context.applicationContext
                ) as T
            }
        }
    )

    val messages by chatViewModel.messages.collectAsState()
    val conversation by chatViewModel.conversation.collectAsState()
    val isSending by chatViewModel.isSending.collectAsState()
    val uploadProgress by chatViewModel.uploadProgress.collectAsState()
    val typingUsers by chatViewModel.typingUsers.collectAsState()
    val errorMessage by chatViewModel.errorMessage.collectAsState()
    val liveOtherUser by chatViewModel.recipientUser.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showPinConfirmDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }

    var textInput by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Launch image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            chatViewModel.sendImage(context, uri)
        }
    }

    val listState = rememberLazyListState()

    var isRecording by remember { mutableStateOf(false) }
    val audioRecorder = remember { AudioRecorderHelper(context) }
    var voiceFile by remember { mutableStateOf<java.io.File?>(null) }
    
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceFile = audioRecorder.startRecording()
            if (voiceFile != null) {
                isRecording = true
            }
        } else {
            android.widget.Toast.makeText(context, "Microphone permission required for voice messages", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size, typingUsers.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size + (if (typingUsers.isNotEmpty()) 1 else 0))
        }
    }

    // Typing debounce and idle timeout
    LaunchedEffect(textInput) {
        if (textInput.isNotEmpty()) {
            kotlinx.coroutines.delay(300) // Debounce before starting
            chatViewModel.startTyping()
            kotlinx.coroutines.delay(2000) // Idle timeout before stopping
            chatViewModel.stopTyping()
        } else {
            chatViewModel.stopTyping()
        }
    }

    // Read receipts
    LaunchedEffect(messages) {
        messages.filter { it.senderId != currentUserId && !it.readBy.contains(currentUserId) }
            .forEach { chatViewModel.markAsRead(it.id) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            chatViewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // User Avatar
                        val initials = if (liveOtherUser.displayName.isNotEmpty()) {
                            liveOtherUser.displayName.split(" ")
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
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(Color(0xFF6C63FF), Color(0xFF3F3D56))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!liveOtherUser.photoUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = liveOtherUser.photoUrl,
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
                            if (liveOtherUser.isOnline) {
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                        .align(Alignment.BottomEnd)
                                        .border(1.5.dp, Color(0xFF0F0F1A), CircleShape)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = liveOtherUser.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (liveOtherUser.isOnline) "Active now" else "Offline",
                                fontSize = 11.sp,
                                color = if (liveOtherUser.isOnline) Color(0xFF4CAF50) else Color.Gray,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
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
                            modifier = Modifier.background(Color(0xFF1E1E2E))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Profile Info", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    showProfileDialog = true
                                }
                            )
                            val isPinned = conversation?.isPinned == true
                            DropdownMenuItem(
                                text = { Text(if (isPinned) "Unpin Chat" else "Pin Chat", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    showPinConfirmDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Chat", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    showClearConfirmDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Chat", color = Color.Red) },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirmDialog = true
                                }
                            )
                            val isBlocked = conversation?.isBlocked == true
                            DropdownMenuItem(
                                text = { Text(if (isBlocked) "Unblock User" else "Block User", color = Color.Red) },
                                onClick = {
                                    showMenu = false
                                    showBlockConfirmDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F0F1A)
                )
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val connectionStatus by com.syncchat.app.data.signalr.SignalRManager.getInstance().connectionStatus.collectAsState()
            
            // Connection Banner
            AnimatedVisibility(
                visible = connectionStatus == com.syncchat.app.data.signalr.ConnectionStatus.Failed || 
                          connectionStatus == com.syncchat.app.data.signalr.ConnectionStatus.Reconnecting,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (connectionStatus == com.syncchat.app.data.signalr.ConnectionStatus.Failed) Color.Red else Color(0xFFFFA500))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (connectionStatus == com.syncchat.app.data.signalr.ConnectionStatus.Failed) 
                                "Connection lost permanently. Restart app." 
                               else "Reconnecting...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }

            // Upload Progress Banner
            AnimatedVisibility(
                visible = uploadProgress != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = uploadProgress ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Message Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No messages. Wave hello! 👋",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages) { message ->
                            val isMe = message.senderId == currentUserId
                            MessageBubble(message = message, isMe = isMe)
                        }
                        
                        if (typingUsers.isNotEmpty()) {
                            item {
                                TypingIndicatorBubble(name = otherUser.displayName)
                            }
                        }
                    }
                }
            }

            // Bottom Input Bar or Block Banner
            val isBlocked = conversation?.isBlocked == true
            val isBlockedByOther = conversation?.isBlockedByOther == true

            if (isBlocked || isBlockedByOther) {
                Surface(
                    color = Color(0xFF1E1E2C),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBlocked) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "You blocked this user. ",
                                    color = Color.LightGray,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Unblock",
                                    color = Color(0xFF6C63FF),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        chatViewModel.blockUser(false)
                                    }
                                )
                            }
                        } else {
                            Text(
                                text = "This user has blocked you.",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = Color(0xFF1E1E2C),
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 4.dp
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRecording) {
                        // STATE 1: RECORDING
                        val transition = rememberInfiniteTransition(label = "pulse")
                        val alpha by transition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "alpha"
                        )
                        Box(
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color.Red.copy(alpha = alpha))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Recording audio...", color = Color.White, modifier = Modifier.weight(1f))
                        
                        IconButton(
                            onClick = {
                                isRecording = false
                                audioRecorder.stopRecording()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Red, CircleShape)
                        ) {
                            Icon(Icons.Default.Clear, "Stop Recording", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    } else if (voiceFile != null) {
                        // STATE 2: RECORDED, WAITING TO SEND
                        IconButton(
                            onClick = {
                                audioRecorder.cancelRecording()
                                voiceFile = null
                            }
                        ) {
                            Icon(Icons.Default.Delete, "Discard", tint = Color.Red)
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF0F0F1A))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Mic, "Audio", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Voice message ready", color = Color.White)
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        IconButton(
                            onClick = {
                                voiceFile?.let { 
                                    chatViewModel.sendVoiceMessage(it)
                                    voiceFile = null
                                }
                            },
                            enabled = !isSending,
                            modifier = Modifier
                                .size(44.dp)
                                .background(if (!isSending) MaterialTheme.colorScheme.primary else Color.DarkGray, CircleShape)
                        ) {
                            Icon(Icons.Default.PlayArrow, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        // STATE 3: NORMAL
                        // Attachment button
                        IconButton(
                            onClick = { imagePickerLauncher.launch("*/*") },
                            enabled = !isSending
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Send File",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Text Input
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = { Text("Type a message...") },
                            maxLines = 4,
                            singleLine = false,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (textInput.isNotBlank()) {
                                        chatViewModel.sendMessage(textInput)
                                        textInput = ""
                                    }
                                }
                            ),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.DarkGray,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                unfocusedContainerColor = Color(0xFF0F0F1A),
                                focusedContainerColor = Color(0xFF0F0F1A)
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Send / Record Button
                        val isTextEmpty = textInput.isBlank()
                        
                        IconButton(
                            onClick = {
                                if (!isTextEmpty) {
                                    chatViewModel.sendMessage(textInput)
                                    textInput = ""
                                    keyboardController?.hide()
                                } else {
                                    // Check permission and start recording
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        voiceFile = audioRecorder.startRecording()
                                        if (voiceFile != null) {
                                            isRecording = true
                                        }
                                    } else {
                                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            enabled = !isSending,
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    color = if (!isTextEmpty) MaterialTheme.colorScheme.primary else Color(0xFF2E2E3E),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (!isTextEmpty) Icons.Default.PlayArrow else Icons.Default.Mic,
                                contentDescription = if (!isTextEmpty) "Send" else "Record Voice",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            }
        }
    }

    // --- Action Dialogs ---
    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            containerColor = Color(0xFF1E1E2E),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = { Text(text = "Profile Info", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val initials = if (liveOtherUser.displayName.isNotEmpty()) {
                        liveOtherUser.displayName.split(" ")
                            .mapNotNull { it.firstOrNull() }
                            .take(2)
                            .joinToString("")
                            .uppercase()
                    } else "?"
                    
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF6C63FF), Color(0xFF3F3D56))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!liveOtherUser.photoUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = liveOtherUser.photoUrl,
                                contentDescription = "Profile Photo",
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = initials,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                       text = liveOtherUser.displayName.ifEmpty { "No Name" },
                       color = Color.White,
                       fontWeight = FontWeight.Bold,
                       fontSize = 20.sp
                    )
                    
                    Text(
                       text = liveOtherUser.email.ifEmpty { "No Email" },
                       color = Color.Gray,
                       fontSize = 14.sp
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                       text = liveOtherUser.bio?.ifEmpty { "No Bio added" } ?: "No Bio added",
                       color = Color(0xFFB0AFFF),
                       fontSize = 15.sp,
                       textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                       modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfileDialog = false }) {
                    Text("Close", color = Color(0xFF6C63FF))
                }
            }
        )
    }

    if (showPinConfirmDialog) {
        val pin = conversation?.isPinned == false
        AlertDialog(
            onDismissRequest = { showPinConfirmDialog = false },
            containerColor = Color(0xFF1E1E2E),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = { Text(text = if (pin) "Pin Chat" else "Unpin Chat") },
            text = { Text(text = if (pin) "Are you sure you want to pin this chat?" else "Are you sure you want to unpin this chat?") },
            confirmButton = {
                Button(
                    onClick = {
                        chatViewModel.pinConversation(pin)
                        showPinConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF))
                ) {
                    Text("OK", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinConfirmDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = Color(0xFF1E1E2E),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = { Text(text = "Clear Chat") },
            text = { Text(text = "Are you sure you want to clear all messages in this chat?") },
            confirmButton = {
                Button(
                    onClick = {
                        chatViewModel.clearChat()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("OK", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = Color(0xFF1E1E2E),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = { Text(text = "Delete Chat") },
            text = { Text(text = "Are you sure you want to delete this chat? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        chatViewModel.deleteConversation()
                        showDeleteConfirmDialog = false
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("OK", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showBlockConfirmDialog) {
        val block = conversation?.isBlocked == false
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            containerColor = Color(0xFF1E1E2E),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = { Text(text = if (block) "Block User" else "Unblock User") },
            text = { Text(text = if (block) "Are you sure you want to block this user?" else "Are you sure you want to unblock this user?") },
            confirmButton = {
                Button(
                    onClick = {
                        chatViewModel.blockUser(block)
                        showBlockConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("OK", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun MessageBubble(message: Message, isMe: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        val bubbleColor = if (isMe) Color(0xFF6C63FF) else Color(0xFF2E2E3E)
        val bubbleShape = if (isMe) {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp)
        }

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                // If contains media
                if (!message.mediaUrl.isNullOrEmpty()) {
                    if (message.text == "[Image]") {
                        var showFullScreenImage by remember { mutableStateOf(false) }
                        
                        AsyncImage(
                            model = message.mediaUrl,
                            contentDescription = "Shared Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showFullScreenImage = true },
                            contentScale = ContentScale.Crop
                        )
                        
                        if (showFullScreenImage) {
                            androidx.compose.ui.window.Dialog(
                                onDismissRequest = { showFullScreenImage = false },
                                properties = androidx.compose.ui.window.DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                        .clickable { showFullScreenImage = false },
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = message.mediaUrl,
                                        contentDescription = "Full Screen Image",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else if (message.text == "[Voice Message]") {
                        InlineAudioPlayer(url = message.mediaUrl ?: "")
                    } else {
                        // Render file/video attachment card
                        val isVideo = message.text == "[Video]"
                        val iconEmoji = if (isVideo) "🎥" else "📄"
                        val titleText = if (isVideo) "Video Attachment" else {
                            message.text.substringAfter("[File] ").trim().ifEmpty { "File Attachment" }
                        }
                        val context = LocalContext.current
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1E2E))
                                .clickable {
                                    try {
                                        val url = message.mediaUrl ?: ""
                                        val safeUrl = if (url.startsWith("http://")) {
                                            url.replace("http://", "https://")
                                        } else {
                                            url
                                        }
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(safeUrl)
                                        )
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        try {
                                            context.startActivity(intent)
                                        } catch (ex: Exception) {
                                            // Fallback: force open in web browser
                                            val webIntent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(safeUrl)
                                            ).apply {
                                                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(webIntent)
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "No browser or viewer app found to open this link", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Text(text = iconEmoji, fontSize = 28.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = titleText,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = if (isVideo) "Tap to play" else "Tap to open",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                val isMediaMessage = !message.mediaUrl.isNullOrEmpty()
                if (message.text.isNotEmpty() && (!isMediaMessage || (!message.text.startsWith("[Image]") && !message.text.startsWith("[Video]") && !message.text.startsWith("[File]") && !message.text.startsWith("[Voice Message]")))) {
                    Text(
                        text = message.text,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Time and Read Receipts
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        val timeStr = format.format(message.timestamp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeStr,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            
            if (isMe) {
                Spacer(modifier = Modifier.width(4.dp))
                when (message.status) {
                    "PENDING" -> Text("⌛", fontSize = 12.sp)
                    "FAILED" -> Text("⚠️", fontSize = 12.sp, color = Color.Red)
                    else -> {
                        val isRead = message.readBy.any { it != message.senderId }
                        val checkColor = if (isRead) Color(0xFF00FF88) else Color.Gray
                        Text(
                            text = if (isRead) "✓✓" else "✓",
                            style = MaterialTheme.typography.bodySmall,
                            color = checkColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicatorBubble(name: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "$name is typing...",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2E2E3E))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val transition = rememberInfiniteTransition(label = "typing")
                for (i in 0 until 3) {
                    val offsetY by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = -8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(300, delayMillis = i * 100),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot"
                    )
                    Box(
                        modifier = Modifier
                            .offset(y = offsetY.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
fun InlineAudioPlayer(url: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    
    val mediaPlayer = remember { android.media.MediaPlayer() }

    DisposableEffect(url) {
        try {
            mediaPlayer.setDataSource(url)
            mediaPlayer.prepareAsync()
            mediaPlayer.setOnPreparedListener {
                isPrepared = true
            }
            mediaPlayer.setOnCompletionListener {
                isPlaying = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        onDispose {
            try {
                mediaPlayer.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E2E))
            .padding(12.dp)
    ) {
        IconButton(
            onClick = {
                if (isPrepared) {
                    if (isPlaying) {
                        mediaPlayer.pause()
                        isPlaying = false
                    } else {
                        mediaPlayer.start()
                        isPlaying = true
                    }
                }
            },
            enabled = isPrepared,
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Voice Message",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (!isPrepared) "Loading audio..." else if (isPlaying) "Playing..." else "Ready to play",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}
