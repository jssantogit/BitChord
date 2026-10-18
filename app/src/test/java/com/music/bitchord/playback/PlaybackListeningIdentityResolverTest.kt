package com.music.bitchord.playback

import com.music.bitchord.data.listening.SelectionIntent
import com.music.bitchord.data.listening.SelectionOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackListeningIdentityResolverTest {
    @Test
    fun canonicalRemoteIdentityWinsWithoutCatalogOrProviderLookup() = runTest {
        var localLookupCalled = false
        var legacyLookupCalled = false
        val resolver = PlaybackListeningIdentityResolver(
            resolveLocalAsset = {
                localLookupCalled = true
                null
            },
            resolveLegacyYoutubeAlias = {
                legacyLookupCalled = true
                null
            },
        )

        val identity = resolver.resolve(
            canonicalUri = "bitchord://canonical?recordingId=recording-1&mediaAssetId=asset-9&providerKey=opensubsonic%3Ahome&sourceKey=native-song-42&token=must-not-cross",
            localSourceKey = "content://should-not-be-used",
            legacyYoutubeId = "abcdefghijk",
        )

        assertEquals(
            CanonicalPlaybackIdentity(
                recordingId = "recording-1",
                mediaAssetId = "asset-9",
                provider = "opensubsonic:home",
            ),
            identity,
        )
        assertFalse(localLookupCalled)
        assertFalse(legacyLookupCalled)
    }

    @Test
    fun canonicalRemoteStartCarriesOnlySafeCanonicalIdentityAndProvenance() = runTest {
        val resolver = PlaybackListeningIdentityResolver(
            resolveLocalAsset = { error("canonical remote must not query local catalog identity") },
            resolveLegacyYoutubeAlias = { error("canonical remote must not query legacy alias") },
        )
        val identity = requireNotNull(
            resolver.resolve(
                canonicalUri = "bitchord://canonical?recordingId=recording-1&mediaAssetId=asset-9&providerKey=opensubsonic%3Ahome&sourceKey=native-secret&token=auth-secret",
                localSourceKey = null,
                legacyYoutubeId = null,
            ),
        )

        val start = identity.toPlaybackStart(
            selectionIntent = SelectionIntent.MANUAL,
            selectionOrigin = SelectionOrigin.LIBRARY,
        )

        assertEquals("recording-1", start.recordingId)
        assertEquals("asset-9", start.mediaAssetId)
        assertEquals("opensubsonic:home", start.assetProvider)
        assertEquals(SelectionIntent.MANUAL, start.selectionIntent)
        assertEquals(SelectionOrigin.LIBRARY, start.selectionOrigin)
        assertFalse(start.toString().contains("native-secret"))
        assertFalse(start.toString().contains("auth-secret"))
    }

    @Test
    fun localCanonicalMappingIsUsedWhenThereIsNoCanonicalRemoteUri() = runTest {
        val resolver = PlaybackListeningIdentityResolver(
            resolveLocalAsset = { sourceKey ->
                if (sourceKey == "content://media/7") {
                    CanonicalPlaybackIdentity("local-recording", "local-asset", "local-mediastore")
                } else {
                    null
                }
            },
            resolveLegacyYoutubeAlias = { null },
        )

        assertEquals(
            CanonicalPlaybackIdentity("local-recording", "local-asset", "local-mediastore"),
            resolver.resolve(
                canonicalUri = null,
                localSourceKey = "content://media/7",
                legacyYoutubeId = "not-used",
            ),
        )
    }

    @Test
    fun legacyAliasRequiresAProvenCanonicalExternalIdentity() = runTest {
        val resolver = PlaybackListeningIdentityResolver(
            resolveLocalAsset = { null },
            resolveLegacyYoutubeAlias = { externalId ->
                externalId.takeIf { it == "abcdefghijk" }?.let {
                    CanonicalPlaybackIdentity("youtube-recording", null, "youtube")
                }
            },
        )

        assertEquals(
            CanonicalPlaybackIdentity("youtube-recording", null, "youtube"),
            resolver.resolve(null, null, "abcdefghijk"),
        )
        assertNull(resolver.resolve(null, null, "unknown-id"))
    }

    @Test
    fun invalidOrUnknownIdentityIsIgnoredRatherThanInvented() = runTest {
        val resolver = PlaybackListeningIdentityResolver(
            resolveLocalAsset = { null },
            resolveLegacyYoutubeAlias = { null },
        )

        assertNull(
            resolver.resolve(
                canonicalUri = "bitchord://canonical?recordingId=&mediaAssetId=asset&providerKey=opensubsonic%3Ahome",
                localSourceKey = null,
                legacyYoutubeId = "provider-native-id",
            ),
        )
    }

    @Test
    fun safeIdentityContainsOnlyCanonicalAndProvenanceFields() {
        val identity = CanonicalPlaybackIdentity("recording", "asset", "opensubsonic:home")

        assertEquals(
            setOf("recordingId", "mediaAssetId", "provider"),
            identity::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet(),
        )
        assertTrue(identity.provider.startsWith("opensubsonic:"))
    }
}
