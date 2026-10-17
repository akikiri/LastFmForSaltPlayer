package com.akikiri

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlaybackTrackerTest {
    private val metadata = TrackMetadata("Track", "Artist")

    @Test fun `forty second track queues at twenty seconds and is confirmed once`() {
        val clock = TestClock()
        val tracker = setup(clock)
        play(tracker, clock, 19)
        assertNull(tracker.eligibleScrobble())
        play(tracker, clock, 1, 19)
        val record = checkNotNull(tracker.eligibleScrobble())
        assertEquals(1_000, record.startedAtEpochSeconds)
        assertEquals(record, tracker.eligibleScrobble())
        tracker.markQueued(record.id)
        assertNull(tracker.eligibleScrobble())
    }

    @Test fun `same ID metadata and lyrics reload never resets a play`() {
        val clock = TestClock()
        val tracker = setup(clock)
        play(tracker, clock, 10)
        assertFalse(tracker.selectTrack("id", PlayerTrack(metadata, 40_000)))
        play(tracker, clock, 10, 10)
        val record = checkNotNull(tracker.eligibleScrobble())
        tracker.markQueued(record.id)
        tracker.selectTrack("id", PlayerTrack(metadata, 40_000))
        play(tracker, clock, 20, 20)
        assertNull(tracker.eligibleScrobble())
    }

    @Test fun `paused load has no timestamp or now playing until playback begins`() {
        val clock = TestClock()
        val tracker = clock.tracker()
        tracker.selectTrack("id", PlayerTrack(metadata, 40_000))
        clock.advance(100_000)
        assertNull(tracker.takeNowPlaying())
        tracker.onPlayingChanged(true)
        assertNotNull(tracker.takeNowPlaying())
        tracker.onPosition(0)
        play(tracker, clock, 20)
        assertEquals(1_100, tracker.eligibleScrobble()?.startedAtEpochSeconds)
    }

    @Test fun `delayed callbacks count continuous real listening`() {
        val clock = TestClock()
        val tracker = setup(clock)
        clock.advance(3_000)
        tracker.onPosition(3_000)
        play(tracker, clock, 17, 3)
        assertNotNull(tracker.eligibleScrobble())
    }

    @Test fun `seek and pause never count skipped or paused time`() {
        val clock = TestClock()
        val tracker = setup(clock, 600_000)
        play(tracker, clock, 5)
        tracker.onSeek(300_000)
        tracker.onPosition(300_000)
        tracker.onPlayingChanged(false)
        clock.advance(300_000)
        tracker.onPosition(300_000)
        tracker.onPlayingChanged(true)
        tracker.onPosition(300_000)
        assertNull(tracker.eligibleScrobble())
    }

    @Test fun `large progress jump without seek is capped at real elapsed time`() {
        val clock = TestClock()
        val tracker = setup(clock)
        clock.advance()
        tracker.onPosition(30_000)
        assertNull(tracker.eligibleScrobble())
    }

    @Test fun `initial ongoing playback is detected from progress without playing replay`() {
        val clock = TestClock()
        val tracker = clock.tracker()
        tracker.selectTrack("id", PlayerTrack(metadata, 40_000))
        tracker.onPosition(10_000)
        clock.advance()
        tracker.onPosition(11_000)
        assertNotNull(tracker.takeNowPlaying())
        play(tracker, clock, 19, 11)
        assertNotNull(tracker.eligibleScrobble())
    }

    @Test fun `ended same ID playback creates a fresh round`() {
        val clock = TestClock()
        val tracker = setup(clock)
        play(tracker, clock, 20)
        val first = checkNotNull(tracker.eligibleScrobble())
        tracker.markQueued(first.id)
        tracker.end()
        tracker.onPlayingChanged(true)
        tracker.onPosition(0)
        play(tracker, clock, 20)
        assertNotEquals(first.id, tracker.eligibleScrobble()?.id)
    }

    @Test fun `explicit seek to zero is not a repeat round`() {
        val clock = TestClock()
        val tracker = setup(clock)
        play(tracker, clock, 39)
        val first = checkNotNull(tracker.eligibleScrobble())
        tracker.markQueued(first.id)
        tracker.onSeek(0)
        tracker.onPosition(0)
        play(tracker, clock, 20)
        assertNull(tracker.eligibleScrobble())
    }

    @Test fun `automatic repeat without ended callback still creates a fresh round`() {
        val clock = TestClock()
        val tracker = setup(clock)
        play(tracker, clock, 39)
        val first = checkNotNull(tracker.eligibleScrobble())
        tracker.markQueued(first.id)
        clock.advance()
        tracker.onPosition(0)
        play(tracker, clock, 20)
        assertNotEquals(first.id, tracker.eligibleScrobble()?.id)
    }

    @Test fun `four minute cap and thirty second minimum use API milliseconds`() {
        val clock = TestClock()
        val tracker = setup(clock, 600_000)
        play(tracker, clock, 240)
        assertNotNull(tracker.eligibleScrobble())
        val shortClock = TestClock()
        val short = setup(shortClock, 30_000)
        play(short, shortClock, 30)
        assertNull(short.eligibleScrobble())
        short.selectTrack("other", PlayerTrack(metadata, 30_500))
        short.onPosition(0)
        play(short, shortClock, 15)
        assertNull(short.eligibleScrobble())
        shortClock.advance(250)
        short.onPosition(15_250)
        assertNotNull(short.eligibleScrobble())
    }

    @Test fun `missing duration still permits now playing but never scrobbles`() {
        val clock = TestClock()
        val tracker = setup(clock, 0)
        assertNotNull(tracker.takeNowPlaying())
        play(tracker, clock, 300)
        assertNull(tracker.eligibleScrobble())
    }

    private fun setup(clock: TestClock, duration: Long = 40_000): PlaybackTracker = clock.tracker().apply {
        selectTrack("id", PlayerTrack(metadata, duration))
        onPlayingChanged(true)
        onPosition(0)
    }
    private fun play(tracker: PlaybackTracker, clock: TestClock, seconds: Int, offset: Int = 0) {
        repeat(seconds) { clock.advance(); tracker.onPosition((it + 1L + offset) * 1_000) }
    }
}
