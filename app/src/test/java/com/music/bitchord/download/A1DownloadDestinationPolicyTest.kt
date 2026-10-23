package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

/** A1 destination contract. No Soulseek transport, user data, or filesystem writes. */
class A1DownloadDestinationPolicyTest {
    @Test fun firstUseTargetsSharedMusicFolderAndFlatLayout() {
        val resolved = A1DownloadDestinationPolicy.resolve(null)
        assertEquals(
            A1DownloadDestinationPolicy.Resolution.Ready(
                A1DownloadDestinationPolicy.Target.SharedMusic(
                    "Music/BitChord/"
                )
            ), resolved
        )
        assertEquals(
            A1DownloadDestinationPolicy.Layout.FLAT,
            A1DownloadDestinationPolicy.layout
        )
    }

    @Test fun chosenDirectoryIsPreservedExactlyWithoutInventingFilesystemPaths() {
        val tree="content://com.android.externalstorage.documents/tree/primary%3AMusic%2FMyAudio"
        val result=A1DownloadDestinationPolicy.resolve(tree)
        assertEquals(
            A1DownloadDestinationPolicy.Resolution.Ready(
                A1DownloadDestinationPolicy.Target.GrantedTree(tree)
            ),result
        )
    }

    @Test fun malformedOrRevokedLookingPreferenceNeverFallsBackSilentlyToDefault() {
        for (bad in listOf(
            "", " ", "/storage/emulated/0/Music", "file:///Music/BitChord",
            "content://com.example.provider/document/a",
            "content://com.example.provider/tree/",
            "https://example.test/tree/a",
            "content://com.example.provider/tree/a\nsecret",
            "content://com.example.provider/tree/a".padEnd(2100,'a')
        )) {
            assertEquals(
                A1DownloadDestinationPolicy.Resolution.NeedsReselection,
                A1DownloadDestinationPolicy.resolve(bad)
            )
        }
    }

    @Test fun userChosenTreeIsNotAChildPathInsideDefaultMusic() {
        val tree="content://com.android.externalstorage.documents/tree/primary%3AMusic%2FOther"
        val selected=A1DownloadDestinationPolicy.resolve(tree)
        assertTrue((selected as A1DownloadDestinationPolicy.Resolution.Ready).target
            is A1DownloadDestinationPolicy.Target.GrantedTree)
        assertFalse(selected.target is A1DownloadDestinationPolicy.Target.SharedMusic)
    }
}
