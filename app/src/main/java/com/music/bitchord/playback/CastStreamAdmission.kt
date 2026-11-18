package com.music.bitchord.playback

import android.net.Uri

/**
 * Casting a source-backed track would export a resolved extension URL to a
 * remote receiver. Unlike Media3's in-process byte lease, that receiver
 * cannot observe a provider opt-out or cancel its active HTTP request.
 * Preserve local media and ordinary non-provider URLs, but fail closed for
 * either provider-owned logical BitChord route until revocable remote
 * playback has a dedicated contract.
 */
internal fun canHandOffToCastReceiver(uri: Uri): Boolean =
    !(uri.scheme.equals("bitchord", ignoreCase = true) &&
        (uri.authority == "source" || uri.authority == "canonical"))
