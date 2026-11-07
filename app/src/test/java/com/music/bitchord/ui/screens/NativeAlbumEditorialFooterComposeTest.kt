package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsEditionEnrichment
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsKind
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsLink
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsObservation
import com.music.bitchord.data.catalog.metadata.discogs.DiscogsRecord
import com.music.bitchord.ui.theme.themeArtworkPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeAlbumEditorialFooterComposeTest {
    @get:Rule val compose = createComposeRule()

    private fun evidence(): DiscogsEditionEnrichment.Verified {
        val link = DiscogsLink(DiscogsKind.RELEASE, 12345)
        val record = DiscogsRecord(
            link, "SUPER REAL ME", listOf("ILLIT"),
            "KR", 2024, listOf("Belift Lab" to "BEL-001"),
            emptyList(), listOf("CD", "EP"), emptyList(), unofficial = false,
        )
        return DiscogsEditionEnrichment.Verified(
            DiscogsObservation(
                "541adf5a-48d4-4f7b-a7bb-afd3cea5af27",
                DiscogsKind.RELEASE, record, System.currentTimeMillis(),
            )
        )
    }

    @Test fun albumRetainsExistingDateAndSummaryThenRendersInlineEditionAndAttribution() {
        compose.setContent {
            MaterialTheme {
                NativeAlbumMetadataFooter(
                    metadata = CanonicalAlbumFooterMetadata(
                        date = "24 de fevereiro de 2017",
                        summary = "13 músicas, 63 minutos",
                        editorialEvidence = evidence(),
                    ),
                    palette = themeArtworkPalette(),
                )
            }
        }
        compose.onNodeWithText("24 de fevereiro de 2017").assertExists()
        compose.onNodeWithText("13 músicas, 63 minutos").assertExists()
        compose.onNodeWithTag("native-album-metadata-footer").assertExists()
        compose.onNodeWithTag("native-album-editorial-footer").assertExists()
        compose.onNodeWithText("Gravadora · Belift Lab (BEL-001)").assertExists()
        compose.onNodeWithTag("native-album-editorial-attribution").assertExists()
    }

    @Test fun nonVerifiedAlbumKeepsExistingMetadataButNeverShowsDiscogsPlaceholder() {
        compose.setContent {
            MaterialTheme {
                NativeAlbumMetadataFooter(
                    metadata = CanonicalAlbumFooterMetadata(
                        date = "2024", summary = "5 músicas, 15 minutos",
                        editorialEvidence = DiscogsEditionEnrichment.Candidate,
                    ),
                    palette = themeArtworkPalette(),
                )
            }
        }
        compose.onNodeWithText("2024").assertExists()
        compose.onNodeWithTag("native-album-editorial-footer").assertDoesNotExist()
        compose.onNodeWithTag("native-album-editorial-attribution").assertDoesNotExist()
    }
}
