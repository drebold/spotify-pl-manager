package com.example.spotifyplaylistmanager.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.spotifyplaylistmanager.auth.AuthManager
import com.example.spotifyplaylistmanager.data.PrefsStorage
import com.example.spotifyplaylistmanager.domain.ContextType
import com.example.spotifyplaylistmanager.domain.DeleteResult
import com.example.spotifyplaylistmanager.domain.MoveResult
import com.example.spotifyplaylistmanager.domain.NowPlayingState
import com.example.spotifyplaylistmanager.domain.PlaybackContext
import com.example.spotifyplaylistmanager.domain.PlaylistInfo
import com.example.spotifyplaylistmanager.domain.SpotifyRepository
import com.example.spotifyplaylistmanager.domain.TrackInfo
import com.example.spotifyplaylistmanager.domain.TrackMover
import com.example.spotifyplaylistmanager.api.SpotifyErrorBody
import com.example.spotifyplaylistmanager.api.TrackObject
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class UiState(
    val isLoggedIn: Boolean = false,
    val nowPlaying: NowPlayingState? = null,
    val sourcePlaylistInfo: PlaylistInfo? = null,
    val sourceTrackCount: Int? = null,
    val targetPlaylist: PlaylistInfo? = null,
    val availablePlaylists: List<PlaylistInfo> = emptyList(),
    val isLoadingPlaylists: Boolean = false,
    val buttonState: ButtonState = ButtonState(),
    val snackbarMessage: String? = null,
    val currentUserId: String? = null
)

data class ButtonState(
    val moveEnabled: Boolean = false,
    val deleteEnabled: Boolean = false,
    val skipEnabled: Boolean = false,
    val isOperationInProgress: Boolean = false
)

