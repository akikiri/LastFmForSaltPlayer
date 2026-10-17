package com.akikiri

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PendingScrobblesTest {
    @TempDir lateinit var directory: Path
    private fun track(timestamp: Long = 100) = ScrobbleTrack(TrackMetadata("Track", "Artist"), timestamp, 40)

    @Test fun `queue survives restart and confirms only accepted records`() {
        val store = FileScrobbleStore(directory.resolve("pending.json"))
        val track = track()
        PendingScrobbles(store).enqueue(track)
        val restored = PendingScrobbles(store)
        assertEquals(listOf(track), restored.ready())
        restored.confirm(track.id)
        restored.enqueue(track)
        assertEquals(0, restored.size())
        assertEquals(0, PendingScrobbles(store).size())
    }

    @Test fun `temporary failure retains record and next successful flush confirms it`() = runBlocking {
        val queue = PendingScrobbles(MemoryScrobbleStore())
        val track = track()
        queue.enqueue(track)
        val gateway = FakeGateway()
        gateway.scrobbleError = LastFmApiException(16, "Offline", true)
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val submitter = ScrobbleSubmitter(queue, LastFmCoordinator(gateway, auth), {})
        submitter.flush()
        assertEquals(listOf(track), queue.ready())
        gateway.scrobbleError = null
        submitter.flush()
        assertEquals(0, queue.size())
        assertEquals(listOf(track.id, track.id), gateway.submitted.map { it.id })
    }

    @Test fun `invalid session retains record through reauthorization`() = runBlocking {
        val queue = PendingScrobbles(MemoryScrobbleStore())
        queue.enqueue(track())
        val gateway = FakeGateway()
        gateway.scrobbleError = LastFmApiException(9, "Invalid session", false)
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "old")), BrowserLauncher { true })
        val submitter = ScrobbleSubmitter(queue, LastFmCoordinator(gateway, auth), {})
        submitter.flush()
        assertEquals(1, queue.size())
        assertEquals(SessionAccess.SignedOut, auth.session())
        auth.beginAuthorization()
        auth.checkAuthorization()
        gateway.scrobbleError = null
        submitter.flush()
        assertEquals(0, queue.size())
    }

    @Test fun `permanent rejection is retained but not automatically retried`() = runBlocking {
        val queue = PendingScrobbles(MemoryScrobbleStore())
        queue.enqueue(track())
        val gateway = FakeGateway()
        gateway.scrobbleError = LastFmApiException(null, "Ignored", false)
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val submitter = ScrobbleSubmitter(queue, LastFmCoordinator(gateway, auth), {})
        submitter.flush()
        submitter.flush()
        assertEquals(1, gateway.submitted.size)
        assertEquals(1, queue.size())
        queue.retryBlocked()
        gateway.scrobbleError = null
        submitter.flush()
        assertEquals(0, queue.size())
    }

    @Test fun `disk failure preserves in memory and duplicate save retries persistence`() {
        val store = MemoryScrobbleStore()
        val queue = PendingScrobbles(store)
        val track = track()
        store.failWrites = true
        assertThrows(java.io.IOException::class.java) { queue.enqueue(track) }
        assertEquals(listOf(track), queue.ready())
        store.failWrites = false
        queue.enqueue(track)
        assertEquals(listOf(track), PendingScrobbles(store).ready())
    }

    @Test fun `older cached records are submitted first`() = runBlocking {
        val queue = PendingScrobbles(MemoryScrobbleStore())
        queue.enqueue(track(200)); queue.enqueue(track(100))
        val gateway = FakeGateway()
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        ScrobbleSubmitter(queue, LastFmCoordinator(gateway, auth), {}).flush()
        assertEquals(listOf(100L, 200L), gateway.submitted.map { it.startedAtEpochSeconds })
    }

    @Test fun `failed local confirmation never resends a known accepted record`() = runBlocking {
        val store = MemoryScrobbleStore()
        val queue = PendingScrobbles(store)
        queue.enqueue(track())
        val gateway = object : FakeGateway() {
            override suspend fun scrobble(sessionKey: String, track: ScrobbleTrack) {
                super.scrobble(sessionKey, track)
                store.failWrites = true
            }
        }
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val submitter = ScrobbleSubmitter(queue, LastFmCoordinator(gateway, auth), {})
        assertThrows(java.io.IOException::class.java) { runBlocking { submitter.flush() } }
        assertTrue(queue.ready().isEmpty())
        store.failWrites = false
        submitter.flush()
        assertEquals(1, gateway.submitted.size)
        assertEquals(0, PendingScrobbles(store).size())
    }

    @Test fun `library filter retains excluded and legacy records without blocking later library songs`() = runBlocking {
        val store = FileScrobbleStore(directory.resolve("filtered.json"))
        val outside = track(100).copy(libraryTrackId = "outside")
        val legacy = track(200)
        val library = track(300).copy(libraryTrackId = "library")
        val queue = PendingScrobbles(store)
        listOf(outside, legacy, library).forEach(queue::enqueue)
        val gateway = FakeGateway()
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val policy = ScrobblePolicy {
            java.util.concurrent.CompletableFuture.completedFuture(
                if (it == "library") com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem(
                    "Track", "Artist", "", "", "", id = "library") else null)
        }
        policy.libraryOnly = true
        val submitter = ScrobbleSubmitter(queue, LastFmCoordinator(gateway, auth), {}, policy)
        submitter.flush()
        assertEquals(listOf(library), gateway.submitted)
        assertEquals(listOf(outside, legacy), PendingScrobbles(store).ready())
        policy.libraryOnly = false
        submitter.flush()
        assertEquals(listOf(library, outside, legacy), gateway.submitted)
        assertEquals(0, queue.size())
    }

    @Test fun `library query failure preserves pending record and can be retried`() = runBlocking {
        val queue = PendingScrobbles(MemoryScrobbleStore())
        val record = track().copy(libraryTrackId = "library")
        queue.enqueue(record)
        val gateway = FakeGateway()
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val policy = ScrobblePolicy {
            java.util.concurrent.CompletableFuture.failedFuture(java.io.IOException("Library unavailable"))
        }
        policy.libraryOnly = true
        val submitter = ScrobbleSubmitter(queue, LastFmCoordinator(gateway, auth), {}, policy)
        assertThrows(java.io.IOException::class.java) { runBlocking { submitter.flush() } }
        assertTrue(gateway.submitted.isEmpty())
        assertEquals(listOf(record), queue.ready())
        policy.libraryOnly = false
        submitter.flush()
        assertEquals(listOf(record), gateway.submitted)
        assertEquals(0, queue.size())
    }
}
