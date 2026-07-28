package com.akikiri

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Desktop
import java.net.URI

internal fun interface BrowserLauncher {
    fun open(url: String): Boolean
}

internal class DesktopBrowserLauncher : BrowserLauncher {
    override fun open(url: String): Boolean = try {
        if (!Desktop.isDesktopSupported()) {
            false
        } else {
            Desktop.getDesktop().browse(URI.create(url))
            true
        }
    } catch (_: Exception) {
        false
    }
}

internal class AuthManager(
    private val gateway: LastFmGateway,
    private val store: SessionStore,
    private val browserLauncher: BrowserLauncher,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()

    suspend fun session(): SessionAccess = mutex.withLock {
        var state = store.load().normalized()
        state.sessionKey?.let { return SessionAccess.Ready(it) }

        if (state.token != null && state.tokenIsExpired()) {
            state = AuthState()
            store.save(state)
        }

        state.token?.let { token ->
            try {
                val sessionKey = gateway.requestSessionKey(token)
                store.save(AuthState(sessionKey = sessionKey))
                return SessionAccess.Ready(sessionKey)
            } catch (exception: LastFmApiException) {
                when (exception.errorCode) {
                    TOKEN_NOT_AUTHORIZED -> {
                        return SessionAccess.WaitingForAuthorization(
                            authorizationUrl = gateway.authorizationUrl(token),
                            browserOpened = false,
                            newlyCreatedToken = false,
                        )
                    }

                    INVALID_AUTH_TOKEN, TOKEN_EXPIRED -> {
                        store.save(AuthState())
                    }

                    else -> throw exception
                }
            }
        }

        val token = gateway.requestToken()
        val createdAt = nowEpochSeconds()
        store.save(
            AuthState(
                token = token,
                tokenCreatedAtEpochSeconds = createdAt,
            ),
        )
        val authorizationUrl = gateway.authorizationUrl(token)
        SessionAccess.WaitingForAuthorization(
            authorizationUrl = authorizationUrl,
            browserOpened = browserLauncher.open(authorizationUrl),
            newlyCreatedToken = true,
        )
    }

    suspend fun invalidateSession() = mutex.withLock {
        store.save(AuthState())
    }

    private fun AuthState.normalized(): AuthState = copy(
        sessionKey = sessionKey?.trim()?.takeIf(String::isNotEmpty),
        token = token?.trim()?.takeIf(String::isNotEmpty),
    )

    private fun AuthState.tokenIsExpired(): Boolean {
        val createdAt = tokenCreatedAtEpochSeconds ?: return false
        return nowEpochSeconds() - createdAt >= TOKEN_LIFETIME_SECONDS
    }

    companion object {
        private const val INVALID_AUTH_TOKEN = 4
        private const val TOKEN_NOT_AUTHORIZED = 14
        private const val TOKEN_EXPIRED = 15
        private const val TOKEN_LIFETIME_SECONDS = 60 * 60
    }
}
