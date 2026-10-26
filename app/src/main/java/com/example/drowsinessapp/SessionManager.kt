package com.example.drowsinessapp
import android.content.Context
object SessionManager {
    private const val PREFS_NAME = "DrowsinessPrefs"
    private const val KEY_HISTORY = "session_history"
    fun saveSession(context: Context, drowsyCount: Int, yawnCount: Int, duration: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentHistory = prefs.getString(KEY_HISTORY, "") ?: ""
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val newEntry = "Date: $date\nDuration: $duration\nDrowsy Alerts: $drowsyCount\nYawns: $yawnCount\n-------------------\n"
        prefs.edit().putString(KEY_HISTORY, newEntry + currentHistory).apply()
    }
    fun getHistory(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HISTORY, "No sessions recorded yet.") ?: "No sessions recorded yet."
    }
}
