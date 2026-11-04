package com.music.bitchord

import android.net.Uri
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.playback.LinkRequest
import com.music.bitchord.playback.MusicLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class LegacyYoutubeLinkCompatibilityTest {
    @Test fun historicalSharedLinksRemainRecognizableButNeverGrantYoutubeAudio() {
        assertEquals(LinkRequest.Track("oldVideo"), MusicLink.parse(
            Uri.parse("https://music.youtube.com/watch?v=oldVideo"),
        ))
        assertEquals(LinkRequest.Page("VLoldList"), MusicLink.parse(
            Uri.parse("https://music.youtube.com/playlist?list=oldList"),
        ))
        assertFalse(SourceRegistry.mayResolveLegacyYoutubeStream(emptyList()))
    }
}
