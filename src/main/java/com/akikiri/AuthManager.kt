package com.akikiri

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Desktop
import java.net.URI

internal fun interface BrowserLauncher { fun open(url: String): Boolean }

internal class DesktopBrowserLauncher : BrowserLauncher {
    override fun open(url: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported()) false
        else { Desktop.getDesktop().browse(URI.create(url)); true }
    }.getOrDefault(false)
}

internal class AuthManager(
    private val gateway: LastFmGateway,
    private val store: SessionStore,
    private val browserLauncher: BrowserLauncher,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val applicationKey: String = "",
) {
    private val mutex = Mutex()
    private var state = store.load().let {
        if (it.applicationKey != null && it.applicationKey != applicationKey) AuthState()
        else it.copy(sessionKey = it.sessionKey?.trim()?.takeIf(String::isNotEmpty),
            token = it.token?.trim()?.takeIf(String::isNotEmpty))
    }

    suspend fun session(): SessionAccess = mutex.withLock { access() }

    suspend fun beginAuthorization(reset: Boolean = false): SessionAccess = mutex.withLock {
        if (reset) save(AuthState())
        if (!state.sessionKey.isNullOrBlank()) return@withLock access()
        if (state.token.isNullOrBlank() || tokenExpired()) {
            val token = gateway.requestToken()
            currentCoroutineContext().ensureActive()
            save(AuthState(token = token, tokenCreatedAtEpochSeconds = nowEpochSeconds()))
        }
        val url = gateway.authorizationUrl(checkNotNull(state.token))
        SessionAccess.WaitingForAuthorization(url, browserLauncher.open(url))
    }

    suspend fun checkAuthorization(): SessionAccess = mutex.withLock {
        if (!state.sessionKey.isNullOrBlank()) return@withLock access()
        val token = state.token ?: return@withLock SessionAccess.SignedOut
        if (tokenExpired()) { save(AuthState()); return@withLock SessionAccess.SignedOut }
        try {
            val session = gateway.requestSessionKey(token)
            currentCoroutineContext().ensureActive()
            save(AuthState(sessionKey = session.key, username = session.username))
            access()
        } catch (exception: LastFmApiException) {
            when (exception.errorCode) {
                14 -> access()
                4, 15 -> { save(AuthState()); SessionAccess.SignedOut }
                else -> throw exception
            }
        }
    }

    // Late failures from old requests must not clear a newer session.
    suspend fun invalidateSession(expectedKey: String): Boolean = mutex.withLock {
        if (state.sessionKey != expectedKey) return@withLock false
        save(AuthState())
        true
    }

    private fun access(): SessionAccess {
        state.sessionKey?.trim()?.takeIf(String::isNotEmpty)?.let {
            return SessionAccess.Ready(it, state.username.orEmpty())
        }
        state.token?.let {
            return SessionAccess.WaitingForAuthorization(gateway.authorizationUrl(it), false)
        }
        return SessionAccess.SignedOut
    }

    private fun tokenExpired(): Boolean = state.tokenCreatedAtEpochSeconds?.let {
        nowEpochSeconds() - it >= 3_600
    } ?: true

    private suspend fun save(next: AuthState) {
        currentCoroutineContext().ensureActive()
        val bound = next.copy(applicationKey = applicationKey.takeIf(String::isNotEmpty))
        store.save(bound)
        state = bound
    }
}
