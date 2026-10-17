@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.akikiri

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.util.function.Consumer

internal class PluginSettings(
    private val manager: ConfigManager,
    private val onChange: (Boolean) -> Unit,
) : AutoCloseable {
    private var closed = false
    private val listener = Consumer<ConfigHelper> { load(it) }

    fun start() {
        manager.addConfigChangeListener(listener)
        try { load(manager.getConfig()) }
        catch (exception: Exception) { close(); throw exception }
    }

    @Synchronized private fun load(helper: ConfigHelper) {
        if (closed) return
        helper.reload()
        onChange(helper.get("library_only", false))
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        manager.removeConfigChangeListener(listener)
    }
}
