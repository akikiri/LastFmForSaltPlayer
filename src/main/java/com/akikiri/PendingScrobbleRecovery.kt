package com.akikiri

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal object PendingScrobbleRecovery {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    data class Result(val records: List<PendingScrobble>, val damaged: Boolean)

    fun read(bytes: ByteArray): Result {
        val root = parse(bytes, 0, bytes.size)
        if (root != null) {
            if (!root.isJsonArray) return Result(emptyList(), damaged = true)
            val array = root.asJsonArray
            val records = array.mapNotNull(::decode)
            return Result(records, damaged = records.size != array.size())
        }
        return Result(recoverArrayEntries(bytes), damaged = true)
    }

    private fun parse(bytes: ByteArray, start: Int, end: Int): JsonElement? = try {
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, start, end - start))
        gson.fromJson(text.toString(), JsonElement::class.java)
    } catch (_: CharacterCodingException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    // JSON delimiters are ASCII. Retain byte offsets so each complete object can
    // be decoded strictly even when another entry contains invalid UTF-8.
    private fun recoverArrayEntries(bytes: ByteArray): List<PendingScrobble> {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        var index = if (bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it] }) bom.size else 0
        fun charAt(i: Int) = bytes[i].toInt().toChar()
        fun isSpace(c: Char) = c == ' ' || c == '\n' || c == '\r' || c == '\t'
        while (index < bytes.size && isSpace(charAt(index))) index++
        if (index == bytes.size || charAt(index) != '[') return emptyList()
        index++

        val records = mutableListOf<PendingScrobble>()
        val closers = ArrayDeque<Char>()
        var start = index
        var quoted = false
        var escaped = false
        while (index < bytes.size) {
            val c = charAt(index)
            if (quoted) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> quoted = false
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    '{' -> closers.addLast('}')
                    '[' -> closers.addLast(']')
                    '}', ']' -> {
                        if (closers.isEmpty()) {
                            if (c == ']') break
                        } else {
                            if (closers.removeLast() != c) break
                            if (closers.isEmpty() && start >= 0) {
                                if (charAt(start) == '{') {
                                    parse(bytes, start, index + 1)?.let(::decode)?.let(records::add)
                                }
                                start = -1 // Wait for the next top-level comma.
                            }
                        }
                    }
                    ',' -> if (closers.isEmpty()) start = index + 1
                    else -> if (index == start && isSpace(c)) start++
                }
            }
            index++
        }
        return records
    }

    private fun decode(element: JsonElement): PendingScrobble? {
        if (!element.isJsonObject) return null
        val record = element.asJsonObject
        return try {
            val track = record.objectField("track")
            val metadata = track.objectField("metadata")
            PendingScrobble(
                ScrobbleTrack(
                    metadata = TrackMetadata(
                        title = metadata.requiredString("title"),
                        artist = metadata.requiredString("artist"),
                        album = metadata.optionalString("album").orEmpty(),
                        albumArtist = metadata.optionalString("albumArtist").orEmpty(),
                    ),
                    startedAtEpochSeconds = track.positiveLong("startedAtEpochSeconds"),
                    durationSeconds = track.positiveLong("durationSeconds"),
                    id = track.requiredString("id"),
                    libraryTrackId = track.optionalString("libraryTrackId"),
                ),
                blockedReason = record.optionalString("blockedReason"),
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun JsonObject.objectField(name: String): JsonObject {
        val value = get(name)
        require(value != null && value.isJsonObject)
        return value.asJsonObject
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun JsonObject.requiredString(name: String): String {
        return requireNotNull(optionalString(name)).also { require(it.isNotBlank()) }
    }

    private fun JsonObject.positiveLong(name: String): Long {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.longValueExact().also { require(it > 0) }
    }
}
