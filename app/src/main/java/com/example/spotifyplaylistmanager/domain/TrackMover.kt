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
        userId: String,
        cachedTargetUris: Set<String> = emptySet()
    ): MoveResult {
        if (trackUri.startsWith("spotify:local:")) {
            return MoveResult.Failure("Local files cannot be managed via the Spotify API")
        }

        // Use cached URIs if available, otherwise fetch from API
        Log.d(TAG, "move() start: trackUri=$trackUri target=$targetPlaylistId context=${context?.type} sourceId=${context?.playlistId}")
        val knownTargetUris = if (cachedTargetUris.isNotEmpty()) {
            cachedTargetUris
        } else {
            val contents = repo.getAllPlaylistItems(targetPlaylistId)
            contents.trackUris
        }
        Log.d(TAG, "target has ${knownTargetUris.size} tracks, alreadyContains=${trackUri in knownTargetUris}")
        if (trackUri !in knownTargetUris) {
            val newSnapshotId = repo.addToPlaylist(targetPlaylistId, trackUri)
            Log.d(TAG, "addToPlaylist returned snapshotId='$newSnapshotId'")
            if (newSnapshotId.isBlank()) {
                return MoveResult.Failure("Could not add track to target playlist")
            }
        }

        val alreadyExisted = trackUri in knownTargetUris

        // Remove from source
        return when (context?.type) {
            ContextType.PLAYLIST -> {
                val sourceId = context.playlistId
                Log.d(TAG, "source playlist remove: sourceId=$sourceId")
                if (sourceId != null) {
                    val snapshotId = repo.getPlaylistSnapshotId(sourceId)
                    Log.d(TAG, "snapshotId='$snapshotId', calling removeFromPlaylist")
                    repo.removeFromPlaylist(sourceId, trackUri, snapshotId)
                    Log.d(TAG, "removeFromPlaylist completed successfully")
                } else {
                    Log.w(TAG, "sourceId is null — skipping remove")
                }
                if (alreadyExisted) MoveResult.AlreadyExists else MoveResult.Success
            }
            ContextType.COLLECTION -> {
                repo.removeFromLikedSongs(trackId)
                if (alreadyExisted) MoveResult.AlreadyExists else MoveResult.Success
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
