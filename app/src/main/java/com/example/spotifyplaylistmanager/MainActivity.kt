package com.example.spotifyplaylistmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.spotifyplaylistmanager.api.SpotifyClient
import com.example.spotifyplaylistmanager.api.SpotifyRepositoryImpl
import com.example.spotifyplaylistmanager.auth.AuthManager
import com.example.spotifyplaylistmanager.data.PrefsStorage
import com.example.spotifyplaylistmanager.data.TokenStorage
import com.example.spotifyplaylistmanager.domain.TrackMover
import com.example.spotifyplaylistmanager.ui.MainScreen
import com.example.spotifyplaylistmanager.ui.MainViewModel
import com.example.spotifyplaylistmanager.ui.theme.SpotifyPlaylistManagerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var tokenStorage: TokenStorage
    private lateinit var authManager: AuthManager
    private lateinit var prefsStorage: PrefsStorage
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenStorage = TokenStorage(this)
        authManager = AuthManager(tokenStorage)
        prefsStorage = PrefsStorage(this)

        val api = SpotifyClient.create(authManager)
        val repo = SpotifyRepositoryImpl(api)
        val mover = TrackMover(repo)

        viewModel = ViewModelProvider(
            this,
            MainViewModel.factory(repo, mover, authManager, prefsStorage)
        )[MainViewModel::class.java]

        // Handle cold-start auth callback
        intent?.data?.let { uri ->
            if (uri.scheme == "myapp") handleAuthCallback(uri)
        }

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.startPolling()
                Lifecycle.Event.ON_PAUSE -> viewModel.stopPolling()
                else -> {}
            }
        })

        setContent {
            SpotifyPlaylistManagerTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            if (uri.scheme == "myapp") handleAuthCallback(uri)
        }
    }

    private fun handleAuthCallback(uri: Uri) {
        lifecycleScope.launch {
            val success = authManager.handleCallback(uri)
            if (success) viewModel.onAuthSuccess()
        }
    }
}
