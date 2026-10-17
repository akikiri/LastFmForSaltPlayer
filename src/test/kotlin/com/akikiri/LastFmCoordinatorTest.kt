package com.akikiri

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LastFmCoordinatorTest {
    @Test fun `scrobble waiting on network never blocks current now playing`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gateway = object : FakeGateway() {
            override suspend fun scrobble(sessionKey: String, track: ScrobbleTrack) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val coordinator = LastFmCoordinator(gateway, auth)
        val task = launch { coordinator.scrobble(ScrobbleTrack(TrackMetadata("Old", "Artist"), 123, 40)) }
        try {
            started.await()
            val result = withTimeout(500) { coordinator.updateNowPlaying(TrackMetadata("Current", "Artist"), 40) { true } }
            assertEquals(LastFmOperationResult.Success, result)
        } finally { task.cancelAndJoin() }
    }

    @Test fun `stale failure after reauthorization does not invalidate new login`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = object : FakeGateway() {
            override suspend fun updateNowPlaying(sessionKey: String, track: TrackMetadata, durationSeconds: Long?) {
                started.complete(Unit)
                release.await()
                throw LastFmApiException(9, "Old session revoked", false)
            }
        }
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "old")), BrowserLauncher { true })
        val coordinator = LastFmCoordinator(gateway, auth)
        val result = async { coordinator.updateNowPlaying(TrackMetadata("Track", "Artist"), 40) { true } }
        started.await()
        auth.beginAuthorization(reset = true)
        auth.checkAuthorization()
        release.complete(Unit)
        assertEquals(LastFmOperationResult.Skipped, result.await())
        assertEquals(SessionAccess.Ready("session", "listener"), auth.session())
    }

    @Test fun `stale now playing is skipped before touching the network`() = runBlocking {
        val gateway = FakeGateway()
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val result = LastFmCoordinator(gateway, auth).updateNowPlaying(TrackMetadata("Old", "Artist"), 40) { false }
        assertEquals(LastFmOperationResult.Skipped, result)
        assertTrue(gateway.nowPlaying.isEmpty())
    }
}
