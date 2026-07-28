package com.akikiri

internal interface LastFmGateway {
    fun authorizationUrl(token: String): String

    suspend fun requestToken(): String

    suspend fun requestSessionKey(token: String): String

    suspend fun updateNowPlaying(
        sessionKey: String,
        track: TrackMetadata,
        durationSeconds: Long?,
    )

    suspend fun scrobble(sessionKey: String, track: ScrobbleTrack)
}
