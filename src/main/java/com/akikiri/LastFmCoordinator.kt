package com.akikiri

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LastFmCoordinator(
    private val gateway: LastFmGateway,
    private val authManager: AuthManager,
) {
    private val operationMutex = Mutex()

    suspend fun updateNowPlaying(
        track: TrackMetadata,
        durationSeconds: Long?,
        isCurrent: () -> Boolean,
    ): LastFmOperationResult = operationMutex.withLock {
        perform(isCurrent) { sessionKey ->
            gateway.updateNowPlaying(sessionKey, track, durationSeconds)
        }
    }

    suspend fun scrobble(track: ScrobbleTrack): LastFmOperationResult =
        operationMutex.withLock {
            perform(isCurrent = { true }) { sessionKey ->
                gateway.scrobble(sessionKey, track)
            }
        }

    private suspend fun perform(
        isCurrent: () -> Boolean,
        operation: suspend (String) -> Unit,
    ): LastFmOperationResult {
        if (!isCurrent()) {
            return LastFmOperationResult.Skipped
        }

        return try {
            when (val access = authManager.session()) {
                is SessionAccess.Ready -> {
                    if (!isCurrent()) {
                        LastFmOperationResult.Skipped
                    } else {
                        operation(access.sessionKey)
                        LastFmOperationResult.Success
                    }
                }

                is SessionAccess.WaitingForAuthorization ->
                    LastFmOperationResult.AuthorizationRequired(access)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LastFmApiException) {
            if (exception.errorCode == INVALID_SESSION_KEY) {
                authManager.invalidateSession()
            }
            LastFmOperationResult.Failed(exception.message ?: "Last.fm 请求失败")
        } catch (exception: Exception) {
            LastFmOperationResult.Failed(exception.message ?: "Last.fm 请求失败")
        }
    }

    companion object {
        private const val INVALID_SESSION_KEY = 9
    }
}
