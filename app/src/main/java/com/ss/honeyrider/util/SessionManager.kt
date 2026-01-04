package com.ss.honeyrider.util

import android.content.Context
import android.content.SharedPreferences

object SessionManager {
    private const val PREF_NAME = "HoneyRiderPrefs"
    private const val KEY_TOKEN = "auth_token"
    private const val PREFS_NAME = "RiderPrefs"
    private const val KEY_RIDER_ID = "rider_id"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val DEFAULT_RIDER_ID = -1L
    private const val KEY_FCM_TOKEN = "fcm_token"

    fun saveSession(context: Context, token: String, riderId: Long) {
        val editor = getPrefs(context).edit()
        editor.putString(KEY_TOKEN, token)
        editor.putLong(KEY_RIDER_ID, riderId)
        editor.apply()
    }

    fun getToken(context: Context): String? = getPrefs(context).getString(KEY_TOKEN, null)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveAuthToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getAuthToken(context: Context): String? {
        return getPrefs(context).getString(KEY_AUTH_TOKEN, null)
    }

    fun saveRiderId(context: Context, riderId: Long) {
        getPrefs(context).edit().putLong(KEY_RIDER_ID, riderId).apply()
    }
    fun saveFcmToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getFcmToken(context: Context): String? {
        return getPrefs(context).getString(KEY_FCM_TOKEN, null)
    }

    fun getRiderId(context: Context): Long {
        return getPrefs(context).getLong(KEY_RIDER_ID, DEFAULT_RIDER_ID)
    }

    fun clearSession(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}