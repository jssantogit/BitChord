package com.music.bitchord.download

/**
 * A1 preference and permission transaction. A successful picker result alone
 * is not authorization: the platform must persist BOTH read/write permissions
 * before any chosen root becomes active.
 *
 * This is settings-only; A0 continues to delete its private scratch receipt.
 * The eventual publisher must re-open/check the DocumentsProvider at write time.
 */
internal class A1DownloadDestinationController(
    private val preference: Preference,
    private val grants: Grants,
) {
    interface Preference {
        fun read():String?
        fun write(uri:String?):Boolean
    }
    interface Grants {
        /** Take and verify persisted read and write grants from a user picker. */
        fun takeReadWrite(uri:String):Boolean
        fun hasReadWrite(uri:String):Boolean
    }

    fun current():A1DownloadDestinationPolicy.Resolution {
        val destination=A1DownloadDestinationPolicy.resolve(preference.read())
        if(destination is A1DownloadDestinationPolicy.Resolution.Ready &&
            destination.target is A1DownloadDestinationPolicy.Target.GrantedTree &&
            !grants.hasReadWrite(destination.target.treeUri)
        ) return A1DownloadDestinationPolicy.Resolution.NeedsReselection
        return destination
    }

    /** Return false and preserve the previous destination on any error. */
    fun choose(uri:String):Boolean {
        val parsed=A1DownloadDestinationPolicy.resolve(uri)
        val target=(parsed as? A1DownloadDestinationPolicy.Resolution.Ready)?.target
        if(target !is A1DownloadDestinationPolicy.Target.GrantedTree)return false
        if(!grants.takeReadWrite(uri) || !grants.hasReadWrite(uri))return false
        return preference.write(uri)
    }

    /** Explicit choice to use shared Music again; no implicit fallback. */
    fun reset():Boolean=preference.write(null)
}
