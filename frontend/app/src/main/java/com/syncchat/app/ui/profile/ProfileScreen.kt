package com.syncchat.app.ui.profile

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.syncchat.app.data.api.RetrofitApiRepository
import com.syncchat.app.auth.FirebaseAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val user = auth.currentUser
    val scope = rememberCoroutineScope()

    // Editable state
    var displayName by remember { mutableStateOf(user?.displayName ?: "") }
    var bio by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPasswordSection by remember { mutableStateOf(false) }
    var showCurrentPassword by remember { mutableStateOf(false) }
    var showNewPassword by remember { mutableStateOf(false) }

    var photoUrl by remember { mutableStateOf(user?.photoUrl?.toString() ?: "") }
    var selectedPhotoUri by remember { mutableStateOf<Uri?>(null) }

    var isUploadingPhoto by remember { mutableStateOf(false) }
    var isSavingProfile by remember { mutableStateOf(false) }
    var isSavingPassword by remember { mutableStateOf(false) }

    // Load bio from backend
    LaunchedEffect(user?.uid) {
        user?.uid?.let { uid ->
            try {
                val idToken = FirebaseAuthRepository().getIdToken()
                if (idToken != null) {
                    val profile = RetrofitApiRepository().getUserProfile(token = idToken, uid = uid)
                    bio = profile?.bio ?: ""
                    if (profile?.photoUrl?.isNotEmpty() == true) {
                        photoUrl = profile.photoUrl ?: ""
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedPhotoUri = uri
            isUploadingPhoto = true
            // Upload to Cloudinary
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.readBytes()
                    } ?: return@launch
                    val client = OkHttpClient()
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                        .addFormDataPart("upload_preset", "syncchat_preset")
                        .build()
                    val request = Request.Builder()
                        .url("https://api.cloudinary.com/v1_1/ddfougzkl/auto/upload")
                        .post(requestBody)
                        .build()
                    val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        val url = JSONObject(body).getString("secure_url")
                        photoUrl = url
                    } else {
                        selectedPhotoUri = null
                        Toast.makeText(context, "Photo upload failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    selectedPhotoUri = null
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isUploadingPhoto = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("My Profile", fontWeight = FontWeight.Bold, color = Color.White)
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1A))
            )
        },
        containerColor = Color(0xFF0F0F1A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- Profile Photo ---
            Box(
                contentAlignment = Alignment.BottomEnd,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF6C63FF), Color(0xFF3F3D56))))
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { photoPickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (isUploadingPhoto) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    } else if (selectedPhotoUri != null) {
                        AsyncImage(
                            model = selectedPhotoUri,
                            contentDescription = "Profile Photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (photoUrl.isNotEmpty()) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = "Profile Photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = (displayName.firstOrNull()?.uppercaseChar() ?: "?").toString(),
                            color = Color.White,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CameraAlt, "Change Photo", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            // Display Name (Username)
            Text(
                text = displayName.ifEmpty { "No name set" },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Email
            Text(
                text = user?.email ?: "",
                color = Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Bio
            if (bio.isNotEmpty()) {
                Text(
                    text = bio,
                    color = Color(0xFFB0AFFF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(bottom = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // --- Profile Info Card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Profile Info", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                    ProfileTextField(
                        label = "Display Name",
                        value = displayName,
                        onValueChange = { displayName = it },
                        leadingIcon = { Icon(Icons.Default.Person, null, tint = Color.Gray) }
                    )

                    ProfileTextField(
                        label = "Bio",
                        value = bio,
                        onValueChange = { bio = it },
                        maxLines = 3,
                        placeholder = "Write something about yourself..."
                    )

                    Button(
                        onClick = {
                            isSavingProfile = true
                            scope.launch {
                                try {
                                    // Update Firebase display name
                                    val profileUpdates = UserProfileChangeRequest.Builder()
                                        .setDisplayName(displayName)
                                        .apply { if (photoUrl.isNotEmpty()) setPhotoUri(Uri.parse(photoUrl)) }
                                        .build()
                                    user?.updateProfile(profileUpdates)?.await()
                                    user?.reload()?.await() // Refresh cached user object

                                    // Update backend
                                    val idToken = FirebaseAuthRepository().getIdToken()
                                    if (idToken != null) {
                                        RetrofitApiRepository().updateUserProfile(
                                            idToken = idToken,
                                            displayName = displayName,
                                            bio = bio,
                                            photoUrl = photoUrl
                                        )
                                    }
                                    Toast.makeText(context, "Profile updated!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isSavingProfile = false
                                }
                            }
                        },
                        enabled = !isSavingProfile,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSavingProfile) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Profile")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Password Card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPasswordSection = !showPasswordSection },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Change Password", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Text(if (showPasswordSection) "▲" else "▼", color = Color.Gray)
                    }

                    AnimatedVisibility(visible = showPasswordSection) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileTextField(
                                label = "Current Password",
                                value = currentPassword,
                                onValueChange = { currentPassword = it },
                                isPassword = true,
                                showPassword = showCurrentPassword,
                                onTogglePassword = { showCurrentPassword = !showCurrentPassword }
                            )
                            ProfileTextField(
                                label = "New Password",
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                isPassword = true,
                                showPassword = showNewPassword,
                                onTogglePassword = { showNewPassword = !showNewPassword }
                            )
                            ProfileTextField(
                                label = "Confirm New Password",
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                isPassword = true,
                                showPassword = showNewPassword,
                                onTogglePassword = { showNewPassword = !showNewPassword }
                            )

                            Button(
                                onClick = {
                                    if (newPassword != confirmPassword) {
                                        Toast.makeText(context, "Passwords do not match", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (newPassword.length < 6) {
                                        Toast.makeText(context, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    isSavingPassword = true
                                    scope.launch {
                                        try {
                                            val email = user?.email ?: ""
                                            val credential = EmailAuthProvider.getCredential(email, currentPassword)
                                            user?.reauthenticate(credential)?.await()
                                            user?.updatePassword(newPassword)?.await()
                                            currentPassword = ""
                                            newPassword = ""
                                            confirmPassword = ""
                                            showPasswordSection = false
                                            Toast.makeText(context, "Password updated!", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isSavingPassword = false
                                        }
                                    }
                                },
                                enabled = !isSavingPassword,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                            ) {
                                if (isSavingPassword) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                                } else {
                                    Text("Update Password")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProfileTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    maxLines: Int = 1,
    placeholder: String = "",
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.Gray) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = Color.DarkGray) },
        maxLines = maxLines,
        singleLine = maxLines == 1,
        visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword && onTogglePassword != null) {
            { TextButton(onClick = onTogglePassword) { Text(if (showPassword) "Hide" else "Show", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) } }
        } else null,
        leadingIcon = leadingIcon,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color(0xFF2E2E3E),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = Color(0xFF0F0F1A),
            unfocusedContainerColor = Color(0xFF0F0F1A)
        )
    )
}
