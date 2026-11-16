package com.akikiri

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.sun.nio.file.ExtendedOpenOption
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class PendingScrobbleRecoveryTest {
    @TempDir lateinit var directory: Path
    private val gson = Gson()
    private val first = ScrobbleTrack(TrackMetadata("歌曲 A", "Artist"), 100, 40, "a")
    private val second = first.copy(id = "b", startedAtEpochSeconds = 200)
    private fun json(track: ScrobbleTrack) = gson.toJson(PendingScrobble(track))
    private fun load(text: String): PendingScrobbles {
        val path = directory.resolve("pending.json")
        Files.writeString(path, text)
        return PendingScrobbles(FileScrobbleStore(path))
    }

    private val path get() = directory.resolve("pending.json")
    private fun backups() = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().startsWith("pending.json.corrupt-") }.toList()
    }

    @Test fun `complete records survive a truncated final record`() {
        val source = "[${json(first)},${json(second)},{\"track\":{\"metadata\":"
        val queue = load(source)
        assertEquals(listOf(first, second), queue.ready())
        assertEquals(source, Files.readString(backups().single()))
        assertEquals(listOf(first, second), PendingScrobbles(FileScrobbleStore(path)).ready())
        assertEquals(1, backups().size)
        assertEquals(2, gson.fromJson(Files.readString(path), com.google.gson.JsonArray::class.java).size())
    }

    @Test fun `complete last record survives missing closing array bracket`() {
        assertEquals(listOf(first, second), load("[${json(first)},${json(second)}").ready())
    }

    @Test fun `syntax error in a complete middle object does not discard later records`() {
        assertEquals(listOf(first, second), load("[${json(first)},{\"track\": nope},${json(second)}]").ready())
    }

    @Test fun `invalid values and nested arrays do not become records`() {
        val source = "[null,12,\"text\",[${json(second)}],{},${json(first)}]"
        assertEquals(listOf(first), load(source).ready())
    }

    @Test fun `separators and stray closing brace cannot hang recovery`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertEquals(listOf(first, second), load("[,},${json(first)},,${json(second)},]").ready())
        }
    }

    @Test fun `strings containing delimiters escapes and unicode round trip`() {
        val track = first.copy(metadata = TrackMetadata("雪 { [ , ] } \\\" end\\", "歌手", "专辑"))
        assertEquals(listOf(track, second), load("[${json(track)},${json(second)}]").ready())
    }

    @Test fun `invalid utf8 discards affected record and keeps both neighbours`() {
        val path = directory.resolve("pending.json")
        val before = "[${json(first)},".toByteArray(UTF_8)
        val broken = json(second.copy(id = "broken")).replace("歌曲 A", "BAD").toByteArray(UTF_8)
        val index = String(broken, UTF_8).indexOf("BAD")
        broken[index] = 0x80.toByte()
        val bytes = before + broken + ",${json(second)}]".toByteArray(UTF_8)
        Files.write(path, bytes)
        assertEquals(listOf(first, second), PendingScrobbles(FileScrobbleStore(path)).ready())
        assertArrayEquals(bytes, Files.readAllBytes(backups().single()))
    }

    @Test fun `literal and escaped replacement characters are valid metadata`() {
        val track = first.copy(metadata = first.metadata.copy(title = "a�b"))
        for (title in listOf("a�b", "a\\uFFFDb")) {
            assertEquals(listOf(track), load("[${json(first).replace("歌曲 A", title)}]").ready())
            assertTrue(backups().isEmpty())
        }
    }

    @Test fun `all byte truncations retain each fully written record only`() {
        val a = json(first).toByteArray(UTF_8)
        val b = json(second).toByteArray(UTF_8)
        val bytes = "[".toByteArray() + a + ",".toByteArray() + b + "]".toByteArray()
        val path = directory.resolve("pending.json")
        for (end in 0..bytes.size) {
            Files.write(path, bytes.copyOf(end))
            val expected = buildList {
                if (end >= 1 + a.size) add(first)
                if (end >= 2 + a.size + b.size) add(second)
            }
            assertEquals(expected, PendingScrobbles(FileScrobbleStore(path)).ready(), "byte offset $end")
        }
    }

    @Test fun `missing invalid and wrongly typed fields do not enter queue`() {
        val good = gson.toJsonTree(PendingScrobble(first)).asJsonObject
        val invalid = mutableListOf<String>()
        for (name in listOf("id", "metadata", "startedAtEpochSeconds", "durationSeconds")) {
            invalid += good.deepCopy().apply { getAsJsonObject("track").remove(name) }.toString()
        }
        for (name in listOf("title", "artist")) {
            invalid += good.deepCopy().apply { getAsJsonObject("track").getAsJsonObject("metadata").remove(name) }.toString()
        }
        for (value in listOf("null", "0", "-1", "1.5", "9223372036854775808", "\"100\"")) {
            invalid += json(first).replace("\"startedAtEpochSeconds\":100", "\"startedAtEpochSeconds\":$value")
        }
        invalid += json(first).replace("\"title\":\"歌曲 A\"", "\"title\":123")
        invalid += json(first).replace("\"id\":\"a\"", "\"id\":\" \"")
        assertEquals(listOf(second), load("[${invalid.joinToString(",")},${json(second)}]").ready())
    }

    @Test fun `absent optional fields retain legacy records with defaults`() {
        val legacy = """{"track":{"id":"a","metadata":{"title":"歌曲 A","artist":"Artist"},"startedAtEpochSeconds":100,"durationSeconds":40}}"""
        assertEquals(listOf(first), load("[$legacy]").ready())
    }

    @Test fun `blocked status and library id survive recovery`() {
        val blocked = PendingScrobble(first.copy(libraryTrackId = "library"), "Rejected")
        val queue = load("[${gson.toJson(blocked)},${json(second)},{")
        assertEquals(listOf(second), queue.ready())
        assertEquals(2, queue.size())
        queue.retryBlocked()
        assertEquals(listOf(blocked.track, second), queue.ready())
        assertEquals(listOf(blocked.track, second), PendingScrobbles(FileScrobbleStore(directory.resolve("pending.json"))).ready())
    }

    @Test fun `unrecoverable content opens empty queue which can save new records`() {
        for (text in listOf("", "garbage", "{broken", "null", "[", "[{\"track\":", "[\"unterminated", "[] trailing garbage")) {
            val queue = load(text)
            assertTrue(queue.ready().isEmpty())
            queue.enqueue(first)
            assertEquals(listOf(first), PendingScrobbles(FileScrobbleStore(directory.resolve("pending.json"))).ready())
        }
    }

    @Test fun `utf8 bom does not discard records`() {
        assertEquals(listOf(first), load("\uFEFF[${json(first)}]").ready())
    }

    @Test fun `recovered record confirmation and new records persist across restart`() {
        val queue = load("[${json(first)},${json(second)},{")
        queue.confirm(first.id)
        val third = first.copy(id = "c", startedAtEpochSeconds = 300)
        queue.enqueue(third)
        assertEquals(listOf(second, third), PendingScrobbles(FileScrobbleStore(directory.resolve("pending.json"))).ready())
    }

    @Test fun `read failure continues empty and write failure can be retried`() {
        val path = Files.createDirectory(directory.resolve("pending.json"))
        val keep = Files.writeString(path.resolve("keep"), "existing")
        val queue = PendingScrobbles(FileScrobbleStore(path))
        assertTrue(queue.ready().isEmpty())
        assertThrows(IOException::class.java) { queue.enqueue(first) }
        assertEquals("existing", Files.readString(keep))
        assertEquals(listOf(first), queue.ready())
        Files.delete(keep)
        Files.delete(path)
        queue.flushStorage()
        assertEquals(listOf(first), PendingScrobbles(FileScrobbleStore(path)).ready())
    }
    @Test fun `recovery stops at the outer array even after a primitive`() {
        for (last in listOf("null", "1", "true")) {
            val source = "[${json(first)},$last]${json(second)}"
            assertEquals(listOf(first), load(source).ready())
        }
    }

    @Test fun `backup failure leaves the active file intact and retry recovers it`() {
        val source = "[${json(first)},{"
        Files.writeString(path, source)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))
        val obstruction = Files.createDirectory(directory.resolve("pending.json.corrupt-$hash"))
        val queue = PendingScrobbles(FileScrobbleStore(path))
        assertThrows(IOException::class.java) { queue.enqueue(second) }
        assertEquals(source, Files.readString(path))
        Files.delete(obstruction)
        queue.flushStorage()
        assertEquals(listOf(first, second), PendingScrobbles(FileScrobbleStore(path)).ready())
        assertEquals(source, Files.readString(backups().single()))
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    fun `read lock defers persistence and merges new entries with the original blocked record`() {
        val blocked = PendingScrobble(first, "Rejected")
        FileScrobbleStore(path).save(listOf(blocked))
        val bytes = Files.readAllBytes(path)
        val queue = FileChannel.open(path, StandardOpenOption.WRITE, ExtendedOpenOption.NOSHARE_READ).use {
            val pending = PendingScrobbles(FileScrobbleStore(path))
            assertThrows(IOException::class.java) { pending.enqueue(first) }
            assertThrows(IOException::class.java) { pending.enqueue(second) }
            assertThrows(IOException::class.java) { pending.retryBlocked() }
            pending
        }
        assertArrayEquals(bytes, Files.readAllBytes(path))
        queue.flushStorage()
        assertEquals(listOf(second), queue.ready())
        assertEquals(listOf(blocked, PendingScrobble(second)), FileScrobbleStore(path).load())
        assertTrue(backups().isEmpty())
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    fun `failed repair write reuses the byte identical backup on retry`() {
        val source = "[${json(first)},{"
        Files.writeString(path, source)
        val queue = FileChannel.open(path, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE).use {
            val pending = PendingScrobbles(FileScrobbleStore(path))
            assertThrows(IOException::class.java) { pending.flushStorage() }
            assertEquals(source, Files.readString(path))
            assertEquals(source, Files.readString(backups().single()))
            pending
        }
        queue.flushStorage()
        assertEquals(listOf(first), queue.ready())
        assertEquals(1, backups().size)
    }
}
