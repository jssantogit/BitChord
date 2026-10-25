package com.music.bitchord.download

import org.junit.Assert.*
import org.junit.Test

class A4LocalPhysicalIdentityTest {
    @Test fun sameMediaStoreRecordingUnderVolumeAliasesAppearsOnce() {
        val a="content://media/external/audio/media/52"
        val b="content://media/external_primary/audio/media/52"
        assertEquals(A4LocalPhysicalIdentity.key(a),A4LocalPhysicalIdentity.key(b))
    }
    @Test fun documentUriToMediaStoreSameAudioIdIsTheSamePhysicalRecording() {
        val a="content://media/external/audio/media/52"
        val b="content://com.android.providers.media.documents/document/audio%3A52"
        assertEquals(A4LocalPhysicalIdentity.key(a),A4LocalPhysicalIdentity.key(b))
    }
    @Test fun twoDifferentMediaStoreRowsNeverDeduplicateByTitle() {
        assertNotEquals(A4LocalPhysicalIdentity.key("content://media/external/audio/media/52"),
            A4LocalPhysicalIdentity.key("content://media/external/audio/media/53"))
    }
    @Test fun sameSafFileUnderTreeUrisDoesNotAppendTwice() {
        val a="content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FBitChord%2Ftest.flac"
        val b="content://com.android.externalstorage.documents/document/primary%3AMusic%2FBitChord%2Ftest.flac"
        assertEquals(A4LocalPhysicalIdentity.key(a),A4LocalPhysicalIdentity.key(b))
    }
}