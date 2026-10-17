package com.akikiri

import java.util.UUID
import kotlin.math.min

internal data class NowPlayingTrack(val playId: String, val track: PlayerTrack)

internal class PlaybackTracker(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var playing: Boolean? = null
    private var current: Play? = null

    @Synchronized fun selectTrack(id: String, track: PlayerTrack?): Boolean {
        val old = current
        if (old?.trackId == id) { old.track = track; return false }
        current = Play(trackId = id, track = track)
        return true
    }

    @Synchronized fun onPlayingChanged(value: Boolean) {
        val wasPlaying = playing
        if (wasPlaying == value && current?.ended != true) return
        playing = value
        var play = current ?: return
        if (value && play.ended) {
            play = Play(trackId = play.trackId, track = play.track)
            current = play
        }
        play.lastPosition = null
        if (value) {
            if (play.startedAt == null) play.startedAt = nowEpochSeconds()
            if (wasPlaying != true) play.nowPlayingPending = true
        }
    }

    @Synchronized fun onPosition(positionMillis: Long) {
        var play = current ?: return
        val position = positionMillis.coerceAtLeast(0)
        val time = nowMillis()
        val epoch = nowEpochSeconds()
        val previous = play.lastPosition
        if (previous != null && play.ended && playing == null && position > previous) {
            play = Play(trackId = play.trackId, track = play.track, startedAt = play.lastEpoch,
                lastPosition = previous, lastTime = play.lastTime, lastEpoch = play.lastEpoch)
            current = play
        }
        if (previous != null && playing != false && !play.ended) {
            val advance = position - previous
            val elapsed = (time - play.lastTime).coerceAtLeast(0)
            if (advance > 0) {
                if (playing == null) playing = true
                if (play.startedAt == null) play.startedAt = play.lastEpoch
                // Count real time supported by position progress, including delayed callbacks.
                play.listenedMillis += min(advance, elapsed)
            } else if (advance < 0 && position <= 2_000 &&
                play.track?.durationMillis?.let { previous >= it - 2_000 } == true) {
                // Repeat playback without an Ended callback. Explicit seeks reset the baseline separately.
                play = Play(trackId = play.trackId, track = play.track, startedAt = epoch)
                current = play
            }
        }
        play.lastPosition = position
        play.lastTime = time
        play.lastEpoch = epoch
    }

    @Synchronized fun onSeek(positionMillis: Long) {
        current?.apply {
            lastPosition = positionMillis.coerceAtLeast(0)
            lastTime = nowMillis()
            lastEpoch = nowEpochSeconds()
        }
    }

    @Synchronized fun end() {
        playing = false
        current?.apply { ended = true; lastPosition = null }
    }

    @Synchronized fun ready() {
        if (current?.ended == true) playing = null
    }

    @Synchronized fun takeNowPlaying(): NowPlayingTrack? {
        val play = current ?: return null
        val track = play.track ?: return null
        if (playing != true || play.ended || play.startedAt == null || !play.nowPlayingPending) return null
        play.nowPlayingPending = false
        return NowPlayingTrack(play.id, track)
    }

    @Synchronized fun requestNowPlaying() { current?.nowPlayingPending = true }
    @Synchronized fun isPlaying(playId: String): Boolean =
        current?.let { it.id == playId && !it.ended && playing == true } == true

    @Synchronized fun eligibleScrobble(): ScrobbleTrack? {
        val play = current ?: return null
        val track = play.track ?: return null
        val startedAt = play.startedAt ?: return null
        if (play.queued || !track.scrobbleAllowed || track.durationMillis <= 30_000) return null
        if (play.listenedMillis < min(track.durationMillis / 2.0, 240_000.0)) return null
        return ScrobbleTrack(track.metadata, startedAt, track.durationMillis / 1_000, play.id, play.trackId)
    }

    @Synchronized fun markQueued(playId: String) {
        current?.takeIf { it.id == playId }?.queued = true
    }

    @Synchronized fun clearSelection() { current = null }

    private data class Play(
        val trackId: String,
        var track: PlayerTrack?,
        val id: String = UUID.randomUUID().toString(),
        var startedAt: Long? = null,
        var lastPosition: Long? = null,
        var lastTime: Long = 0,
        var lastEpoch: Long = 0,
        var listenedMillis: Long = 0,
        var nowPlayingPending: Boolean = true,
        var queued: Boolean = false,
        var ended: Boolean = false,
    )
}
