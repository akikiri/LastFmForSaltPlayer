package com.akikiri

import org.jaudiotagger.audio.AudioFileIO
import java.nio.file.Files
import java.nio.file.Path

internal fun interface AudioDurationReader {
    fun readSeconds(path: String): Long?
}

internal class JaudiotaggerDurationReader : AudioDurationReader {
    override fun readSeconds(path: String): Long? = runCatching {
        val audioPath = Path.of(path)
        if (!Files.isRegularFile(audioPath)) {
            return null
        }

        AudioFileIO.read(audioPath.toFile())
            .audioHeader
            .trackLength
            .toLong()
            .takeIf { it > 0 }
    }.getOrNull()
}
