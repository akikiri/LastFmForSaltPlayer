package com.akikiri

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object ApiSigner {
    fun sign(parameters: Map<String, String>, secret: String): String {
        val source = buildString {
            parameters.toSortedMap().forEach { (key, value) ->
                append(key)
                append(value)
            }
            append(secret)
        }

        return MessageDigest.getInstance("MD5")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
