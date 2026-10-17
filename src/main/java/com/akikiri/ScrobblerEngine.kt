package com.akikiri

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

internal class ScrobblerEngine(
    private val gateway: LastFmGateway,
    auth: AuthManager,
    private val queue: PendingScrobbles,
    private val reader: PlayerTrackReader,
    private val notify: (String) -> Unit,
    private val tracker: PlaybackTracker = PlaybackTracker(),
    private val refreshMillis: Long = 2_000,
    private val retryMillis: Long = 30_000,
    authPollMillis: Long = 5_000,
    private val policy: ScrobblePolicy = ScrobblePolicy(),
) : AutoCloseable {
    private val logger = Logger.getLogger(ScrobblerEngine::class.java.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    private val revision = AtomicLong()
    private val metadataPending = AtomicBoolean(true)
    private val policyPending = AtomicBoolean(false)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val flushRequests = Channel<Unit>(Channel.CONFLATED)
    private val coordinator = LastFmCoordinator(gateway, auth)
    private val nowPlayingLock = Any()
    private var nowPlayingJob: Job? = null
    private val login = LoginController(scope, auth, notify, {
        if (!closed.get()) {
            authenticated.set(true)
            tracker.requestNowPlaying()
            refreshRequests.trySend(Unit)
            flushRequests.trySend(Unit)
        }
    }, pollIntervalMillis = authPollMillis)
    private val submitter = ScrobbleSubmitter(queue, coordinator, ::report, policy)

    fun start() {
        login.start()
        scope.launch {
            for (ignored in refreshRequests) guarded {
                val version = revision.get()
                val snapshot = reader.read()
                val allowed = snapshot == null || canScrobble(snapshot.id)
                currentCoroutineContext().ensureActive()
                if (revision.get() != version) return@guarded
                enqueueEligible()
                if (snapshot == null) {
                    cancelNowPlaying()
                    tracker.clearSelection()
                } else if (tracker.selectTrack(snapshot.id, snapshot.track?.copy(scrobbleAllowed = allowed))) {
                    cancelNowPlaying()
                }
                policyPending.set(false)
                metadataPending.set(false)
                pump()
            }
        }
        scope.launch { for (ignored in flushRequests) guarded { submitter.flush() } }
        scope.launch {
            while (isActive) { refreshRequests.trySend(Unit); delay(refreshMillis) }
        }
        scope.launch {
            while (isActive) { flushRequests.trySend(Unit); delay(retryMillis) }
        }
    }

    fun refresh() {
        if (closed.get()) return
        metadataPending.set(true)
        revision.incrementAndGet()
        refreshRequests.trySend(Unit)
    }

    fun setLibraryOnly(value: Boolean) {
        if (closed.get() || policy.libraryOnly == value) return
        policyPending.set(true)
        policy.libraryOnly = value
        refresh()
        flushRequests.trySend(Unit)
    }

    fun onPlayingChanged(value: Boolean) {
        if (closed.get()) return
        tracker.onPlayingChanged(value)
        if (!value) cancelNowPlaying()
        refresh()
    }

    fun onPosition(position: Long) {
        if (closed.get()) return
        if (metadataPending.get()) return
        tracker.onPosition(position)
        pump()
    }

    fun onSeek(position: Long) { if (!closed.get()) tracker.onSeek(position) }
    fun onEnded() {
        if (closed.get()) return
        tracker.end()
        cancelNowPlaying()
        enqueueEligible()
    }
    fun onReady() { if (!closed.get()) { tracker.ready(); refresh() } }

    fun openAuthorization(reset: Boolean = false) {
        if (closed.get()) return
        if (reset) { authenticated.set(false); cancelNowPlaying() }
        login.open(reset)
    }
    fun checkAuthorization() { if (!closed.get()) login.check() }
    fun status() { if (!closed.get()) login.status("；待提交 ${queue.size()} 条") }
    fun retryPending() {
        if (closed.get()) return
        scope.launch { guarded { queue.retryBlocked(); flushRequests.trySend(Unit) } }
    }

    private fun pump() {
        enqueueEligible()
        if (closed.get() || !authenticated.get() || metadataPending.get()) return
        synchronized(nowPlayingLock) {
            val event = tracker.takeNowPlaying() ?: return
            nowPlayingJob?.cancel()
            nowPlayingJob = scope.launch {
                guarded {
                    val result = coordinator.updateNowPlaying(event.track.metadata, event.track.durationSeconds) {
                        !closed.get() && tracker.isPlaying(event.playId)
                    }
                    report(result)
                }
            }
        }
    }

    private fun enqueueEligible() {
        if (closed.get() || policyPending.get()) return
        val track = tracker.eligibleScrobble() ?: return
        try {
            queue.enqueue(track)
            tracker.markQueued(track.id)
            flushRequests.trySend(Unit)
        } catch (exception: Exception) {
            logger.log(Level.WARNING, "待提交记录保存失败", exception)
            notify("Last.fm 待提交记录保存失败，将保留在内存并重试：${exception.message}")
        }
    }

    private suspend fun canScrobble(trackId: String): Boolean = try {
        policy.allows(trackId)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        // A library failure must not suppress Now Playing or terminate playback tracking.
        logger.log(Level.WARNING, "无法核实歌曲是否在音乐库中，本次不新增收听记录", exception)
        false
    }

    private fun report(result: LastFmOperationResult) {
        if (closed.get()) return
        when (result) {
            is LastFmOperationResult.AuthorizationRequired -> {
                authenticated.set(false)
                if (result.sessionInvalidated) {
                    notify("Last.fm 授权已失效，正在重新打开授权页；待提交记录已保留")
                    login.open()
                }
            }
            is LastFmOperationResult.Failed -> {
                logger.warning(result.message)
                notify("Last.fm：${result.message}")
            }
            else -> Unit
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try { currentCoroutineContext().ensureActive(); block() }
        catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) {
            logger.log(Level.WARNING, "Last.fm 后台操作失败", exception)
            if (!closed.get()) notify("Last.fm：${exception.message}；待提交记录保留，可在设置重试")
        }
    }

    private fun cancelNowPlaying() = synchronized(nowPlayingLock) { nowPlayingJob?.cancel() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        gateway.close()
        refreshRequests.close()
        flushRequests.close()
    }
}
