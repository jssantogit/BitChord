package com.music.bitchord.download

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Only exact physical provider identities may collapse local rows.
 * Never merge recordings by title, artist, duration, release or basename.
 */
internal object A4LocalPhysicalIdentity {
    fun key(raw:String):String {
        val uri=runCatching { URI(raw) }.getOrNull() ?: return raw
        if(!uri.scheme.equals("content",ignoreCase=true)) return raw
        val authority=uri.authority?.lowercase() ?: return raw
        val path=runCatching { URLDecoder.decode(uri.rawPath.orEmpty().replace("+","%2B"),
            StandardCharsets.UTF_8.name()) }
            .getOrDefault(uri.path.orEmpty())
        if(authority=="media") {
            val parts=path.trim('/').split('/')
            if(parts.size==4 && parts[1]=="audio" && parts[2]=="media" &&
                parts[3].toLongOrNull()!=null) {
                val volume=if(parts[0] in setOf("external","external_primary"))
                    "external" else parts[0]
                return "media:$volume:${parts[3]}"
            }
        }
        if(authority=="com.android.providers.media.documents") {
            val document=path.substringAfter("/document/","")
            val parts=document.split(':')
            if(parts.size==2 && parts[0]=="audio" && parts[1].toLongOrNull()!=null)
                return "media:external:${parts[1]}"
        }
        if(authority=="com.android.externalstorage.documents") {
            val doc=path.substringAfterLast("/document/","")
            if(doc.isNotBlank()) {
                val volume=doc.substringBefore(':',"")
                val relative=doc.substringAfter(':',"")
                if(volume.equals("primary",ignoreCase=true) && relative.isNotBlank())
                    return pathKey("/storage/emulated/0/$relative")
            }
        }
        return raw
    }

    fun pathKey(path:String):String = "path:"+path.replace('\\','/')
        .replace(Regex("/+"),"/").trimEnd('/')
}
