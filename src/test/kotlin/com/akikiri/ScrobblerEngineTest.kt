package com.akikiri

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ScrobblerEngineTest {
    @Test fun `mid track startup bootstraps API and infers playback then ignores lyrics reload`() = runBlocking {
        val gateway = FakeGateway()
        val clock = TestClock()
        val queries = AtomicInteger()
        val reader = PlayerTrackReader { queries.incrementAndGet(); item() }
        val queue = PendingScrobbles(MemoryScrobbleStore())
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val engine = ScrobblerEngine(gateway, auth, queue, reader, {}, clock.tracker(), 20, 50)
        try {
            engine.start()
            awaitCondition { queries.get() > 0 }
            delay(30)
            engine.onPosition(0)
            for (second in 1..20) { clock.advance(); engine.onPosition(second * 1_000L) }
            awaitCondition { gateway.submitted.size == 1 && gateway.nowPlaying.size == 1 }
            engine.refresh()
            delay(40)
            for (second in 21..40) { clock.advance(); engine.onPosition(second * 1_000L) }
            delay(40)
            assertEquals(1, gateway.submitted.size)
            assertEquals(1, gateway.nowPlaying.size)
        } finally { engine.close() }
        assertTrue(gateway.closed)
    }

    @Test fun `scrobble collected before login is flushed automatically after authorization`() = runBlocking {
        val gateway = FakeGateway()
        val clock = TestClock()
        val queue = PendingScrobbles(MemoryScrobbleStore())
        val queries = AtomicInteger()
        val auth = AuthManager(gateway, MemorySessionStore(), BrowserLauncher { true })
        val engine = ScrobblerEngine(gateway, auth, queue,
            PlayerTrackReader { queries.incrementAndGet(); item() }, {}, clock.tracker(), 20, 50, 5)
        try {
            engine.start()
            awaitCondition { queries.get() > 0 }
            delay(30)
            engine.onPlayingChanged(true)
            delay(40)
            engine.onPosition(0)
            for (second in 1..20) { clock.advance(); engine.onPosition(second * 1_000L) }
            assertEquals(1, queue.size())
            assertEquals(0, gateway.submitted.size)
            engine.openAuthorization()
            awaitCondition { queue.size() == 0 && gateway.submitted.size == 1 }
        } finally { engine.close() }
    }

    @Test fun `closed engine does not query player or send new operations`() = runBlocking {
        val gateway = FakeGateway()
        val queries = AtomicInteger()
        val auth = AuthManager(gateway, MemorySessionStore(), BrowserLauncher { true })
        val engine = ScrobblerEngine(gateway, auth, PendingScrobbles(MemoryScrobbleStore()),
            PlayerTrackReader { queries.incrementAndGet(); item() }, {})
        engine.close()
        engine.onPosition(10_000); engine.onPlayingChanged(true); engine.refresh(); engine.openAuthorization()
        delay(20)
        assertEquals(0, queries.get())
        assertEquals(0, gateway.tokens)
    }

    @Test fun `same song refresh preserves an in flight now playing request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val gateway = object : FakeGateway() {
            override suspend fun updateNowPlaying(sessionKey: String, track: TrackMetadata, durationSeconds: Long?) {
                started.complete(Unit)
                release.await()
                super.updateNowPlaying(sessionKey, track, durationSeconds)
                finished.complete(Unit)
            }
        }
        val queries = AtomicInteger()
        val clock = TestClock()
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val engine = ScrobblerEngine(gateway, auth, PendingScrobbles(MemoryScrobbleStore()),
            PlayerTrackReader { queries.incrementAndGet(); item() }, {}, clock.tracker(), 100_000)
        try {
            engine.start(); awaitCondition { queries.get() > 0 }; delay(30)
            engine.onPosition(0); clock.advance(); engine.onPosition(1_000)
            withTimeout(1_000) { started.await() }
            engine.refresh(); awaitCondition { queries.get() > 1 }; delay(20)
            release.complete(Unit)
            withTimeout(1_000) { finished.await() }
            assertEquals(1, gateway.nowPlaying.size)
        } finally { engine.close() }
    }

    @Test fun `track change cancels old now playing and sends only current metadata`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gateway = object : FakeGateway() {
            override suspend fun updateNowPlaying(sessionKey: String, track: TrackMetadata, durationSeconds: Long?) {
                if (track.title == "Track") {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                } else super.updateNowPlaying(sessionKey, track, durationSeconds)
            }
        }
        val queries = AtomicInteger()
        val current = java.util.concurrent.atomic.AtomicReference(item())
        val clock = TestClock()
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val engine = ScrobblerEngine(gateway, auth, PendingScrobbles(MemoryScrobbleStore()),
            PlayerTrackReader { queries.incrementAndGet(); current.get() }, {}, clock.tracker(), 100_000)
        try {
            engine.start(); awaitCondition { queries.get() > 0 }; delay(30)
            engine.onPosition(0); clock.advance(); engine.onPosition(1_000)
            withTimeout(1_000) { started.await() }
            current.set(item().copy(title = "Current", id = "current"))
            engine.refresh()
            withTimeout(1_000) { cancelled.await() }
            delay(20)
            engine.onPosition(0); clock.advance(); engine.onPosition(1_000)
            awaitCondition { gateway.nowPlaying.size == 1 }
            assertEquals("Current", gateway.nowPlaying.single().title)
        } finally { engine.close() }
    }

    @Test fun `query finishing after stop cannot create playback or send requests`() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val gateway = FakeGateway()
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val engine = ScrobblerEngine(gateway, auth, PendingScrobbles(MemoryScrobbleStore()),
            PlayerTrackReader {
                entered.countDown()
                check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                returned.countDown()
                item()
            }, {})
        try {
            engine.start()
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            engine.close()
            release.countDown()
            assertTrue(returned.await(1, java.util.concurrent.TimeUnit.SECONDS))
            engine.onPlayingChanged(true); engine.onPosition(10_000)
            delay(20)
            assertTrue(gateway.nowPlaying.isEmpty())
            assertTrue(gateway.submitted.isEmpty())
        } finally { release.countDown(); engine.close() }
    }

    @Test fun `library only suppresses outside scrobbles but not now playing and disabling applies immediately`() = runBlocking {
        val gateway = FakeGateway()
        val clock = TestClock()
        val tracker = clock.tracker()
        val queue = PendingScrobbles(MemoryScrobbleStore())
        val queries = AtomicInteger()
        val policy = ScrobblePolicy {
            queries.incrementAndGet()
            java.util.concurrent.CompletableFuture.completedFuture(null)
        }
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val engine = ScrobblerEngine(gateway, auth, queue, PlayerTrackReader { item() }, {},
            tracker, refreshMillis = 100_000, policy = policy)
        try {
            engine.setLibraryOnly(true)
            engine.start()
            awaitCondition { queries.get() > 0 }
            delay(30)
            engine.onPosition(0)
            for (second in 1..25) { clock.advance(); engine.onPosition(second * 1_000L) }
            awaitCondition { gateway.nowPlaying.size == 1 }
            assertEquals(0, queue.size())
            assertTrue(gateway.submitted.isEmpty())
            assertNull(tracker.eligibleScrobble())
            engine.setLibraryOnly(false)
            awaitCondition { gateway.submitted.size == 1 }
            assertEquals("id", gateway.submitted.single().libraryTrackId)
            assertEquals(1, gateway.nowPlaying.size)
            assertEquals(0, queue.size())
        } finally { engine.close() }
    }

    @Test fun `library membership failure does not break now playing and recovery permits scrobble`() = runBlocking {
        val gateway = FakeGateway()
        val clock = TestClock()
        val tracker = clock.tracker()
        val queries = AtomicInteger()
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val policy = ScrobblePolicy {
            queries.incrementAndGet()
            if (fail.get()) java.util.concurrent.CompletableFuture.failedFuture(java.io.IOException("Unavailable"))
            else java.util.concurrent.CompletableFuture.completedFuture(item())
        }
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(sessionKey = "session")), BrowserLauncher { true })
        val queue = PendingScrobbles(MemoryScrobbleStore())
        val engine = ScrobblerEngine(gateway, auth, queue, PlayerTrackReader { item() }, {},
            tracker, refreshMillis = 100_000, policy = policy)
        try {
            engine.setLibraryOnly(true)
            engine.start()
            awaitCondition { queries.get() > 0 }
            delay(30)
            engine.onPosition(0)
            for (second in 1..25) { clock.advance(); engine.onPosition(second * 1_000L) }
            awaitCondition { gateway.nowPlaying.size == 1 }
            assertTrue(gateway.submitted.isEmpty())
            assertEquals(0, queue.size())
            fail.set(false)
            engine.refresh()
            awaitCondition { gateway.submitted.size == 1 }
            assertEquals("id", gateway.submitted.single().libraryTrackId)
        } finally { engine.close() }
    }

    private fun item() = MediaItem("Track", "Artist", "Album", "Artist", "Z:\\missing.flac", id = "id", duration = 40_000)
}
