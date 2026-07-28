package com.akikiri

import java.io.IOException

internal data class LastFmCredentials(
    val apiKey: String,
    val apiSecret: String,
)

internal data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val albumArtist: String = "",
) {
    val isValid: Boolean
        get() = title.isNotBlank() && artist.isNotBlank()
}

internal data class ScrobbleTrack(
    val generation: Long,
    val metadata: TrackMetadata,
    val startedAtEpochSeconds: Long,
    val durationSeconds: Long,
)

internal class LastFmApiException(
    val errorCode: Int?,
    message: String,
    val retryable: Boolean,
) : IOException(message)

internal data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 750,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(baseDelayMillis >= 0) { "baseDelayMillis cannot be negative" }
    }
}

internal sealed interface SessionAccess {
    data class Ready(val sessionKey: String) : SessionAccess

    data class WaitingForAuthorization(
        val authorizationUrl: String,
        val browserOpened: Boolean,
        val newlyCreatedToken: Boolean,
    ) : SessionAccess
}

internal sealed interface LastFmOperationResult {
    data object Success : LastFmOperationResult
    data object Skipped : LastFmOperationResult
    data class AuthorizationRequired(val access: SessionAccess.WaitingForAuthorization) :
        LastFmOperationResult

    data class Failed(val message: String) : LastFmOperationResult
}
