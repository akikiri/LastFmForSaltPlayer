@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.akikiri

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.function.Consumer

class PluginSettingsTest {
    @Test fun `settings load false by default and react to saved changes`() {
        val manager = FakeConfigManager()
        val values = mutableListOf<Boolean>()
        val settings = PluginSettings(manager, values::add)
        settings.start()
        assertEquals(listOf(false), values)
        manager.helper.value = true
        manager.listener!!.accept(manager.helper)
        assertEquals(listOf(false, true), values)
        assertEquals(2, manager.helper.reloads)
        val lateCallback = manager.listener!!
        settings.close()
        lateCallback.accept(manager.helper)
        assertEquals(listOf(false, true), values)
        assertNull(manager.listener)
    }

    @Test fun `saved enabled value is applied during startup`() {
        val manager = FakeConfigManager()
        manager.helper.value = true
        val values = mutableListOf<Boolean>()
        PluginSettings(manager, values::add).use { it.start() }
        assertEquals(listOf(true), values)
    }

    @Test fun `startup failure unregisters listener`() {
        val manager = FakeConfigManager()
        assertThrows(IllegalStateException::class.java) {
            PluginSettings(manager) { error("Cannot apply settings") }.start()
        }
        assertNull(manager.listener)
    }

    private class FakeConfigManager : ConfigManager {
        val helper = FakeConfigHelper()
        var listener: Consumer<ConfigHelper>? = null
        override fun getConfig() = helper
        override fun getConfig(fileName: String) = helper
        override fun addConfigChangeListener(listener: Consumer<ConfigHelper>) { this.listener = listener }
        override fun addConfigChangeListener(fileName: String, listener: Consumer<ConfigHelper>) {
            this.listener = listener
        }
        override fun removeConfigChangeListener(listener: Consumer<ConfigHelper>) {
            if (this.listener === listener) this.listener = null
        }
    }

    private class FakeConfigHelper : ConfigHelper {
        var value: Boolean? = null
        var reloads = 0
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: String, defaultValue: T): T {
            assertEquals("library_only", key)
            return (value ?: defaultValue) as T
        }
        override fun set(key: String, value: Any) = error("Not used")
        override fun save() = true
        override fun reload(): Boolean { reloads++; return true }
        override fun getConfigPath() = Path.of("config.json")
    }
}
