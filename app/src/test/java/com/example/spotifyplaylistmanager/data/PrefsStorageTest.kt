package com.example.spotifyplaylistmanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsStorageTest {

    private fun storage() = PrefsStorage(FakeSharedPreferences())

    @Test
    fun `saveClientId and getClientId round-trip`() {
        val prefs = storage()
        prefs.saveClientId("abc123")
        assertEquals("abc123", prefs.getClientId())
    }

    @Test
    fun `getClientId returns null when not set`() {
        val prefs = storage()
        assertNull(prefs.getClientId())
    }

    @Test
    fun `saveClientId trims whitespace`() {
        val prefs = storage()
        prefs.saveClientId("  abc123  ")
        assertEquals("abc123", prefs.getClientId())
    }

    @Test
    fun `saveClientId with blank string leaves stored value null`() {
        val prefs = storage()
        prefs.saveClientId("   ")
        assertNull(prefs.getClientId())
    }

    @Test
    fun `clearClientId removes a previously saved clientId`() {
        val prefs = storage()
        prefs.saveClientId("abc123")
        prefs.clearClientId()
        assertNull(prefs.getClientId())
    }
}
