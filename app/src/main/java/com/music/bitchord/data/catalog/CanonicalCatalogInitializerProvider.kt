package com.music.bitchord.data.catalog

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.music.bitchord.data.LocalMediaRepository

/**
 * Process initializer for the phone app's transitional Local Music seam.
 *
 * The provider performs no database I/O on startup. It only composes the
 * existing [LocalMediaRepository.extraLocalMusic] hook so Room opens lazily
 * the first time Local Music is actually scanned.
 */
class CanonicalCatalogInitializerProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val previous = LocalMediaRepository.extraLocalMusic
        LocalMediaRepository.extraLocalMusic = { context, scannedSongs ->
            val composedSongs = previous?.invoke(context, scannedSongs) ?: scannedSongs
            if (!LocalMediaRepository.hasStoragePermission(context)) {
                // getLocalMusic invokes the hook with an empty list when permission is
                // missing. Do not interpret that state as a real empty MediaStore scan.
                composedSongs
            } else {
                CanonicalLocalLibrary.sync(context.applicationContext, composedSongs)
            }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
