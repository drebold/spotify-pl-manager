package com.example.spotifyplaylistmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.spotifyplaylistmanager.ui.AuthFailedScreen
import com.example.spotifyplaylistmanager.ui.MainScreen
import com.example.spotifyplaylistmanager.ui.MainViewModel
import com.example.spotifyplaylistmanager.ui.SetupScreen
import com.example.spotifyplaylistmanager.ui.theme.SpotifyPlaylistManagerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class AppScreen {
    object Loading : AppScreen()
    object Setup : AppScreen()
    object Login : AppScreen()
    object Main : AppScreen()
    object AuthFailed : AppScreen()
}

internal suspend fun checkAuthState(
    prefsStorage: PrefsStorage,
    authManager: AuthManager
): AppScreen {
    return try {
        val token = authManager.getValidAccessToken()
        if (token != null) {
            if (prefsStorage.getClientId() == null) {
                // Stale token from before user entered a client ID (e.g. upgrade from hardcoded version)
                authManager.logout()
                AppScreen.Setup
            } else {
                AppScreen.Main
            }
        } else if (prefsStorage.getClientId() == null) {
            AppScreen.Setup
        } else {
            AppScreen.Login
        }
    } catch (e: Exception) {
        AppScreen.AuthFailed
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var tokenStorage: TokenStorage
    private lateinit var authManager: AuthManager
    private lateinit var prefsStorage: PrefsStorage
    private lateinit var viewModel: MainViewModel

    private var screen by mutableStateOf<AppScreen>(AppScreen.Loading)
    private var authCheckJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenStorage = TokenStorage(this)
        prefsStorage = PrefsStorage(this)
        authManager = AuthManager(tokenStorage, prefsStorage)

        val api = SpotifyClient.create(authManager)
        val repo = SpotifyRepositoryImpl(api)
        val mover = TrackMover(repo)

        viewModel = ViewModelProvider(
            this,
            MainViewModel.factory(repo, mover, authManager, prefsStorage)
        )[MainViewModel::class.java]

        resolveAuthState()

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
                val onChangeClientId = {
                    authManager.logout()
                    prefsStorage.clearClientId()
                    viewModel.onLogout()
                    screen = AppScreen.Setup
                }
                when (screen) {
                    AppScreen.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    AppScreen.Setup -> SetupScreen { clientId ->
                        prefsStorage.saveClientId(clientId)
                        screen = AppScreen.Login
                    }
                    AppScreen.Login -> MainScreen(
                        viewModel = viewModel,
                        onChangeClientId = onChangeClientId
                    )
                    AppScreen.Main -> MainScreen(
                        viewModel = viewModel,
                        onChangeClientId = onChangeClientId
                    )
                    AppScreen.AuthFailed -> AuthFailedScreen(
                        onChangeClientId = onChangeClientId,
                        onRetry = { resolveAuthState() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            if (uri.scheme == "myapp") handleAuthCallback(uri)
        }
    }

    private fun resolveAuthState() {
        authCheckJob?.cancel()
        authCheckJob = lifecycleScope.launch {
            screen = checkAuthState(prefsStorage, authManager)
        }
    }

    private fun handleAuthCallback(uri: Uri) {
        authCheckJob?.cancel()
        lifecycleScope.launch {
            val success = authManager.handleCallback(uri)
            if (success) {
                screen = AppScreen.Main
                viewModel.onAuthSuccess()
            } else {
                screen = AppScreen.AuthFailed
            }
        }
    }
}
