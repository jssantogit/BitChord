package com.music.bitchord

import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityRepository
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityStore
import com.music.bitchord.data.catalog.CanonicalLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalLibraryProviderAuthorizationTest {
    private val local = CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER
    private val oldRemote = "opensubsonic:home:old-realm"

    @Test fun revocationWhileRoomReadsCannotPublishOldRemoteRecordingIds() = runTest {
        var remoteAllowed = true
        val configured = CanonicalLibraryProviderConfigurationReader {
            if (remoteAllowed) setOf(local, oldRemote) else setOf(local)
        }
        val store = CanonicalLibraryAvailabilityStore { providers ->
            when {
                oldRemote in providers -> {
                    remoteAllowed = false
                    listOf("local-recording", "old-private-recording")
                }
                local in providers -> listOf("local-recording")
                else -> emptyList()
            }
        }
        val result = CanonicalLibraryAvailabilityRepository(store, configured)
            .availableRecordingIds()
        assertEquals(listOf("local-recording"), result)
    }

    @Test fun sourceEvidenceFailsClosedIfRemoteAccountChangesDuringRoomRead() = runTest {
        var remoteAllowed = true
        val configured = CanonicalLibraryProviderConfigurationReader {
            if (remoteAllowed) setOf(local, oldRemote) else setOf(local)
        }
        val store = CanonicalLibraryAvailabilityStore { providers ->
            if (oldRemote in providers) {
                remoteAllowed = false
                listOf("old-private-recording")
            } else listOf("local-recording")
        }
        val result = CanonicalLibraryAvailabilityRepository(store, configured)
            .availableRecordingSources()
        assertTrue(result.none { it.recordingId == "old-private-recording" })
    }
}
