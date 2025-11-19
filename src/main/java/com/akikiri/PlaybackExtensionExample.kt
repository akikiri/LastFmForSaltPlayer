package com.akikiri

import AudioDuration.getDurationSeconds
import LastFmService
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.pf4j.Extension

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
    private var currentMediaItem: PlaybackExtensionPoint.MediaItem? = null
    private var currentPosition = 0L
    private var duration = 0L
    private var hasScrobbled = false


    /**
     * 加载歌词前的回调
     * 返回 null 表示使用默认逻辑
     */
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        hasScrobbled = false
        currentMediaItem = mediaItem
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
        currentMediaItem?.let { duration = getDurationSeconds(it.path) }
        val positionInSeconds = position / 1000
        // 如果position不为空且超过当前播放进度的50%且未提交scrobble，执行scrobble
        if (currentMediaItem != null &&
            duration > 0 &&
            positionInSeconds >= duration / 2 &&
            !hasScrobbled
        ) {
            hasScrobbled = true
            submitScrobble(currentMediaItem!!.artist, currentMediaItem!!.title)
        }
    }



    /**
     * 异步提交 scrobble 信息
     */
    private fun submitScrobble(artist: String, title: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lastFm: LastFmService = LastFmService.defaultInstance()
                lastFm.scrobble(artist, title)
//                WorkshopApi.ui.toast("提交scrobble 成功", WorkshopApi.Ui.ToastType.Success)
            } catch (e: Exception) {
                hasScrobbled = false
                WorkshopApi.ui.toast("提交scrobble 失败", WorkshopApi.Ui.ToastType.Error)
            }
        }
    }

    /**
     * 异步提交 now playing 信息
     */
    private fun submitNowPlaying(artist: String, title: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lastFm: LastFmService = LastFmService.defaultInstance()
                lastFm.nowPlaying(artist, title)
//                WorkshopApi.ui.toast("提交nowplaying 成功", WorkshopApi.Ui.ToastType.Success)
            } catch (e: Exception) {
                WorkshopApi.ui.toast("提交nowplaying 失败", WorkshopApi.Ui.ToastType.Error)
            }
        }
    }
}