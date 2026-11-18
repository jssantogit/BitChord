package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Different Media3 items can load playlists/segments concurrently. A single
 * shared factory would allow a later source resolve to overwrite another
 * item's provider authorization; each source item needs its own transport.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class SourceScopedMediaSourceFactoryTest {
    private val unused = DataSource.Factory {
        error("Factory construction must not open a request")
    }

    @Test fun everySourceMediaItemGetsIndependentScopedFactory() {
        var created = 0
        val factory = SourceScopedMediaSourceFactory(
            defaultFactory = DefaultMediaSourceFactory(unused),
            createSourceFactory = {
                created++
                DefaultMediaSourceFactory(unused)
            },
        )
        val first = MediaItem.Builder()
            .setUri("bitchord://source?s=addon-one&t=x")
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val second = MediaItem.Builder()
            .setUri("bitchord://source?s=addon-two&t=y")
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
        factory.createMediaSource(first)
        factory.createMediaSource(second)
        assertEquals(2, created)
    }

    @Test fun localAndUnrelatedItemsPreserveDefaultFactory() {
        var created = 0
        val factory = SourceScopedMediaSourceFactory(
            defaultFactory = DefaultMediaSourceFactory(unused),
            createSourceFactory = {
                created++
                DefaultMediaSourceFactory(unused)
            },
        )
        factory.createMediaSource(MediaItem.fromUri("file:///sdcard/Music/a.flac"))
        factory.createMediaSource(MediaItem.fromUri("https://example.invalid/a.flac"))
        factory.createMediaSource(MediaItem.fromUri("bitchord://canonical?recordingId=x"))
        assertEquals(0, created)
    }
}
