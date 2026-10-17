package com.akikiri

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

internal class MemorySessionStore(var state: AuthState = AuthState()) : SessionStore {
    override fun load() = state
    override fun save(state: AuthState) { this.state = state }
}

internal class MemoryScrobbleStore : ScrobbleStore {
    var records = emptyList<PendingScrobble>()
    var failWrites = false
    override fun load() = records.toList()
    override fun save(records: List<PendingScrobble>) {
        if (failWrites) throw java.io.IOException("Disk unavailable")
        this.records = records.toList()
    }
}

internal open class FakeGateway : LastFmGateway {
    var tokens = 0
    var checks = 0
    @Volatile var closed = false
    @Volatile var sessionError: LastFmApiException? = null
    @Volatile var scrobbleError: LastFmApiException? = null
    val nowPlaying = CopyOnWriteArrayList<TrackMetadata>()
    val submitted = CopyOnWriteArrayList<ScrobbleTrack>()
    override fun authorizationUrl(token: String) = "https://example.invalid/auth?token=$token"
    override suspend fun requestToken(): String { tokens++; return "token-$tokens" }
    override suspend fun requestSessionKey(token: String): LastFmSession {
        checks++
        sessionError?.let { throw it }
        return LastFmSession("session", "listener")
    }
    override suspend fun updateNowPlaying(sessionKey: String, track: TrackMetadata, durationSeconds: Long?) {
        nowPlaying.add(track)
    }
    override suspend fun scrobble(sessionKey: String, track: ScrobbleTrack) {
        submitted.add(track)
        scrobbleError?.let { throw it }
    }
    override fun close() { closed = true }
}

internal class TestClock {
    @Volatile var millis = 0L
    fun tracker() = PlaybackTracker({ 1_000L + millis / 1_000 }, { millis })
    fun advance(millis: Long = 1_000) { this.millis += millis }
}

internal suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(3_000) { while (!condition()) delay(5) }
}
