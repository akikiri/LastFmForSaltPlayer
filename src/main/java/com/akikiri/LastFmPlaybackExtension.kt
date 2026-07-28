package com.akikiri

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension

@Extension
class LastFmPlaybackExtension : PlaybackExtensionPoint {
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        PluginRuntime.onTrackChanged(mediaItem)
        return null
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        PluginRuntime.onPlayingChanged(isPlaying)
    }

    override fun onSeekTo(position: Long) {
        PluginRuntime.onSeek(position)
    }

    override fun onPositionUpdated(position: Long) {
        PluginRuntime.onPosition(position)
    }

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        if (
            state == PlaybackExtensionPoint.State.Idle ||
            state == PlaybackExtensionPoint.State.Ended
        ) {
            PluginRuntime.onPlaybackEnded()
        }
    }
}
