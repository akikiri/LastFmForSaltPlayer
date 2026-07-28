package com.akikiri

import java.util.Properties

internal object CredentialsLoader {
    fun load(): LastFmCredentials {
        val properties = Properties()
        val resource = CredentialsLoader::class.java.classLoader
            .getResourceAsStream("lastfm.properties")
            ?: error("缺少 lastfm.properties")

        resource.use(properties::load)

        val apiKey = System.getenv("LASTFM_API_KEY")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: properties.getProperty("api.key", "").trim()
        val apiSecret = System.getenv("LASTFM_API_SECRET")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: properties.getProperty("api.secret", "").trim()

        require(API_CREDENTIAL_PATTERN.matches(apiKey)) {
            "Last.fm API key 未配置或格式错误"
        }
        require(API_CREDENTIAL_PATTERN.matches(apiSecret)) {
            "Last.fm API secret 未配置或格式错误"
        }

        return LastFmCredentials(apiKey, apiSecret)
    }

    private val API_CREDENTIAL_PATTERN = Regex("^[0-9a-fA-F]{32}$")
}
