package com.syncchat.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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

    // Inject ChatViewModel with conversationId, currentUserId and Room Database
    val chatViewModel: ChatViewModel = viewModel(
        key = "${conversationId}_${currentUserId}",
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(conversationId = conversationId, currentUserId = currentUserId, database = database, context = context.applicationContext) as T
            }
        }
    )

    val messages by chatViewModel.messages.collectAsState()
    val isSending by chatViewModel.isSending.collectAsState()
    val uploadProgress by chatViewModel.uploadProgress.collectAsState()
    val typingUsers by chatViewModel.typingUsers.collectAsState()
    val errorMessage by chatViewModel.errorMessage.collectAsState()

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
                        val initials = if (otherUser.displayName.isNotEmpty()) {
                            otherUser.displayName.split(" ")
                                .mapNotNull { it.firstOrNull() }
                                .take(2)
                                .joinToString("")
                                .uppercase()
                        } else "?"

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
                            Text(
                                text = initials,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = otherUser.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
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

            // Bottom Input Bar
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
                                        if (isVideo) {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                            context.startActivity(intent)
                                        } else {
                                            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                                            request.setTitle(titleText)
                                            request.setDescription("Downloading file from SyncChat")
                                            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, titleText)
                                            val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                                            downloadManager.enqueue(request)
                                            android.widget.Toast.makeText(context, "Downloading $titleText...", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Cannot handle file", android.widget.Toast.LENGTH_SHORT).show()
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
                                    text = if (isVideo) "Click to play" else "Click to view",
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
