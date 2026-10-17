package com.akikiri

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `round trips the complete authentication state`() {
        val store = FileSessionStore(temporaryDirectory.resolve("session.json"))
        val expected = AuthState(
            sessionKey = "session",
            token = "token",
            tokenCreatedAtEpochSeconds = 123,
        )

        store.save(expected)

        assertEquals(expected, store.load())
    }

    @Test
    fun `missing session file returns an empty state`() {
        val store = FileSessionStore(temporaryDirectory.resolve("missing.json"))

        assertEquals(AuthState(), store.load())
    }

    @Test
    fun `damaged session is preserved and allows reauthorization`() {
        val path = temporaryDirectory.resolve("session.json")
        java.nio.file.Files.writeString(path, "{broken")
        val store = FileSessionStore(path)
        assertEquals(AuthState(), store.load())
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(path))
        java.nio.file.Files.list(temporaryDirectory).use { files ->
            org.junit.jupiter.api.Assertions.assertTrue(files.anyMatch { it.fileName.toString().startsWith("session.json.corrupt-") })
        }
        store.save(AuthState(sessionKey = "new-session"))
        assertEquals("new-session", store.load().sessionKey)
    }
}
