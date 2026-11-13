package com.example.spotifyplaylistmanager.api

import com.example.spotifyplaylistmanager.auth.AuthManager
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object SpotifyClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun create(authManager: AuthManager): SpotifyApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(authManager))
            .addInterceptor(RetryInterceptor(authManager))
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.spotify.com/v1/")
            .client(client)
            .addConverterFactory(
                json.asConverterFactory("application/json; charset=UTF-8".toMediaType())
            )
            .build()
            .create(SpotifyApi::class.java)
    }
}
