package com.example.spotifyplaylistmanager.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerTest {

    @Test
    fun `code verifier length is between 43 and 128`() {
        val verifier = AuthManager.generateCodeVerifier()
        assertTrue("Length was ${verifier.length}", verifier.length in 43..128)
    }

    @Test
    fun `code verifier uses only URL-safe base64 characters`() {
        val verifier = AuthManager.generateCodeVerifier()
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `code challenge matches expected SHA256 base64url for known verifier`() {
        // RFC 7636 Appendix B test vector
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = AuthManager.generateCodeChallenge(verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }
}
