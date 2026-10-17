package com.akikiri

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

internal class LastFmCoordinator(
    private val gateway: LastFmGateway,
    private val authManager: AuthManager,
) {
    suspend fun updateNowPlaying(track: TrackMetadata, durationSeconds: Long?, isCurrent: () -> Boolean)
        : LastFmOperationResult = perform(isCurrent) { gateway.updateNowPlaying(it, track, durationSeconds) }

    suspend fun scrobble(track: ScrobbleTrack): LastFmOperationResult =
        perform({ true }) { gateway.scrobble(it, track) }

    private suspend fun perform(isCurrent: () -> Boolean, operation: suspend (String) -> Unit): LastFmOperationResult {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return LastFmOperationResult.Skipped
        val session = authManager.session() as? SessionAccess.Ready
            ?: return LastFmOperationResult.AuthorizationRequired()
        return try {
            if (!isCurrent()) return LastFmOperationResult.Skipped
            operation(session.sessionKey)
            currentCoroutineContext().ensureActive()
            LastFmOperationResult.Success
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LastFmApiException) {
            if (exception.errorCode == 9) {
                if (authManager.invalidateSession(session.sessionKey)) LastFmOperationResult.AuthorizationRequired(true)
                else LastFmOperationResult.Skipped
            } else LastFmOperationResult.Failed(exception.message.orEmpty(), exception.retryable)
        } catch (exception: IOException) {
            LastFmOperationResult.Failed(exception.message ?: "网络请求失败", true)
        } catch (exception: Exception) {
            LastFmOperationResult.Failed(exception.message ?: "Last.fm 请求失败")
        }
    }
}
