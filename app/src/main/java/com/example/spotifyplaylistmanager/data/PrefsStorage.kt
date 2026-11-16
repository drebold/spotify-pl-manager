package com.example.spotifyplaylistmanager.data

import android.content.Context
import android.content.SharedPreferences

class PrefsStorage internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE))

    fun saveLastPlaylistId(id: String) {
        prefs.edit().putString(KEY_LAST_PLAYLIST, id).apply()
    }

    fun getLastPlaylistId(): String? = prefs.getString(KEY_LAST_PLAYLIST, null)

    fun saveClientId(id: String) {
        val trimmed = id.trim()
        if (trimmed.isBlank()) {
            prefs.edit().remove(KEY_CLIENT_ID).apply()
        } else {
            prefs.edit().putString(KEY_CLIENT_ID, trimmed).apply()
        }
    }

    fun getClientId(): String? = prefs.getString(KEY_CLIENT_ID, null).takeIf { !it.isNullOrBlank() }

    fun clearClientId() {
        prefs.edit().remove(KEY_CLIENT_ID).apply()
    }

    companion object {
        private const val KEY_LAST_PLAYLIST = "last_playlist_id"
        private const val KEY_CLIENT_ID = "client_id"
    }
}
