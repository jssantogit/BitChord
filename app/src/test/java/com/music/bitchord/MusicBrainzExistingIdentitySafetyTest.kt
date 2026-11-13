package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.ExternalIdentityEntity
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.identity.CanonicalRecordingIdentityDiscoveryRepository
import com.music.bitchord.data.catalog.identity.RecordingIdentityDiscoveryFailure
import com.music.bitchord.data.catalog.identity.RecordingIdentityDiscoveryProvider
import com.music.bitchord.data.catalog.identity.RecordingIdentityResolutionResult
import com.music.bitchord.data.catalog.identity.StrongRecordingIdentityLookupResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * Identity cutover gate: persisted external claims are editable provider data,
 * not a license to report an unparseable value as a canonical Recording MBID.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class MusicBrainzExistingIdentitySafetyTest {
    private val validMbid = "b51c672b-85e0-48fe-8648-470a2422229f"

    private suspend fun withDatabase(block: suspend (CanonicalCatalogDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try { block(database) } finally { database.close() }
    }

    private class NoLookup : RecordingIdentityDiscoveryProvider {
        override val providerKey = "musicbrainz"
        var calls = 0

        override suspend fun lookupByIsrc(isrc: String): StrongRecordingIdentityLookupResult {
            calls++
            error("Existing identity must not trigger an external lookup")
        }
    }

    @Test fun corruptPersistedMbidIsNotReportedAsAnAlreadyResolvedRecording() = runBlocking {
        withDatabase { db ->
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r-invalid", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(ExternalIdentityEntity(
                "musicbrainz", "recording", "not-a-mbid", "r-invalid",
            ))
            val provider = NoLookup()
            val result = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)
                .resolveMusicBrainzRecordingIdentity("r-invalid")
            assertEquals(
                RecordingIdentityResolutionResult.Failed(
                    RecordingIdentityDiscoveryFailure.INVALID_IDENTIFIER,
                ), result,
            )
            assertEquals(0, provider.calls)
            assertEquals("r-invalid",
                dao.entityIdForExternalIdentity("musicbrainz", "recording", "not-a-mbid"))
        }
    }

    @Test fun invalidClaimCannotBeMaskedByASecondValidMbid() = runBlocking {
        withDatabase { db ->
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r-mixed", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(ExternalIdentityEntity(
                "musicbrainz", "recording", validMbid, "r-mixed",
            ))
            dao.upsertExternalIdentity(ExternalIdentityEntity(
                "musicbrainz", "recording", "broken", "r-mixed",
            ))
            val provider = NoLookup()
            val result = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)
                .resolveMusicBrainzRecordingIdentity("r-mixed")
            assertEquals(
                RecordingIdentityResolutionResult.Failed(
                    RecordingIdentityDiscoveryFailure.INVALID_IDENTIFIER,
                ), result,
            )
            assertEquals(0, provider.calls)
            assertEquals(2,
                dao.externalIdentitiesForEntity("musicbrainz", "recording", "r-mixed").size)
        }
    }

    @Test fun uppercaseAndLowercaseClaimsAreSameRecordingMbid() = runBlocking {
        withDatabase { db ->
            val dao = db.catalogDao()
            dao.upsertRecording(RecordingEntity("r-case", "Track", "Artist", 1L))
            dao.upsertExternalIdentity(ExternalIdentityEntity(
                "musicbrainz", "recording", validMbid.uppercase(), "r-case",
            ))
            dao.upsertExternalIdentity(ExternalIdentityEntity(
                "musicbrainz", "recording", validMbid, "r-case",
            ))
            val provider = NoLookup()
            val result = CanonicalRecordingIdentityDiscoveryRepository(dao, provider)
                .resolveMusicBrainzRecordingIdentity("r-case")
            assertEquals(
                RecordingIdentityResolutionResult.AlreadyResolved(validMbid),
                result,
            )
            assertEquals(0, provider.calls)
            assertTrue(dao.externalIdentitiesForEntity("musicbrainz","recording","r-case").isNotEmpty())
        }
    }
}
