package com.akikiri

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthManagerTest {
    @Test fun `saved session is available without a request or browser`() = runBlocking {
        val gateway = FakeGateway()
        val manager = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "saved")),
            BrowserLauncher { error("No browser") })
        assertEquals(SessionAccess.Ready("saved"), manager.session())
        assertEquals(0, gateway.checks)
    }

    @Test fun `opening authorization again reuses the pending token`() = runBlocking {
        val gateway = FakeGateway()
        val store = MemorySessionStore()
        var opened = 0
        val manager = AuthManager(gateway, store, BrowserLauncher { opened++; true }, { 100 })
        manager.beginAuthorization()
        manager.beginAuthorization()
        assertEquals(1, gateway.tokens)
        assertEquals(2, opened)
        assertEquals("token-1", store.state.token)
    }

    @Test fun `browser failure retains a reopenable token`() = runBlocking {
        val gateway = FakeGateway()
        val manager = AuthManager(gateway, MemorySessionStore(), BrowserLauncher { false })
        val access = manager.beginAuthorization() as SessionAccess.WaitingForAuthorization
        assertFalse(access.browserOpened)
        assertTrue(access.authorizationUrl.contains("token-1"))
        assertTrue(manager.session() is SessionAccess.WaitingForAuthorization)
    }

    @Test fun `pending token is checked and exchanged independently of music`() = runBlocking {
        val gateway = FakeGateway()
        val store = MemorySessionStore()
        val manager = AuthManager(gateway, store, BrowserLauncher { true })
        manager.beginAuthorization()
        gateway.sessionError = LastFmApiException(14, "Pending", false)
        assertTrue(manager.checkAuthorization() is SessionAccess.WaitingForAuthorization)
        gateway.sessionError = null
        assertEquals(SessionAccess.Ready("session", "listener"), manager.checkAuthorization())
        assertEquals("listener", store.state.username)
        assertNull(store.state.token)
    }

    @Test fun `expired token is replaced on explicit authorization`() = runBlocking {
        val gateway = FakeGateway()
        val store = MemorySessionStore(AuthState(token = "old", tokenCreatedAtEpochSeconds = 100))
        val manager = AuthManager(gateway, store, BrowserLauncher { true }, { 3_700 })
        assertEquals(SessionAccess.SignedOut, manager.checkAuthorization())
        manager.beginAuthorization()
        assertEquals("token-1", store.state.token)
    }

    @Test fun `temporary network error preserves session and token`() = runBlocking {
        val gateway = FakeGateway()
        val store = MemorySessionStore(AuthState(token = "pending", tokenCreatedAtEpochSeconds = 100))
        val manager = AuthManager(gateway, store, BrowserLauncher { true }, { 200 })
        gateway.sessionError = LastFmApiException(16, "Offline", true)
        assertThrows(LastFmApiException::class.java) { runBlocking { manager.checkAuthorization() } }
        assertEquals("pending", store.state.token)
    }

    @Test fun `reset and stale invalidation cannot clear a newer session`() = runBlocking {
        val gateway = FakeGateway()
        val manager = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "old")), BrowserLauncher { true })
        manager.beginAuthorization(reset = true)
        manager.checkAuthorization()
        assertFalse(manager.invalidateSession("old"))
        assertEquals(SessionAccess.Ready("session", "listener"), manager.session())
    }

    @Test fun `session for another API key is not reused`() = runBlocking {
        val manager = AuthManager(FakeGateway(), MemorySessionStore(AuthState(sessionKey = "saved", applicationKey = "other")),
            BrowserLauncher { true }, applicationKey = "current")
        assertEquals(SessionAccess.SignedOut, manager.session())
    }

    @Test fun `cancelling exchange does not save a late session`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gateway = object : FakeGateway() {
            override suspend fun requestSessionKey(token: String): LastFmSession {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val store = MemorySessionStore()
        val manager = AuthManager(gateway, store, BrowserLauncher { true })
        manager.beginAuthorization()
        val job = launch { manager.checkAuthorization() }
        started.await()
        job.cancelAndJoin()
        assertNull(store.state.sessionKey)
        assertNotNull(store.state.token)
    }
}
