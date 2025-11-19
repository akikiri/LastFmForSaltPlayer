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
        WorkshopApi.ui.toast("插件已启动", WorkshopApi.Ui.ToastType.Success)
        println(pluginContext.toString())
    }

    override fun stop() {
        WorkshopApi.ui.toast("插件已停止", WorkshopApi.Ui.ToastType.Warning)
    }

    override fun delete() {
        WorkshopApi.ui.toast("插件已删除", WorkshopApi.Ui.ToastType.Error)
    }

    override fun update() {
        WorkshopApi.ui.toast("插件已更新", WorkshopApi.Ui.ToastType.Success)
    }
}