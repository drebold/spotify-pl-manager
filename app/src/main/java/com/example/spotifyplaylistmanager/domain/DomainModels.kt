package com.example.spotifyplaylistmanager.domain

data class TrackInfo(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<String>,
    val albumName: String,
    val albumArtUrl: String?,
    val isLocal: Boolean
)

data class PlaylistInfo(
    val id: String,
    val name: String,
    val snapshotId: String,
    val ownerId: String,
    val isCollaborative: Boolean
)

enum class ContextType { PLAYLIST, ALBUM, COLLECTION, OTHER }

data class PlaybackContext(
    val type: ContextType,
    val uri: String,
    val playlistId: String?
)

data class NowPlayingState(
    val track: TrackInfo,
    val context: PlaybackContext?,
    val sourceLabel: String,
    val isShuffling: Boolean
)

data class PlaylistContents(
    val snapshotId: String,
    val trackUris: Set<String>
)

sealed class MoveResult {
    object Success : MoveResult()
    object AlreadyExists : MoveResult()
    data class AddOnlySuccess(val reason: String) : MoveResult()
    data class Failure(val message: String) : MoveResult()
}

sealed class DeleteResult {
    object Success : DeleteResult()
    object NotApplicable : DeleteResult()
    data class Failure(val message: String) : DeleteResult()
}
