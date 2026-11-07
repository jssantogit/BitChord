package com.music.bitchord.data.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMediaPolicyTest {
    @Test fun automaticVideoDownloadRequiresExplicitOptInAndUnmeteredConnection() {
        assertFalse(shouldSaveVisualMotion(VisualRetentionMode.STREAM_ONLY, VisualSurface.ARTIST,
            setOf(VisualSurface.ARTIST), metered = false))
        assertFalse(shouldSaveVisualMotion(VisualRetentionMode.SAVE_ON_WIFI, VisualSurface.ARTIST,
            setOf(VisualSurface.ARTIST), metered = null))
        assertFalse(shouldSaveVisualMotion(VisualRetentionMode.SAVE_ON_WIFI, VisualSurface.ARTIST,
            setOf(VisualSurface.ARTIST), metered = true))
        assertTrue(shouldSaveVisualMotion(VisualRetentionMode.SAVE_ON_WIFI, VisualSurface.ARTIST,
            setOf(VisualSurface.ARTIST), metered = false))
        assertFalse(shouldSaveVisualMotion(VisualRetentionMode.SAVE_ON_WIFI, VisualSurface.ALBUM,
            setOf(VisualSurface.ARTIST), metered = false))
        assertFalse(shouldSaveVisualMotion(VisualRetentionMode.STILL_ONLY, VisualSurface.NOW_PLAYING,
            VisualSurface.entries.toSet(), metered = false))
    }

    @Test fun stillImagePreferenceNeverStartsAnimationAndKeepsFallback() {
        assertFalse(shouldShowVisualMotion(VisualRetentionMode.STILL_ONLY,
            animatedEnabled = true, metered = false, savedOffline = true,
            cellularAllowed = true))
        assertTrue(shouldShowVisualMotion(VisualRetentionMode.SAVE_ON_WIFI,
            animatedEnabled = true, metered = null, savedOffline = true,
            cellularAllowed = false))
        assertFalse(shouldShowVisualMotion(VisualRetentionMode.SAVE_ON_WIFI,
            animatedEnabled = true, metered = true, savedOffline = false,
            cellularAllowed = false))
        assertFalse(shouldShowVisualMotion(VisualRetentionMode.STREAM_ONLY,
            animatedEnabled = false, metered = false, savedOffline = true,
            cellularAllowed = true))
    }

    @Test fun visualCacheKeysAreProviderAgnosticStableAndDistinctBySurface() {
        assertEquals(visualMediaCacheKey(VisualSurface.ARTIST, "ILLIT"),
            visualMediaCacheKey(VisualSurface.ARTIST, " illit "))
        assertFalse(visualMediaCacheKey(VisualSurface.ARTIST, "ILLIT") ==
            visualMediaCacheKey(VisualSurface.ALBUM, "ILLIT"))
        assertEquals(64, visualMediaCacheKey(VisualSurface.ALBUM, "Say It").length)
    }

    @Test fun mediaUrlValidationRejectsLocalAndInsecureSourcesWithoutInvokingNetwork() {
        assertTrue(isSafeVisualMediaUrl("https://is1-ssl.mzstatic.com/image/thumb/image.jpg"))
        assertFalse(isSafeVisualMediaUrl("file:///data/user/0/private"))
        assertFalse(isSafeVisualMediaUrl("http://example.com/a.mp4"))
        assertFalse(isSafeVisualMediaUrl("https://localhost/a.mp4"))
        assertFalse(isSafeVisualMediaUrl("https://127.0.0.1/a.mp4"))
        assertFalse(isSafeVisualMediaUrl("https://user:password@example.com/a.jpg"))
    }
}
