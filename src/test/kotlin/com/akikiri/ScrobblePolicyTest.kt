package com.akikiri

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class ScrobblePolicyTest {
    @Test fun `disabled filter preserves behavior without library queries`() = runBlocking {
        val policy = ScrobblePolicy { error("Must not query") }
        assertTrue(policy.allows(null))
        assertTrue(policy.allows("outside"))
    }

    @Test fun `enabled filter requires matching library ID not readable or file existence`() = runBlocking {
        val item = MediaItem("Track", "Artist", "", "", "Z:\\missing.flac", id = "library", readable = false)
        val policy = ScrobblePolicy { CompletableFuture.completedFuture(if (it == "library") item else null) }
        policy.libraryOnly = true
        assertTrue(policy.allows("library"))
        assertFalse(policy.allows("outside"))
        assertFalse(policy.allows(null))
        assertFalse(policy.allows(""))
    }

    @Test fun `incorrect ID is not accepted as membership`() = runBlocking {
        val policy = ScrobblePolicy {
            CompletableFuture.completedFuture(MediaItem("Track", "Artist", "", "", "", id = "other"))
        }
        policy.libraryOnly = true
        assertFalse(policy.allows("requested"))
    }

    @Test fun `query timeout denies membership without cancelling caller`() = runBlocking {
        val pending = CompletableFuture<MediaItem?>()
        val policy = ScrobblePolicy(20) { pending }
        policy.libraryOnly = true
        assertFalse(policy.allows("library"))
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(pending.isCancelled)
    }

    @Test fun `query failure propagates and policy can recover`() = runBlocking {
        var failed = true
        val policy = ScrobblePolicy {
            if (failed) CompletableFuture.failedFuture(java.io.IOException("Library unavailable"))
            else CompletableFuture.completedFuture(MediaItem("Track", "Artist", "", "", "", id = it))
        }
        policy.libraryOnly = true
        assertThrows(java.io.IOException::class.java) { runBlocking { policy.allows("library") } }
        failed = false
        assertTrue(policy.allows("library"))
    }
}
