package com.ss.honeyrider.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ss.honeyrider.SessionManager // Import from root package
import com.ss.honeyrider.RetrofitClient // Import from root package
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RiderFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New Token: $token")

        // Save locally
        SessionManager.saveAuthToken(applicationContext, token) // Or save specific FCM token if needed

        // Send to server
        val authToken = SessionManager.getAuthToken(applicationContext)
        if (authToken != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val api = RetrofitClient.getInstance(applicationContext)
                    api.updateFcmToken(mapOf("token" to token))
                } catch (e: Exception) {
                    Log.e("FCM", "Failed to sync token", e)
                }
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        // Ensure you don't use old logic here.
        // The OrderSocketService handles "Active" notifications.
        // This service handles "Background" system tray notifications.
    }
}