package com.ss.honeyrider.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import okhttp3.*
import com.ss.honeyrider.util.SessionManager
import android.util.Log

class RiderSocketService : Service() {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectToSocket()
        return START_STICKY
    }

    private fun connectToSocket() {
        val token = SessionManager.getToken(this)
        val riderId = SessionManager.getRiderId(this)

        if (token == null || riderId == -1L) return

        // Assuming backend endpoint: ws://SERVER_IP:8080/ws/rider?riderId=123
        val request = Request.Builder()
            .url("ws://192.168.1.5:8080/ws/rider?riderId=$riderId")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("Socket", "Connected!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("Socket", "Message: $text")
                if (text.contains("NEW_ORDER")) {
                    // Trigger a local broadcast or Notification here
                    // sendBroadcast(Intent("com.ss.honeyrider.NEW_ORDER"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Socket", "Error: ${t.message}")
            }
        })
    }

    override fun onDestroy() {
        webSocket?.close(1000, "Service Destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}