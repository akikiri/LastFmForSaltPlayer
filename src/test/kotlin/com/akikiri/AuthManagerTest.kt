package com.akikiri

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthManagerTest {
    @Test
    fun `existing session is reused without network calls`() = runBlocking {
        val gateway = FakeGateway()
        val manager = AuthManager(
            gateway,
            MemorySessionStore(AuthState(sessionKey = "saved-session")),
            BrowserLauncher { error("browser should not open") },
        )

        assertEquals(SessionAccess.Ready("saved-session"), manager.session())
        assertEquals(0, gateway.sessionRequests)
    }

    @Test
    fun `new token is stored and browser is opened once`() = runBlocking {
        val store = MemorySessionStore()
        var openedUrl: String? = null
        val manager = AuthManager(
            FakeGateway(),
            store,
            BrowserLauncher { url ->
                openedUrl = url
                true
            },
            nowEpochSeconds = { 100 },
        )

        val result = manager.session() as SessionAccess.WaitingForAuthorization

        assertTrue(result.newlyCreatedToken)
        assertTrue(result.browserOpened)
        assertEquals(result.authorizationUrl, openedUrl)
        assertEquals("new-token", store.state.token)
        assertEquals(100, store.state.tokenCreatedAtEpochSeconds)
    }

    @Test
    fun `unauthorized token is preserved without reopening browser`() = runBlocking {
        val store = MemorySessionStore(
            AuthState(token = "pending", tokenCreatedAtEpochSeconds = 100),
        )
        val gateway = FakeGateway(sessionError = LastFmApiException(14, "pending", false))
        val manager = AuthManager(
            gateway,
            store,
            BrowserLauncher { error("browser should not reopen") },
            nowEpochSeconds = { 200 },
        )

        val result = manager.session() as SessionAccess.WaitingForAuthorization

        assertFalse(result.newlyCreatedToken)
        assertFalse(result.browserOpened)
        assertEquals("pending", store.state.token)
    }

    @Test
    fun `authorized token is exchanged and cleared`() = runBlocking {
        val store = MemorySessionStore(
            AuthState(token = "authorized", tokenCreatedAtEpochSeconds = 100),
        )
        val manager = AuthManager(
            FakeGateway(sessionKey = "session-key"),
            store,
            BrowserLauncher { false },
            nowEpochSeconds = { 200 },
        )

        assertEquals(SessionAccess.Ready("session-key"), manager.session())
        assertEquals(AuthState(sessionKey = "session-key"), store.state)
    }

    private class MemorySessionStore(
        var state: AuthState = AuthState(),
    ) : SessionStore {
        override fun load(): AuthState = state

        override fun save(state: AuthState) {
            this.state = state
        }
    }

    private class FakeGateway(
        private val sessionKey: String = "session-key",
        private val sessionError: LastFmApiException? = null,
    ) : LastFmGateway {
        var sessionRequests = 0

        override fun authorizationUrl(token: String): String = "https://auth.example/$token"

        override suspend fun requestToken(): String = "new-token"

        override suspend fun requestSessionKey(token: String): String {
            sessionRequests += 1
            sessionError?.let { throw it }
            return sessionKey
        }

        override suspend fun updateNowPlaying(
            sessionKey: String,
            track: TrackMetadata,
            durationSeconds: Long?,
        ) = Unit

        override suspend fun scrobble(sessionKey: String, track: ScrobbleTrack) = Unit
    }
}
