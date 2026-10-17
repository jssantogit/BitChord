package com.music.bitchord.data.catalog.provider.opensubsonic

import java.security.MessageDigest
import java.util.UUID

internal const val OPEN_SUBSONIC_API_VERSION = "1.16.1"
internal const val OPEN_SUBSONIC_CLIENT_NAME = "BitChord"

internal fun defaultOpenSubsonicSalt(): String =
    UUID.randomUUID().toString().replace("-", "").take(16)

/**
 * Shared OpenSubsonic authentication rule used by catalog API calls and
 * ephemeral runtime media access. Callers provide a salt factory so password
 * authentication produces a fresh token at request time and stays testable.
 */
internal fun openSubsonicAuthenticationParameters(
    auth: OpenSubsonicAuth,
    saltFactory: () -> String = ::defaultOpenSubsonicSalt,
): Map<String, String> = when (auth) {
    is OpenSubsonicAuth.ApiKey -> mapOf("apiKey" to auth.value)
    is OpenSubsonicAuth.Password -> {
        val salt = saltFactory().trim()
        require(salt.length >= 6) { "OpenSubsonic auth salt must be at least 6 characters" }
        mapOf(
            "u" to auth.username,
            "s" to salt,
            "t" to md5(auth.password + salt),
        )
    }
}

private fun md5(value: String): String = MessageDigest.getInstance("MD5")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
