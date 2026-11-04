package com.music.bitchord.ui.screens

import com.music.bitchord.data.catalog.CanonicalBrowseMediaAsset
import com.music.bitchord.data.catalog.CanonicalBrowseTrack
import com.music.bitchord.ui.components.SourceQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Catalog evidence, never quality guessed from a release name or actual stream playback. */
class CanonicalAlbumSourceQualityTest {
    private fun asset(
        id: String,
        provider: String = "opensubsonic:home",
        mime: String? = null,
        suffix: String? = null,
        bitrate: Long? = null,
        depth: Int? = null,
        sampleRate: Int? = null,
    ) = CanonicalBrowseMediaAsset(
        mediaAssetId = id, providerKey = provider,
        audioContentType = mime, audioSuffix = suffix,
        audioBitrateBps = bitrate, audioBitDepth = depth,
        audioSamplingRateHz = sampleRate,
    )

    private fun track(id: String, vararg assets: CanonicalBrowseMediaAsset) =
        CanonicalBrowseTrack(id, id, "Artist", 100_000, null, assets.toList())

    @Test fun serverFlac24Bit96kHzIsHiResAndOrdinaryFlacIsLossless() {
        assertEquals(SourceQuality.HI_RES_LOSSLESS, canonicalAssetSourceQuality(
            asset("24", mime = "audio/flac", depth = 24, sampleRate = 96000)))
        assertEquals(SourceQuality.LOSSLESS, canonicalAssetSourceQuality(
            asset("16", mime = "audio/flac", depth = 16, sampleRate = 44100)))
        assertEquals(SourceQuality.LOSSLESS, canonicalAssetSourceQuality(
            asset("unknown-depth", suffix = "flac")))
    }

    @Test fun localMediaStoreMimeAndBitrateCanShowHighQualityButNeverInventHiRes() {
        assertEquals(SourceQuality.LOSSLESS, canonicalAssetSourceQuality(
            asset("local-flac", provider = "local-mediastore", mime = "audio/flac",
                bitrate = 900_000)))
        assertEquals(SourceQuality.HIGH_QUALITY, canonicalAssetSourceQuality(
            asset("local-mp3", provider = "local-mediastore", mime = "audio/mpeg",
                bitrate = 320_000)))
        assertNull(canonicalAssetSourceQuality(
            asset("local-no-mime", provider = "local-mediastore", bitrate = 900_000)))
        assertNull(canonicalAssetSourceQuality(
            asset("poor-mp3", provider = "local-mediastore", mime = "audio/mpeg",
                bitrate = 128_000)))
    }

    @Test fun albumShowsOnlyUnanimouslyKnownTierAcrossAllTracksAndSourceCopies() {
        val flac = asset("r1", mime = "audio/flac", depth = 16, sampleRate = 44100)
        val hiRes = asset("r2", mime = "audio/flac", depth = 24, sampleRate = 96000)
        assertEquals(SourceQuality.LOSSLESS, canonicalAlbumSourceQuality(
            listOf(track("a", flac), track("b", flac.copy(mediaAssetId = "r3")))))
        assertNull(canonicalAlbumSourceQuality(listOf(track("a", flac), track("b", hiRes))))
        assertNull(canonicalAlbumSourceQuality(listOf(track("a", flac, hiRes))))
        assertNull(canonicalAlbumSourceQuality(listOf(track("a", flac), track("b",
            asset("unknown")))))
        assertNull(canonicalAlbumSourceQuality(emptyList()))
    }

    @Test fun qualityUsesOnlySelectedSourceAssetsAndIgnoresUnavailableCopy() {
        val local = asset("local", provider = "local-mediastore",
            mime = "audio/flac", depth = 16, sampleRate = 44100)
        val remote = asset("remote", mime = "audio/flac", depth = 24, sampleRate = 96000)
        val album = listOf(track("a", local, remote), track("b", local, remote))
        assertEquals(SourceQuality.LOSSLESS,
            canonicalAlbumSourceQuality(album, selectedProviderKey = "local-mediastore"))
        assertEquals(SourceQuality.HI_RES_LOSSLESS,
            canonicalAlbumSourceQuality(album, selectedProviderKey = "opensubsonic:home"))
        assertNull(canonicalAlbumSourceQuality(album))
    }

    @Test fun ambiguousOrUnsupportedCodecAndHighBitrateRemainUnknown() {
        assertNull(canonicalAssetSourceQuality(asset("unknown", bitrate = 1_200_000)))
        assertNull(canonicalAssetSourceQuality(asset("unknown", mime = "audio/unknown",
            suffix = "flac", bitrate = 1_200_000)))
        assertNull(canonicalAssetSourceQuality(asset("fake", mime = "audio/mpeg",
            depth = 24, sampleRate = 96000, bitrate = 320_000)))
    }
}