class MainViewModel(
    private val repo: SpotifyRepository,
    private val mover: TrackMover,
    private val authManager: AuthManager,
    private val prefsStorage: PrefsStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        if (authManager.isLoggedIn()) {
            _uiState.update { it.copy(isLoggedIn = true) }
            viewModelScope.launch { loadUserAndPlaylists() }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshNowPlaying()
                delay(4000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    fun initiateLogin(activity: Activity) {
        authManager.startAuth(activity)
    }

    fun onAuthSuccess() {
        _uiState.update { it.copy(isLoggedIn = true) }
        viewModelScope.launch { loadUserAndPlaylists() }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private suspend fun loadUserAndPlaylists() {
        try {
            val user = repo.getCurrentUser()
            _uiState.update { it.copy(currentUserId = user.id) }
            loadPlaylists()
            restoreLastPlaylist()
        } catch (e: Exception) {
            showSnackbar("Failed to load user data: ${e.message}")
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingPlaylists = true) }
            try {
                val userId = _uiState.value.currentUserId ?: return@launch
                val playlists = mutableListOf<PlaylistInfo>()
                var offset = 0
                while (true) {
                    val page = repo.getUserPlaylists(offset)
                    page.items
                        .filter { it.owner.id == userId || it.collaborative }
                        .map { PlaylistInfo(it.id, it.name, it.snapshotId, it.owner.id, it.collaborative) }
                        .let { playlists.addAll(it) }
                    if (page.next == null) break
                    offset += 50
                }
                _uiState.update { it.copy(availablePlaylists = playlists, isLoadingPlaylists = false) }
                restoreLastPlaylist()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingPlaylists = false) }
                showSnackbar("Failed to load playlists: ${e.message}")
            }
        }
    }

    private fun restoreLastPlaylist() {
        val lastId = prefsStorage.getLastPlaylistId() ?: return
        val playlist = _uiState.value.availablePlaylists.find { it.id == lastId } ?: return
        _uiState.update { it.copy(targetPlaylist = playlist) }
    }

    fun selectTargetPlaylist(playlist: PlaylistInfo) {
        prefsStorage.saveLastPlaylistId(playlist.id)
        _uiState.update { it.copy(targetPlaylist = playlist) }
        updateButtonState()
    }

    // ── Now Playing ───────────────────────────────────────────────────────────

    internal suspend fun refreshNowPlaying() {
        try {
            val state = repo.getPlaybackState()
            if (state == null || state.item == null) {
                _uiState.update { it.copy(
                    nowPlaying = null,
                    sourcePlaylistInfo = null,
                    sourceTrackCount = null,
                    buttonState = ButtonState()
                )}
                return
            }

            val track = state.item
            val context = parseContext(state.context?.type, state.context?.uri)
            val sourcePlaylistInfo = context?.playlistId?.let { playlistId ->
                _uiState.value.availablePlaylists.find { it.id == playlistId }
            }
            val sourceLabel = buildSourceLabel(track, context, sourcePlaylistInfo)

            val nowPlaying = NowPlayingState(
                track = track.toDomain(),
                context = context,
                sourceLabel = sourceLabel,
                isShuffling = state.shuffleState
            )

            _uiState.update { it.copy(
                nowPlaying = nowPlaying,
                sourcePlaylistInfo = sourcePlaylistInfo,
            )}

            // Source playlist track count
            if (context?.type == ContextType.PLAYLIST && context.playlistId != null) {
                try {
                    val page = repo.getPlaylistItems(context.playlistId, 0)
                    _uiState.update { it.copy(sourceTrackCount = page.total) }
                } catch (_: Exception) {
                    _uiState.update { it.copy(sourceTrackCount = null) }
                }
            } else {
                _uiState.update { it.copy(sourceTrackCount = null) }
            }

            updateButtonState()
        } catch (e: HttpException) {
            if (e.code() == 403) showSnackbar("Spotify Premium is required for playback control")
        } catch (_: Exception) {
            // Swallow transient polling errors silently
        }
    }

    private fun updateButtonState() {
        val state = _uiState.value
        val nowPlaying = state.nowPlaying
        val inProgress = state.buttonState.isOperationInProgress
        _uiState.update { it.copy(buttonState = ButtonState(
            moveEnabled = nowPlaying != null && !nowPlaying.track.isLocal && state.targetPlaylist != null && !inProgress,
            deleteEnabled = nowPlaying != null && !nowPlaying.track.isLocal && mover.canDelete(nowPlaying.context) && !inProgress,
            skipEnabled = nowPlaying != null && !inProgress,
            isOperationInProgress = inProgress
        ))}
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun onMove() {
        val state = _uiState.value
        val track = state.nowPlaying?.track ?: return
        val context = state.nowPlaying.context
        val target = state.targetPlaylist ?: return
        val userId = state.currentUserId ?: return

        viewModelScope.launch {
            setOperationInProgress(true)
            try {
                val result = mover.move(track.uri, track.id, target.id, context, userId)
                when (result) {
                    is MoveResult.Success -> showSnackbar("Moved to ${target.name}")
                    is MoveResult.AddOnlySuccess -> showSnackbar("Added to ${target.name}. ${result.reason}")
                    is MoveResult.Failure -> showSnackbar(result.message)
                }
            } catch (e: HttpException) {
                if (e.code() == 403) showSnackbar("Spotify Premium is required")
                else showSnackbar("Error: ${e.spotifyMessage()}")
            } catch (e: Exception) {
                showSnackbar("Error: ${e.message}")
            } finally {
                setOperationInProgress(false)
                refreshNowPlaying()
            }
        }
    }

    fun onDelete() {
        val state = _uiState.value
        val track = state.nowPlaying?.track ?: return
        val context = state.nowPlaying.context

        viewModelScope.launch {
            setOperationInProgress(true)
            try {
                val result = mover.delete(track.uri, track.id, context, null)
                when (result) {
                    is DeleteResult.Success -> showSnackbar("Track removed")
                    is DeleteResult.NotApplicable -> showSnackbar("Cannot remove from this source")
                    is DeleteResult.Failure -> showSnackbar(result.message)
                }
            } catch (e: HttpException) {
                if (e.code() == 403) showSnackbar("Spotify Premium is required")
                else showSnackbar("Error: ${e.spotifyMessage()}")
            } catch (e: Exception) {
                showSnackbar("Error: ${e.message}")
            } finally {
                setOperationInProgress(false)
                refreshNowPlaying()
            }
        }
    }

    fun onSkip() {
        viewModelScope.launch {
            setOperationInProgress(true)
            try {
                repo.skipToNext()
                delay(600)
            } catch (e: HttpException) {
                if (e.code() == 403) showSnackbar("Spotify Premium is required for skip")
                else showSnackbar("Skip failed: ${e.spotifyMessage()}")
            } catch (e: Exception) {
                showSnackbar("Skip failed: ${e.message}")
            } finally {
                setOperationInProgress(false)
                refreshNowPlaying()
            }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    private fun HttpException.spotifyMessage(): String {
        val body = response()?.errorBody()?.string() ?: return "HTTP ${code()}"
        return try {
            errorJson.decodeFromString<SpotifyErrorBody>(body).error.message
        } catch (_: Exception) {
            "HTTP ${code()}"
        }
    }

    private fun setOperationInProgress(inProgress: Boolean) {
        _uiState.update { it.copy(buttonState = it.buttonState.copy(
            isOperationInProgress = inProgress,
            moveEnabled = !inProgress && it.buttonState.moveEnabled || (!inProgress && it.nowPlaying != null && !it.nowPlaying.track.isLocal && it.targetPlaylist != null),
            deleteEnabled = !inProgress && it.buttonState.deleteEnabled || (!inProgress && it.nowPlaying != null && !it.nowPlaying.track.isLocal && mover.canDelete(it.nowPlaying.context)),
            skipEnabled = !inProgress && it.nowPlaying != null
        ))}
    }

    private fun parseContext(type: String?, uri: String?): PlaybackContext? {
        if (type == null || uri == null) return null
        val contextType = when (type) {
            "playlist" -> ContextType.PLAYLIST
            "album" -> ContextType.ALBUM
            "collection" -> ContextType.COLLECTION
            else -> ContextType.OTHER
        }
        val playlistId = if (contextType == ContextType.PLAYLIST) {
            uri.substringAfterLast(":")
        } else null
        return PlaybackContext(contextType, uri, playlistId)
    }

    private fun buildSourceLabel(
        track: TrackObject,
        context: PlaybackContext?,
        sourcePlaylistInfo: PlaylistInfo?
    ): String = when (context?.type) {
        ContextType.PLAYLIST -> sourcePlaylistInfo?.name ?: "Playlist"
        ContextType.ALBUM -> track.album.name
        ContextType.COLLECTION -> "Liked Songs"
        else -> ""
    }

    companion object {
        private val errorJson = Json { ignoreUnknownKeys = true }

        fun factory(
            repo: SpotifyRepository,
            mover: TrackMover,
            auth: AuthManager,
            prefs: PrefsStorage
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(repo, mover, auth, prefs) }
        }
    }
}

private fun TrackObject.toDomain(): TrackInfo = TrackInfo(
    id = id,
    uri = uri,
    name = name,
    artists = artists.map { it.name },
    albumName = album.name,
    albumArtUrl = album.images.maxByOrNull { it.width ?: 0 }?.url,
    isLocal = isLocal
)
