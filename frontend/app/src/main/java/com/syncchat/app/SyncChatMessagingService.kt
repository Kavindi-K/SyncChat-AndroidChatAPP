package com.syncchat.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.syncchat.app.auth.FirebaseAuthRepository
import com.syncchat.app.data.api.RetrofitApiRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SyncChatMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authRepository = FirebaseAuthRepository()
    private val apiRepository = RetrofitApiRepository()

    companion object {
        const val CHANNEL_ID = "syncchat_messages"
        const val CHANNEL_NAME = "Messages"

        /**
         * The conversationId currently open on screen — set by ChatScreen, cleared on back.
         * The service reads this to skip showing a notification when the user is already viewing the chat.
         */
        @Volatile
        var activeChatConversationId: String? = null
    }

    // ── Token lifecycle ──────────────────────────────────────────────────────

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New FCM token: $token")
        registerTokenWithBackend(token)
    }

    private fun registerTokenWithBackend(token: String) {
        serviceScope.launch {
            try {
                val idToken = authRepository.getIdToken() ?: return@launch
                apiRepository.registerFcmToken(idToken, token)
                Log.d("FCM", "Token registered with backend")
            } catch (e: Exception) {
                Log.w("FCM", "Token registration failed: ${e.message}")
            }
        }
    }

    // ── Incoming messages ────────────────────────────────────────────────────

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i("FCM", "onMessageReceived called. Data: ${remoteMessage.data}, Notification: ${remoteMessage.notification != null}")

        val conversationId = remoteMessage.data["conversationId"] ?: return
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["senderName"]
            ?: "SyncChat"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["preview"]
            ?: "New message"

        Log.i("FCM", "activeChatConversationId: $activeChatConversationId, incoming conversationId: $conversationId")

        // If the user is already viewing this conversation, skip the system tray notification
        if (activeChatConversationId == conversationId) {
            Log.i("FCM", "User is viewing conversation $conversationId — skipping notification")
            return
        }

        Log.i("FCM", "Showing notification: $title - $body")
        showNotification(title, body, conversationId)
    }

    // ── Notification display ─────────────────────────────────────────────────

    private fun showNotification(title: String, body: String, conversationId: String) {
        ensureChannelExists()

        // Deep-link intent: opens MainActivity with the target conversationId attached
        val deepLinkIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this)
                .notify(conversationId.hashCode(), notification)
        }
    }

    private fun ensureChannelExists() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SyncChat message notifications"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
