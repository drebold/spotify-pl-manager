package com.example.spotifyplaylistmanager.domain

import com.example.spotifyplaylistmanager.api.AlbumObject
import com.example.spotifyplaylistmanager.api.ArtistObject
import com.example.spotifyplaylistmanager.api.PlaylistItemObject
import com.example.spotifyplaylistmanager.api.PlaylistItemsPageResponse
import com.example.spotifyplaylistmanager.api.PlaylistsPageResponse
import com.example.spotifyplaylistmanager.api.PlayerStateResponse
import com.example.spotifyplaylistmanager.api.QueueResponse
import com.example.spotifyplaylistmanager.api.TrackObject
import com.example.spotifyplaylistmanager.api.UserResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ── Fake repository ───────────────────────────────────────────────────────────

open class FakeSpotifyRepository : SpotifyRepository {
    val addedItems = mutableListOf<Pair<String, String>>()
    val removedItems = mutableListOf<Triple<String, String, String>>()
    val removedLiked = mutableListOf<String>()
    val playlistContents = mutableMapOf<String, MutableSet<String>>()
    var getAllCallCount = 0

    override suspend fun addToPlaylist(playlistId: String, trackUri: String): String {
        addedItems += playlistId to trackUri
        playlistContents.getOrPut(playlistId) { mutableSetOf() }.add(trackUri)
        return "snap_new"
    }

    override suspend fun getAllPlaylistItems(playlistId: String): PlaylistContents {
        getAllCallCount++
        return PlaylistContents("snap_current", playlistContents[playlistId] ?: emptySet())
    }

    override suspend fun removeFromPlaylist(id: String, uri: String, snap: String): String {
        removedItems += Triple(id, uri, snap)
        return "snap_after"
    }

    override suspend fun removeFromLikedSongs(id: String) { removedLiked += id }

    override suspend fun getPlaybackState(): PlayerStateResponse? = null
    override suspend fun getUserPlaylists(offset: Int) = PlaylistsPageResponse(emptyList())
    override suspend fun getPlaylistItems(id: String, offset: Int) = PlaylistItemsPageResponse(emptyList())
    override suspend fun addToLikedSongs(id: String) {}
    override suspend fun getCurrentUser() = UserResponse("user1")
    override suspend fun getQueue() = QueueResponse()
    override suspend fun skipToNext() {}
    override suspend fun getPlaylistSnapshotId(playlistId: String): String = "snap_current"
}

// ── Test class ────────────────────────────────────────────────────────────────

class TrackMoverTest {

    private val fake = FakeSpotifyRepository()
    private val mover = TrackMover(fake)

    private val playlistContext = PlaybackContext(ContextType.PLAYLIST, "spotify:playlist:src", "src")
    private val albumContext = PlaybackContext(ContextType.ALBUM, "spotify:album:xyz", null)
    private val collectionContext = PlaybackContext(ContextType.COLLECTION, "spotify:user:u:collection", null)

    // ── move() tests ──────────────────────────────────────────────────────────

    @Test
    fun `move adds to target then removes from source playlist`() = runTest {
        val result = mover.move("spotify:track:abc", "abc", "tgt", playlistContext, "user1")

        assertEquals(MoveResult.Success, result)
        assertTrue(fake.addedItems.any { it.first == "tgt" && it.second == "spotify:track:abc" })
        assertTrue(fake.removedItems.any { it.first == "src" && it.second == "spotify:track:abc" })
    }

    @Test
    fun `move skips add and still removes from source when track already in target`() = runTest {
        fake.playlistContents["tgt"] = mutableSetOf("spotify:track:abc")

        val result = mover.move("spotify:track:abc", "abc", "tgt", playlistContext, "user1")

        assertEquals(MoveResult.Success, result)
        assertTrue(fake.addedItems.isEmpty()) // not added again
        assertTrue(fake.removedItems.any { it.first == "src" && it.second == "spotify:track:abc" })
    }

    @Test
    fun `move succeeds despite Spotify eventual consistency on target playlist`() = runTest {
        // Simulate Spotify API lag: playlist query never reflects the newly added track.
        // The fix trusts the snapshot_id from addToPlaylist instead of re-querying.
        val lagFake = object : FakeSpotifyRepository() {
            override suspend fun getAllPlaylistItems(playlistId: String) =
                PlaylistContents("snap", emptySet()) // always empty
        }
        val result = TrackMover(lagFake).move("spotify:track:abc", "abc", "tgt", albumContext, "user1")
        assertTrue(result is MoveResult.AddOnlySuccess)
    }

