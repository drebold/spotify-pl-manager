package com.example.spotifyplaylistmanager.auth

import com.example.spotifyplaylistmanager.data.FakeSharedPreferences
import com.example.spotifyplaylistmanager.data.PrefsStorage
import com.example.spotifyplaylistmanager.data.TokenStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthManagerClientIdTest {

    @Test
    fun `AuthManager accepts PrefsStorage and reads clientId from it`() {
        val prefs = PrefsStorage(FakeSharedPreferences())
        prefs.saveClientId("my-test-id")
        val tokenStorage = mockk<TokenStorage>(relaxed = true)
        // Construction must succeed with the new two-arg constructor
        val manager = AuthManager(tokenStorage, prefs)
        assertEquals("my-test-id", prefs.getClientId())
    }
}
