@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.akikiri

import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.util.logging.Logger

internal object PluginRuntime {
    @Volatile private var engine: ScrobblerEngine? = null
    private var settings: PluginSettings? = null

    @Synchronized fun start() {
        if (engine != null) return
        check(WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) {
            "请授予 Last.fm 插件音乐库读取权限"
        }
        val configManager = WorkshopApi.manager.createConfigManager()
        val credentials = CredentialsLoader.load()
        val gateway = LastFmApiClient(credentials)
        try {
            val directory = PluginDataDirectory.prepare(configManager.getConfig().getConfigPath())
            val auth = AuthManager(gateway, FileSessionStore(directory.resolve("Last_Fm_Session.json")),
                DesktopBrowserLauncher(), applicationKey = credentials.apiKey)
            val instance = ScrobblerEngine(gateway, auth,
                PendingScrobbles(FileScrobbleStore(directory.resolve("Last_Fm_Pending.json"))),
                PlayerTrackReader(), ::notify)
            engine = instance
            val binding = PluginSettings(configManager, instance::setLibraryOnly)
            try {
                binding.start()
                settings = binding
                instance.start()
            } catch (exception: Exception) {
                settings = null; binding.close(); engine = null; instance.close(); throw exception
            }
        } catch (exception: Exception) { gateway.close(); throw exception }
    }

    @Synchronized fun stop() {
        val old = engine; engine = null
        try { settings?.close() } finally { settings = null; old?.close() }
    }
    fun refresh() { engine?.refresh() }
    fun onPlayingChanged(value: Boolean) { engine?.onPlayingChanged(value) }
    fun onPosition(position: Long) { engine?.onPosition(position) }
    fun onSeek(position: Long) { engine?.onSeek(position) }
    fun onEnded() { engine?.onEnded() }
    fun onReady() { engine?.onReady() }

    fun openAuthorization(reset: Boolean = false) = withEngine { it.openAuthorization(reset) }
    fun checkAuthorization() = withEngine { it.checkAuthorization() }
    fun status() = withEngine { it.status() }
    fun retryPending() = withEngine { it.retryPending() }

    private fun withEngine(action: (ScrobblerEngine) -> Unit) {
        val current = engine
        if (current == null) notify("请先启用 Last.fm 插件并授予音乐库读取权限") else action(current)
    }

    private fun notify(message: String) {
        Logger.getLogger(PluginRuntime::class.java.name).info(message)
        runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Warning) }
    }
}
