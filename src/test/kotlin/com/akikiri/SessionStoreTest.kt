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
}
