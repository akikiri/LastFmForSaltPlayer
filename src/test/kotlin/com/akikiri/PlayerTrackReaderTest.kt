package com.akikiri

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlayerTrackReaderTest {
    @Test fun `current API supplies metadata without reading the audio path`() {
        var queries = 0
        val reader = PlayerTrackReader { queries++; item() }
        val snapshot = checkNotNull(reader.read())
        assertEquals("id", snapshot.id)
        assertEquals(TrackMetadata("Track", "Artist", "Album", "Album Artist"), snapshot.track?.metadata)
        assertEquals(180_500L, snapshot.track?.durationMillis)
        assertEquals(180L, snapshot.track?.durationSeconds)
        assertEquals(1, queries)
    }
    @Test fun `missing current song is empty`() { assertNull(PlayerTrackReader { null }.read()) }
    @Test fun `missing ID is empty`() { assertNull(PlayerTrackReader { item("") }.read()) }
    @Test fun `missing duration is retained only for now playing`() {
        assertEquals(0L, PlayerTrackReader { item(duration = 0) }.read()?.track?.durationMillis)
    }
    @Test fun `invalid metadata retains identity but is not submitted`() {
        val snapshot = PlayerTrackReader { item().copy(title = " ") }.read()
        assertEquals("id", snapshot?.id)
        assertNull(snapshot?.track)
    }
    @Test fun `API failure propagates without any other metadata source`() {
        var queries = 0
        val reader = PlayerTrackReader { queries++; throw IllegalStateException("Permission revoked") }
        assertThrows(IllegalStateException::class.java) { reader.read() }
        assertEquals(1, queries)
    }
    private fun item(id: String = "id", duration: Long = 180_500) = MediaItem(
        " Track ", " Artist ", " Album ", " Album Artist ", "Z:\\nonexistent.flac", id = id, duration = duration,
    )
}
