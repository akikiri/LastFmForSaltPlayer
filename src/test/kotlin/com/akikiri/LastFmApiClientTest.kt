package com.akikiri

import kotlinx.coroutines.runBlocking
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
                generation = 1,
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
    fun `temporary LastFm error is retried`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":16,"message":"Temporary"}"""))
        server.enqueue(MockResponse().setBody("""{"nowplaying":{}}"""))
        val client = client()

        client.updateNowPlaying(
            sessionKey = "session",
            track = TrackMetadata("Track", "Artist"),
            durationSeconds = null,
        )

        assertEquals(2, server.requestCount)
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
