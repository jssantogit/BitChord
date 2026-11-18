package com.music.bitchord.playback

import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSourceCacheAdmissionTest {
    private fun assertEqualsCompat(expected: String, actual: String?) =
        org.junit.Assert.assertEquals(expected, actual)
    private val addon = ProviderKey("addon", "one")
    private val sibling = ProviderKey("custom_module", "two")

    private fun gate(addonOn: Boolean, siblingOn: Boolean = true) =
        ProviderPolicyGate(ProviderCapabilityPolicy(
            listOf(addon, sibling).map {
                ProviderDescriptor(it, setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    ProviderReadiness.READY)
            },
            mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(addon, addonOn, 0),
                ProviderChoice(sibling, siblingOn, 1),
            )),
        ))

    @Test fun remoteManifestCannotOpenFileContentOrSmbSegmentsOnThePhone() {
        val denied = listOf(
            "file" to null,
            "content" to "media",
            "smb" to "private-server",
            "ftp" to "remote",
            "data" to null,
            "bitchord" to "canonical",
            "bitchord" to "watch",
            null to null,
        )
        denied.forEach { (scheme, authority) ->
            try {
                requireAdaptiveSourceResourceScheme(scheme, authority)
                org.junit.Assert.fail("Unsafe adaptive segment scheme passed: $scheme")
            } catch (_: java.io.IOException) {
                // A manifest is untrusted input; it cannot gain device access.
            }
        }
    }

    @Test fun logicalSourceManifestAndHttpSegmentsRemainAvailable() {
        requireAdaptiveSourceResourceScheme("bitchord", "source")
        requireAdaptiveSourceResourceScheme("https", "cdn.example")
        requireAdaptiveSourceResourceScheme("http", "cdn.example")
    }

    @Test fun adaptiveSegmentsUseProviderScopedHashedKeysInsteadOfSharedVideoQuery() {
        val urlA = "https://cdn.example/track.ts?v=same&token=private-A"
        val urlB = "https://cdn.example/other.ts?v=same&token=private-B"
        val keyA = providerAdaptiveSegmentCacheKey(addon, urlA)
        val keyB = providerAdaptiveSegmentCacheKey(addon, urlB)
        val siblingKey = providerAdaptiveSegmentCacheKey(sibling, urlA)
        assertTrue(keyA.startsWith("source-segment:"))
        assertTrue(keyA != keyB)
        assertTrue(keyA != siblingKey)
        assertTrue(!keyA.contains("private-A"))
        assertTrue(!keyA.contains(urlA))
        assertTrue(keyA == providerAdaptiveSegmentCacheKey(addon, urlA))
        assertTrue(keyA != providerAdaptiveSegmentCacheKey(addon, urlA, "variant"))
        // A provider may use the same static CDN URL for two accounts, with
        // identity carried only by Authorization/Referer headers. Never return
        // the previous account's cached audio after credential rotation.
        val ownerOne = providerAdaptiveSegmentCacheKey(
            addon, urlA, null, mapOf("Authorization" to "Bearer account-one"))
        val ownerTwo = providerAdaptiveSegmentCacheKey(
            addon, urlA, null, mapOf("Authorization" to "Bearer account-two"))
        assertTrue(ownerOne != ownerTwo)
        assertTrue(ownerOne != keyA)
        assertTrue(!ownerOne.contains("account-one"))
        assertTrue(ownerOne == providerAdaptiveSegmentCacheKey(
            addon, urlA, null, mapOf("authorization" to "Bearer account-one")))
    }

    @Test fun adaptiveSegmentCacheHitRequiresCurrentMediaItemLease() {
        val gate = gate(addonOn = true, siblingOn = true)
        val addonTicket = requireNotNull(gate.open(
            ProviderCapability.EXTENSION_PLAYBACK, addon))
        val siblingTicket = requireNotNull(gate.open(
            ProviderCapability.EXTENSION_PLAYBACK, sibling))
        val addonLease = ProviderPlaybackByteLease(gate, addonTicket)
        val siblingLease = ProviderPlaybackByteLease(gate, siblingTicket)

        assertTrue(admitAdaptiveSegmentCacheRead(addonLease) === addonLease)
        try {
            admitAdaptiveSegmentCacheRead(null)
            org.junit.Assert.fail("An unknown segment must not read cached HTTP bytes")
        } catch (_: java.io.IOException) {}

        gate.replace(ProviderCapabilityPolicy(
            listOf(addon, sibling).map {
                ProviderDescriptor(it, setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    ProviderReadiness.READY)
            },
            mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(addon, false, 0),
                ProviderChoice(sibling, true, 1),
            )),
        ))
        try {
            admitAdaptiveSegmentCacheRead(addonLease)
            org.junit.Assert.fail("Cached segment bypassed disabled addon")
        } catch (_: java.io.IOException) {}
        assertTrue(admitAdaptiveSegmentCacheRead(siblingLease) === siblingLease)
    }

    @Test fun fullyCachedBytesNeedAnExplicitEnabledWinningProvider() {
        val gate = gate(addonOn = true)
        assertNotNull(sourceCacheReadLease("addon", "one", true, gate))
        assertNull(sourceCacheReadLease("addon", "one", false, gate))
        assertNull(sourceCacheReadLease(null, "one", true, gate))
        assertNull(sourceCacheReadLease("addon", null, true, gate))
        assertNull(sourceCacheReadLease("musicbrainz", "one", true, gate))
        assertNull(sourceCacheReadLease("addon", "one", true, null))
    }

    @Test fun disabledWinningProviderCannotReplayCachedBytesEvenWhenSiblingEnabled() {
        val gate = gate(addonOn = false, siblingOn = true)
        assertNull(sourceCacheReadLease("addon", "one", true, gate))
        val siblingLease = sourceCacheReadLease("custom_module", "two", true, gate)
        assertNotNull(siblingLease)
        assertTrue(requireNotNull(siblingLease).allowed())
        gate.replace(ProviderCapabilityPolicy(
            listOf(addon, sibling).map {
                ProviderDescriptor(it, setOf(ProviderCapability.EXTENSION_PLAYBACK),
                    ProviderReadiness.READY)
            },
            mapOf(ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(addon, false, 0),
                ProviderChoice(sibling, false, 1),
            )),
        ))
        assertFalse(siblingLease.allowed())
    }

    @Test fun malformedOrForgedPersistedProviderIdentityFailsClosed() {
        val gate = gate(addonOn = true)
        assertNull(sourceCacheReadLease("addon", "", true, gate))
        assertNull(sourceCacheReadLease("addon", "missing", true, gate))
        assertNull(sourceCacheReadLease("custom_module", "one", true, gate))
    }
    @Test fun legacyProgressiveContainerSignaturesRejectAdaptiveManifestsAndUnknownBytes() {
        fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
        assertTrue(hasProgressiveAudioSignature(ascii("ID3---audio data")))
        assertTrue(hasProgressiveAudioSignature(ascii("fLaC--audio data")))
        assertTrue(hasProgressiveAudioSignature(ascii("OggS--audio data")))
        assertTrue(hasProgressiveAudioSignature(ascii("RIFF0000WAVEdata")))
        assertTrue(hasProgressiveAudioSignature(ascii("0000ftypM4A audio")))
        assertTrue(hasProgressiveAudioSignature(byteArrayOf(
            0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(),
        )))
        assertTrue(hasProgressiveAudioSignature(byteArrayOf(
            0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64,
        )))
        assertFalse(hasProgressiveAudioSignature(ascii("#EXTM3U\\n#EXTINF:1")))
        assertFalse(hasProgressiveAudioSignature(ascii("<?xml version='1.0'?><MPD")))
        assertFalse(hasProgressiveAudioSignature(ascii("<MPD type='static'>")))
        assertFalse(hasProgressiveAudioSignature(ascii("unknown streamed data")))
        assertFalse(hasProgressiveAudioSignature(byteArrayOf(0x12, 0x34)))
    }

    @Test fun scopedAudioCacheRejectsUnknownAndRepointedAccounts() {
        val config = com.music.bitchord.data.sources.SourceConfig(
            id = "one",
            kind = com.music.bitchord.data.sources.SourceKind.ADDON,
            baseUrl = "https://account-one.example",
            playbackCacheEpoch = "epoch-one",
        )
        val owner = ProviderKey("addon", "one")
        val scope = requireNotNull(sourcePlaybackCacheScope(config, owner))
        assertTrue(scope.matches(Regex("[0-9a-f]{64}")))
        assertFalse(scope.contains("account-one"))
        assertFalse(scope.contains("epoch-one"))
        assertTrue(sourceCacheScopeMatches(scope, sourcePlaybackCacheScope(config, owner)))
        assertFalse(sourceCacheScopeMatches(null, scope))
        assertFalse(sourceCacheScopeMatches("", scope))
        assertFalse(sourceCacheScopeMatches(scope, null))
        assertFalse(sourceCacheScopeMatches(scope, sourcePlaybackCacheScope(
            config.copy(baseUrl = "https://account-two.example"), owner)))
        assertFalse(sourceCacheScopeMatches(scope, sourcePlaybackCacheScope(
            config.copy(playbackCacheEpoch = "epoch-two"), owner)))
        assertTrue(sourceCacheScopeMatches(scope, sourcePlaybackCacheScope(
            config.copy(label = "Cosmetic name"), owner)))
        assertNull(sourcePlaybackCacheScope(config, ProviderKey("custom_module", "one")))
        assertNull(sourcePlaybackCacheScope(config, ProviderKey("addon", "other")))
        val scopedOne = providerAdaptiveSegmentCacheKey(
            owner, "https://cdn.example/a.flac", null,
            mapOf("authorization" to "Bearer shared"), "realm-one")
        val scopedTwo = providerAdaptiveSegmentCacheKey(
            owner, "https://cdn.example/a.flac", null,
            mapOf("authorization" to "Bearer shared"), "realm-two")
        assertTrue(scopedOne != scopedTwo)
        assertTrue(isScopedSourcePhysicalCacheKey(scopedOne))
        assertFalse(isScopedSourcePhysicalCacheKey("https://cdn.example/a.flac"))
        assertFalse(isScopedSourcePhysicalCacheKey("source-segment:addon:one:bad-hash"))
        assertFalse(scopedOne.contains("realm-one"))
        assertFalse(scopedOne.contains("shared"))
        val rootKeyOne = providerAdaptiveSegmentCacheKey(
            owner, "https://cdn.example/same-stream", scopedProgressiveRootRequestKey(
                null, "source-one|track-1"), emptyMap(), "same-realm")
        val rootKeyTwo = providerAdaptiveSegmentCacheKey(
            owner, "https://cdn.example/same-stream", scopedProgressiveRootRequestKey(
                null, "source-one|track-2"), emptyMap(), "same-realm")
        val variantKey = providerAdaptiveSegmentCacheKey(
            owner, "https://cdn.example/same-stream", scopedProgressiveRootRequestKey(
                null, "source-one|track-1#stereo"), emptyMap(), "same-realm")
        assertTrue(rootKeyOne != rootKeyTwo)
        assertTrue(rootKeyOne != variantKey)
        assertFalse(rootKeyOne.contains("track-1"))
        assertTrue(isScopedSourcePhysicalCacheKey(rootKeyOne))
        assertTrue(scopedPhysicalKeyBelongsTo(scopedOne, owner))
        assertFalse(scopedPhysicalKeyBelongsTo(scopedOne,
            ProviderKey("addon", "different")))
        assertFalse(scopedPhysicalKeyBelongsTo(scopedOne,
            ProviderKey("custom_module", "one")))
        assertEqualsCompat(scopedOne, scopedOfflineSourceCacheKey(
            "bitchord", "source", scopedOne))
        assertNull(scopedOfflineSourceCacheKey("https", "cdn.example", scopedOne))
        assertNull(scopedOfflineSourceCacheKey("bitchord", "watch", scopedOne))
        assertNull(scopedOfflineSourceCacheKey("bitchord", "source",
            "https://cdn.example/private-stream"))
    }

    @Test fun cacheGenerationRotatesOnServerEditsEvenWhenRestoredToTheOriginalUrl() {
        val original = com.music.bitchord.data.sources.SourceConfig(
            id = "one",
            kind = com.music.bitchord.data.sources.SourceKind.ADDON,
            baseUrl = "https://account-one.example",
            playbackCacheEpoch = "epoch-one",
        )
        val cosmetic = com.music.bitchord.data.sources.SourceRegistry.scopeAfterSourceUpdate(
            original, original.copy(label = "Favorite", enabled = false))
        assertTrue(cosmetic.playbackCacheEpoch == "epoch-one")
        val updated = com.music.bitchord.data.sources.SourceRegistry.scopeAfterSourceUpdate(
            cosmetic, cosmetic.copy(baseUrl = "https://account-two.example"))
        assertFalse(updated.playbackCacheEpoch == "epoch-one")
        val restored = com.music.bitchord.data.sources.SourceRegistry.scopeAfterSourceUpdate(
            updated, updated.copy(baseUrl = original.baseUrl))
        assertFalse(restored.playbackCacheEpoch == original.playbackCacheEpoch)
        assertFalse(restored.playbackCacheEpoch == updated.playbackCacheEpoch)

        // Cache ownership must survive a process restart. The registry's
        // persistence format writes the epoch rather than minting a new one.
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val serialized = json.encodeToString(
            com.music.bitchord.data.sources.SourceConfig.serializer(), original)
        val persisted = json.decodeFromString(
            com.music.bitchord.data.sources.SourceConfig.serializer(), serialized)
        assertEqualsCompat(original.playbackCacheEpoch, persisted.playbackCacheEpoch)
    }

}
