package com.akikiri

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LastFmApiClientTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `scrobble validates accepted response and sends track start timestamp`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"scrobbles":{"@attr":{"accepted":1,"ignored":0}}}""",
            ),
        )
        val client = client()

        client.scrobble(
            sessionKey = "session",
            track = ScrobbleTrack(
                metadata = TrackMetadata("Track", "Artist", "Album", "Album Artist"),
                startedAtEpochSeconds = 1_700_000_000,
                durationSeconds = 180,
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("timestamp=1700000000"))
        assertTrue(body.contains("api_sig="))
        assertFalse(body.contains("shared-secret"))
    }

    @Test
    fun `invalid session response is exposed as error code nine`() {
        server.enqueue(
            MockResponse().setBody("""{"error":9,"message":"Invalid session key"}"""),
        )
        val client = client()

        val exception = assertThrows(LastFmApiException::class.java) {
            runBlocking {
                client.updateNowPlaying(
                    sessionKey = "bad-session",
                    track = TrackMetadata("Track", "Artist"),
                    durationSeconds = 180,
                )
            }
        }

        assertEquals(9, exception.errorCode)
        assertFalse(exception.retryable)
    }

    @Test
    fun `now playing failure is not retried`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":16,"message":"Temporary"}"""))
        server.enqueue(MockResponse().setBody("""{"nowplaying":{}}"""))
        val client = client()

        assertThrows(LastFmApiException::class.java) {
            runBlocking { client.updateNowPlaying("session", TrackMetadata("Track", "Artist"), null) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `filtered now playing is reported as rejection`() {
        server.enqueue(MockResponse().setBody(
            """{"nowplaying":{"ignoredMessage":{"code":"1","#text":"Artist was ignored"}}}""",
        ))
        assertThrows(LastFmApiException::class.java) {
            runBlocking { client().updateNowPlaying("session", TrackMetadata("Track", "Artist"), 40) }
        }
    }

    @Test
    fun `scrobble temporary failure is retried with the same timestamp`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":16,"message":"Temporary"}"""))
        server.enqueue(MockResponse().setBody("""{"scrobbles":{"@attr":{"accepted":1,"ignored":0}}}"""))
        client().scrobble("session", ScrobbleTrack(TrackMetadata("Track", "Artist"), 123, 40))
        assertEquals(2, server.requestCount)
        repeat(2) { assertTrue(server.takeRequest().body.readUtf8().contains("timestamp=123")) }
    }

    @Test
    fun `session response contains key and username`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"session":{"key":"session","name":"listener"}}"""))
        assertEquals(LastFmSession("session", "listener"), client().requestSessionKey("token"))
    }

    @Test
    fun `coroutine cancellation promptly cancels a waiting HTTP call`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                return MockResponse().setBody("""{"nowplaying":{}}""")
            }
        }
        val task = async(kotlinx.coroutines.Dispatchers.IO) {
            client().updateNowPlaying("session", TrackMetadata("Track", "Artist"), 40)
        }
        try {
            org.junit.jupiter.api.Assertions.assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
            kotlinx.coroutines.withTimeout(1_000) { task.cancel(); task.join() }
            assertTrue(task.isCancelled)
        } finally { release.countDown() }
    }

    private fun client(): LastFmApiClient = LastFmApiClient(
        credentials = LastFmCredentials(
            apiKey = "0123456789abcdef0123456789abcdef",
            apiSecret = "shared-secret",
        ),
        apiUrl = server.url("/2.0/"),
        retryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 0),
        jitterMillis = { 0 },
    )
}
