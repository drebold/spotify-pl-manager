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

        when (response.code) {
            401 -> {
                response.close()
                runBlocking { authManager.getValidAccessToken() }
                val newToken = runBlocking { authManager.getValidAccessToken() }
                val retryRequest = originalRequest.newBuilder()
                    .apply { if (newToken != null) header("Authorization", "Bearer $newToken") }
                    .build()
                response = chain.proceed(retryRequest)
            }
            429 -> {
                val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                val delayMs = if (retryAfterSeconds != null) retryAfterSeconds * 1000L
                              else defaultRetryAfterMs
                response.close()
                Thread.sleep(delayMs)
                response = chain.proceed(originalRequest)
            }
        }

        return response
    }
}
