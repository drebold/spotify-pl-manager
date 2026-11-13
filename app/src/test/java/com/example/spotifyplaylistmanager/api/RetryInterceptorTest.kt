package com.example.spotifyplaylistmanager.api

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import com.example.spotifyplaylistmanager.auth.AuthManager
import com.example.spotifyplaylistmanager.data.TokenStorage

class RetryInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var authManager: AuthManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val tokenStorage = mockk<TokenStorage>(relaxed = true)
        authManager = mockk<AuthManager>(relaxed = true)
        coEvery { authManager.getValidAccessToken() } returns "test-token"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(authManager))
            .addInterceptor(RetryInterceptor(authManager, defaultRetryAfterMs = 100))
            .build()
    }

    @Test
    fun `on 401 refreshes token and retries once`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = buildClient()
        val request = Request.Builder().url(server.url("/test")).build()
        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `on 401 does not retry more than once`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val client = buildClient()
        val request = Request.Builder().url(server.url("/test")).build()
        val response = client.newCall(request).execute()

        assertEquals(401, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `on 429 with Retry-After header waits and retries`() {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val start = System.currentTimeMillis()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(authManager))
            .addInterceptor(RetryInterceptor(authManager, defaultRetryAfterMs = 100))
            .build()
        val request = Request.Builder().url(server.url("/test")).build()
        val response = client.newCall(request).execute()
        val elapsed = System.currentTimeMillis() - start

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        // Retry-After: 1 means 1 second; allow some slack
        assert(elapsed >= 900) { "Expected at least 900ms delay, got $elapsed" }
    }

    @Test
    fun `on 429 without Retry-After uses default delay`() {
        server.enqueue(MockResponse().setResponseCode(429)) // no Retry-After header
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val start = System.currentTimeMillis()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(authManager))
            .addInterceptor(RetryInterceptor(authManager, defaultRetryAfterMs = 200))
            .build()
        val request = Request.Builder().url(server.url("/test")).build()
        val response = client.newCall(request).execute()
        val elapsed = System.currentTimeMillis() - start

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        assert(elapsed >= 150) { "Expected at least 150ms, got $elapsed" }
    }

    @Test
    fun `on 429 with invalid Retry-After uses default delay`() {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "not-a-number"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(authManager))
            .addInterceptor(RetryInterceptor(authManager, defaultRetryAfterMs = 100))
            .build()
        val request = Request.Builder().url(server.url("/test")).build()
        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }
}
