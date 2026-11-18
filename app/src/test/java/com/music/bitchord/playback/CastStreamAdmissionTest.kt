package com.music.bitchord.playback

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import org.robolectric.RobolectricTestRunner

/**
 * A Cast receiver cannot honor an in-process extension capability revocation
 * after being handed a resolved CDN URL. Do not export such URLs from BitChord.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class CastStreamAdmissionTest {
    @Test fun sourceBackedAddonNeverHandsUnrevocableCdnUrlToCast() {
        assertFalse(canHandOffToCastReceiver(
            Uri.parse("bitchord://source?s=addon-one&t=recording")
        ))
        assertFalse(canHandOffToCastReceiver(
            Uri.parse("bitchord://source?s=module-two&t=recording")
        ))
    }

    @Test fun ordinaryLocalAndWebStreamsRetainPriorCastAdmissibility() {
        assertTrue(canHandOffToCastReceiver(Uri.parse("file:///storage/emulated/0/Music/a.flac")))
        assertTrue(canHandOffToCastReceiver(Uri.parse("content://media/external/audio/9")))
        assertTrue(canHandOffToCastReceiver(Uri.parse("https://example.invalid/audio.flac")))
    }

    @Test fun canonicalProviderRemoteIsNotExportedToCastEither() {
        assertFalse(canHandOffToCastReceiver(
            Uri.parse("bitchord://canonical?recordingId=r&mediaAssetId=a&providerKey=opensubsonic%3Ahome")
        ))
    }
}
