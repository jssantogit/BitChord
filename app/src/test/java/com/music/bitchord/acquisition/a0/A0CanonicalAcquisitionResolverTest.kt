package com.music.bitchord.acquisition.a0

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Read-only canonical relationship checks; no peer claim can create an MBID. */
class A0CanonicalAcquisitionResolverTest {
    private val recording = "11111111-1111-4111-8111-111111111111"
    private val release = "22222222-2222-4222-8222-222222222222"
    private val otherRelease = "44444444-4444-4444-8444-444444444444"
    private val group = "33333333-3333-4333-8333-333333333333"

    private fun target(releaseId: String = release) =
        A0CanonicalAcquisitionIntent.confirmedCatalog(
            title = "Blue Sky", requestedAlbum = "Blue Sky",
            strictExactRelease = true,
            ids = A0CanonicalAcquisitionIntent.CanonicalIds(
                recordingMbid = recording, releaseMbid = releaseId,
                releaseGroupMbid = group
            )
        )

    private fun found(
        linkedRecordings: Set<String> = setOf(recording),
        edition: String = release,
    ) = A0CanonicalAcquisitionResolver.Lookup.Found(
        A0CanonicalAcquisitionResolver.CatalogFacts(
            releaseMbid = edition, releaseGroupMbid = group,
            recordingMbidsOnRelease = linkedRecordings,
        )
    )

    @Test fun manualTextDoesNotCallCatalogueOrClaimCanonicalIdentity() = runBlocking {
        var read = false
        val resolver = A0CanonicalAcquisitionResolver({ read = true; found() })
        val result = resolver.resolve(A0CanonicalAcquisitionIntent.manual("Blue Sky"))
        assertEquals(A0CanonicalAcquisitionResolver.Status.MISSING, result.status)
        assertFalse(read)
    }

    @Test fun exactEditionNeedsRecordingToBeOnThatRelease() = runBlocking {
        val resolver = A0CanonicalAcquisitionResolver({ found() })
        val result = resolver.resolve(target())
        assertEquals(A0CanonicalAcquisitionResolver.Status.CONFIRMED_RELATION, result.status)
        assertFalse(result.provesAudioMaster)
        assertFalse(result.authorizesTransfer)
    }

    @Test fun sameRecordingOnDifferentReleaseCannotSatisfyExactEdition() = runBlocking {
        val resolver = A0CanonicalAcquisitionResolver({ found(edition = otherRelease) })
        assertEquals(
            A0CanonicalAcquisitionResolver.Status.CONTRADICTED,
            resolver.resolve(target()).status,
        )
    }

    @Test fun editionWithoutRecordingTrackLinkRemainsInconclusive() = runBlocking {
        val resolver = A0CanonicalAcquisitionResolver({ found(linkedRecordings = emptySet()) })
        assertEquals(
            A0CanonicalAcquisitionResolver.Status.PARTIAL,
            resolver.resolve(target()).status,
        )
    }

    @Test fun releaseGroupOnlyNeverProvesAnEdition() = runBlocking {
        val manualContext = A0CanonicalAcquisitionIntent.confirmedCatalog(
            title = "Blue Sky",
            ids = A0CanonicalAcquisitionIntent.CanonicalIds(releaseGroupMbid = group),
        )
        val resolver = A0CanonicalAcquisitionResolver({ found() })
        assertEquals(A0CanonicalAcquisitionResolver.Status.PARTIAL,
            resolver.resolve(manualContext).status)
    }

    @Test fun lookupOutagesAndAmbiguityHaveTypedConservativeResults() = runBlocking {
        val down = A0CanonicalAcquisitionResolver({
            A0CanonicalAcquisitionResolver.Lookup.Unavailable
        })
        val ambiguous = A0CanonicalAcquisitionResolver({
            A0CanonicalAcquisitionResolver.Lookup.Ambiguous
        })
        assertEquals(A0CanonicalAcquisitionResolver.Status.UNAVAILABLE, down.resolve(target()).status)
        assertEquals(A0CanonicalAcquisitionResolver.Status.AMBIGUOUS, ambiguous.resolve(target()).status)
    }

    @Test fun timeoutsNeverBecomeMissingOrContradictedFacts() = runBlocking {
        val stalled = A0CanonicalAcquisitionResolver({
            delay(120)
            found()
        }, timeoutMillis = 20)
        assertEquals(A0CanonicalAcquisitionResolver.Status.UNAVAILABLE, stalled.resolve(target()).status)
    }

    @Test fun externalCancellationIsPropagatedRatherThanSilenced() = runBlocking {
        val cancelled = A0CanonicalAcquisitionResolver({
            throw CancellationException("user left screen")
        })
        try {
            cancelled.resolve(target())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Cooperative cancellation is required for search/logout.
        }
    }

    @Test fun failedRepositoryIsUnavailableAndCannotAssertTransfer() = runBlocking {
        val crashed = A0CanonicalAcquisitionResolver({ throw IllegalStateException("offline") })
        val result = crashed.resolve(target())
        assertEquals(A0CanonicalAcquisitionResolver.Status.UNAVAILABLE, result.status)
        assertFalse(result.authorizesTransfer)
        assertFalse(result.toString().contains(recording))
    }
}
