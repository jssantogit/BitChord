package com.music.bitchord.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalAssetPathEvidenceTest {
    @Test fun windowsAndUnixPathsShareOneNormalFormWithoutBorrowingUris() {
        assertEquals("D:/Music/Albums/01.flac",
            canonicalAssetFilePath("  D:\\Music\\Albums\\01.flac  "))
        assertEquals("D:/Music/Albums",
            canonicalAssetFolderPath("D:\\Music\\Albums\\01.flac"))
        assertEquals("/storage/emulated/0/Music/Dream Pop",
            canonicalAssetFolderPath("/storage/emulated/0/Music/Dream Pop/01.mp3"))
        assertEquals("Artist/Album", canonicalAssetFolderPath("Artist/Album/song.opus"))
        assertNull(canonicalAssetFolderPath("song.mp3"))
        assertNull(canonicalAssetFolderPath(null))
        assertNull(canonicalAssetFolderPath("content://media/external/audio/media/25"))
        assertNull(canonicalAssetFilePath("https://server/stream?id=1"))
    }
}
