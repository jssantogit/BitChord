package com.music.bitchord.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * A2 Android publisher endpoint. This does not run automatically after A0:
 * the eventual download manager must call [publishPrivateReceipt] after the
 * user explicitly requested permanent local storage and transport succeeded.
 *
 * Default is a pending MediaStore.Audio row under Music/BitChord. A chosen SAF
 * tree uses a temporary filename and rename, which is best effort and cannot
 * promise atomic visibility across all DocumentsProvider implementations.
 * The returned content URI is the library identity seam, never a guessed path.
 */
internal object A2AndroidAudioDestination {
    fun publishPrivateReceipt(
        context:Context,
        source:File,
        displayName:String,
        receivedBytes:Long,
        observedSha256:String,
        isCancelled:()->Boolean={false}
    ):A2LocalAudioPublisher.Published {
        check(Looper.myLooper()!=Looper.getMainLooper()) {
            "A2 publication must not block Android main thread"
        }
        val app=context.applicationContext
        val resolution=A1AndroidDownloadDestination.current(app)
        val target=(resolution as? A1DownloadDestinationPolicy.Resolution.Ready)?.target
            ?:throw IOException("A2 destination requires folder re-selection")
        val factory=A2LocalAudioPublisher.DestinationFactory { _, _ ->
            error("Kotlin interfaces require the createPending override below")
        }
        return A2LocalAudioPublisher.publish(
            source,displayName,receivedBytes,observedSha256,
            object:A2LocalAudioPublisher.DestinationFactory {
                override fun createPending(
                    name:String,mime:String
                ):A2LocalAudioPublisher.PendingDestination =
                    when(target) {
                        is A1DownloadDestinationPolicy.Target.SharedMusic ->
                            newMediaStoreRow(app,name,mime,target.relativePath)
                        is A1DownloadDestinationPolicy.Target.GrantedTree ->
                            newDocumentTreeEntry(app,Uri.parse(target.treeUri),name,mime)
                    }
            },isCancelled
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun newMediaStoreRow(
        context:Context,name:String,mime:String,relativePath:String
    ):A2LocalAudioPublisher.PendingDestination {
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q)
            throw IOException("A2 legacy shared media publishing requires an API26-28 adapter")
        val resolver=context.contentResolver
        val collection=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        // Preserve existing user files instead of accepting MediaStore's
        // implicit ' (1)' filename collision fallback.
        val exists=try {
            resolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name,relativePath),null)?.use{it.moveToFirst()}
                ?:throw IOException("A2 cannot check existing MediaStore names")
        }catch(e:Exception) {
            if(e is IOException)throw e
            throw IOException("A2 MediaStore preflight failed",e)
        }
        if(exists)throw IOException("A2 destination filename already exists")
        val uri=resolver.insert(collection,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,relativePath)
            put(MediaStore.MediaColumns.IS_PENDING,1)
        })?:throw IOException("A2 could not reserve pending audio row")
        return object:A2LocalAudioPublisher.PendingDestination {
            override fun openStream():OutputStream=
                resolver.openOutputStream(uri,"w")
                    ?:throw IOException("A2 MediaStore pending stream unavailable")

            override fun publish():String {
                val actual=resolver.query(uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null,null,null)?.use{
                    if(it.moveToFirst())it.getString(0) else null
                }
                if(actual!=name)throw IOException("A2 media provider renamed destination")
                val count=resolver.update(uri,ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING,0)
                },null,null)
                if(count!=1)throw IOException("A2 cannot publish pending audio")
                return uri.toString()
            }

            override fun abort() {
                runCatching { resolver.delete(uri,null,null) }
            }
        }
    }

    private fun newDocumentTreeEntry(
        context:Context,tree:Uri,name:String,mime:String
    ):A2LocalAudioPublisher.PendingDestination {
        // Fail closed if permission was lost between opening settings and now.
        val stillSelected=A1AndroidDownloadDestination.current(context)
        val selected=(stillSelected as?
            A1DownloadDestinationPolicy.Resolution.Ready)?.target as?
            A1DownloadDestinationPolicy.Target.GrantedTree
        if(selected?.treeUri!=tree.toString() ||
            !DocumentsContract.isTreeUri(tree)) {
            throw IOException("A2 SAF folder permission is no longer available")
        }
        val resolver=context.contentResolver
        val root=try {
            DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree))
        }catch(e:Exception) {
            throw IOException("A2 invalid SAF root",e)
        }
        if(hasChildName(resolver,tree,name))
            throw IOException("A2 destination filename already exists")
        val tempName=".bitchord-${UUID.randomUUID()}.partial"
        val temp=DocumentsContract.createDocument(resolver,root,mime,tempName)
            ?:throw IOException("A2 cannot create temporary SAF document")
        var created:Uri=temp
        return object:A2LocalAudioPublisher.PendingDestination {
            override fun openStream():OutputStream=
                resolver.openOutputStream(created,"w")
                    ?:throw IOException("A2 SAF temporary document cannot be written")

            override fun publish():String {
                // Recheck before rename, so a conflict during copying normally
                // fails without silently replacing another user's file.
                if(hasChildName(resolver,tree,name))
                    throw IOException("A2 destination filename appeared during copy")
                val renamed=DocumentsContract.renameDocument(resolver,created,name)
                    ?:throw IOException("A2 SAF provider cannot finalize document")
                created=renamed
                val actual=resolver.query(renamed,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,null,null)?.use{
                    if(it.moveToFirst())it.getString(0) else null
                }
                if(actual!=name)
                    throw IOException("A2 SAF provider did not preserve filename")
                return renamed.toString()
            }
            override fun abort() {
                runCatching { DocumentsContract.deleteDocument(resolver,created) }
                if(created!=temp)
                    runCatching { DocumentsContract.deleteDocument(resolver,temp) }
            }
        }
    }

    private fun hasChildName(
        resolver:ContentResolver,tree:Uri,expectedName:String
    ):Boolean {
        val children=try {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree))
        }catch(e:Exception) {
            throw IOException("A2 SAF children cannot be resolved",e)
        }
        return try{
            resolver.query(children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,null,null)?.use { cursor ->
                var found=false
                while(cursor.moveToNext()) {
                    if(cursor.getString(0)?.equals(expectedName,ignoreCase=true)==true) {
                        found=true;break
                    }
                }
                found
            }?:throw IOException("A2 cannot inspect SAF destination conflicts")
        }catch(e:Exception) {
            if(e is IOException)throw e
            throw IOException("A2 SAF destination inspection failed",e)
        }
    }
}
