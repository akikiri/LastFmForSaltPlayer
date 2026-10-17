@file:Suppress("unused")

package com.akikiri

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi

class MainPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {
    override fun start() {
        try {
            PluginRuntime.start()
            WorkshopApi.ui.toast("Last.fm Scrobbler 已启动", WorkshopApi.Ui.ToastType.Success)
        } catch (exception: Exception) {
            WorkshopApi.ui.toast("Last.fm 启动失败：${exception.message}", WorkshopApi.Ui.ToastType.Error)
            throw exception
        }
    }

    override fun stop() {
        PluginRuntime.stop()
        WorkshopApi.ui.toast("Last.fm Scrobbler 已停止", WorkshopApi.Ui.ToastType.Warning)
    }

    companion object {
        @JvmStatic fun openAuthorization() = PluginRuntime.openAuthorization()
        @JvmStatic fun checkAuthorization() = PluginRuntime.checkAuthorization()
        @JvmStatic fun reauthorize() = PluginRuntime.openAuthorization(reset = true)
        @JvmStatic fun showStatus() = PluginRuntime.status()
        @JvmStatic fun retryPending() = PluginRuntime.retryPending()
    }
}
