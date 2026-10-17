package com.akikiri

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LastFmApiClient(
    private val credentials: LastFmCredentials,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val apiUrl: HttpUrl = API_URL.toHttpUrl(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val jitterMillis: () -> Long = { Random.nextLong(0, 251) },
) : LastFmGateway {
    override fun authorizationUrl(token: String): String =
        AUTH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("api_key", credentials.apiKey)
            .addQueryParameter("token", token)
            .build()
            .toString()

    override suspend fun requestToken(): String = withRetry {
        val response = get(
            mapOf(
                "method" to "auth.getToken",
                "api_key" to credentials.apiKey,
            ),
        )
        response.string("token")
    }

    override suspend fun requestSessionKey(token: String): LastFmSession = withRetry {
        val response = get(
            mapOf(
                "method" to "auth.getSession",
                "api_key" to credentials.apiKey,
                "token" to token,
            ),
        )
        val session = response.objectValue("session")
        LastFmSession(session.string("key"), session.get("name")?.asString.orEmpty())
    }

    override suspend fun updateNowPlaying(
        sessionKey: String,
        track: TrackMetadata,
        durationSeconds: Long?,
    ) {
        require(track.isValid) { "歌曲标题和艺术家不能为空" }
        run {
            val parameters = mutableMapOf(
                "method" to "track.updateNowPlaying",
                "artist" to track.artist,
                "track" to track.title,
                "api_key" to credentials.apiKey,
                "sk" to sessionKey,
            )
            track.album.takeIf(String::isNotBlank)?.let { parameters["album"] = it }
            track.albumArtist.takeIf(String::isNotBlank)?.let { parameters["albumArtist"] = it }
            durationSeconds?.takeIf { it > 0 }?.let { parameters["duration"] = it.toString() }

            val response = post(parameters)
            if (!response.has("nowplaying")) {
                throw LastFmApiException(null, "Last.fm 未确认 Now Playing", false)
            }
            val ignored = response.objectValue("nowplaying").getAsJsonObject("ignoredMessage")
            if (ignored != null && ignored.get("code")?.asInt != 0) {
                throw LastFmApiException(null, ignored.get("#text")?.asString
                    ?: "Last.fm 忽略了 Now Playing", false)
            }
        }
    }

    override suspend fun scrobble(sessionKey: String, track: ScrobbleTrack) {
        require(track.metadata.isValid) { "歌曲标题和艺术家不能为空" }
        withRetry {
            val parameters = mutableMapOf(
                "method" to "track.scrobble",
                "artist" to track.metadata.artist,
                "track" to track.metadata.title,
                "timestamp" to track.startedAtEpochSeconds.toString(),
                "duration" to track.durationSeconds.toString(),
                "api_key" to credentials.apiKey,
                "sk" to sessionKey,
            )
            track.metadata.album.takeIf(String::isNotBlank)?.let { parameters["album"] = it }
            track.metadata.albumArtist.takeIf(String::isNotBlank)
                ?.let { parameters["albumArtist"] = it }

            val response = post(parameters)
            val attributes = response.objectValue("scrobbles").objectValue("@attr")
            val accepted = attributes.intValue("accepted")
            val ignored = attributes.intValue("ignored")
            if (accepted != 1 || ignored != 0) {
                throw LastFmApiException(
                    errorCode = null,
                    message = "Last.fm 忽略了这条 Scrobble",
                    retryable = false,
                )
            }
        }
    }

    private suspend fun get(unsignedParameters: Map<String, String>): JsonObject {
        val parameters = signed(unsignedParameters)
        val url = apiUrl.newBuilder().apply {
            parameters.forEach(::addQueryParameter)
        }.build()
        return execute(Request.Builder().url(url).get().build())
    }

    private suspend fun post(unsignedParameters: Map<String, String>): JsonObject {
        val parameters = signed(unsignedParameters)
        val body = FormBody.Builder().apply {
            parameters.forEach(::add)
        }.build()
        return execute(Request.Builder().url(apiUrl).post(body).build())
    }

    private fun signed(unsignedParameters: Map<String, String>): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            putAll(unsignedParameters)
            put("api_sig", ApiSigner.sign(unsignedParameters, credentials.apiSecret))
            put("format", "json")
        }

    private suspend fun execute(request: Request): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { continuation.resume(parseResponse(it)) }
                } catch (exception: Exception) {
                    continuation.resumeWithException(exception)
                }
            }
        })
    }

    private fun parseResponse(response: Response): JsonObject {
            val responseBody = response.body?.string()
                ?: throw LastFmApiException(
                    errorCode = null,
                    message = "Last.fm 返回了空响应",
                    retryable = response.code >= 500,
                )

            val json = try {
                JsonParser.parseString(responseBody).asJsonObject
            } catch (exception: Exception) {
                throw LastFmApiException(
                    errorCode = null,
                    message = "无法解析 Last.fm 响应（HTTP ${response.code}）",
                    retryable = response.code == 429 || response.code >= 500,
                )
            }

            if (json.has("error")) {
                val code = json.get("error").asInt
                val message = json.get("message")?.asString ?: "Last.fm API 错误 $code"
                throw LastFmApiException(code, message, code in RETRYABLE_ERROR_CODES)
            }

            if (!response.isSuccessful) {
                throw LastFmApiException(
                    errorCode = response.code,
                    message = "Last.fm HTTP ${response.code}",
                    retryable = response.code == 429 || response.code >= 500,
                )
            }

            return json
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastFailure: Throwable? = null

        repeat(retryPolicy.maxAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                lastFailure = exception
                val retryable = when (exception) {
                    is LastFmApiException -> exception.retryable
                    is IOException -> true
                    else -> false
                }
                if (!retryable || attempt == retryPolicy.maxAttempts - 1) {
                    throw exception
                }

                val exponent = min(attempt, 10)
                val backoff = retryPolicy.baseDelayMillis * (1L shl exponent)
                delay(backoff + jitterMillis())
            }
        }

        throw checkNotNull(lastFailure)
    }

    override fun close() {
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
            ?: throw LastFmApiException(null, "Last.fm 响应缺少 $key", false)

    private fun JsonObject.objectValue(key: String): JsonObject =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw LastFmApiException(null, "Last.fm 响应缺少 $key", false)

    private fun JsonObject.intValue(key: String): Int =
        get(key)?.takeUnless { it.isJsonNull }?.asInt
            ?: throw LastFmApiException(null, "Last.fm 响应缺少 $key", false)

    companion object {
        private const val API_URL = "https://ws.audioscrobbler.com/2.0/"
        private const val AUTH_URL = "https://www.last.fm/api/auth/"
        private val RETRYABLE_ERROR_CODES = setOf(11, 16, 29)

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
