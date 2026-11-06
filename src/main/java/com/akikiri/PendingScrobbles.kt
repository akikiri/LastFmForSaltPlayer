package com.akikiri

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.reflect.TypeToken
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Logger

internal data class PendingScrobble(val track: ScrobbleTrack, val blockedReason: String? = null)

internal interface ScrobbleStore {
    fun load(): List<PendingScrobble>
    fun save(records: List<PendingScrobble>)
}

internal class FileScrobbleStore(private val path: Path) : ScrobbleStore {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()

    override fun load(): List<PendingScrobble> {
        if (Files.notExists(path)) return emptyList()
        val json = try {
            Files.readString(path)
        } catch (_: CharacterCodingException) {
            return preserveCorruptFile()
        }
        return try {
            val records = gson.fromJson<List<PendingScrobble?>>(
                json, object : TypeToken<List<PendingScrobble?>>() {}.type,
            ) ?: throw JsonSyntaxException("Expected a pending scrobble list")
            records.map { record ->
                val track = record?.track
                val metadata = track?.metadata
                if (record == null || track == null || track.id.isNullOrBlank() || metadata == null) {
                    throw JsonSyntaxException("Invalid pending scrobble record")
                }
                record
            }
        } catch (_: JsonSyntaxException) {
            preserveCorruptFile()
        }
    }

    private fun preserveCorruptFile(): List<PendingScrobble> {
        val backup = path.resolveSibling("${path.fileName}.corrupt-${UUID.randomUUID()}")
        // Only start an empty queue once the original bytes are safely out of the save path.
        Files.move(path, backup)
        Logger.getLogger(FileScrobbleStore::class.java.name)
            .warning("Last.fm 待提交队列文件损坏，已保留为 ${backup.fileName}")
        return emptyList()
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
    private val records = store.load().associateByTo(linkedMapOf()) { it.track.id }
    private val seen = records.keys.toMutableSet()
    private val acknowledged = mutableSetOf<String>()
    private var dirty = false

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
        records.replaceAll { _, value -> value.copy(blockedReason = null) }
        dirty = true
        persist()
    }
    @Synchronized fun size(): Int = records.keys.count { it !in acknowledged }
    @Synchronized fun flushStorage() { if (dirty) persist() }
    private fun persist() {
        store.save(records.values.filter { it.track.id !in acknowledged })
        records.keys.removeAll(acknowledged)
        acknowledged.clear()
        dirty = false
    }
}
