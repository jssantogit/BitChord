package com.music.bitchord

import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountConfig
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAuth
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProvider
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicProviderStreamHandoff
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamCapability
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamCapabilitySnapshot
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamConfidence
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamEvidenceBasis
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamLifecycleLease
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamLeaseStatus
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicStreamMechanism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubsonicStreamLifecycleTest {
    private fun account(server: String = "https://music.example", key: String = "secret-one") =
        OpenSubsonicAccountConfig("home", server, OpenSubsonicAuth.ApiKey(key))

    private fun advertised(config: OpenSubsonicAccountConfig): OpenSubsonicStreamCapabilitySnapshot {
        val provider = OpenSubsonicProvider(config)
        return OpenSubsonicStreamCapabilitySnapshot(
            generation = 4,
            traditional = OpenSubsonicStreamCapability(
                OpenSubsonicStreamMechanism.TRADITIONAL_STREAM_BEST_EFFORT,
                OpenSubsonicStreamConfidence.ADVERTISED,
                OpenSubsonicStreamEvidenceBasis.PROTOCOL_SPEC,
            ),
            transcoding = OpenSubsonicStreamCapability(
                OpenSubsonicStreamMechanism.EXTENSION_TRANSCODING_V1,
                OpenSubsonicStreamConfidence.ADVERTISED,
                OpenSubsonicStreamEvidenceBasis.MANIFEST_V1,
            ),
            configurationIsCurrent = provider::matchesSavedConfig,
        )
    }

    private class Fixture {
        val original = OpenSubsonicAccountConfig(
            "home", "https://music.example", OpenSubsonicAuth.ApiKey("secret-one"),
        )
        var current: OpenSubsonicAccountConfig? = original
        var revision = 9L
        val provider = OpenSubsonicProvider(original)
        val lease = OpenSubsonicStreamLifecycleLease(
            providerKey = "opensubsonic:home",
            generation = 4,
            initialRevision = revision,
            revisionNow = { revision },
            savedConfig = { current },
            configMatches = provider::matchesSavedConfig,
            capability = advertised(original),
        )
    }

    @Test fun originalConfigurationGivesVersionedCredentialFreePreview() {
        val f = Fixture()
        val state: OpenSubsonicProviderStreamHandoff = f.lease.preview(4, "opensubsonic:home")
        assertEquals(1, state.contractVersion)
        assertEquals(OpenSubsonicStreamLeaseStatus.CURRENT, state.lifecycle)
        assertEquals(OpenSubsonicStreamConfidence.ADVERTISED, state.transcoding)
        assertTrue(f.lease.isCurrent())
        assertFalse(state.toString().contains("secret-one"))
        assertFalse(state.toString().contains("music.example"))
        assertFalse(state.toString().contains("opensubsonic:home"))
    }

    @Test fun endpointOrCredentialRotationFailsClosedWithoutDeletingCatalog() {
        val f = Fixture()
        f.current = OpenSubsonicAccountConfig(
            "home", "https://other.example", OpenSubsonicAuth.ApiKey("secret-one"),
        )
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, f.lease.preview(4, "opensubsonic:home").lifecycle)
        assertFalse(f.lease.isCurrent())
        f.current = OpenSubsonicAccountConfig(
            "home", "https://music.example", OpenSubsonicAuth.ApiKey("rotated-token"),
        )
        assertEquals(OpenSubsonicStreamConfidence.UNKNOWN, f.lease.preview(4, "opensubsonic:home").transcoding)
        assertFalse(f.lease.isCurrent())
    }

    @Test fun logoutAccountSwitchProviderMismatchAndGenerationDriftInvalidate() {
        val f = Fixture()
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, f.lease.preview(5, "opensubsonic:home").lifecycle)
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, f.lease.preview(4, "opensubsonic:other").lifecycle)
        f.current = null
        assertFalse(f.lease.isCurrent())
        f.current = OpenSubsonicAccountConfig(
            "other", "https://music.example", OpenSubsonicAuth.ApiKey("secret-two"),
        )
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, f.lease.preview(4, "opensubsonic:home").lifecycle)
    }

    @Test fun concurrentAccountMutationRevokesActiveLeaseEvenWhenValuesAreRestored() {
        val f = Fixture()
        assertTrue(f.lease.isCurrent())
        f.revision++
        // A stale async callback cannot revive a credentialed request by
        // observing a new account whose values happen to be identical.
        f.current = f.original
        assertFalse(f.lease.isCurrent())
        assertEquals(OpenSubsonicStreamLeaseStatus.STALE, f.lease.preview(4, "opensubsonic:home").lifecycle)
    }
}
