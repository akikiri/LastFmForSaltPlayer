package com.akikiri

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PluginDataDirectoryTest {
    @TempDir lateinit var root: Path
    private val host get() = root.resolve("custom-host/plugin-id")

    @Test fun `fresh storage uses the host config directory without creating a config file`() {
        val directory = PluginDataDirectory.prepare(host.resolve("config.json"))
        assertEquals(host, directory)
        assertTrue(Files.isDirectory(host))
        Files.list(host).use { assertEquals(0L, it.count()) }
    }

    @Test fun `old session and queue remain untouched and are not imported`() {
        val old = root.resolve("old-data")
        Files.createDirectories(old)
        Files.writeString(old.resolve("Last_Fm_Session.json"), "old-session")
        Files.writeString(old.resolve("Last_Fm_Pending.json"), "old-queue")

        PluginDataDirectory.prepare(host.resolve("config.json"))

        assertEquals(AuthState(), FileSessionStore(host.resolve("Last_Fm_Session.json")).load())
        assertTrue(FileScrobbleStore(host.resolve("Last_Fm_Pending.json")).load().isEmpty())
        assertEquals("old-session", Files.readString(old.resolve("Last_Fm_Session.json")))
        assertEquals("old-queue", Files.readString(old.resolve("Last_Fm_Pending.json")))
        Files.list(host).use { assertEquals(0L, it.count()) }
    }

    @Test fun `managed session and queue persist across repeated startup`() {
        val directory = PluginDataDirectory.prepare(host.resolve("config.json"))
        val state = AuthState(sessionKey = "new-session", username = "listener", applicationKey = "app")
        val pending = listOf(PendingScrobble(ScrobbleTrack(TrackMetadata("Track", "Artist"), 100, 40)))
        FileSessionStore(directory.resolve("Last_Fm_Session.json")).save(state)
        FileScrobbleStore(directory.resolve("Last_Fm_Pending.json")).save(pending)

        repeat(2) { PluginDataDirectory.prepare(host.resolve("config.json")) }

        assertEquals(state, FileSessionStore(directory.resolve("Last_Fm_Session.json")).load())
        assertEquals(pending, FileScrobbleStore(directory.resolve("Last_Fm_Pending.json")).load())
    }

    @Test fun `host path is normalized before resolving its parent directory`() {
        assertEquals(host, PluginDataDirectory.prepare(host.resolve("nested/../config.json")))
    }

    @Test fun `unavailable host directory fails without a fallback location`() {
        val blocked = root.resolve("not-a-directory")
        Files.writeString(blocked, "keep-file")
        assertThrows(java.io.IOException::class.java) {
            PluginDataDirectory.prepare(blocked.resolve("config.json"))
        }
        assertEquals("keep-file", Files.readString(blocked))
        assertFalse(Files.exists(host))
    }

    @Test fun `relative config paths are rejected rather than using the working directory`() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginDataDirectory.prepare(Path.of("config.json"))
        }
    }
}
