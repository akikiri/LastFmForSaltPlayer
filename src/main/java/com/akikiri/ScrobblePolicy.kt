package com.akikiri

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletionStage

internal class ScrobblePolicy(
    private val queryTimeoutMillis: Long = 5_000,
    private val findTrack: (String) -> CompletionStage<MediaItem?> = {
        WorkshopApi.library.getTrackById(it)
    },
) {
    @Volatile var libraryOnly: Boolean = false

    suspend fun allows(trackId: String?): Boolean {
        if (!libraryOnly) return true
        if (trackId.isNullOrBlank()) return false
        // Query failures propagate: never guess membership or discard pending records.
        return withTimeoutOrNull(queryTimeoutMillis) { findTrack(trackId).await()?.id == trackId } ?: false
    }
}
