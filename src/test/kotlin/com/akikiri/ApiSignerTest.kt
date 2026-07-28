package com.akikiri

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApiSignerTest {
    @Test
    fun `signs parameters in sorted order without logging secrets`() {
        val signature = ApiSigner.sign(
            parameters = linkedMapOf(
                "timestamp" to "123",
                "method" to "track.scrobble",
                "sk" to "session",
                "api_key" to "abc",
            ),
            secret = "secret",
        )

        assertEquals("9b35eb1e2ae14c85715d0891fd718628", signature)
    }
}
