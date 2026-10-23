package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L18 never exports track titles, canonical IDs, rating values, scopes or assets. */
class MyRatingsRenderEvidenceTest {
    @Test fun pendingAndUnavailableAreUnknownNotZero() {
        val fields = MyRatingsRenderEvidence.from(
            MyRatingsUiState.Loading,
            RemoteRatingsUiState.Unavailable,
        ).metadata()
        assertEquals("loading", fields["localState"])
        assertEquals("unavailable", fields["remoteState"])
        assertNull(fields["localCount"])
        assertNull(fields["remoteEvidenceCount"])
        assertEquals("unknown", fields["visibleProven"])
    }

    @Test fun actualEmptyStateHasExplicitZeroAndNotAnInventedRemoteZero() {
        val fields = MyRatingsRenderEvidence.from(
            MyRatingsUiState.Empty,
            RemoteRatingsUiState.Unavailable,
        ).metadata()
        assertEquals("0", fields["localCount"])
        assertNull(fields["remoteEvidenceCount"])
    }

    @Test fun titlesAndAllIdentifiersAndRatingValuesAreExcluded() {
        val fields = MyRatingsRenderEvidence.from(
            MyRatingsUiState.Content(
                items = listOf(MyRatingsRow("secret-recording-id", 4.5, "Private song title", "Artist")),
                hasMore = false,
            ),
            RemoteRatingsUiState.Ready(
                rows = listOf(RemoteRatingUiRow(
                    recordingId = "secret-recording-id", title = "Private song title",
                    artistDisplay = "Artist",
                    evidence = listOf(RemoteRatingUiEvidence(
                        "secret-asset-id", 5, false, true,
                    )),
                )),
            ),
        ).metadata()
        assertEquals("1", fields["localCount"])
        assertEquals("1", fields["remoteEvidenceCount"])
        assertEquals("1", fields["differentRatingRecordingCount"])
        assertFalse(fields.toString().contains("secret"))
        assertFalse(fields.toString().contains("Private"))
        assertFalse(fields.toString().contains("4.5"))
        assertFalse(fields.toString().contains("scope"))
        assertTrue(fields.keys.all { it in MyRatingsRenderEvidence.ALLOWED_KEYS })
    }

    @Test fun localRoomErrorIsNotAZeroRowAndRemoteReadCanRemainReady() {
        val fields = MyRatingsRenderEvidence.from(
            MyRatingsUiState.Error,
            RemoteRatingsUiState.Ready(emptyList()),
        ).metadata()
        assertEquals("error", fields["localState"])
        assertEquals("ready", fields["remoteState"])
        assertNull(fields["localCount"])
        assertEquals("0", fields["remoteEvidenceCount"])
    }
}
