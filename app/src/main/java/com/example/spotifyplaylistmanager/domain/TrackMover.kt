package com.example.spotifyplaylistmanager.domain

import kotlinx.coroutines.delay

class TrackMover(
    private val repo: SpotifyRepository,
    private val delayMs: Long = 500L,
    private val retryDelayMs: Long = 1000L
) {

    fun canDelete(context: PlaybackContext?): Boolean =
        context?.type == ContextType.PLAYLIST || context?.type == ContextType.COLLECTION

    suspend fun move(
        trackUri: String,
        trackId: String,
        targetPlaylistId: String,
        context: PlaybackContext?,
        userId: String
    ): MoveResult {
        if (trackUri.startsWith("spotify:local:")) {
            return MoveResult.Failure("Local files cannot be managed via the Spotify API")
        }

        repo.addToPlaylist(targetPlaylistId, trackUri)
        delay(delayMs)

        // Verify track landed in target
        var targetContents = repo.getAllPlaylistItems(targetPlaylistId)
        if (trackUri !in targetContents.trackUris) {
            delay(retryDelayMs)
            targetContents = repo.getAllPlaylistItems(targetPlaylistId)
        }
        if (trackUri !in targetContents.trackUris) {
            return MoveResult.Failure("Could not verify track was added to target playlist")
        }

        // Remove from source
        return when (context?.type) {
            ContextType.PLAYLIST -> {
                val sourceId = context.playlistId
                if (sourceId != null) {
                    // Fetch source snapshot_id fresh to avoid race conditions
                    val sourceContents = repo.getAllPlaylistItems(sourceId)
                    repo.removeFromPlaylist(sourceId, trackUri, sourceContents.snapshotId)
                }
                MoveResult.Success
            }
            ContextType.COLLECTION -> {
                repo.removeFromLikedSongs(trackId)
                MoveResult.Success
            }
            else -> MoveResult.AddOnlySuccess(contextLabel(context))
        }
    }

    suspend fun delete(
        trackUri: String,
        trackId: String,
        context: PlaybackContext?,
        snapshotId: String?
    ): DeleteResult {
        return when (context?.type) {
            ContextType.PLAYLIST -> {
                val sourceId = context.playlistId
                val snap = snapshotId
                if (sourceId != null && snap != null) {
                    repo.removeFromPlaylist(sourceId, trackUri, snap)
                    DeleteResult.Success
                } else {
                    DeleteResult.NotApplicable
                }
            }
            ContextType.COLLECTION -> {
                repo.removeFromLikedSongs(trackId)
                DeleteResult.Success
            }
            else -> DeleteResult.NotApplicable
        }
    }

    private fun contextLabel(context: PlaybackContext?): String = when (context?.type) {
        ContextType.ALBUM -> "Cannot remove from album"
        ContextType.OTHER -> "Cannot remove from this source"
        null -> "Cannot remove — nothing playing or source unknown"
        else -> "Cannot remove from this source"
    }
}
