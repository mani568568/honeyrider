package com.ss.honeyrider.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ss.honeyrider.RetrofitClient
import com.ss.honeyrider.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RiderFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "🔄 New FCM Token generated: $token")

        // 1. Save locally
        SessionManager.saveFcmToken(applicationContext, token)

        // 2. Check if user is logged in
        val authToken = SessionManager.getAuthToken(applicationContext)
        if (!authToken.isNullOrEmpty()) {
            // 3. Sync with server immediately
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val api = RetrofitClient.getInstance(applicationContext)
                    val response = api.updateFcmToken(mapOf("token" to token))
                    if (response.isSuccessful) {
                        Log.d("FCM", "✅ Token updated on server")
                    } else {
                        Log.e("FCM", "❌ Failed to update token on server: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e("FCM", "❌ Network error updating token", e)
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