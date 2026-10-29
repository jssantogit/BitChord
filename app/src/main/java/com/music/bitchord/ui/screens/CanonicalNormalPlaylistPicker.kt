package com.music.bitchord.ui.screens

import android.content.Context
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLocalLibrary
import com.music.bitchord.data.catalog.OpenSubsonicLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.RecordingEntity
import com.music.bitchord.data.catalog.provider.opensubsonic.AndroidKeystoreOpenSubsonicCredentialStore
import com.music.bitchord.data.catalog.provider.opensubsonic.OpenSubsonicAccountRepository
import com.music.bitchord.data.catalog.provider.opensubsonic.SharedPrefsOpenSubsonicAccountPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Preserves the user's hand-picked order, with no implicit duplicate members. */
internal fun toggleCanonicalStaticMember(ids: List<String>, id: String): List<String> =
    if (id in ids) ids.filterNot { it == id } else ids + id

/**
 * Search is capped at 200 rows per request inside SQLite, not 200 recordings
 * from the whole library. Every row is associated with a currently configured
 * local or authenticated OpenSubsonic source.
 */
internal suspend fun canonicalNormalPlaylistSearch(
    context: Context,
    term: String,
): List<V2DisplayRow> = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val keys = activeCanonicalPlaylistProviderKeys(app)
    if (keys.isEmpty()) return@withContext emptyList()
    val dao = CanonicalCatalogDatabase.get(app).catalogDao()
    val recordings = dao.searchAvailableRecordingsForPlaylist(keys, term.trim())
    canonicalNormalRows(dao, recordings, keys)
}

/** A saved playlist keeps unavailable IDs; only known current records get labels. */
internal suspend fun canonicalNormalPlaylistSelectedRows(
    context: Context,
    ids: List<String>,
): List<V2DisplayRow> = withContext(Dispatchers.IO) {
    if (ids.isEmpty()) return@withContext emptyList()
    val app = context.applicationContext
    val keys = activeCanonicalPlaylistProviderKeys(app)
    val dao = CanonicalCatalogDatabase.get(app).catalogDao()
    val recordings = ids.distinct().chunked(400)
        .flatMap { dao.recordingsByIds(it) }
    val indexed = canonicalNormalRows(dao, recordings, keys).associateBy { it.id }
    ids.map { id ->
        indexed[id] ?: V2DisplayRow(id = id, title = "Gravação indisponível", artist = "Fonte não disponível")
    }
}

private fun activeCanonicalPlaylistProviderKeys(context: Context): List<String> {
    val accountRepository = OpenSubsonicAccountRepository(
        persistence = SharedPrefsOpenSubsonicAccountPersistence(context),
        credentials = AndroidKeystoreOpenSubsonicCredentialStore(context),
    )
    return OpenSubsonicLibraryProviderConfigurationReader(accountRepository)
        .configuredProviderKeys().sorted()
}

private suspend fun canonicalNormalRows(
    dao: com.music.bitchord.data.catalog.CanonicalCatalogDao,
    recordings: List<RecordingEntity>,
    providerKeys: List<String>,
): List<V2DisplayRow> {
    if (recordings.isEmpty()) return emptyList()
    val artworkById = if (providerKeys.isEmpty()) emptyMap() else recordings
        .map { it.id }.chunked(400)
        .flatMap { dao.browseAssetsForRecordingIds(providerKeys, it) }
        .filter { !it.artworkUri.isNullOrBlank() }
        .groupBy { it.recordingId }
    return recordings.map { info ->
        val assets = artworkById[info.id].orEmpty()
        val local = assets.firstOrNull {
            it.providerKey == CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER &&
                (it.artworkUri?.startsWith("content://") == true ||
                 it.artworkUri?.startsWith("file://") == true ||
                 it.artworkUri?.startsWith("https://") == true)
        }
        val remote = assets.firstOrNull { it.providerKey.startsWith("opensubsonic:") &&
            it.providerKey in providerKeys }
        V2DisplayRow(
            id = info.id, title = info.title, artist = info.artistDisplay,
            localArtworkUri = local?.artworkUri,
            remoteMediaAssetId = if (local == null) remote?.mediaAssetId else null,
            remoteArtworkRevision = if (local == null) remote?.artworkUri else null,
        )
    }
}
