package com.music.bitchord

import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogIndex
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseGroupHit
import com.music.bitchord.data.providers.ProviderCachedReadAccess
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilityPolicy
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderPolicyGate
import com.music.bitchord.data.providers.ProviderReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ProviderCachedReadAccessTest {
    private val musicBrainz = ProviderKey("musicbrainz")
    private val artwork = ProviderKey("fanarttv")

    private fun policy(discography: Boolean, covers: Boolean = true) =
        ProviderCapabilityPolicy(
            descriptors = listOf(
                ProviderDescriptor(
                    musicBrainz, setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY),
                    readiness = ProviderReadiness.READY,
                ),
                ProviderDescriptor(
                    artwork, setOf(ProviderCapability.RELEASE_ARTWORK),
                    readiness = ProviderReadiness.READY,
                ),
            ),
            choices = mapOf(
                ProviderCapability.EDITORIAL_DISCOGRAPHY to
                    listOf(ProviderChoice(musicBrainz, discography, 0)),
                ProviderCapability.RELEASE_ARTWORK to
                    listOf(ProviderChoice(artwork, covers, 0)),
            ),
        )

    @Test fun missingGateFailsClosedInsteadOfPermittingCacheRead(): Unit = runBlocking {
        var queried = false
        val result = ProviderCachedReadAccess { null }.read(
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            musicBrainz,
            emptyList<String>(),
        ) {
            queried = true
            listOf("should-never-leak")
        }
        assertTrue(result.isEmpty())
        assertFalse(queried)
    }

    @Test fun disabledSourceDoesNotEvenReadCachedMusicBrainzData(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(false))
        var queried = false
        val result = ProviderCachedReadAccess { gate }.read(
            ProviderCapability.EDITORIAL_DISCOGRAPHY,
            musicBrainz,
            emptyList<String>(),
        ) {
            queried = true
            listOf("from cached source")
        }
        assertTrue(result.isEmpty())
        assertFalse(queried)
    }

    @Test fun disablingProviderWhileRoomReadIsSuspendedDropsTheOldResult(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = async {
            ProviderCachedReadAccess { gate }.read(
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
                musicBrainz,
                emptyList<String>(),
            ) {
                entered.complete(Unit)
                release.await()
                listOf("stale data")
            }
        }
        entered.await()
        gate.replace(policy(false))
        release.complete(Unit)
        assertEquals(emptyList<String>(), request.await())
        assertEquals(listOf(artwork),
            gate.ordered(ProviderCapability.RELEASE_ARTWORK).map { it.key })
    }

    @Test fun revokingCachedReadCancelsBlockedSourceOperationImmediately(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(true))
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val request = async {
            ProviderCachedReadAccess { gate }.read(
                ProviderCapability.EDITORIAL_DISCOGRAPHY,
                musicBrainz,
                emptyList<String>(),
            ) {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }
        }
        withTimeout(5_000) { entered.await() }
        gate.replace(policy(false))
        withTimeout(5_000) { cancelled.await() }
        assertEquals(emptyList<String>(), withTimeout(5_000) { request.await() })
        assertTrue(gate.open(ProviderCapability.RELEASE_ARTWORK, artwork) != null)
    }

    @Test fun existingArtistDiscographyCacheRemainsStoredButIsNotVisibleWhileOff(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val gate = ProviderPolicyGate(policy(true))
            val index = MusicBrainzCatalogIndex(
                db,
                cacheRead = ProviderCachedReadAccess { gate },
            )
            val artistMbid = "6b128dd7-e998-4edb-a4f5-400fef190445"
            val groupMbid = "e614e432-ca77-41c1-82c1-4e3a24ebbb0c"
            index.persistArtists(listOf(MusicBrainzArtistHit(
                artistMbid, "ILLIT", null, "KR",
            )))
            index.persistArtistGroups(artistMbid, listOf(
                MusicBrainzReleaseGroupHit(groupMbid, "SUPER REAL ME",
                    "EP", "2024-03-25", "ILLIT"),
            ))
            assertEquals(listOf(groupMbid),
                index.cachedArtistGroups(artistMbid).map { it.musicBrainzId })

            gate.replace(policy(false))
            assertTrue(index.cachedArtistGroups(artistMbid).isEmpty())
            // Switching a metadata provider off never deletes canonical data.
            assertTrue(db.catalogDao().entityIdForExternalIdentity(
                "musicbrainz", "release-group", groupMbid,
            ) != null)

            gate.replace(policy(true))
            assertEquals(listOf(groupMbid),
                index.cachedArtistGroups(artistMbid).map { it.musicBrainzId })
        } finally { db.close() }
    }

    @Test fun otherProviderCacheCanRemainAvailableWithoutMusicBrainz(): Unit = runBlocking {
        val gate = ProviderPolicyGate(policy(false, covers = true))
        assertEquals(listOf("art"), ProviderCachedReadAccess { gate }.read(
            ProviderCapability.RELEASE_ARTWORK, artwork, emptyList<String>(),
        ) { listOf("art") })
    }

    @Test fun frontV5ArtistShelfKeepsEditorialRowsButHidesCaaArtworkWhenDisabled(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CanonicalCatalogDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val caa = ProviderKey("coverartarchive")
            fun frontPolicy(enabled: Boolean) = ProviderCapabilityPolicy(
                descriptors = listOf(
                    ProviderDescriptor(musicBrainz,
                        setOf(ProviderCapability.EDITORIAL_DISCOGRAPHY),
                        ProviderReadiness.READY),
                    ProviderDescriptor(caa, setOf(ProviderCapability.RELEASE_ARTWORK),
                        ProviderReadiness.READY),
                ),
                choices = mapOf(
                    ProviderCapability.EDITORIAL_DISCOGRAPHY to
                        listOf(ProviderChoice(musicBrainz, true, 0)),
                    ProviderCapability.RELEASE_ARTWORK to
                        listOf(ProviderChoice(caa, enabled, 0)),
                ),
            )
            val gate = ProviderPolicyGate(frontPolicy(true))
            val index = MusicBrainzCatalogIndex(db, cacheRead = ProviderCachedReadAccess { gate })
            val artist = "12121212-1212-4121-8121-121212121212"
            val group = "22222222-2222-4222-8222-222222222222"
            val url = "https://archive.org/download/example/front-500.jpg"
            index.persistArtists(listOf(MusicBrainzArtistHit(artist, "Artist", null, "BR")))
            index.persistArtistGroups(artist, listOf(
                MusicBrainzReleaseGroupHit(group, "Album", "Album", "2024-01-01", "Artist"),
            ))
            db.catalogDao().upsertMusicBrainzCoverArtCache(
                com.music.bitchord.data.catalog.MusicBrainzCoverArtCacheEntity(
                    "release-group:front-v5", group, url, System.currentTimeMillis(), url,
                ),
            )
            assertEquals(url, index.cachedArtistGroups(artist).single().artworkUri)
            gate.replace(frontPolicy(false))
            val withoutCaa = index.cachedArtistGroups(artist).single()
            assertEquals("Album", withoutCaa.title)
            assertEquals(null, withoutCaa.artworkUri)
            assertTrue(db.catalogDao().musicBrainzCoverArtCache("release-group:front-v5", group) != null)
            gate.replace(frontPolicy(true))
            assertEquals(url, index.cachedArtistGroups(artist).single().artworkUri)
        } finally { db.close() }
    }
}
