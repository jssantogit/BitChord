package com.music.bitchord.ui.screens

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReader
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityReaders
import com.music.bitchord.data.catalog.provider.RemoteNumericRatingKind
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicRemoteRatingReader
import com.music.bitchord.data.catalog.provider.opensubsonic.RemoteRatingAvailability
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import com.music.bitchord.data.listening.CanonicalRuleBasedSmartPlaylistPreview
import com.music.bitchord.data.listening.ListeningEventDatabase
import com.music.bitchord.data.listening.RemoteSmartPlaylistRatingEvidence
import com.music.bitchord.data.listening.RemoteSmartPlaylistRatingKind
import com.music.bitchord.data.listening.RemoteSmartPlaylistRatingReader
import com.music.bitchord.data.listening.RemoteSmartPlaylistRatingSnapshot
import com.music.bitchord.data.listening.RoomListeningEventStore
import com.music.bitchord.data.listening.RoomRecordingRatingStore

/**
 * The ONE production rule evaluator for editor preview and saved playlist actions.
 * It reads remote evidence from a current account-scoped complete Catalog cache;
 * it never migrates or writes remote feedback to first-party Listening/Room.
 */
internal fun persistentSmartPlaylistPreview(
    context: Context,
    availability: CanonicalLibraryAvailabilityReader =
        CanonicalLibraryAvailabilityReaders.persistent(context),
): CanonicalRuleBasedSmartPlaylistPreview {
    val app = context.applicationContext
    val catalog = CanonicalCatalogDatabase.get(app)
    val listening = ListeningEventDatabase.get(app)
    val persistence = SharedPrefsOpenSubsonicAccountPersistence(app)
    val remoteReader = OpenSubsonicRemoteRatingReader(catalog) { persistence.read() }
    return CanonicalRuleBasedSmartPlaylistPreview(
        availability = availability,
        catalog = catalog.catalogDao(),
        ratings = RoomRecordingRatingStore(listening.recordingRatingDao()),
        listening = RoomListeningEventStore(listening.listeningEventDao()),
        remoteRatings = RemoteSmartPlaylistRatingReader {
            val snapshot = remoteReader.read() // cached Room read, no network/auth or mutation
            if (snapshot.availability != RemoteRatingAvailability.CACHED_COMPLETE) {
                RemoteSmartPlaylistRatingSnapshot(complete = false, evidence = emptyList())
            } else {
                RemoteSmartPlaylistRatingSnapshot(
                    complete = true,
                    evidence = snapshot.items.map { item ->
                        val kind = when (item.numeric.kind) {
                            RemoteNumericRatingKind.VALUE -> RemoteSmartPlaylistRatingKind.VALUE
                            RemoteNumericRatingKind.CLEARED_ZERO -> RemoteSmartPlaylistRatingKind.CLEARED
                            RemoteNumericRatingKind.NOT_PROVIDED -> RemoteSmartPlaylistRatingKind.NOT_PROVIDED
                            RemoteNumericRatingKind.INVALID -> RemoteSmartPlaylistRatingKind.INVALID
                        }
                        RemoteSmartPlaylistRatingEvidence(
                            recordingId = item.recordingId, // canonical identity only
                            kind = kind,
                            value = item.numeric.value,
                        )
                    },
                )
            }
        },
    )
}
