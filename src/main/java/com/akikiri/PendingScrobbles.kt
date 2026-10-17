package com.akikiri

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class PendingScrobble(val track: ScrobbleTrack, val blockedReason: String? = null)

internal interface ScrobbleStore {
    fun load(): List<PendingScrobble>
    fun save(records: List<PendingScrobble>)
}

internal class FileScrobbleStore(private val path: Path) : ScrobbleStore {
    private val gson = Gson()
    override fun load(): List<PendingScrobble> {
        if (!Files.exists(path)) return emptyList()
        return gson.fromJson<List<PendingScrobble>>(
            Files.readString(path), object : TypeToken<List<PendingScrobble>>() {}.type,
        ) ?: emptyList()
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
