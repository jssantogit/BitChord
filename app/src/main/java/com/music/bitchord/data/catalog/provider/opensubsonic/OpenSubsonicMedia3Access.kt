package com.music.bitchord.data.catalog.provider.opensubsonic

import android.net.Uri
import androidx.media3.datasource.DataSpec

/**
 * Provider-owned transport adapter for Media3.
 *
 * The runtime request stays opaque outside this package. Only here are its
 * private source locator and authentication material turned into the ephemeral
 * URI Media3 will open. Callers must not log or persist the returned DataSpec.
 */
internal fun OpenSubsonicRuntimeAudioRequest.materializeDataSpec(
    logicalDataSpec: DataSpec,
): DataSpec {
    val uriBuilder = Uri.parse(endpointUrl).buildUpon()
    materializeParameters().forEach { (key, value) ->
        uriBuilder.appendQueryParameter(key, value)
    }
    return logicalDataSpec.buildUpon()
        .setUri(uriBuilder.build())
        .build()
}
