package com.akikiri

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class AuthState(
    val sessionKey: String? = null,
    val token: String? = null,
    val tokenCreatedAtEpochSeconds: Long? = null,
    val username: String? = null,
    val applicationKey: String? = null,
)

internal interface SessionStore {
    fun load(): AuthState
    fun save(state: AuthState)
}

internal class FileSessionStore(
    private val path: Path,
    private val gson: Gson = Gson(),
) : SessionStore {
    private val lock = ReentrantLock()

    override fun load(): AuthState = lock.withLock {
        if (Files.notExists(path)) {
            return AuthState()
        }

        val json = Files.readString(path, StandardCharsets.UTF_8)
        if (json.isBlank()) {
            return AuthState()
        }

        try {
            gson.fromJson(json, AuthState::class.java) ?: AuthState()
        } catch (exception: JsonSyntaxException) {
            Files.move(path, path.resolveSibling("${path.fileName}.corrupt-${java.util.UUID.randomUUID()}"))
            java.util.logging.Logger.getLogger(FileSessionStore::class.java.name)
                .warning("Last.fm Session 文件损坏，已保留副本，请重新授权")
            AuthState()
        }
    }

    override fun save(state: AuthState) {
        lock.withLock {
            val parent = requireNotNull(path.parent) { "Session 文件必须位于目录中" }
            Files.createDirectories(parent)

            try {
                val temporary = Files.createTempFile(parent, path.fileName.toString(), ".tmp")
                try {
                    Files.writeString(
                        temporary,
                        gson.toJson(state),
                        StandardCharsets.UTF_8,
                    )
                    try {
                        Files.move(
                            temporary,
                            path,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
            } catch (exception: Exception) {
                throw IllegalStateException("无法保存 Last.fm Session", exception)
            }
        }
    }
}
