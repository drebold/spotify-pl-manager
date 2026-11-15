package com.example.spotifyplaylistmanager.api

import com.example.spotifyplaylistmanager.auth.AuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class RetryInterceptor(
    private val authManager: AuthManager,
    private val defaultRetryAfterMs: Long = 5_000L
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        var response = chain.proceed(originalRequest)

        // Handle 429 with exponential backoff, then fall through to 401 check
        if (response.code == 429) {
            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
            // If Retry-After is more than 60 seconds, don't block — surface the 429 immediately
            if (retryAfterSeconds != null && retryAfterSeconds > 60) {
                android.util.Log.w("RetryInterceptor", "429 with Retry-After=${retryAfterSeconds}s — not retrying, surfacing error")
                return response
            }
            var retries = 0
            while (response.code == 429 && retries < 4) {
                val delayMs = if (retryAfterSeconds != null) {
                    retryAfterSeconds * 1000L
                } else {
                    defaultRetryAfterMs * (1L shl retries)
                }
                android.util.Log.d("RetryInterceptor", "429 on ${originalRequest.url}, sleeping ${delayMs}ms (attempt ${retries+1})")
                response.close()
                Thread.sleep(delayMs)
                val newToken = runBlocking { authManager.getValidAccessToken() }
                val retryRequest = originalRequest.newBuilder()
                    .apply { if (newToken != null) header("Authorization", "Bearer $newToken") }
                    .build()
                response = chain.proceed(retryRequest)
                retries++
            }
        }

        // Handle 401: refresh token and retry once
        if (response.code == 401) {
            response.close()
            val newToken = runBlocking { authManager.getValidAccessToken() }
            val retryRequest = originalRequest.newBuilder()
                .apply { if (newToken != null) header("Authorization", "Bearer $newToken") }
                .build()
            response = chain.proceed(retryRequest)
        }

        return response
    }
}
