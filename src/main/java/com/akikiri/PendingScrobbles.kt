package com.akikiri

import com.google.gson.Gson
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.logging.Level
import java.util.logging.Logger

internal data class PendingScrobble(val track: ScrobbleTrack, val blockedReason: String? = null)

internal interface ScrobbleStore {
    fun load(): List<PendingScrobble>
    fun save(records: List<PendingScrobble>)
}

internal class FileScrobbleStore(private val path: Path) : ScrobbleStore {
    private val gson = Gson()
    override fun load(): List<PendingScrobble> {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        val result = PendingScrobbleRecovery.read(bytes)
        if (result.damaged) {
            val backup = preserveOriginal(bytes)
            save(result.records)
            Logger.getLogger(FileScrobbleStore::class.java.name)
                .warning("已修复 Last.fm 待提交队列，恢复 ${result.records.size} 条，原文件保存在 ${backup.fileName}")
        }
        return result.records
    }

    private fun preserveOriginal(bytes: ByteArray): Path {
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val backup = path.resolveSibling("${path.fileName}.corrupt-$hash")
        // A failed queue replacement may retry the same recovery on every flush.
        if (!Files.exists(backup)) {
            val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
            try {
                Files.write(temporary, bytes)
                try {
                    Files.move(temporary, backup)
                } catch (_: FileAlreadyExistsException) {
                    // Verify the existing copy before replacing the active queue.
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        if (!Files.readAllBytes(backup).contentEquals(bytes)) {
            throw IOException("待提交队列备份校验失败：$backup")
        }
        return backup
    }

    override fun save(records: List<PendingScrobble>) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, gson.toJson(records))
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}

internal class PendingScrobbles(private val store: ScrobbleStore) {
    private val records = linkedMapOf<String, PendingScrobble>()
    private val seen = mutableSetOf<String>()
    private val acknowledged = mutableSetOf<String>()
    private var dirty = false

    private var loaded = false

    init {
        try {
            loadIfNeeded()
        } catch (exception: IOException) {
            Logger.getLogger(PendingScrobbles::class.java.name)
                .log(Level.WARNING, "待提交队列暂不可用，将在下次写入或提交前重读", exception)
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        for (record in store.load()) {
            // The persisted record owns its metadata and any permanent rejection.
            records[record.track.id] = record
            seen.add(record.track.id)
        }
        loaded = true
    }

    @Synchronized fun enqueue(track: ScrobbleTrack) {
        if (track.id in seen && track.id !in records) return
        records.putIfAbsent(track.id, PendingScrobble(track))
        dirty = true
        persist()
        seen.add(track.id)
    }
    @Synchronized fun ready(): List<ScrobbleTrack> = records.values.filter {
        it.blockedReason == null && it.track.id !in acknowledged
    }
        .map { it.track }.sortedBy { it.startedAtEpochSeconds }

    @Synchronized fun confirm(id: String) {
        seen.add(id)
        acknowledged.add(id)
        dirty = true
        persist()
    }
    @Synchronized fun block(id: String, reason: String) {
        val record = records[id] ?: return
        records[id] = record.copy(blockedReason = reason)
        dirty = true
        persist()
    }
    @Synchronized fun retryBlocked() {
        loadIfNeeded()
        records.replaceAll { _, value -> value.copy(blockedReason = null) }
        dirty = true
        persist()
    }
    @Synchronized fun size(): Int = records.keys.count { it !in acknowledged }
    @Synchronized fun flushStorage() {
        loadIfNeeded()
        if (dirty) persist()
    }
    private fun persist() {
        loadIfNeeded()
        store.save(records.values.filter { it.track.id !in acknowledged })
        records.keys.removeAll(acknowledged)
        acknowledged.clear()
        dirty = false
    }
}
