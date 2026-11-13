package com.example.spotifyplaylistmanager.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
                    .verticalScroll(rememberScrollState())
            ) {
                NowPlayingCard(
                    nowPlaying = uiState.nowPlaying,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )

                // Target playlist row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.targetPlaylist?.name ?: "No target playlist selected",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            if (uiState.availablePlaylists.isEmpty()) viewModel.loadPlaylists()
                            showPlaylistPicker = true
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("Change")
                    }
                }

                // Action buttons
                val bs = uiState.buttonState
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::onMove,
                        enabled = bs.moveEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (bs.isOperationInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Move")
                        }
                    }
                    Button(
                        onClick = viewModel::onDelete,
                        enabled = bs.deleteEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Delete")
                    }
                    Button(
                        onClick = viewModel::onSkip,
                        enabled = bs.skipEnabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Skip")
                    }
                }

                // Remaining tracks
                if (uiState.isShuffleQueueNotice) {
                    Text(
                        text = "Shuffle is on — showing upcoming queue",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                } else if (uiState.remainingTracks.isNotEmpty()) {
                    Text(
                        text = "Up next",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                uiState.remainingTracks.forEach { track ->
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(track.name, maxLines = 1) },
                        supportingContent = { Text(track.artists.joinToString(", "), maxLines = 1) }
                    )
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
