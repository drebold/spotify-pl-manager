package com.example.spotifyplaylistmanager.ui

import com.example.spotifyplaylistmanager.api.AlbumObject
import com.example.spotifyplaylistmanager.api.ArtistObject
import com.example.spotifyplaylistmanager.api.ContextObject
import com.example.spotifyplaylistmanager.api.PlaylistItemObject
import com.example.spotifyplaylistmanager.api.PlaylistItemsPageResponse
import com.example.spotifyplaylistmanager.api.PlaylistsPageResponse
import com.example.spotifyplaylistmanager.api.PlayerStateResponse
import com.example.spotifyplaylistmanager.api.QueueResponse
import com.example.spotifyplaylistmanager.api.TrackObject
import com.example.spotifyplaylistmanager.api.UserResponse
import com.example.spotifyplaylistmanager.auth.AuthManager
import com.example.spotifyplaylistmanager.data.PrefsStorage
import com.example.spotifyplaylistmanager.domain.PlaylistContents
import com.example.spotifyplaylistmanager.domain.SpotifyRepository
import com.example.spotifyplaylistmanager.domain.TrackMover
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainViewModelTest {

    private fun makeTrack(n: Int) = TrackObject(
        id = "id$n",
        uri = "spotify:track:id$n",
        name = "Track $n",
        artists = listOf(ArtistObject("Artist")),
        album = AlbumObject()
    )

    @Test
    fun `refreshNowPlaying stores source track count from playlist total`() = runTest {
        val tracks = (1..51).map { makeTrack(it) }
        val playlistId = "src"

        val fakeRepo = object : SpotifyRepository {
            override suspend fun getPlaybackState() = PlayerStateResponse(
                isPlaying = true,
                shuffleState = false,
                item = tracks[0],
                context = ContextObject("playlist", "spotify:playlist:$playlistId")
            )
            override suspend fun getPlaylistItems(id: String, offset: Int) = PlaylistItemsPageResponse(
                items = tracks.take(50).map { PlaylistItemObject(it) },
                next = null,
                total = 51
            )
            override suspend fun getAllPlaylistItems(id: String) =
                PlaylistContents("snap123", tracks.map { it.uri }.toSet())
            override suspend fun getUserPlaylists(offset: Int) = PlaylistsPageResponse()
            override suspend fun addToPlaylist(playlistId: String, trackUri: String) = ""
            override suspend fun removeFromPlaylist(playlistId: String, trackUri: String, snapshotId: String) = ""
            override suspend fun addToLikedSongs(trackId: String) {}
            override suspend fun removeFromLikedSongs(trackId: String) {}
            override suspend fun getCurrentUser() = UserResponse("user1")
            override suspend fun getQueue() = QueueResponse()
            override suspend fun skipToNext() {}
            override suspend fun getPlaylistSnapshotId(playlistId: String): String = "snap_current"
        }

        val authManager = mockk<AuthManager>()
        every { authManager.isLoggedIn() } returns false
        val prefsStorage = mockk<PrefsStorage>(relaxed = true)
        val mover = TrackMover(fakeRepo)
        val viewModel = MainViewModel(fakeRepo, mover, authManager, prefsStorage)

        viewModel.refreshNowPlaying()

        assertEquals(51, viewModel.uiState.value.sourceTrackCount)
    }

    @Test
    fun `onLogout resets isLoggedIn to false`() = runTest {
        val authManager = mockk<AuthManager>()
        every { authManager.isLoggedIn() } returns false
        val prefsStorage = mockk<PrefsStorage>(relaxed = true)
        val fakeRepo = object : SpotifyRepository {
            override suspend fun getPlaybackState() = null
            override suspend fun getPlaylistItems(id: String, offset: Int) = PlaylistItemsPageResponse()
            override suspend fun getAllPlaylistItems(id: String) = PlaylistContents("", emptySet())
            override suspend fun getUserPlaylists(offset: Int) = PlaylistsPageResponse()
            override suspend fun addToPlaylist(playlistId: String, trackUri: String) = ""
            override suspend fun removeFromPlaylist(playlistId: String, trackUri: String, snapshotId: String) = ""
            override suspend fun addToLikedSongs(trackId: String) {}
            override suspend fun removeFromLikedSongs(trackId: String) {}
            override suspend fun getCurrentUser() = UserResponse("user1")
            override suspend fun getQueue() = QueueResponse()
            override suspend fun skipToNext() {}
            override suspend fun getPlaylistSnapshotId(playlistId: String) = ""
        }
        val mover = TrackMover(fakeRepo)
        val viewModel = MainViewModel(fakeRepo, mover, authManager, prefsStorage)

        viewModel.onLogout()

        assertFalse(viewModel.uiState.value.isLoggedIn)
    }
}
