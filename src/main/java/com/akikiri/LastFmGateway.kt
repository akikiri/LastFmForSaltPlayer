package com.akikiri

internal interface LastFmGateway : AutoCloseable {
    fun authorizationUrl(token: String): String

    suspend fun requestToken(): String

    suspend fun requestSessionKey(token: String): LastFmSession

    suspend fun updateNowPlaying(
        sessionKey: String,
        track: TrackMetadata,
        durationSeconds: Long?,
    )

    suspend fun scrobble(sessionKey: String, track: ScrobbleTrack)

    override fun close() = Unit
}
