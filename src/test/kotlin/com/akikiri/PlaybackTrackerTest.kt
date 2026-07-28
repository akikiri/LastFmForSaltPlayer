package com.akikiri

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaybackTrackerTest {
    private val metadata = TrackMetadata(
        title = "Track",
        artist = "Artist",
        album = "Album",
    )

    @Test
    fun `forty second track scrobbles after twenty listened seconds`() {
        val tracker = PlaybackTracker { 1_700_000_000 }
        tracker.onPlayingChanged(true)
        val track = tracker.beginTrack(metadata)
        assertNull(tracker.setDuration(track.generation, 40))

        tracker.onPosition(0)
        repeat(19) { second ->
            assertNull(tracker.onPosition((second + 1L) * 1_000))
        }

        val scrobble = tracker.onPosition(20_000)
        assertNotNull(scrobble)
        assertEquals(1_700_000_000, scrobble?.startedAtEpochSeconds)
        assertNull(tracker.onPosition(21_000))
    }

    @Test
    fun `ten minute track scrobbles at four minute cap`() {
        val tracker = PlaybackTracker()
        tracker.onPlayingChanged(true)
        val track = tracker.beginTrack(metadata)
        tracker.setDuration(track.generation, 600)
        tracker.onPosition(0)

        repeat(239) { second ->
            assertNull(tracker.onPosition((second + 1L) * 1_000))
        }

        assertNotNull(tracker.onPosition(240_000))
    }

    @Test
    fun `track of exactly thirty seconds is never eligible`() {
        val tracker = PlaybackTracker()
        tracker.onPlayingChanged(true)
        val track = tracker.beginTrack(metadata)
        tracker.setDuration(track.generation, 30)
        tracker.onPosition(0)

        repeat(30) { second ->
            assertNull(tracker.onPosition((second + 1L) * 1_000))
        }
    }

    @Test
    fun `seek does not count skipped audio as listened time`() {
        val tracker = PlaybackTracker()
        tracker.onPlayingChanged(true)
        val track = tracker.beginTrack(metadata)
        tracker.setDuration(track.generation, 600)
        tracker.onPosition(0)
        repeat(5) { second -> tracker.onPosition((second + 1L) * 1_000) }

        tracker.onSeek(300_000)

        assertNull(tracker.onPosition(300_000))
        assertNull(tracker.onPosition(301_000))
    }

    @Test
    fun `paused position updates do not count`() {
        val tracker = PlaybackTracker()
        tracker.onPlayingChanged(true)
        val track = tracker.beginTrack(metadata)
        tracker.setDuration(track.generation, 40)
        tracker.onPosition(0)
        tracker.onPosition(10_000)
        tracker.onPlayingChanged(false)

        repeat(30) { second ->
            assertNull(tracker.onPosition(11_000L + second * 1_000L))
        }
    }
}
