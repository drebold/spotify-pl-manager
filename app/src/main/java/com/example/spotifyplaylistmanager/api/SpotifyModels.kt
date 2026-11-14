package com.example.spotifyplaylistmanager.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Request bodies ────────────────────────────────────────────────────────────

@Serializable
data class AddToPlaylistRequest(val uris: List<String>)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoveFromPlaylistRequest(
    val items: List<TrackUriItem>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("snapshot_id") val snapshotId: String? = null
)

@Serializable
data class TrackUriItem(val uri: String)

@Serializable
data class LikedSongsRequest(val ids: List<String>)

// ── Response bodies ───────────────────────────────────────────────────────────

@Serializable
data class PlayerStateResponse(
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("shuffle_state") val shuffleState: Boolean = false,
    val item: TrackObject? = null,
    val context: ContextObject? = null,
    @SerialName("progress_ms") val progressMs: Long? = null
)

@Serializable
data class TrackObject(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<ArtistObject> = emptyList(),
    val album: AlbumObject = AlbumObject(),
    @SerialName("is_local") val isLocal: Boolean = false
)

@Serializable
data class ArtistObject(val name: String = "")

@Serializable
data class AlbumObject(
    val name: String = "",
    val images: List<ImageObject> = emptyList()
)

@Serializable
data class ImageObject(
    val url: String,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class ContextObject(
    val type: String,
    val uri: String
)

@Serializable
data class PlaylistsPageResponse(
    val items: List<PlaylistObject> = emptyList(),
    val next: String? = null,
    val total: Int = 0
)

@Serializable
data class PlaylistObject(
    val id: String,
    val name: String,
    val owner: OwnerObject,
    val collaborative: Boolean = false,
    @SerialName("snapshot_id") val snapshotId: String = ""
)

@Serializable
data class OwnerObject(
    val id: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class PlaylistItemsPageResponse(
    val items: List<PlaylistItemObject> = emptyList(),
    val next: String? = null,
    @SerialName("snapshot_id") val snapshotId: String = "",
    val total: Int = 0
)

@Serializable
data class PlaylistItemObject(val track: TrackObject? = null)

@Serializable
data class SnapshotResponse(@SerialName("snapshot_id") val snapshotId: String)

@Serializable
data class QueueResponse(
    @SerialName("currently_playing") val currentlyPlaying: TrackObject? = null,
    val queue: List<TrackObject> = emptyList()
)

@Serializable
data class UserResponse(
    val id: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class SpotifyErrorBody(val error: SpotifyError)

@Serializable
data class SpotifyError(val status: Int, val message: String)
