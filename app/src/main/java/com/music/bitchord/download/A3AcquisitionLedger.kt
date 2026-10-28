package com.music.bitchord.download

import java.io.IOException
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Durable A3 state for explicit user-requested Soulseek acquisitions.
 *
 * Intentionally does NOT contain peer usernames, remote filenames, credentials,
 * search terms, socket IDs, or scratch paths. An AVAILABLE entry records only
 * a verified/published local content URI. A process restart never implies
 * that an in-flight peer connection is still alive.
 *
 * This is a ledger and state contract, not a running background downloader.
 */
internal class A3AcquisitionLedger(private val store:Store) {
    interface Store {
        fun read():String?
        /** Must commit durably before returning true (not SharedPreferences.apply). */
        fun commit(raw:String):Boolean
    }

    @Serializable
    enum class Phase {
        REQUESTED,
        SEARCHING,
        AWAITING_SELECTION,
        SELECTED,
        TRANSFERRING,
        STAGED,
        PUBLISHING,
        AVAILABLE,
        RECONCILE,
        INTERRUPTED,
        FAILED,
        CANCELLED
    }

    @Serializable
    data class Entry(
        val id:String,
        val phase:Phase,
        val contentUri:String?=null,
        val byteCount:Long?=null
    )

    @Serializable
    private data class Envelope(
        val version:Int,
        val jobs:List<Entry>
    )

    private val lock=Any()
    private val json=Json { ignoreUnknownKeys=false; encodeDefaults=true }
    private var items:LinkedHashMap<String,Entry> = load()

    fun all():List<Entry> = synchronized(lock) { items.values.toList() }
    fun get(id:String):Entry?=synchronized(lock){items[id]}

    fun create(id:String):Entry = synchronized(lock) {
        require(validId(id)) { "A3 acquisition ID must be opaque UUID" }
        check(id !in items) { "A3 acquisition ID already exists" }
        // Reclaim ONLY terminal failed/cancelled jobs. Never drop AVAILABLE
        // content-URI ownership or INTERRUPTED/PUBLISHING reconciliation.
        val retained=LinkedHashMap(items)
        if(retained.size>=MAX_JOBS) {
            val iterator=retained.entries.iterator()
            while(iterator.hasNext() && retained.size>=MAX_JOBS) {
                val state=iterator.next().value.phase
                if(state==Phase.FAILED || state==Phase.CANCELLED)
                    iterator.remove()
            }
        }
        check(retained.size<MAX_JOBS) { "A3 journal capacity exhausted" }
        val record=Entry(id,Phase.REQUESTED)
        commit(retained + (id to record))
        record
    }

    fun advance(id:String,target:Phase):Entry = synchronized(lock) {
        check(target !in setOf(Phase.AVAILABLE,Phase.RECONCILE,
            Phase.REQUESTED,Phase.INTERRUPTED)) {
            "A3 destination requires explicit transition method"
        }
        val old=items[id] ?: error("A3 unknown acquisition ID")
        check(target in allowed.getValue(old.phase)) {
            "A3 illegal state transition"
        }
        val record=old.copy(phase=target,contentUri=null,byteCount=null)
        commit(items + (id to record))
        record
    }

    /**
     * Must be called only after A2 finished publishing and returned an actual
     * local content URI. The future orchestrator must verify it can still open
     * the published file before exposing it as playable.
     */
    fun available(id:String,contentUri:String,byteCount:Long):Entry =
        synchronized(lock) {
            require(validUri(contentUri)) { "A3 expected local content URI" }
            require(byteCount in 1..MAX_BYTES){"A3 invalid published byte count"}
            val old=items[id] ?: error("A3 unknown acquisition ID")
            check(old.phase==Phase.PUBLISHING){"A3 cannot claim uncompleted transfer"}
            check(items.values.none { it.id!=id &&
                it.phase in setOf(Phase.AVAILABLE,Phase.RECONCILE) &&
                it.contentUri==contentUri
            }){"A3 published URI already owned by another acquisition"}
            val record=old.copy(phase=Phase.AVAILABLE,
                contentUri=contentUri,byteCount=byteCount)
            commit(items + (id to record))
            record
        }

    /**
     * The provider made an audio URI visible, but the AVAILABLE commit and
     * compensating deletion both failed. Preserve URI across process death
     * so a later explicit reconciliation can inspect/delete this exact item.
     * NEVER automatically resume or retry a RECONCILE job.
     */
    fun reconcile(id:String,contentUri:String,byteCount:Long):Entry =
        synchronized(lock) {
            require(validUri(contentUri)){"A3 reconcile requires a content URI"}
            require(byteCount in 1..MAX_BYTES){"A3 reconcile byte count invalid"}
            val old=items[id] ?: error("A3 unknown acquisition ID")
            check(old.phase==Phase.PUBLISHING) {
                "A3 may only reconcile a previously publishing artifact"
            }
            check(items.values.none { it.id!=id &&
                it.phase in setOf(Phase.AVAILABLE,Phase.RECONCILE) &&
                it.contentUri==contentUri
            }){"A3 reconciliation URI already owned"}
            val next=old.copy(phase=Phase.RECONCILE,
                contentUri=contentUri,byteCount=byteCount)
            commit(items + (id to next))
            next
        }

