@file:OptIn(UnstableSpwWorkshopApi::class)
@file:Suppress("unused")

package com.akikiri

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi

class MainPlugin(
    pluginContext: PluginContext
) : SpwPlugin(pluginContext) {
    override fun start() {
        PluginRuntime.start()
        WorkshopApi.ui.toast("Last.fm Scrobbler 已启动", WorkshopApi.Ui.ToastType.Success)
    }

    override fun stop() {
        PluginRuntime.stop()
        WorkshopApi.ui.toast("Last.fm Scrobbler 已停止", WorkshopApi.Ui.ToastType.Warning)
    }

    override fun delete() {
        WorkshopApi.ui.toast("插件已删除", WorkshopApi.Ui.ToastType.Error)
    }

    override fun update() {
        WorkshopApi.ui.toast("插件已更新", WorkshopApi.Ui.ToastType.Success)
    }
}
