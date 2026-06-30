package com.syncchat.app.data.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.syncchat.app.auth.FirebaseAuthRepository
import com.syncchat.app.data.api.RetrofitApiRepository
import com.syncchat.app.data.local.AppDatabase
import com.syncchat.app.data.model.MessageStatus

/**
 * WorkManager worker that syncs any PENDING messages to the backend
 * when network connectivity is restored.
 *
 * Scheduled with a CONNECTED network constraint so it only runs
 * when the device has an active internet connection.
 */
class MessageSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "MessageSyncWorker"
        const val UNIQUE_WORK_NAME = "message_sync"
    }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val pendingMessages = db.messageDao().getPendingMessages()

        if (pendingMessages.isEmpty()) {
            Log.d(TAG, "No pending messages to sync")
            return Result.success()
        }

        Log.d(TAG, "Found ${pendingMessages.size} pending message(s) to sync")

        val authRepo = FirebaseAuthRepository()
        val apiRepo = RetrofitApiRepository()
        val token = authRepo.getIdToken()

        if (token == null) {
            Log.w(TAG, "No auth token available, will retry later")
            return Result.retry()
        }

        var allSucceeded = true

        for (msg in pendingMessages) {
            try {
                apiRepo.sendMessage(token, msg.conversationId, msg.text, msg.mediaUrl)
                db.messageDao().deleteMessageById(msg.id)
                Log.d(TAG, "Successfully synced message ${msg.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync message ${msg.id}", e)
                allSucceeded = false
                // Leave as PENDING — will be retried on next worker run
            }
        }

        return if (allSucceeded) Result.success() else Result.retry()
    }
}
