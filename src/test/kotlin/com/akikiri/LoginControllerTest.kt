package com.akikiri

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LoginControllerTest {
    @Test fun `authorization succeeds without song events and notifies ready`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = FakeGateway()
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val auth = AuthManager(gateway, MemorySessionStore(), BrowserLauncher { true })
        val controller = LoginController(scope, auth, messages::add, { ready.set(true) }, 5, 4)
        try {
            controller.open().join()
            awaitCondition { ready.get() }
            assertTrue(messages.any { it.contains("listener") })
        } finally { scope.cancel() }
    }

    @Test fun `pending login polling is bounded and can be restarted`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = FakeGateway()
        gateway.sessionError = LastFmApiException(14, "Pending", false)
        val auth = AuthManager(gateway, MemorySessionStore(), BrowserLauncher { true })
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val controller = LoginController(scope, auth, messages::add, {}, 5, 2)
        try {
            controller.open().join()
            awaitCondition { messages.any { it.contains("已暂停") } }
            assertEquals(2, gateway.checks)
            gateway.sessionError = null
            controller.check().join()
            assertTrue(auth.session() is SessionAccess.Ready)
        } finally { scope.cancel() }
    }

    @Test fun `starting with pending token reopens the existing authorization page`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = FakeGateway()
        var opened = 0
        val auth = AuthManager(gateway, MemorySessionStore(AuthState(token = "old", tokenCreatedAtEpochSeconds = 100)),
            BrowserLauncher { opened++; true }, { 200 })
        val controller = LoginController(scope, auth, {}, {}, 100, 1)
        try {
            controller.start().join()
            assertEquals(1, opened)
            assertEquals(0, gateway.tokens)
        } finally { scope.cancel() }
    }

    @Test fun `slow authorization check is cancelled at the overall polling deadline`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cancelled = CompletableDeferred<Unit>()
        val gateway = object : FakeGateway() {
            override suspend fun requestSessionKey(token: String): LastFmSession {
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        val auth = AuthManager(gateway, MemorySessionStore(), BrowserLauncher { true })
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val controller = LoginController(scope, auth, messages::add, {}, 1, 100, 50)
        try {
            controller.open().join()
            withTimeout(1_000) { cancelled.await() }
            awaitCondition { messages.any { it.contains("已暂停") } }
            assertTrue(auth.session() is SessionAccess.WaitingForAuthorization)
        } finally { scope.cancel() }
    }
}
