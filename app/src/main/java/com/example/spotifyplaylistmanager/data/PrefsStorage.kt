package com.example.spotifyplaylistmanager.data

import android.content.Context

class PrefsStorage(context: Context) {

    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun saveLastPlaylistId(id: String) {
        prefs.edit().putString(KEY_LAST_PLAYLIST, id).apply()
    }

    fun getLastPlaylistId(): String? = prefs.getString(KEY_LAST_PLAYLIST, null)

    companion object {
        private const val KEY_LAST_PLAYLIST = "last_playlist_id"
    }
}
