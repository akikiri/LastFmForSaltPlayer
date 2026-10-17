package com.akikiri

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ScrobbleSubmitter(
    private val queue: PendingScrobbles,
    private val coordinator: LastFmCoordinator,
    private val report: (LastFmOperationResult) -> Unit,
    private val policy: ScrobblePolicy = ScrobblePolicy(),
) {
    private val mutex = Mutex()

    suspend fun flush() = mutex.withLock {
        queue.flushStorage()
        for (track in queue.ready()) {
            if (!policy.allows(track.libraryTrackId)) continue
            val result = coordinator.scrobble(track)
            when (result) {
                LastFmOperationResult.Success -> queue.confirm(track.id)
                is LastFmOperationResult.Failed -> {
                    if (!result.retryable) queue.block(track.id, result.message)
                    report(result)
                    if (result.retryable) return@withLock
                }
                is LastFmOperationResult.AuthorizationRequired -> {
                    report(result)
                    return@withLock
                }
                LastFmOperationResult.Skipped -> return@withLock
            }
        }
    }
}
