package com.akikiri

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi

internal class PlayerTrackReader(
    private val getCurrentMediaItem: () -> MediaItem? = {
        WorkshopApi.playback.getCurrentMediaItem()
    },
) {
    fun read(): PlayerSnapshot? {
        val item = getCurrentMediaItem() ?: return null
        if (item.id.isBlank()) return null

        val metadata = TrackMetadata(
            title = item.title.trim(),
            artist = item.artist.trim(),
            album = item.album.trim(),
            albumArtist = item.albumArtist.trim(),
        )
        return PlayerSnapshot(item.id, if (metadata.isValid) PlayerTrack(metadata, item.duration) else null)
    }
}

internal data class PlayerTrack(
    val metadata: TrackMetadata,
    val durationMillis: Long,
    val scrobbleAllowed: Boolean = true,
) {
    val durationSeconds: Long?
        get() = durationMillis.takeIf { it > 0 }?.div(1_000)
}

internal data class PlayerSnapshot(val id: String, val track: PlayerTrack?)
