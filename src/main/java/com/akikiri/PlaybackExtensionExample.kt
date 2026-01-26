package com.akikiri

import AudioDuration.getDurationSeconds
import LastFmService
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.pf4j.Extension
import java.util.logging.Logger

/**
 * 播放扩展示例
 *
 * 这个扩展展示了如何监听播放器的各种状态变化：
 * - 播放状态变化（空闲、缓冲、就绪、结束）
 * - 播放/暂停状态变化
 * - 进度跳转
 * - 歌词加载和更新
 * - 播放位置更新
 */
@Extension
class PlaybackExtensionExample : PlaybackExtensionPoint {
    private val logger = Logger.getLogger(PlaybackExtensionExample::class.java.name)
    
    private var currentMediaItem: PlaybackExtensionPoint.MediaItem? = null
    private var currentPosition = 0L
    private var duration = 0L
    private var hasScrobbled = false
    private var isDurationCalculated = false


    /**
     * 加载歌词前的回调
     * 返回 null 表示使用默认逻辑
     */
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        logger.info("开始加载歌曲: ${mediaItem.title} - ${mediaItem.artist}")
        resetScrobbleState()
        currentMediaItem = mediaItem
        isDurationCalculated = false
        submitNowPlaying(mediaItem.artist, mediaItem.title)

        // 这里可以实现自定义歌词加载逻辑
        // 例如从特定的歌词服务器获取歌词
        // 返回 null 表示使用 SPW 默认的歌词加载逻辑
        return null
    }


    /**
     * 播放位置更新回调（每秒调用一次）
     */
    override fun onPositionUpdated(position: Long) {
        this.currentPosition = position
        // 延迟计算时长，避免频繁 I/O 操作
        if (!isDurationCalculated && currentMediaItem != null) {
            duration = getDurationSeconds(currentMediaItem!!.path)
            isDurationCalculated = true
            logger.info("计算歌曲时长: ${duration}秒")
        }

        val positionInSeconds = position / 1000

        // 优化 scrobble 条件检查
        if (shouldScrobble(positionInSeconds)) {
            performScrobble()
        }
    }

    /**
     * 重置 scrobble 状态
     */
    private fun resetScrobbleState() {
        logger.info("重置 scrobble 状态")
        hasScrobbled = false
        isDurationCalculated = false
        duration = 0L
    }

    /**
     * 执行 scrobble 操作
     */
    private fun performScrobble() {
        currentMediaItem?.let { mediaItem ->
            logger.info("执行 scrobble: ${mediaItem.title} - ${mediaItem.artist}")
            hasScrobbled = true
            submitScrobble(mediaItem.artist, mediaItem.title)
        }
    }

    /**
     * 检查是否应该执行 scrobble
     */
    private fun shouldScrobble(positionInSeconds: Long): Boolean {
        // 符合 Last.fm 的 scrobble 规则：
        // 1. 歌曲播放时间至少 30 秒
        // 2. 或者歌曲播放了一半以上的时长
        // 3. 且只 scrobble 一次
        val scrobbleThreshold = Math.max(30, duration / 2)
        
        val shouldScrobble = currentMediaItem != null &&
                duration > 0 &&
                positionInSeconds >= scrobbleThreshold &&
                !hasScrobbled
        
        if (shouldScrobble) {
            logger.info("满足 scrobble 条件: 播放位置 ${positionInSeconds}秒, 阈值 ${scrobbleThreshold}秒")
        }
        
        return shouldScrobble
    }


    /**
     * 异步执行 Last.fm API 调用
     */
    private fun executeLastFmApi(actionName: String, onError: (() -> Unit)? = null, action: suspend (LastFmService) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lastFm: LastFmService = LastFmService.defaultInstance()
                action(lastFm)
                logger.info("${actionName} 操作成功")
//                WorkshopApi.ui.toast("提交${actionName} 成功", WorkshopApi.Ui.ToastType.Success)
            } catch (e: Exception) {
                logger.severe("${actionName} 操作失败: ${e.message}")
                onError?.invoke()
                WorkshopApi.ui.toast("提交${actionName} 失败", WorkshopApi.Ui.ToastType.Error)
            }
        }
    }

    /**
     * 异步提交 scrobble 信息
     */
    private fun submitScrobble(artist: String, title: String) {
        executeLastFmApi("scrobble", { hasScrobbled = false }) {
            it.scrobble(artist, title)
        }
    }

    /**
     * 异步提交 now playing 信息
     */
    private fun submitNowPlaying(artist: String, title: String) {
        executeLastFmApi("nowplaying") {
            it.nowPlaying(artist, title)
        }
    }
}