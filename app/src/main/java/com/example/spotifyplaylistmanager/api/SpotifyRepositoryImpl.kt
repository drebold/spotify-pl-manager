package com.example.spotifyplaylistmanager.api

import com.example.spotifyplaylistmanager.domain.PlaylistContents
import com.example.spotifyplaylistmanager.domain.SpotifyRepository

class SpotifyRepositoryImpl(private val api: SpotifyApi) : SpotifyRepository {

    override suspend fun getPlaybackState(): PlayerStateResponse? {
        val response = api.getPlaybackState()
        return if (response.isSuccessful) response.body() else null
    }

    override suspend fun getUserPlaylists(offset: Int): PlaylistsPageResponse =
        api.getUserPlaylists(offset = offset)

    override suspend fun getPlaylistItems(playlistId: String, offset: Int): PlaylistItemsPageResponse =
        api.getPlaylistItems(playlistId, offset = offset)

    override suspend fun getAllPlaylistItems(playlistId: String): PlaylistContents {
        val uris = mutableSetOf<String>()
        var offset = 0
        var snapshotId = ""
        while (true) {
            val page = api.getPlaylistItems(playlistId, offset = offset)
            snapshotId = page.snapshotId
            page.items.forEach { item ->
                val uri = item.track?.uri
                if (uri != null) uris.add(uri)
            }
            if (page.next == null) break
            offset += 50
        }
        return PlaylistContents(snapshotId = snapshotId, trackUris = uris)
    }

    override suspend fun addToPlaylist(playlistId: String, trackUri: String): String =
        api.addToPlaylist(playlistId, AddToPlaylistRequest(listOf(trackUri))).snapshotId

    override suspend fun removeFromPlaylist(
        playlistId: String,
        trackUri: String,
        snapshotId: String
    ): String = api.removeFromPlaylist(
        playlistId,
        RemoveFromPlaylistRequest(
            items = listOf(TrackUriItem(trackUri)),
            snapshotId = snapshotId.takeIf { it.isNotBlank() }
        )
    ).snapshotId

    override suspend fun addToLikedSongs(trackId: String) {
        api.addToLikedSongs(LikedSongsRequest(listOf(trackId)))
    }

    override suspend fun removeFromLikedSongs(trackId: String) {
        api.removeFromLikedSongs(LikedSongsRequest(listOf(trackId)))
    }

    override suspend fun getCurrentUser(): UserResponse = api.getCurrentUser()

    override suspend fun getQueue(): QueueResponse = api.getQueue()

    override suspend fun skipToNext() {
        api.skipToNext()
    }
}
