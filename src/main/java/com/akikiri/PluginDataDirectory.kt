package com.akikiri

import java.nio.file.Files
import java.nio.file.Path

internal object PluginDataDirectory {
    fun prepare(configPath: Path): Path {
        require(configPath.isAbsolute) { "宿主配置文件路径必须为绝对路径" }
        val directory = requireNotNull(configPath.normalize().parent) { "宿主配置文件必须位于插件目录中" }
        Files.createDirectories(directory)
        return directory
    }
}
