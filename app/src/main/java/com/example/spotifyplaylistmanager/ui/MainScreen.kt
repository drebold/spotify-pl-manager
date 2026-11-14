package com.example.spotifyplaylistmanager.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (!uiState.isLoggedIn) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = { viewModel.initiateLogin(context as Activity) }) {
                    Text("Login with Spotify")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ── Top: now-playing card + source info ───────────────────────
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    NowPlayingCard(
                        nowPlaying = uiState.nowPlaying,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val sourceLabel = uiState.nowPlaying?.sourceLabel?.takeIf { it.isNotEmpty() }
                    if (sourceLabel != null) {
                        val countSuffix = uiState.sourceTrackCount?.let { " · $it tracks" } ?: ""
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "From: $sourceLabel$countSuffix",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // ── Bottom: action buttons + target picker ────────────────────
                Column(modifier = Modifier.padding(bottom = 20.dp)) {
                    val bs = uiState.buttonState

                    Button(
                        onClick = viewModel::onMove,
                        enabled = bs.moveEnabled,
                        modifier = Modifier.fillMaxWidth().height(72.dp)
                    ) {
                        if (bs.isOperationInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Move", style = MaterialTheme.typography.titleLarge)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = viewModel::onDelete,
                        enabled = bs.deleteEnabled,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
                            disabledContentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text("Delete", style = MaterialTheme.typography.titleLarge)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = viewModel::onSkip,
                        enabled = bs.skipEnabled,
                        modifier = Modifier.fillMaxWidth().height(72.dp)
                    ) {
                        Text("Skip", style = MaterialTheme.typography.titleLarge)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Target playlist row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.targetPlaylist?.name ?: "No playlist selected",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        OutlinedButton(
                            onClick = {
                                if (uiState.availablePlaylists.isEmpty()) viewModel.loadPlaylists()
                                showPlaylistPicker = true
                            }
                        ) {
                            Text("Change")
                        }
                    }
                }
            }
        }
    }

    if (showPlaylistPicker) {
        PlaylistPickerSheet(
            playlists = uiState.availablePlaylists,
            isLoading = uiState.isLoadingPlaylists,
            onSelect = { viewModel.selectTargetPlaylist(it) },
            onDismiss = { showPlaylistPicker = false }
        )
    }
}