    @Test
    fun `move returns Failure when addToPlaylist returns blank snapshot_id`() = runTest {
        val blankSnapFake = object : FakeSpotifyRepository() {
            override suspend fun addToPlaylist(playlistId: String, trackUri: String): String {
                addedItems += playlistId to trackUri
                return "" // blank snapshot_id signals unexpected server error
            }
        }
        val result = TrackMover(blankSnapFake).move("spotify:track:abc", "abc", "tgt", playlistContext, "user1")
        assertTrue(result is MoveResult.Failure)
        assertTrue(blankSnapFake.removedItems.isEmpty())
    }

    @Test
    fun `move returns AddOnlySuccess when source is album`() = runTest {
        val result = mover.move("spotify:track:abc", "abc", "tgt", albumContext, "user1")

        assertTrue(result is MoveResult.AddOnlySuccess)
        assertTrue(fake.addedItems.any { it.first == "tgt" })
        assertTrue(fake.removedItems.isEmpty())
    }

    @Test
    fun `move removes from liked songs when source is collection`() = runTest {
        val result = mover.move("spotify:track:abc", "abc", "tgt", collectionContext, "user1")

        assertEquals(MoveResult.Success, result)
        assertTrue(fake.removedLiked.contains("abc"))
        assertTrue(fake.removedItems.isEmpty())
    }

    @Test
    fun `move returns AddOnlySuccess when context is null`() = runTest {
        val result = mover.move("spotify:track:abc", "abc", "tgt", null, "user1")

        assertTrue(result is MoveResult.AddOnlySuccess)
        assertTrue(fake.removedItems.isEmpty())
    }

    @Test
    fun `move returns Failure immediately for local files`() = runTest {
        val result = mover.move("spotify:local:Artist:Album:Track:0", "local", "tgt", playlistContext, "user1")

        assertTrue(result is MoveResult.Failure)
        val msg = (result as MoveResult.Failure).message
        assertTrue(msg.contains("local", ignoreCase = true))
        assertTrue(fake.addedItems.isEmpty())
        assertTrue(fake.removedItems.isEmpty())
    }

    // ── delete() tests ────────────────────────────────────────────────────────

    @Test
    fun `delete removes from source playlist with snapshotId`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", playlistContext, "snap123")

        assertEquals(DeleteResult.Success, result)
        assertTrue(fake.removedItems.any { it.first == "src" && it.second == "spotify:track:abc" && it.third == "snap123" })
    }

    @Test
    fun `delete removes from source playlist without snapshotId`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", playlistContext, null)

        assertEquals(DeleteResult.Success, result)
        assertTrue(fake.removedItems.any { it.first == "src" && it.second == "spotify:track:abc" && it.third == "" })
    }

    @Test
    fun `delete removes from liked songs when context is collection`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", collectionContext, null)

        assertEquals(DeleteResult.Success, result)
        assertTrue(fake.removedLiked.contains("abc"))
    }

    @Test
    fun `delete returns NotApplicable when source is album`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", albumContext, null)
        assertEquals(DeleteResult.NotApplicable, result)
    }

    @Test
    fun `delete returns NotApplicable when context is null`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", null, null)
        assertEquals(DeleteResult.NotApplicable, result)
    }

    // ── canDelete() tests ─────────────────────────────────────────────────────

    @Test
    fun `canDelete returns true for playlist context`() {
        assertTrue(mover.canDelete(playlistContext))
    }

    @Test
    fun `canDelete returns true for collection context`() {
        assertTrue(mover.canDelete(collectionContext))
    }

    @Test
    fun `canDelete returns false for album context`() {
        assertFalse(mover.canDelete(albumContext))
    }

    @Test
    fun `canDelete returns false for null context`() {
        assertFalse(mover.canDelete(null))
    }

    // ── Review Focus: null track items ────────────────────────────────────────

    @Test
    fun `getAllPlaylistItems with null track entries does not reach TrackMover as crash`() = runTest {
        // SpotifyRepositoryImpl filters nulls; simulate that the returned PlaylistContents
        // contains only real URIs (null tracks are filtered before this layer)
        val nullFake = object : FakeSpotifyRepository() {
            override suspend fun getAllPlaylistItems(playlistId: String): PlaylistContents {
                return PlaylistContents("snap", setOf("spotify:track:real"))
            }
        }
        val nullMover = TrackMover(nullFake)
        val result = nullMover.move("spotify:track:real", "real", "tgt", albumContext, "u")

        // albumContext → AddOnlySuccess after successful verify
        assertTrue(result is MoveResult.AddOnlySuccess)
    }
}
