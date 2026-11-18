package com.music.bitchord

import android.content.Context
import com.music.bitchord.data.providers.MusicBrainzCapabilitySearch
import com.music.bitchord.data.providers.ProviderAuthorityCoordinator
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.data.providers.ProviderCallResult
import com.music.bitchord.data.providers.ProviderCapability
import com.music.bitchord.data.providers.ProviderCapabilitySession
import com.music.bitchord.data.providers.ProviderChoice
import com.music.bitchord.data.providers.ProviderDescriptor
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.data.providers.ProviderOperationalHealth
import com.music.bitchord.data.providers.ProviderPreferenceStore
import com.music.bitchord.data.providers.ProviderQueryExecutor
import com.music.bitchord.data.providers.ProviderReadiness
import com.music.bitchord.data.providers.ProviderSessionStatus
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogPage
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogSearchResult
import com.music.bitchord.data.catalog.metadata.MusicBrainzArtistHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
class ProviderCapabilitySessionTest {
    private val musicBrainz = ProviderKey("musicbrainz")
    private val apple = ProviderKey("itunes")
    private val covers = ProviderKey("fanarttv")
    private val prefs by lazy {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "provider-capability-session-test", Context.MODE_PRIVATE,
        )
    }
    private val store by lazy { ProviderPreferenceStore(prefs) }
    private fun session() = ProviderCapabilitySession(
        store,
        listOf(
            ProviderDescriptor(musicBrainz,
                setOf(ProviderCapability.CATALOG_SEARCH),
                readiness = ProviderReadiness.READY),
            ProviderDescriptor(apple,
                setOf(ProviderCapability.RELEASE_ARTWORK),
                readiness = ProviderReadiness.READY),
            ProviderDescriptor(covers,
                setOf(ProviderCapability.RELEASE_ARTWORK),
                readiness = ProviderReadiness.READY),
        ),
    )

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun scrobblingConsentNeverImplicitlyEnablesPersonalRecommendations(): Unit = runBlocking {
        val provider = BuiltInProviderKeys.LISTENBRAINZ
        val first = ProviderCapabilitySession(store, emptyList())
        assertTrue(ProviderAuthorityCoordinator(first).migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
            includeMusicBrainzBaseline = false, includeDefaultArtworkBaseline = false,
            listenBrainzConfigured = true, listenBrainzEnabled = true,
        ))
        assertTrue(first.gate.open(ProviderCapability.LISTENING_STATISTICS, provider) != null)
        assertTrue(first.gate.open(ProviderCapability.PERSONAL_RECOMMENDATIONS, provider) == null)
        assertTrue(first.update(ProviderCapability.PERSONAL_RECOMMENDATIONS, listOf(
            ProviderChoice(provider, true, 0),
        )))
        val restored = ProviderCapabilitySession(store, emptyList())
        assertTrue(ProviderAuthorityCoordinator(restored).migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
            includeMusicBrainzBaseline = false, includeDefaultArtworkBaseline = false,
            listenBrainzConfigured = true, listenBrainzEnabled = false,
        ))
        assertTrue(restored.gate.open(ProviderCapability.PERSONAL_RECOMMENDATIONS, provider) != null)
        assertTrue(restored.gate.open(ProviderCapability.LISTENING_STATISTICS, provider) != null)
        assertTrue(restored.update(ProviderCapability.LISTENING_STATISTICS, emptyList()))
        assertTrue(restored.gate.open(ProviderCapability.PERSONAL_RECOMMENDATIONS, provider) != null)
        assertTrue(restored.gate.open(ProviderCapability.LISTENING_STATISTICS, provider) == null)
    }

    @Test fun explicitlyEmptyDownloadCategoryNeverReenablesSoulseekDuringMigration(): Unit = runBlocking {
        // An empty persisted category is a user choice, not an unmigrated feature.
        assertTrue(store.replaceCapability(ProviderCapability.DOWNLOAD, emptyList()))
        val restored = ProviderCapabilitySession(store, emptyList())
        assertTrue(ProviderAuthorityCoordinator(restored).migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
            includeMusicBrainzBaseline = false, includeDefaultArtworkBaseline = false,
            soulseekConfigured = true,
        ))
        assertEquals(emptyList<ProviderChoice>(),
            restored.state.value.configured.getValue(ProviderCapability.DOWNLOAD))
        assertTrue(restored.gate.open(ProviderCapability.DOWNLOAD, ProviderKey("soulseek")) == null)
        val persisted = store.read() as ProviderPreferenceStore.Read.Loaded
        assertEquals(emptyList<ProviderChoice>(),
            persisted.choices.getValue(ProviderCapability.DOWNLOAD))
    }

    @Test fun existingDownloadChoicesNeverAutoGrantNewSoulseekProvider(): Unit = runBlocking {
        val addon = ProviderKey("addon", "existing-addon")
        val prior = listOf(ProviderChoice(addon, enabled = true, order = 0))
        assertTrue(store.replaceCapability(ProviderCapability.DOWNLOAD, prior))
        val restored = ProviderCapabilitySession(store, emptyList())
        assertTrue(ProviderAuthorityCoordinator(restored).migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(),
            sources = listOf(com.music.bitchord.data.sources.SourceConfig(
                id = "existing-addon",
                kind = com.music.bitchord.data.sources.SourceKind.ADDON,
                baseUrl = "https://configured.example",
                enabled = true,
            )),
            includeMusicBrainzBaseline = false, includeDefaultArtworkBaseline = false,
            soulseekConfigured = true,
        ))
        assertEquals(prior,
            restored.state.value.configured.getValue(ProviderCapability.DOWNLOAD))
        assertTrue(restored.gate.open(ProviderCapability.DOWNLOAD, addon) != null)
        assertTrue(restored.gate.open(ProviderCapability.DOWNLOAD, ProviderKey("soulseek")) == null)
    }

    @Test fun publicChartMigrationPreservesExistingOptOutAcrossRestart(): Unit = runBlocking {
        val first = ProviderCapabilitySession(store, emptyList())
        assertTrue(ProviderAuthorityCoordinator(first).migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
            includeMusicBrainzBaseline = false, includeDefaultArtworkBaseline = false,
        ))
        val provider = BuiltInProviderKeys.LISTENBRAINZ
        assertTrue(first.gate.open(ProviderCapability.CHART_MOST_PLAYED, provider) != null)
        assertTrue(first.update(ProviderCapability.CHART_MOST_PLAYED, emptyList()))
        val second = ProviderCapabilitySession(store, emptyList())
        assertTrue(ProviderAuthorityCoordinator(second).migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
            includeMusicBrainzBaseline = false, includeDefaultArtworkBaseline = false,
        ))
        assertTrue(second.gate.open(ProviderCapability.CHART_MOST_PLAYED, provider) == null)
        assertEquals(emptyList<ProviderChoice>(),
            second.state.value.configured[ProviderCapability.CHART_MOST_PLAYED])
    }

    @Test fun anonymousPublicPlaylistMigrationPreservesExistingOptOutOnRestart(): Unit = runBlocking {
        val first = ProviderCapabilitySession(store, emptyList())
        val old = ProviderAuthorityCoordinator(first)
        assertTrue(old.migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
            includeMusicBrainzBaseline = false,
            includeDefaultArtworkBaseline = false,
        ))
        val provider = BuiltInProviderKeys.LISTENBRAINZ
        assertTrue(first.gate.open(ProviderCapability.PUBLIC_PLAYLISTS, provider) != null)
        assertTrue(first.update(ProviderCapability.PUBLIC_PLAYLISTS, listOf(
            ProviderChoice(provider, false, 0))))
        assertTrue(first.gate.open(ProviderCapability.PUBLIC_PLAYLISTS, provider) == null)

        val restored = ProviderCapabilitySession(store, emptyList())
        assertTrue(ProviderAuthorityCoordinator(restored).migrateOnce(
            lyricsOrder = emptyList(), enabledLyrics = emptySet(), sources = emptyList(),
            includeMusicBrainzBaseline = false,
            includeDefaultArtworkBaseline = false,
        ))
        assertTrue(restored.gate.open(ProviderCapability.PUBLIC_PLAYLISTS, provider) == null)
        assertEquals(listOf(ProviderChoice(provider, false, 0)),
            restored.state.value.configured[ProviderCapability.PUBLIC_PLAYLISTS])
    }

    @Test fun sourceRefreshDoesNotDisableUnrelatedReadyBuiltInProviders(): Unit = runBlocking {
        val session = ProviderCapabilitySession(store, emptyList())
        val authority = ProviderAuthorityCoordinator(session)
        assertTrue(authority.migrateOnce(
            lyricsOrder = emptyList(),
            enabledLyrics = emptySet(),
            sources = emptyList(),
            includeMusicBrainzBaseline = false,
            includeDefaultArtworkBaseline = false,
            itunesImplemented = true,
            discogsImplemented = true,
            discogsTokenConfigured = true,
            soulseekConfigured = true,
        ))
        assertTrue(session.update(ProviderCapability.EDITION_METADATA, listOf(
            ProviderChoice(BuiltInProviderKeys.DISCOGS, true, 0))))
        val itunes = requireNotNull(session.gate.open(
            ProviderCapability.CATALOG_SEARCH, BuiltInProviderKeys.ITUNES))
        val discogs = requireNotNull(session.gate.open(
            ProviderCapability.EDITION_METADATA, BuiltInProviderKeys.DISCOGS))
        val soulseek = requireNotNull(session.gate.open(
            ProviderCapability.DOWNLOAD, ProviderKey("soulseek")))

        authority.onSourcesChanged(emptyList())
        assertTrue(session.gate.accept(itunes))
        assertTrue(session.gate.accept(discogs))
        assertTrue(session.gate.accept(soulseek))

        authority.updateSoulseekReadiness(configured = true)
        assertTrue(session.gate.accept(itunes))
        assertTrue(session.gate.accept(discogs))
        assertTrue(session.gate.accept(soulseek))
    }

    @Test fun headlessStateIsObservableWithoutAnAndroidSettingsScreen(): Unit = runBlocking {
        val session = session()
        assertEquals(ProviderSessionStatus.BOOTING, session.state.value.status)
        assertTrue(session.state.value.eligible.isEmpty())
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, enabled = true, order = 0),
            ),
        )))
        val loaded = session.state.value
        assertEquals(ProviderSessionStatus.READY, loaded.status)
        assertEquals(listOf(musicBrainz),
            loaded.eligible.getValue(ProviderCapability.CATALOG_SEARCH))
        assertEquals(listOf(ProviderChoice(musicBrainz, true, 0)),
            loaded.configured.getValue(ProviderCapability.CATALOG_SEARCH))

        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, emptyList()))
        val disabled = session.state.value
        assertEquals(ProviderSessionStatus.READY, disabled.status)
        assertTrue(disabled.configured.containsKey(ProviderCapability.CATALOG_SEARCH))
        assertEquals(emptyList<ProviderChoice>(),
            disabled.configured.getValue(ProviderCapability.CATALOG_SEARCH))
        assertTrue(disabled.eligible[ProviderCapability.CATALOG_SEARCH].isNullOrEmpty())
    }

    @Test fun sessionUpdateDisablingOneProviderPreservesSiblingProviderInSameCapability(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.RELEASE_ARTWORK to listOf(
                ProviderChoice(apple, enabled = true, order = 0),
                ProviderChoice(covers, enabled = true, order = 1),
            ),
        )))
        val appleTicket = requireNotNull(session.gate.open(ProviderCapability.RELEASE_ARTWORK, apple))
        val fanartTicket = requireNotNull(session.gate.open(ProviderCapability.RELEASE_ARTWORK, covers))
        var appleRevoked = false
        var fanartRevoked = false
        session.gate.onRevoke(appleTicket) { appleRevoked = true }
        session.gate.onRevoke(fanartTicket) { fanartRevoked = true }

        // Turn off iTunes while keeping Fanart.tv enabled in the same capability.
        assertTrue(session.update(
            ProviderCapability.RELEASE_ARTWORK,
            listOf(
                ProviderChoice(apple, enabled = false, order = 0),
                ProviderChoice(covers, enabled = true, order = 1),
            ),
        ))
        assertTrue(appleRevoked)
        assertFalse(session.gate.accept(appleTicket))
        assertFalse(fanartRevoked)
        assertTrue(session.gate.accept(fanartTicket))
        assertEquals(
            listOf(covers),
            session.gate.ordered(ProviderCapability.RELEASE_ARTWORK).map { it.key },
        )
    }

    @Test fun disablingLastProviderRemainsDeniedWhenPreferencesCommitFails(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, enabled = true, order = 0),
            ),
        )))
        val activeTicket = requireNotNull(
            session.gate.open(ProviderCapability.CATALOG_SEARCH, musicBrainz),
        )
        // Corrupt the backing store to simulate a failed durable preference change.
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, "{invalid").commit()
        assertFalse(session.update(ProviderCapability.CATALOG_SEARCH, emptyList()))
        assertEquals(ProviderSessionStatus.STORAGE_ERROR, session.state.value.status)
        assertFalse(session.gate.accept(activeTicket))
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertTrue(ProviderCapability.CATALOG_SEARCH in session.state.value.quarantined)
    }

    @Test fun siblingProviderIsNeverRevokedEvenOnFailedUnrelatedSwitchWrite(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.RELEASE_ARTWORK to listOf(
                ProviderChoice(apple, enabled = true, order = 0),
                ProviderChoice(covers, enabled = true, order = 1),
            ),
        )))
        val appleTicket = requireNotNull(
            session.gate.open(ProviderCapability.RELEASE_ARTWORK, apple),
        )
        val fanartTicket = requireNotNull(
            session.gate.open(ProviderCapability.RELEASE_ARTWORK, covers),
        )
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, "{invalid").commit()
        assertFalse(session.update(ProviderCapability.RELEASE_ARTWORK, listOf(
            ProviderChoice(apple, enabled = false, order = 0),
            ProviderChoice(covers, enabled = true, order = 1),
        )))
        assertFalse(session.gate.accept(appleTicket))
        assertTrue(session.gate.accept(fanartTicket))
        assertEquals(listOf(covers),
            session.gate.ordered(ProviderCapability.RELEASE_ARTWORK).map { it.key })
    }

    @Test fun reorderingTwoEnabledProvidersKeepsBothInflightTicketsValid(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.RELEASE_ARTWORK to listOf(
                ProviderChoice(apple, enabled = true, order = 0),
                ProviderChoice(covers, enabled = true, order = 1),
            ),
        )))
        val appleTicket = requireNotNull(
            session.gate.open(ProviderCapability.RELEASE_ARTWORK, apple),
        )
        val fanartTicket = requireNotNull(
            session.gate.open(ProviderCapability.RELEASE_ARTWORK, covers),
        )
        assertTrue(session.update(ProviderCapability.RELEASE_ARTWORK, listOf(
            ProviderChoice(covers, enabled = true, order = 0),
            ProviderChoice(apple, enabled = true, order = 1),
        )))
        assertTrue(session.gate.accept(appleTicket))
        assertTrue(session.gate.accept(fanartTicket))
    }

    @Test fun restoringConfiguredExtensionDescriptorsAfterLegacyRegistryLoadMakesOnlySelectedInstanceEligible(): Unit = runBlocking {
        val extensionA = ProviderKey("addon", "home-a")
        val extensionB = ProviderKey("addon", "home-b")
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.EXTENSION_PLAYBACK to listOf(
                ProviderChoice(extensionA, enabled = true, order = 0),
                ProviderChoice(extensionB, enabled = false, order = 1),
            ),
        )))
        assertTrue(session.gate.ordered(ProviderCapability.EXTENSION_PLAYBACK).isEmpty())
        session.refreshDescriptors(listOf(
            ProviderDescriptor(musicBrainz,
                setOf(ProviderCapability.CATALOG_SEARCH),
                readiness = ProviderReadiness.READY),
            ProviderDescriptor(extensionA,
                setOf(ProviderCapability.EXTENSION_PLAYBACK),
                readiness = ProviderReadiness.READY),
            ProviderDescriptor(extensionB,
                setOf(ProviderCapability.EXTENSION_PLAYBACK),
                readiness = ProviderReadiness.READY),
        ))
        assertEquals(listOf(extensionA),
            session.gate.ordered(ProviderCapability.EXTENSION_PLAYBACK).map { it.key })
        assertTrue(session.state.value.configured.getValue(
            ProviderCapability.EXTENSION_PLAYBACK).any { it.key == extensionB && !it.enabled })
    }

    @Test fun failedSettingsWriteIsRepresentedAsUnavailableNotAReactivatedSwitch(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, enabled = true, order = 0),
            ),
        )))
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, "{bad").commit()
        assertFalse(session.update(ProviderCapability.CATALOG_SEARCH, emptyList()))
        val failed = session.state.value
        assertEquals(ProviderSessionStatus.STORAGE_ERROR, failed.status)
        assertTrue(ProviderCapability.CATALOG_SEARCH in failed.quarantined)
        assertTrue(failed.eligible[ProviderCapability.CATALOG_SEARCH].isNullOrEmpty())
        // The stale saved selection must never be displayed as a successful
        // OFF or ON change while the durable write has failed.
        assertEquals(listOf(ProviderChoice(musicBrainz, true, 0)),
            failed.configured[ProviderCapability.CATALOG_SEARCH])
    }

    @Test fun duplicateNoOpDoesNotPublishAnotherStateSnapshot(): Unit = runBlocking {
        val session = session()
        val selected = listOf(ProviderChoice(musicBrainz, true, 0))
        assertTrue(session.initialize(mapOf(ProviderCapability.CATALOG_SEARCH to selected)))
        val snapshot = session.state.value
        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, selected))
        assertTrue(session.state.value === snapshot)
    }

    @Test fun noNetworkIsEligibleBeforeConfigurationIsLoaded(): Unit = runBlocking {
        val session = session()
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        var contacted = false
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(session.gate),
            lookupArtists = { _, _, _ ->
                contacted = true
                throw IllegalStateException("network should be denied")
            },
        )
        assertEquals(ProviderCallResult.Denied, adapter.searchArtists("NewJeans"))
        assertFalse(contacted)
    }

    @Test fun cleanInstallWithNoLegacyProvidersCanEnableItsFirstProvider(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(emptyMap()))
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, listOf(
            ProviderChoice(musicBrainz, enabled = true, order = 0),
        )))
        assertEquals(listOf(musicBrainz),
            session.gate.ordered(ProviderCapability.CATALOG_SEARCH).map { it.key })
        assertTrue(ProviderCapabilitySession(store, listOf(
            ProviderDescriptor(musicBrainz, setOf(ProviderCapability.CATALOG_SEARCH),
                readiness = ProviderReadiness.READY),
        )).initialize(emptyMap()))
    }

    @Test fun explicitLegacyImportEnablesOnlyItsDeclaredCapability(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, true, 0),
            ),
        )))
        assertEquals(listOf(musicBrainz),
            session.gate.ordered(ProviderCapability.CATALOG_SEARCH).map { it.key })
        assertTrue(session.gate.ordered(ProviderCapability.RELEASE_ARTWORK).isEmpty())
    }

    @Test fun turningOffTheOnlyProviderBlocksRealCatalogAdapterAndSurvivesRestart(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, true, 0),
            ),
        )))
        var contacted = 0
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(session.gate),
            lookupArtists = { _, _, _ ->
                contacted++
                MusicBrainzCatalogSearchResult.Success(
                    MusicBrainzCatalogPage(
                        listOf(MusicBrainzArtistHit(
                            "11111111-2222-4333-8444-555555555555",
                            "NewJeans", null, "KR",
                        )), 1, 0,
                    ),
                )
            },
        )
        assertTrue(adapter.searchArtists("NewJeans") is ProviderCallResult.Success)
        assertEquals(1, contacted)

        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, listOf(
            ProviderChoice(musicBrainz, false, 0),
        )))
        assertEquals(ProviderCallResult.Denied, adapter.searchArtists("NewJeans"))
        assertEquals(1, contacted)

        val restored = session()
        assertTrue(restored.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, true, 0), // old legacy value must never reactivate it
            ),
        )))
        assertTrue(restored.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
    }

    @Test fun providerDisableRevokesInFlightResponseWithoutAffectingArtwork(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(musicBrainz, true, 0)),
            ProviderCapability.RELEASE_ARTWORK to listOf(ProviderChoice(covers, true, 0)),
        )))
        val requested = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val adapter = MusicBrainzCapabilitySearch(
            executor = ProviderQueryExecutor(session.gate),
            lookupArtists = { _, _, _ ->
                requested.complete(Unit)
                resume.await()
                MusicBrainzCatalogSearchResult.Success(
                    MusicBrainzCatalogPage(emptyList<MusicBrainzArtistHit>(), 0, 0),
                )
            },
        )
        val result = async { adapter.searchArtists("NewJeans") }
        requested.await()
        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, emptyList()))
        resume.complete(Unit)
        assertEquals(ProviderCallResult.Stale, result.await())
        assertEquals(listOf(covers),
            session.gate.ordered(ProviderCapability.RELEASE_ARTWORK).map { it.key })
    }

    @Test fun repeatedIdenticalPreferenceDoesNotRevokeAnUnrelatedInflightRequest(): Unit = runBlocking {
        val session = session()
        val selected = listOf(ProviderChoice(musicBrainz, true, 0))
        assertTrue(session.initialize(mapOf(ProviderCapability.CATALOG_SEARCH to selected)))
        val ticket = session.gate.open(ProviderCapability.CATALOG_SEARCH, musicBrainz)
        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, selected))
        assertTrue(session.gate.accept(requireNotNull(ticket)))
    }

    @Test fun failedPersistenceKeepsOptedOutCapabilityClosedAcrossOtherEdits(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(musicBrainz, true, 0)),
        )))
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, "{bad").commit()
        assertFalse(session.update(ProviderCapability.CATALOG_SEARCH, listOf(
            ProviderChoice(musicBrainz, false, 0),
        )))
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertFalse(session.update(ProviderCapability.RELEASE_ARTWORK, listOf(
            ProviderChoice(covers, true, 0),
        )))
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
    }

    @Test fun corruptedNewPreferencesFailClosedAndSurfaceStorageErrorAndRecoverCleanly(): Unit = runBlocking {
        val bad = """{"version":999,"rows":[]}"""
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, bad).commit()
        val session = session()
        assertFalse(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, true, 0),
            ),
        )))
        assertEquals(ProviderSessionStatus.STORAGE_ERROR, session.state.value.status)
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        assertEquals(bad, prefs.getString(ProviderPreferenceStore.STORAGE_KEY, null))

        // Once the external schema corruption is repaired, re-initializing recovers cleanly.
        prefs.edit().clear().commit()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(
                ProviderChoice(musicBrainz, false, 0),
            ),
        )))
        assertEquals(ProviderSessionStatus.READY, session.state.value.status)
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
    }

    @Test fun quarantinedCapabilityRecoversWhenSubsequentSaveSucceeds(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(musicBrainz, true, 0)),
        )))
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, "{corrupt").commit()
        assertFalse(session.update(ProviderCapability.CATALOG_SEARCH, emptyList()))
        assertEquals(ProviderSessionStatus.STORAGE_ERROR, session.state.value.status)
        assertTrue(ProviderCapability.CATALOG_SEARCH in session.state.value.quarantined)

        // Repair storage and retry saving an explicit opt-out.
        prefs.edit().clear().commit()
        assertTrue(session.update(ProviderCapability.CATALOG_SEARCH, emptyList()))
        assertEquals(ProviderSessionStatus.READY, session.state.value.status)
        assertFalse(ProviderCapability.CATALOG_SEARCH in session.state.value.quarantined)
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
    }

    @Test fun operationalHealthSuspendsDispatchWithoutDeletingUserAuthorization(): Unit = runBlocking {
        val session = session()
        assertTrue(session.initialize(mapOf(
            ProviderCapability.CATALOG_SEARCH to listOf(ProviderChoice(musicBrainz, true, 0)),
        )))
        assertEquals(
            ProviderOperationalHealth.UNKNOWN,
            session.statusOf(ProviderCapability.CATALOG_SEARCH, musicBrainz).operationalHealth,
        )
        session.reportHealth(musicBrainz, ProviderOperationalHealth.UNAVAILABLE)
        assertTrue(session.gate.ordered(ProviderCapability.CATALOG_SEARCH).isEmpty())
        val status = session.statusOf(ProviderCapability.CATALOG_SEARCH, musicBrainz)
        assertTrue(status.userAuthorized)
        assertFalse(status.effectiveEligible)

        session.reportHealth(musicBrainz, ProviderOperationalHealth.OPERATIONAL)
        assertEquals(
            listOf(musicBrainz),
            session.gate.ordered(ProviderCapability.CATALOG_SEARCH).map { it.key },
        )
    }
    @Test fun failedWriteSnapshotListsOnlyRevokedProviderInstance(): Unit = runBlocking {
        val session = session()
        val capability = ProviderCapability.RELEASE_ARTWORK
        assertTrue(session.initialize(mapOf(capability to listOf(
            ProviderChoice(apple, true, 0), ProviderChoice(covers, true, 1),
        ))))
        val sibling = requireNotNull(session.gate.open(capability, apple))
        val revoked = requireNotNull(session.gate.open(capability, covers))

        // A failed durable opt-out must revoke only the changed instance;
        // reporting an empty per-instance quarantine hides this fact from the
        // future headless Sources diagnostics and settings integration.
        prefs.edit().putString(ProviderPreferenceStore.STORAGE_KEY, "{corrupt").commit()
        assertFalse(session.update(capability, listOf(
            ProviderChoice(apple, true, 0), ProviderChoice(covers, false, 1),
        )))
        assertTrue(session.gate.accept(sibling))
        assertFalse(session.gate.accept(revoked))
        assertEquals(setOf(capability to covers),
            session.state.value.quarantinedProviders)
        assertEquals(setOf(capability), session.state.value.quarantined)
        assertEquals(ProviderSessionStatus.STORAGE_ERROR, session.state.value.status)

        // Recovering a valid persistence document clears only this capability's
        // quarantine and must not grant the user's explicit opt-out.
        prefs.edit().clear().commit()
        assertTrue(session.update(capability, listOf(
            ProviderChoice(apple, true, 0), ProviderChoice(covers, false, 1),
        )))
        assertTrue(session.state.value.quarantinedProviders.isEmpty())
        assertTrue(session.gate.ordered(capability).map { it.key } == listOf(apple))
    }

}
