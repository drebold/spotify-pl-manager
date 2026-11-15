package com.example.spotifyplaylistmanager.auth

import android.app.Activity
import android.net.Uri
import java.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.example.spotifyplaylistmanager.data.TokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class AuthManager(private val tokenStorage: TokenStorage) {

    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    companion object {
        private const val CLIENT_ID = "0e20f98f65704eb9b09be9bd71291c93"
        private const val REDIRECT_URI = "myapp://callback"
        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val SCOPES = "user-read-playback-state user-modify-playback-state " +
                "playlist-read-private playlist-read-collaborative " +
                "playlist-modify-private playlist-modify-public " +
                "user-library-modify user-library-read"

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(96)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun generateCodeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        }
    }

    fun startAuth(activity: Activity) {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()
        pendingVerifier = verifier
        pendingState = state

        val scope = URLEncoder.encode(SCOPES, "UTF-8")
        val redirectUri = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        val url = "$AUTH_URL?client_id=$CLIENT_ID" +
                "&response_type=code" +
                "&redirect_uri=$redirectUri" +
                "&scope=$scope" +
                "&code_challenge_method=S256" +
                "&code_challenge=$challenge" +
                "&state=$state"

        CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
    }

    suspend fun handleCallback(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val returnedState = uri.getQueryParameter("state")
        if (returnedState != pendingState) return@withContext false

        val code = uri.getQueryParameter("code") ?: return@withContext false
        val verifier = pendingVerifier ?: return@withContext false

        try {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder().url(TOKEN_URL).post(body).build()
            val response = OkHttpClient().newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext false

            if (!response.isSuccessful) return@withContext false

            val json = JSONObject(responseBody)
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token")
            val expiresIn = json.getInt("expires_in")

            tokenStorage.saveTokens(
                accessToken,
                refreshToken,
                System.currentTimeMillis() + expiresIn * 1000L - 60_000L
            )
            pendingVerifier = null
            pendingState = null
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getValidAccessToken(): String? {
        val token = tokenStorage.getAccessToken()
        val expiresAt = tokenStorage.getExpiresAtMs()
        return if (token != null && System.currentTimeMillis() < expiresAt) {
            token
        } else {
            refreshAccessToken()
        }
    }

    private suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        val refreshToken = tokenStorage.getRefreshToken() ?: return@withContext null
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", CLIENT_ID)
                .build()

            val request = Request.Builder().url(TOKEN_URL).post(body).build()
            val response = OkHttpClient().newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (response.code == 400) {
                tokenStorage.clear()
                return@withContext null
            }
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(responseBody)
            val newAccess = json.getString("access_token")
            val newRefresh = json.optString("refresh_token").ifEmpty { refreshToken }
            val expiresIn = json.getInt("expires_in")

            tokenStorage.saveTokens(
                newAccess,
                newRefresh,
                System.currentTimeMillis() + expiresIn * 1000L - 60_000L
            )
            newAccess
        } catch (e: Exception) {
            null
        }
    }

    fun isLoggedIn(): Boolean = tokenStorage.getAccessToken() != null

    fun logout() = tokenStorage.clear()

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
