package com.example.spotifyplaylistmanager.domain

import com.example.spotifyplaylistmanager.api.PlaylistItemsPageResponse
import com.example.spotifyplaylistmanager.api.PlaylistsPageResponse
import com.example.spotifyplaylistmanager.api.PlayerStateResponse
import com.example.spotifyplaylistmanager.api.QueueResponse
import com.example.spotifyplaylistmanager.api.UserResponse

interface SpotifyRepository {
    suspend fun getPlaybackState(): PlayerStateResponse?
    suspend fun getUserPlaylists(offset: Int = 0): PlaylistsPageResponse
    suspend fun getPlaylistItems(playlistId: String, offset: Int = 0): PlaylistItemsPageResponse
    suspend fun getAllPlaylistItems(playlistId: String): PlaylistContents
    suspend fun addToPlaylist(playlistId: String, trackUri: String): String
    suspend fun removeFromPlaylist(playlistId: String, trackUri: String, snapshotId: String): String
    suspend fun addToLikedSongs(trackId: String)
    suspend fun removeFromLikedSongs(trackId: String)
    suspend fun getCurrentUser(): UserResponse
    suspend fun getQueue(): QueueResponse
    suspend fun skipToNext()
}
