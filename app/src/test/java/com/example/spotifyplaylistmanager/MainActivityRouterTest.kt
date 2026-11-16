package com.example.spotifyplaylistmanager

import com.example.spotifyplaylistmanager.auth.AuthManager
import com.example.spotifyplaylistmanager.data.FakeSharedPreferences
import com.example.spotifyplaylistmanager.data.PrefsStorage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityRouterTest {

    @Test
    fun `checkAuthState returns Main when valid token exists`() = runTest {
        val prefs = PrefsStorage(FakeSharedPreferences())
        val auth = mockk<AuthManager>()
        coEvery { auth.getValidAccessToken() } returns "token"
        assertEquals(AppScreen.Main, checkAuthState(prefs, auth))
    }

    @Test
    fun `checkAuthState returns Setup when no clientId and no token`() = runTest {
        val prefs = PrefsStorage(FakeSharedPreferences())
        val auth = mockk<AuthManager>()
        coEvery { auth.getValidAccessToken() } returns null
        assertEquals(AppScreen.Setup, checkAuthState(prefs, auth))
    }

    @Test
    fun `checkAuthState returns Login when clientId exists but no token`() = runTest {
        val prefs = PrefsStorage(FakeSharedPreferences())
        prefs.saveClientId("some-id")
        val auth = mockk<AuthManager>()
        coEvery { auth.getValidAccessToken() } returns null
        assertEquals(AppScreen.Login, checkAuthState(prefs, auth))
    }

    @Test
    fun `checkAuthState returns AuthFailed when getValidAccessToken throws`() = runTest {
        val prefs = PrefsStorage(FakeSharedPreferences())
        prefs.saveClientId("some-id")
        val auth = mockk<AuthManager>()
        coEvery { auth.getValidAccessToken() } throws RuntimeException("network error")
        assertEquals(AppScreen.AuthFailed, checkAuthState(prefs, auth))
    }
}
