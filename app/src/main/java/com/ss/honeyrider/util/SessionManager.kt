package com.ss.honeyrider.util

import android.content.Context
import android.content.SharedPreferences

object SessionManager {
    private const val PREF_NAME = "HoneyRiderPrefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_RIDER_ID = "rider_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveSession(context: Context, token: String, riderId: Long) {
        val editor = getPrefs(context).edit()
        editor.putString(KEY_TOKEN, token)
        editor.putLong(KEY_RIDER_ID, riderId)
        editor.apply()
    }

    fun getToken(context: Context): String? = getPrefs(context).getString(KEY_TOKEN, null)
    fun getRiderId(context: Context): Long = getPrefs(context).getLong(KEY_RIDER_ID, -1)

    fun clearSession(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}