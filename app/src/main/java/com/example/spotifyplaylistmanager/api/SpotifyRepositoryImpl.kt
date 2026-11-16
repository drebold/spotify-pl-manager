package com.example.spotifyplaylistmanager.api

import android.util.Log
import com.example.spotifyplaylistmanager.domain.PlaylistContents
import com.example.spotifyplaylistmanager.domain.SpotifyRepository

private const val TAG = "SpotifyRepo"

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
            Log.d(TAG, "getAllPlaylistItems($playlistId) offset=$offset: total=${page.total} items=${page.items.size} next=${page.next != null}")
            snapshotId = page.snapshotId
            page.items.forEach { item ->
                val uri = item.item?.uri
                Log.d(TAG, "  item: track=${item.item?.name} uri=$uri")
                if (uri != null) uris.add(uri)
            }
            if (page.next == null) break
            offset += 50
        }
        Log.d(TAG, "getAllPlaylistItems($playlistId) done: ${uris.size} uris collected")
        return PlaylistContents(snapshotId = snapshotId, trackUris = uris)
    }

    override suspend fun getPlaylistSnapshotId(playlistId: String): String =
        api.getPlaylistItems(playlistId, limit = 1, offset = 0).snapshotId

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
        val response = api.addToLikedSongs(LikedSongsRequest(listOf(trackId)))
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
    }

    override suspend fun removeFromLikedSongs(trackId: String) {
        val response = api.removeFromLikedSongs(LikedSongsRequest(listOf(trackId)))
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
    }

    override suspend fun getCurrentUser(): UserResponse = api.getCurrentUser()

    override suspend fun getQueue(): QueueResponse = api.getQueue()

    override suspend fun skipToNext() {
        api.skipToNext()
    }
}
