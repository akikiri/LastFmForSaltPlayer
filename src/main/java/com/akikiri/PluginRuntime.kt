package com.akikiri

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

internal object PluginRuntime {
    private val logger = Logger.getLogger(PluginRuntime::class.java.name)
    private val tracker = PlaybackTracker()
    private val durationReader: AudioDurationReader = JaudiotaggerDurationReader()
    private val lifecycleLock = Any()

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var coordinator: LastFmCoordinator? = null

    fun start() {
        synchronized(lifecycleLock) {
            if (scope != null) {
                return
            }

            try {
                val credentials = CredentialsLoader.load()
                val gateway = LastFmApiClient(credentials)
                val authManager = AuthManager(
                    gateway = gateway,
                    store = FileSessionStore(defaultSessionPath()),
                    browserLauncher = DesktopBrowserLauncher(),
                )
                coordinator = LastFmCoordinator(gateway, authManager)
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            } catch (exception: Exception) {
                coordinator = null
                scope = null
                logger.log(Level.SEVERE, "Last.fm Scrobbler 初始化失败", exception)
                notify("Last.fm 配置无效：${exception.message}", WorkshopApi.Ui.ToastType.Error)
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            scope?.cancel("插件已停止")
            scope = null
            coordinator = null
            tracker.clear()
        }
    }

    fun onTrackChanged(mediaItem: PlaybackExtensionPoint.MediaItem) {
        ensureStarted()
        val metadata = TrackMetadata(
            title = mediaItem.title.trim(),
            artist = mediaItem.artist.trim(),
            album = mediaItem.album.trim(),
            albumArtist = mediaItem.albumArtist.trim(),
        )
        val snapshot = tracker.beginTrack(metadata)

        if (!metadata.isValid) {
            notify("歌曲缺少标题或艺术家，已跳过 Last.fm", WorkshopApi.Ui.ToastType.Warning)
            return
        }

        launchFor(snapshot.generation) {
            val duration = durationReader.readSeconds(mediaItem.path)
            val scrobble = tracker.setDuration(snapshot.generation, duration)
            val currentCoordinator = coordinator ?: return@launchFor
            val result = currentCoordinator.updateNowPlaying(
                track = metadata,
                durationSeconds = duration,
                isCurrent = { tracker.isCurrent(snapshot.generation) },
            )
            handleResult("Now Playing", result)
            if (scrobble != null) {
                submitScrobble(scrobble)
            }
        }
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        tracker.onPlayingChanged(isPlaying)
    }

    fun onSeek(positionMillis: Long) {
        tracker.onSeek(positionMillis)
    }

    fun onPosition(positionMillis: Long) {
        tracker.onPosition(positionMillis)?.let(::submitScrobble)
    }

    fun onPlaybackEnded() {
        tracker.onPlayingChanged(false)
    }

    private fun submitScrobble(track: ScrobbleTrack) {
        val currentScope = scope ?: return
        currentScope.launch {
            val currentCoordinator = coordinator ?: return@launch
            try {
                val result = currentCoordinator.scrobble(track)
                ensureActive()
                handleResult("Scrobble", result)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.log(Level.WARNING, "Scrobble 后台任务失败", exception)
                notify("Scrobble 失败：${exception.message}", WorkshopApi.Ui.ToastType.Error)
            }
        }
    }

    private fun launchFor(generation: Long, block: suspend CoroutineScope.() -> Unit) {
        val currentScope = scope ?: return
        currentScope.launch {
            if (!tracker.isCurrent(generation)) {
                return@launch
            }
            try {
                block()
                ensureActive()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.log(Level.WARNING, "Last.fm 后台任务失败", exception)
                notify("Last.fm 操作失败：${exception.message}", WorkshopApi.Ui.ToastType.Error)
            }
        }
    }

    private fun handleResult(action: String, result: LastFmOperationResult) {
        when (result) {
            LastFmOperationResult.Success -> logger.info("$action 成功")
            LastFmOperationResult.Skipped -> Unit
            is LastFmOperationResult.AuthorizationRequired -> {
                if (result.access.newlyCreatedToken) {
                    val message = if (result.access.browserOpened) {
                        "请在浏览器完成 Last.fm 授权，下一首歌会自动继续"
                    } else {
                        "无法打开浏览器，请访问：${result.access.authorizationUrl}"
                    }
                    notify(message, WorkshopApi.Ui.ToastType.Warning)
                }
            }

            is LastFmOperationResult.Failed -> {
                logger.warning("$action 失败：${result.message}")
                notify("$action 失败：${result.message}", WorkshopApi.Ui.ToastType.Error)
            }
        }
    }

    private fun ensureStarted() {
        if (scope == null) {
            start()
        }
    }

    private fun defaultSessionPath(): Path {
        val roaming = System.getenv("APPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(
                System.getProperty("user.home"),
                "AppData",
                "Roaming",
            )

        return roaming.resolve(
            Path.of(
                "Salt Player for Windows",
                "workshop",
                "data",
                "Last_Fm_Session.json",
            ),
        )
    }

    private fun notify(message: String, type: WorkshopApi.Ui.ToastType) {
        runCatching { WorkshopApi.ui.toast(message, type) }
            .onFailure { logger.log(Level.WARNING, "无法显示通知", it) }
    }
}
