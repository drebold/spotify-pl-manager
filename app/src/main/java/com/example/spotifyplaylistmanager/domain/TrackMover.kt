package com.example.spotifyplaylistmanager.domain

import android.util.Log

private const val TAG = "TrackMover"

class TrackMover(private val repo: SpotifyRepository) {

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

        // Skip add if track is already in the target playlist.
        Log.d(TAG, "move() start: trackUri=$trackUri target=$targetPlaylistId context=${context?.type} sourceId=${context?.playlistId}")
        val targetContents = repo.getAllPlaylistItems(targetPlaylistId)
        Log.d(TAG, "target has ${targetContents.trackUris.size} tracks, alreadyContains=${trackUri in targetContents.trackUris}")
        if (trackUri !in targetContents.trackUris) {
            val newSnapshotId = repo.addToPlaylist(targetPlaylistId, trackUri)
            Log.d(TAG, "addToPlaylist returned snapshotId='$newSnapshotId'")
            if (newSnapshotId.isBlank()) {
                return MoveResult.Failure("Could not add track to target playlist")
            }
        }

        // Remove from source
        return when (context?.type) {
            ContextType.PLAYLIST -> {
                val sourceId = context.playlistId
                Log.d(TAG, "source playlist remove: sourceId=$sourceId")
                if (sourceId != null) {
                    // Fetch source snapshot_id fresh to avoid race conditions
                    val sourceContents = repo.getAllPlaylistItems(sourceId)
                    Log.d(TAG, "source has ${sourceContents.trackUris.size} tracks, snapshotId='${sourceContents.snapshotId}', calling removeFromPlaylist")
                    repo.removeFromPlaylist(sourceId, trackUri, sourceContents.snapshotId)
                    Log.d(TAG, "removeFromPlaylist completed successfully")
                } else {
                    Log.w(TAG, "sourceId is null — skipping remove")
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
                if (sourceId != null) {
                    repo.removeFromPlaylist(sourceId, trackUri, snapshotId ?: "")
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
