package com.akikiri

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LoginController(
    private val scope: CoroutineScope,
    private val auth: AuthManager,
    private val notify: (String) -> Unit,
    private val onReady: () -> Unit,
    private val pollIntervalMillis: Long = 5_000,
    private val pollAttempts: Int = 24,
    private val pollWindowMillis: Long = 120_000,
) {
    private val commands = Mutex()
    private var poll: Job? = null

    fun start() = command {
        when (val state = auth.session()) {
            is SessionAccess.Ready -> ready(state)
            is SessionAccess.WaitingForAuthorization -> begin(false)
            SessionAccess.SignedOut -> notify("尚未登录 Last.fm，请在插件设置中点击“登录 / 打开授权页”")
        }
    }

    fun open(reset: Boolean = false) = command { begin(reset) }

    fun check() = command {
        poll?.cancelAndJoin()
        poll = null
        when (val state = auth.checkAuthorization()) {
            is SessionAccess.Ready -> ready(state)
            is SessionAccess.WaitingForAuthorization -> {
                notify("等待浏览器授权，可在插件设置重新打开授权页")
                startPolling()
            }
            SessionAccess.SignedOut -> notify("尚未登录或授权已过期，请点击“登录 / 打开授权页”")
        }
    }

    fun status(extra: String = "") = command {
        val label = when (val state = auth.session()) {
            is SessionAccess.Ready -> "已登录 Last.fm ${state.username}"
            is SessionAccess.WaitingForAuthorization -> "等待 Last.fm 网页授权"
            SessionAccess.SignedOut -> "未登录 Last.fm"
        }
        notify("$label$extra")
    }

    private fun command(block: suspend () -> Unit) = scope.launch {
        commands.withLock {
            try { block() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { notify("Last.fm 授权操作失败：${exception.message}；可在设置重试") }
        }
    }

    private suspend fun begin(reset: Boolean) {
        poll?.cancelAndJoin()
        when (val state = auth.beginAuthorization(reset)) {
            is SessionAccess.Ready -> ready(state)
            is SessionAccess.WaitingForAuthorization -> {
                if (state.browserOpened) notify("请在浏览器授权 Last.fm，插件将自动确认登录")
                else notify("无法打开浏览器，请访问 ${state.authorizationUrl}；也可在设置重新打开")
                startPolling()
            }
            SessionAccess.SignedOut -> Unit
        }
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            val finished = withTimeoutOrNull(pollWindowMillis) {
                repeat(pollAttempts) {
                    delay(pollIntervalMillis)
                    try {
                        when (val state = auth.checkAuthorization()) {
                            is SessionAccess.Ready -> { ready(state); return@withTimeoutOrNull true }
                            SessionAccess.SignedOut -> {
                                notify("Last.fm 授权已过期，请在设置重新授权")
                                return@withTimeoutOrNull true
                            }
                            is SessionAccess.WaitingForAuthorization -> Unit
                        }
                    } catch (exception: CancellationException) { throw exception }
                    catch (_: Exception) { /* Retain the token after temporary network failure. */ }
                }
                false
            }
            if (finished != true) notify("等待授权已暂停，可在插件设置点击“检查授权”或重新打开授权页")
        }
    }

    private fun ready(state: SessionAccess.Ready) {
        notify("Last.fm 已登录 ${state.username}")
        onReady()
    }
}