    /** Cold-start reconciliation is fail-closed: do NOT revive stale sockets. */
    fun interruptActiveOnColdStart():Int=synchronized(lock) {
        val changed=items.mapValues { (_,row) ->
            if(row.phase in ACTIVE)row.copy(phase=Phase.INTERRUPTED,
                contentUri=null,byteCount=null) else row
        }
        val count=items.values.count { it.phase in ACTIVE }
        if(count>0)commit(changed)
        count
    }

    /** Requires a fresh explicit user action; no silent requery/re-download. */
    fun retry(id:String):Entry=synchronized(lock) {
        val old=items[id] ?: error("A3 unknown acquisition ID")
        check(old.phase in setOf(Phase.INTERRUPTED,Phase.FAILED)) {
            "A3 only interrupted or failed work may retry"
        }
        val record=old.copy(phase=Phase.SEARCHING,contentUri=null,byteCount=null)
        commit(items + (id to record))
        record
    }

    private fun commit(updated:Map<String,Entry>) {
        val next=LinkedHashMap(updated)
        val raw=json.encodeToString(Envelope(VERSION,next.values.toList()))
        if(!store.commit(raw))throw IOException("A3 journal commit failed")
        items=next
    }

    private fun load():LinkedHashMap<String,Entry> {
        val raw=store.read() ?: return linkedMapOf()
        val decoded=try{
            json.decodeFromString<Envelope>(raw)
        }catch(e:Exception){
            throw IOException("A3 acquisition journal format invalid",e)
        }
        if(decoded.version!=VERSION || decoded.jobs.size>MAX_JOBS ||
            decoded.jobs.any { !validId(it.id) ||
                (it.phase in setOf(Phase.AVAILABLE,Phase.RECONCILE) &&
                    (!validUri(it.contentUri.orEmpty()) ||
                    it.byteCount==null || it.byteCount !in 1L..MAX_BYTES)) ||
                (it.phase !in setOf(Phase.AVAILABLE,Phase.RECONCILE) &&
                    (it.contentUri!=null || it.byteCount!=null))
            } || decoded.jobs.map{it.id}.toSet().size!=decoded.jobs.size) {
            throw IOException("A3 acquisition journal consistency violation")
        }
        return LinkedHashMap<String,Entry>().apply {
            decoded.jobs.forEach { put(it.id,it) }
        }
    }

    private fun validId(id:String):Boolean=OPAQUE_ID.matches(id)
    private fun validUri(uri:String):Boolean {
        if(uri.length !in 18..2048 || uri.any { it.isWhitespace() || it.code<0x20 })
            return false
        return runCatching {
            val p=URI(uri)
            p.scheme.equals("content",ignoreCase=true) &&
                !p.rawAuthority.isNullOrBlank() && (p.rawPath?.length ?: 0) > 1 &&
                p.rawFragment==null && p.rawUserInfo==null
        }.getOrDefault(false)
    }

    private companion object {
        const val VERSION=1
        const val MAX_JOBS=2048
        val MAX_BYTES=com.music.bitchord.acquisition.a0.A0ObservedTransferLimits.MAX_AUDIO_BYTES
        val OPAQUE_ID=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val ACTIVE=setOf(Phase.REQUESTED,Phase.SEARCHING,Phase.AWAITING_SELECTION,
            Phase.SELECTED,Phase.TRANSFERRING,Phase.STAGED,Phase.PUBLISHING)
        val allowed=mapOf(
            Phase.REQUESTED to setOf(Phase.SEARCHING,Phase.CANCELLED,Phase.FAILED),
            Phase.SEARCHING to setOf(Phase.AWAITING_SELECTION,Phase.CANCELLED,Phase.FAILED),
            Phase.AWAITING_SELECTION to setOf(Phase.SELECTED,Phase.CANCELLED,Phase.FAILED),
            Phase.SELECTED to setOf(Phase.TRANSFERRING,Phase.CANCELLED,Phase.FAILED),
            Phase.TRANSFERRING to setOf(Phase.STAGED,Phase.CANCELLED,Phase.FAILED),
            Phase.STAGED to setOf(Phase.PUBLISHING,Phase.CANCELLED,Phase.FAILED),
            Phase.PUBLISHING to setOf(Phase.CANCELLED,Phase.FAILED),
            Phase.AVAILABLE to emptySet(),
            Phase.RECONCILE to emptySet(),
            Phase.INTERRUPTED to setOf(Phase.CANCELLED),
            Phase.FAILED to setOf(Phase.CANCELLED),
            Phase.CANCELLED to emptySet()
        )
    }
}
