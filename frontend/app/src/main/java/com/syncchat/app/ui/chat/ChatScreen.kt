package com.syncchat.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
                            Text(
                                text = "Active Now",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF00FF88) // Glowing green active indicator
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
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment button
                    IconButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        enabled = !isSending
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Send Image",
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

                    // Send Button
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                chatViewModel.sendMessage(textInput)
                                textInput = ""
                                keyboardController?.hide()
                            }
                        },
                        enabled = !isSending && textInput.isNotBlank(),
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = if (textInput.isNotBlank() && !isSending) MaterialTheme.colorScheme.primary else Color.DarkGray,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
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
                // If contains image
                if (!message.mediaUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = message.mediaUrl,
                        contentDescription = "Shared Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (message.text.isNotEmpty() && message.text != "[Image]") {
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
