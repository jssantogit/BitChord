package com.music.bitchord.download

import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
        val byteCount:Long?=null,
        /** HMAC only, never user recording ID or query metadata in cleartext. */
        val autoRecordingDigest:String?=null,
        val autoReleaseDigest:String?=null,
        /** Opaque root ID joins every slot of one explicit album request. */
        val autoBatchId:String?=null,
    )

    /** Input exists only during explicit admission, never in A3 persistence. */
    data class AutomaticSlot(
        val id:String,
        val recordingId:String,
        val query:String,
        val album:String?,
    )

    /** Safe for UI presentation: never contains the ID, query or peer path. */
    class AutomaticIdentityAlreadyReserved:
        IllegalStateException("A3 automatic identity previously reserved")

    @Serializable
    private data class Envelope(
        val version:Int,
        val jobs:List<Entry>,
        /** Install-scoped secret committed atomically with the first slot. */
        val autoSalt:String?=null,
    )

    private val lock=Any()
    private val json=Json { ignoreUnknownKeys=false; encodeDefaults=true }
    private var autoSalt:String?=null
    private var items:LinkedHashMap<String,Entry> = load()

    /**
     * Reserve ALL canonical slots before Soulseek discovery or authentication.
     * The same single A3 commit records both fingerprints and their opaque
     * future job IDs. A process crash cannot leave an unindexed published
     * artifact that a newly generated request could unknowingly repeat.
     *
     * Includes failed/interrupted slots: without proving A2 rolled back, a
     * previous attempt is NOT permission for an unattended re-download.
     */
    fun reserveAutomaticBatch(
        requests:List<AutomaticSlot>,
        /** Serialized by SoulseekActiveSession generation on Android. */
        commitGuard:((()->Unit)->Unit)={ action -> action() },
    ):Unit {
        // Lock order must match A3 AVAILABLE: Soulseek generation -> A3.
        // Never acquire the generation gate while holding the A3 monitor.
        var invoked=false
        commitGuard {
            check(!invoked) { "A3 auto reservation guard invoked twice" }
            invoked=true
            synchronized(lock) {
        require(requests.size in 1..64) { "A3 auto album size invalid" }
        require(requests.map { it.id }.distinct().size==requests.size)
        require(requests.all { slot ->
            validId(slot.id) && slot.recordingId.isNotBlank() &&
                slot.recordingId.length<=180 &&
                !slot.recordingId.any { it.isISOControl() } &&
                slot.query.isNotBlank() && slot.query.length<=120 &&
                !slot.query.any { it.isISOControl() } &&
                (slot.album==null || (slot.album.length<=120 &&
                    !slot.album.any { it.isISOControl() }))
        }) { "A3 auto slot metadata invalid" }
        val salt=autoSalt ?: ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val rows=requests.map { slot ->
            Entry(
                id=slot.id,
                phase=Phase.REQUESTED,
                autoRecordingDigest=fingerprint(salt,"recording",slot.recordingId),
                autoReleaseDigest=fingerprint(
                    salt,"release",slot.query,slot.album.orEmpty()),
                autoBatchId=requests.first().id,
            )
        }
        val existingRecordings=items.values.mapNotNull { it.autoRecordingDigest }.toSet()
        val existingReleases=items.values.mapNotNull { it.autoReleaseDigest }.toSet()
        if(rows.any { it.id in items ||
            it.autoRecordingDigest in existingRecordings ||
            it.autoReleaseDigest in existingReleases } ||
            rows.map { it.autoRecordingDigest }.distinct().size!=rows.size ||
            rows.map { it.autoReleaseDigest }.distinct().size!=rows.size) {
            throw AutomaticIdentityAlreadyReserved()
        }
        val retained=LinkedHashMap(items)
        if(retained.size+rows.size>MAX_JOBS) {
            val iterator=retained.entries.iterator()
            while(iterator.hasNext() && retained.size+rows.size>MAX_JOBS) {
                val row=iterator.next().value
                if(row.autoRecordingDigest==null &&
                    row.phase in setOf(Phase.FAILED,Phase.CANCELLED))
                    iterator.remove()
            }
        }
        check(retained.size+rows.size<=MAX_JOBS) {
            "A3 automatic history capacity: requires explicit reconciliation"
        }
        rows.forEach { retained[it.id]=it }
        // Any failed commit leaves both the old in-memory map AND the salt
        // unchanged; never mint a new key over old durable reservations.
        commit(retained,salt)
            }
        }
        // A no-op authorization callback is NOT permission to proceed.
        check(invoked) { "A3 auto reservation was never authorized" }
    }

    /**
     * Only an *entire* automatic release that never left REQUESTED is safe
     * to forget. The P/F worker journals TRANSFERRING synchronously before
     * requesting bytes, so any other phase (including INTERRUPTED after a
     * process crash) prevents release. No URI or published file is touched.
     *
     * With a partial album we keep ALL reservations, even those untouched,
     * until a human explicitly reviews the remaining tracks.
     */
    fun releaseUntransferredAutomaticBatch(jobIds:List<String>):Boolean =
        synchronized(lock) {
            if(jobIds.isEmpty() || jobIds.size>64 ||
                jobIds.distinct().size!=jobIds.size)return@synchronized false
            val rows=jobIds.map { items[it] ?: return@synchronized false }
            val batchId=rows.first().autoBatchId ?: return@synchronized false
            if(rows.any { it.phase!=Phase.REQUESTED ||
                    it.autoRecordingDigest==null ||
                    it.autoReleaseDigest==null ||
                    it.autoBatchId!=batchId } ||
                items.values.count { it.autoBatchId==batchId }!=rows.size)
                return@synchronized false
            val next=LinkedHashMap(items)
            jobIds.forEach { next.remove(it) }
            commit(next)
            true
        }

    private fun fingerprint(salt:String,kind:String,vararg values:String):String {
        val mac=Mac.getInstance("HmacSHA256")
        val key=ByteArray(32) { i ->
            salt.substring(i*2,i*2+2).toInt(16).toByte()
        }
        mac.init(SecretKeySpec(key,"HmacSHA256"))
        val input=buildString {
            append(kind)
            for(value in values) {
                val normalized=value.trim().lowercase(Locale.ROOT)
                    .replace(Regex("\\s+")," ")
                append(0.toChar())
                append(normalized.length)
                append(':')
                append(normalized)
            }
        }
        return mac.doFinal(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

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
                val row=iterator.next().value
                if(row.autoRecordingDigest==null &&
                    row.phase in setOf(Phase.FAILED,Phase.CANCELLED))
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
        // Auto jobs use a separately persisted identity fence. A FAILED or
        // INTERRUPTED auto slot may have started P/F or published an artifact
        // that could not be committed. The legacy retry API has no proof of
        // rollback and must not reanimate the original UUID.
        check(old.autoRecordingDigest==null && old.autoReleaseDigest==null) {
            "A3 automatic work requires explicit physical reconciliation"
        }
        check(old.phase in setOf(Phase.INTERRUPTED,Phase.FAILED)) {
            "A3 only interrupted or failed work may retry"
        }
        val record=old.copy(phase=Phase.SEARCHING,contentUri=null,byteCount=null)
        commit(items + (id to record))
        record
    }

    private fun commit(updated:Map<String,Entry>,salt:String?=autoSalt) {
        val next=LinkedHashMap(updated)
        val raw=json.encodeToString(Envelope(VERSION,next.values.toList(),salt))
        if(!store.commit(raw))throw IOException("A3 journal commit failed")
        items=next
        autoSalt=salt
    }

    private fun load():LinkedHashMap<String,Entry> {
        val raw=store.read() ?: return linkedMapOf()
        val decoded=try{
            json.decodeFromString<Envelope>(raw)
        }catch(e:Exception){
            throw IOException("A3 acquisition journal format invalid",e)
        }
        if(decoded.version!=VERSION || decoded.jobs.size>MAX_JOBS ||
            (decoded.autoSalt!=null && !DIGEST.matches(decoded.autoSalt)) ||
            (decoded.autoSalt==null && decoded.jobs.any {
                it.autoRecordingDigest!=null || it.autoReleaseDigest!=null
            }) ||
            decoded.jobs.any { !validId(it.id) ||
                ((it.autoRecordingDigest==null)!=(it.autoReleaseDigest==null)) ||
                (it.autoBatchId!=null && (!validId(it.autoBatchId) ||
                    it.autoRecordingDigest==null)) ||
                (it.autoRecordingDigest!=null &&
                    !DIGEST.matches(it.autoRecordingDigest)) ||
                (it.autoReleaseDigest!=null &&
                    !DIGEST.matches(it.autoReleaseDigest)) ||
                (it.phase in setOf(Phase.AVAILABLE,Phase.RECONCILE) &&
                    (!validUri(it.contentUri.orEmpty()) ||
                    it.byteCount==null || it.byteCount !in 1L..MAX_BYTES)) ||
                (it.phase !in setOf(Phase.AVAILABLE,Phase.RECONCILE) &&
                    (it.contentUri!=null || it.byteCount!=null))
            } || decoded.jobs.map{it.id}.toSet().size!=decoded.jobs.size ||
            decoded.jobs.mapNotNull{it.autoRecordingDigest}.distinct().size !=
                decoded.jobs.count { it.autoRecordingDigest!=null } ||
            decoded.jobs.mapNotNull{it.autoReleaseDigest}.distinct().size !=
                decoded.jobs.count { it.autoReleaseDigest!=null }) {
            throw IOException("A3 acquisition journal consistency violation")
        }
        autoSalt=decoded.autoSalt
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
        val DIGEST=Regex("[0-9a-f]{64}")
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
