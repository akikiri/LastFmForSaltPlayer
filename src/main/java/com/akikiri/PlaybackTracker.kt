package com.akikiri

import kotlin.math.min

internal class PlaybackTracker(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private var generation = 0L
    private var playerIsPlaying = false
    private var current: TrackState? = null

    @Synchronized
    fun beginTrack(metadata: TrackMetadata): TrackSnapshot {
        generation += 1
        current = TrackState(
            generation = generation,
            metadata = metadata,
            startedAtEpochSeconds = nowEpochSeconds(),
            isPlaying = playerIsPlaying,
        )
        return checkNotNull(current).snapshot()
    }

    @Synchronized
    fun setDuration(generation: Long, durationSeconds: Long?): ScrobbleTrack? {
        val state = current?.takeIf { it.generation == generation } ?: return null
        state.durationSeconds = durationSeconds?.takeIf { it > 0 }
        return state.takeScrobbleIfEligible()
    }

    @Synchronized
    fun onPlayingChanged(isPlaying: Boolean) {
        playerIsPlaying = isPlaying
        current?.isPlaying = isPlaying
    }

    @Synchronized
    fun onSeek(positionMillis: Long) {
        current?.lastPositionMillis = positionMillis.coerceAtLeast(0)
    }

    @Synchronized
    fun onPosition(positionMillis: Long): ScrobbleTrack? {
        val state = current ?: return null
        val safePosition = positionMillis.coerceAtLeast(0)
        val previousPosition = state.lastPositionMillis

        if (previousPosition != null && state.isPlaying) {
            val delta = safePosition - previousPosition
            if (delta in 1..MAX_CONTIGUOUS_ADVANCE_MILLIS) {
                state.listenedMillis += delta
            }
        }
        state.lastPositionMillis = safePosition

        return state.takeScrobbleIfEligible()
    }

    @Synchronized
    fun currentGeneration(): Long? = current?.generation

    @Synchronized
    fun isCurrent(generation: Long): Boolean = current?.generation == generation

    @Synchronized
    fun clear() {
        generation += 1
        current = null
        playerIsPlaying = false
    }

    private fun TrackState.takeScrobbleIfEligible(): ScrobbleTrack? {
        val duration = durationSeconds ?: return null
        if (scrobbleScheduled || duration <= MIN_TRACK_LENGTH_SECONDS) {
            return null
        }

        val thresholdSeconds = min(duration / 2.0, MAX_REQUIRED_PLAY_SECONDS.toDouble())
        if (listenedMillis < thresholdSeconds * 1000) {
            return null
        }

        scrobbleScheduled = true
        return ScrobbleTrack(
            generation = generation,
            metadata = metadata,
            startedAtEpochSeconds = startedAtEpochSeconds,
            durationSeconds = duration,
        )
    }

    private data class TrackState(
        val generation: Long,
        val metadata: TrackMetadata,
        val startedAtEpochSeconds: Long,
        var durationSeconds: Long? = null,
        var isPlaying: Boolean,
        var lastPositionMillis: Long? = null,
        var listenedMillis: Long = 0,
        var scrobbleScheduled: Boolean = false,
    ) {
        fun snapshot(): TrackSnapshot = TrackSnapshot(
            generation = generation,
            metadata = metadata,
            startedAtEpochSeconds = startedAtEpochSeconds,
            durationSeconds = durationSeconds,
        )
    }

    companion object {
        private const val MIN_TRACK_LENGTH_SECONDS = 30
        private const val MAX_REQUIRED_PLAY_SECONDS = 240
        private const val MAX_CONTIGUOUS_ADVANCE_MILLIS = 2_500L
    }
}

internal data class TrackSnapshot(
    val generation: Long,
    val metadata: TrackMetadata,
    val startedAtEpochSeconds: Long,
    val durationSeconds: Long?,
)
