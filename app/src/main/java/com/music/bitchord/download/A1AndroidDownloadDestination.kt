package com.music.bitchord.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Storage Access Framework adapter for the settings-only A1 destination.
 * Isolated from DownloadStore and legacy settings: no existing saved files
 * are moved and no Soulseek data is published here.
 */
internal object A1AndroidDownloadDestination {
    private const val PREFS = "bitchord_settings"
    private const val KEY = "acquisition_destination_tree_uri_v1"
    private const val GRANTS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun current(context: Context): A1DownloadDestinationPolicy.Resolution =
        controller(context.applicationContext).current()

    fun select(context: Context, treeUri: Uri): Boolean =
        controller(context.applicationContext).choose(treeUri.toString())

    fun reset(context: Context): Boolean =
        controller(context.applicationContext).reset()

    private fun controller(context: Context): A1DownloadDestinationController {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val resolver=context.contentResolver
        return A1DownloadDestinationController(
            preference=object:A1DownloadDestinationController.Preference {
                override fun read():String?=prefs.getString(KEY,null)
                override fun write(uri:String?):Boolean=runCatching {
                    val edit=prefs.edit()
                    if(uri==null)edit.remove(KEY) else edit.putString(KEY,uri)
                    edit.commit()
                }.getOrDefault(false)
            },
            grants=object:A1DownloadDestinationController.Grants {
                override fun hasReadWrite(uri:String):Boolean=runCatching {
                    val parsed=Uri.parse(uri)
                    DocumentsContract.isTreeUri(parsed) &&
                        resolver.persistedUriPermissions.any { grant ->
                            grant.uri==parsed &&
                                grant.isReadPermission && grant.isWritePermission
                        }
                }.getOrDefault(false)

                override fun takeReadWrite(uri:String):Boolean=runCatching {
                    val parsed=Uri.parse(uri)
                    if(!DocumentsContract.isTreeUri(parsed))return@runCatching false
                    resolver.takePersistableUriPermission(parsed,GRANTS)
                    hasReadWrite(uri)
                }.getOrDefault(false)
            }
        )
    }
}
