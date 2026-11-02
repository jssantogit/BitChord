package com.music.bitchord.acquisition.a0

import com.music.bitchord.download.SoulseekBatchPlan

/**
 * One bounded DISCOVERY round per needed slot followed immediately by its
 * serial TRANSFER. The next slot can reuse sibling peer evidence already
 * collected, avoiding redundant searches and album-wide discovery stalls.
 *
 * SearchPort is an exclusive login/search transport that MUST close all
 * readers and P sockets before returning. The transfer port owns a different
 * fresh authenticated session per attempt. This class does not itself open
 * sockets or register a production DownloadService entrypoint.
 */
internal class A0AutoDiscoveryCoordinator(
    private val discovery:SearchPort,
    private val batchExecutor:A0AutoBatchExecutor,
) {
    interface SearchPort {
        fun search(
            entry:SoulseekBatchPlan.Entry,
            releaseSlots:List<SoulseekBatchPlan.Entry>,
            isCancelled:()->Boolean,
        ):SearchResult
        /** Read-only, best-effort browse of the EXACT folder of a peer
         * already proven to publish a sibling. Default never touches network.
         * Every returned file is still an untrusted candidate claim. */
        fun inspectPublishedFolder(
            source:A0ObservedCandidateHandoff.Choice,
            releaseSlots:List<SoulseekBatchPlan.Entry>,
            isCancelled:()->Boolean,
        ):SearchResult=SearchResult.NoReply
        fun close()
    }

    sealed interface SearchResult {
        class Found(
            val choices:List<A0ObservedCandidateHandoff.Choice>,
            val truncatedSlots:Set<Int> = emptySet(),
        ):SearchResult
        data object NoReply:SearchResult
        /** Valid peer replies arrived, but no candidates survived screening. */
        data object ScreenedOut:SearchResult
        /** The slot's query cannot be represented on the search wire.
         * This is not an authentication or peer-connectivity failure. */
        data object QueryRejected:SearchResult
        /** A finished search couldn't drain its peer readers. Its observation
         * is discarded, but this is NOT an auth/protocol/account stop; fresh
         * sibling searches may proceed with independent closed sessions. */
        data object TeardownIncomplete:SearchResult
        /** Auth/account/protocol failure: do NOT automatically reconnect. */
        data object Stop:SearchResult
    }

    enum class CacheAction {
        REUSED, SEARCH_REQUIRED, SEARCH_NO_REPLY, SEARCH_SCREENED_OUT, QUERY_REJECTED,
        PEER_SUPPRESSED,
        SIBLING_RESCUE, SIBLING_RESCUE_SKIPPED,
        FOLDER_BROWSE_ADDED, FOLDER_BROWSE_EMPTY
    }
    /** Aggregate-only evidence: never exposes a filename, peer or path. */
    data class CacheEvidence(
        val action:CacheAction,
        val observed:Int,
        val truncated:Boolean,
        val decisionReason:A0AutoCandidateSelectionPolicy.Reason?,
        // Only aggregate counts for diagnostics. Peer/path never leave RAM.
        val publishedFolderCount:Int=0,
        val samePeerOffers:Int=0,
        val samePeerFolderOffers:Int=0,
    )

    class Report(
        val searches:Int,
        val batch:A0AutoBatchExecutor.Report,
        /** A3 atomically proved that NO sibling began P/F. Explicit retry
         * of the same identity is safe; never set this from peer claims.
         */
        val unusedReservationReleased:Boolean=false,
        val folderQueries:Int=0,
        val noReplySearches:Int=0,
        val longestNoReplyStreak:Int=0,
        val suppressedOffers:Int=0,
        val rejectedQueries:Int=0,
        val screenedOutSearches:Int=0,
    ) {
        fun withReservationReleased(released:Boolean)=Report(
            searches,batch,unusedReservationReleased=released,
            folderQueries=folderQueries,noReplySearches=noReplySearches,
            longestNoReplyStreak=longestNoReplyStreak,suppressedOffers=suppressedOffers,
            rejectedQueries=rejectedQueries,
            screenedOutSearches=screenedOutSearches)
        override fun toString()=
            "A0AutoDiscoveryReport(searches=$searches,published=${batch.published})"
    }

    private fun refused(count:Int,searches:Int=0)=Report(
        searches,A0AutoBatchExecutor.Report(
            List(count){A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW},0))

    private fun revoked(count:Int,searches:Int)=Report(
        searches,A0AutoBatchExecutor.Report(
            List(count){if(it==0) A0AutoBatchExecutor.TrackStatus.CANCELLED
                else A0AutoBatchExecutor.TrackStatus.NOT_STARTED},0))

    fun run(
        entries:List<SoulseekBatchPlan.Entry>,
        quality:A0CanonicalAcquisitionIntent.QualityProfileSnapshot,
        userRequested:Boolean,
        optedIn:Boolean,
        isCancelled:()->Boolean,
        stillAuthorized:()->Boolean,
        onSlotResolved:(Int,A0AutoBatchExecutor.TrackStatus)->Unit={ _,_ -> },
        onSlotSearching:(Int)->Unit={},
        onCandidateDecision:(Int,A0AutoCandidateSelectionPolicy.Decision)->Unit={ _,_ -> },
        onCohesionEvidence:(Int,A0ReleaseCohesionPolicy.Assessment)->Unit={ _,_ -> },
        onCacheEvidence:(Int,CacheEvidence)->Unit={ _,_ -> },
        /** Observed, never transfer-authorized; consumer may retain in RAM
         * for one explicit human review after this search closes.
         */
        onSlotEvidence:(Int,List<A0ObservedCandidateHandoff.Choice>)->Unit={ _,_ -> },
    ):Report {
        val count=entries.size
        val valid=count in 1..SoulseekBatchPlan.MAX_TRACKS &&
            entries.map {it.trackId}.distinct().size==count &&
            entries.all {it.trackId.isNotBlank() && it.trackId.length<=180 &&
                it.query.isNotBlank()}
        if(!valid || !userRequested || !optedIn)return refused(count)
        fun shouldStop()=isCancelled() || !stillAuthorized() ||
            Thread.currentThread().isInterrupted
        var searches=0
        if(shouldStop())return revoked(count,searches)
        val pooled=A0MultiSearchCandidateEvidence(entries,quality)
        val statuses=ArrayList<A0AutoBatchExecutor.TrackStatus>(count)
        val publishedPaths=HashSet<Pair<String,String>>()
        // Repeated pre-accept failures from the same peer must not poison a
        // large album. Only ephemeral, unpersisted peer evidence is used.
        val peerPreAcceptFailures=HashMap<String,Int>()
        fun sourceAllowed(peer:String)=(peerPreAcceptFailures[peer] ?: 0)<2
        fun allowedSources(offers:List<A0ObservedCandidateHandoff.Choice>)=
            offers.filter { sourceAllowed(it.peerUsername) }
        // A slot that never began P/F may benefit from sibling evidence
        // discovered later in this SAME explicitly requested release.
        val reviewWithoutTransfer=LinkedHashSet<Int>()
        // No repeat browsing of a published peer/folder inside one album.
        // A failed lookup is not grounds for a retry storm.
        val inspectedFolders=HashSet<Pair<String,String>>()
        var folderQueries=0
        var noReplySearches=0
        var consecutiveNoReply=0
        var rejectedQueries=0
        var screenedOutSearches=0
        var longestNoReplyStreak=0
        var suppressedOffers=0
        // Two extra direct-peer probes for short albums; four for long
        // releases. Never let unsupported browse clients multiply network
        // waiting time for every title in a user-requested batch.
        val folderQueryBudget=if(count<=8)2 else 4
        // An account/protocol stop or uncertain A3 publication terminates
        // the entire automatic session, including late-evidence rescue.
        var mayRescueLateSlots=true
        var attempts=0
        fun append(index:Int,status:A0AutoBatchExecutor.TrackStatus) {
            statuses.add(status)
            try {onSlotResolved(index,status)}catch(_:Exception){}
        }
        fun finish():Report=Report(searches,
            A0AutoBatchExecutor.Report(statuses.toList(),attempts,
                publishedPaths.toSet()),folderQueries=folderQueries,
            noReplySearches=noReplySearches,
            longestNoReplyStreak=longestNoReplyStreak,
            suppressedOffers=suppressedOffers,rejectedQueries=rejectedQueries,
            screenedOutSearches=screenedOutSearches)
        // Only an ambiguous, fully observed slot may use independent
        // release-wide sibling coverage to break a peer-claim tie.
        // The executor still re-runs the unchanged per-track preview on
        // the restricted candidates, then requires full A3 gates.
        fun select(index:Int,offers:List<A0ObservedCandidateHandoff.Choice>,
            truncated:Boolean,notify:Boolean):
            Pair<List<A0ObservedCandidateHandoff.Choice>,
                A0AutoCandidateSelectionPolicy.Decision> {
            // Try the more informative release-wide consensus first.
            // If equally strong release cohorts remain tied, the user's
            // explicit auto opt-in permits first-observed READY selection.
            val baseline=A0AutoCandidateSelectionPolicy.preview(
                entries[index],offers,quality,sampleTruncated=truncated,
                resolveExactTies=false)
            if(baseline.reason!=A0AutoCandidateSelectionPolicy.Reason.AMBIGUOUS_EDITION)
                return offers to baseline
            val snapshots=entries.indices.map {
                checkNotNull(pooled.slotEvidence(it))
            }
            val outcome=A0ReleaseCohesionPolicy.evaluate(
                entries,index,snapshots.map {it.first},
                snapshots.indices.filter {snapshots[it].second}.toSet(),
                publishedPaths,quality)
            if(notify)try {onCohesionEvidence(index,outcome)}catch(_:Exception){}
            if(outcome.reason!=A0ReleaseCohesionPolicy.Reason.UNIQUE_RELEASE_COHORT)
                return offers to A0AutoCandidateSelectionPolicy.preview(
                    entries[index],offers,quality,sampleTruncated=truncated)
            val filtered=A0AutoCandidateSelectionPolicy.preview(
                entries[index],outcome.choices,quality)
            // The policy's cached evidence is not permission to bypass
            // any manual-review or invalid-target rule.
            return if(filtered.status==A0AutoCandidateSelectionPolicy.Status.PROVISIONAL)
                outcome.choices to filtered else offers to baseline
        }
        try {
            // Each search() shuts down its readers and sockets before
            // returning. Publish this slot NOW, rather than searching an
            // entire album first. Peer evidence already includes sibling
            // tracks and can eliminate the next redundant search.
            for(index in entries.indices) {
                if(shouldStop()) {
                    append(index,A0AutoBatchExecutor.TrackStatus.CANCELLED)
                    for(next in index+1 until count)
                        append(next,A0AutoBatchExecutor.TrackStatus.NOT_STARTED)
                    break
                }
                // Observable per-track state, never network authority.
                try {onSlotSearching(index)}catch(_:Exception){}
                val (cached,cacheTruncated)=checkNotNull(pooled.slotEvidence(index))
                // The old selectionPreview() recomputed every one of 64 slots
                // before EACH next slot: quadratic and expensive with large
                // 512-offer buckets. Only the current slot is relevant here.
                // A file already durably published for another canonical
                // slot cannot prove that this slot is ready. Filter spent
                // physical paths BEFORE deciding whether to skip discovery.
                val unspentCached=cached.filterNot {
                    (it.peerUsername to it.remotePath) in publishedPaths
                }
                val activeCached=allowedSources(unspentCached)
                if(activeCached.size<unspentCached.size)try {
                    suppressedOffers+=(unspentCached.size-activeCached.size)
                    onCacheEvidence(index,CacheEvidence(
                        CacheAction.PEER_SUPPRESSED,
                        (unspentCached.size-activeCached.size).coerceIn(0,512),
                        cacheTruncated,null))
                }catch(_:Exception){}
                val currentPreview=A0AutoCandidateSelectionPolicy.preview(
                    entries[index],activeCached,quality,sampleTruncated=cacheTruncated)
                val (_,cachedDecision)=select(index,activeCached,cacheTruncated,false)
                val readyInCache=currentPreview.status==
                    A0AutoCandidateSelectionPolicy.Status.PROVISIONAL ||
                    cachedDecision.status==
                    A0AutoCandidateSelectionPolicy.Status.PROVISIONAL
                try { onCacheEvidence(index,CacheEvidence(
                    if(readyInCache) CacheAction.REUSED else CacheAction.SEARCH_REQUIRED,
                    activeCached.size.coerceIn(0,512),cacheTruncated,cachedDecision.reason
                )) }catch(_:Exception) {}
                if(!readyInCache) {
                    searches++
                    val result=try {
                        discovery.search(entries[index],entries,::shouldStop)
                    }catch(_:Exception) { SearchResult.Stop }
                    if(shouldStop()) {
                        append(index,A0AutoBatchExecutor.TrackStatus.CANCELLED)
                        for(next in index+1 until count)
                            append(next,A0AutoBatchExecutor.TrackStatus.NOT_STARTED)
                        break
                    }
                    when(result) {
                        is SearchResult.Found -> {
                            consecutiveNoReply=0
                            pooled.add(result.choices,result.truncatedSlots)
                        }
                        SearchResult.NoReply -> {
                            noReplySearches++
                            consecutiveNoReply++
                            longestNoReplyStreak=maxOf(
                                longestNoReplyStreak,consecutiveNoReply)
                            try {onCacheEvidence(index,CacheEvidence(
                                CacheAction.SEARCH_NO_REPLY,
                                consecutiveNoReply.coerceIn(0,64),
                                false,null))}catch(_:Exception){}
                        }
                        SearchResult.ScreenedOut -> {
                            // Valid replies exist: not a server silence
                            // or search rate-limit signal. Never extend
                            // the consecutive true no-reply streak.
                            screenedOutSearches++
                            consecutiveNoReply=0
                            try {onCacheEvidence(index,CacheEvidence(
                                CacheAction.SEARCH_SCREENED_OUT,0,false,null))
                            }catch(_:Exception){}
                        }
                        SearchResult.QueryRejected -> {
                            // No query reached the wire. Keep scanning this
                            // album; a later sibling may provide safe reusable
                            // evidence for this unsearchable entry.
                            searches--
                            rejectedQueries++
                            consecutiveNoReply=0
                            try {onCacheEvidence(index,CacheEvidence(
                                CacheAction.QUERY_REJECTED,0,false,null))
                            }catch(_:Exception){}
                        }
                        SearchResult.TeardownIncomplete -> {
                            // A reader-drain failure is NOT a genuine empty
                            // network reply. Break a streak of true NoReply
                            // slots or the diagnostic would implicate Soulseek
                            // throttling when our own reader failed between.
                            consecutiveNoReply=0
                            // The failing search's observation is unusable.
                            // Do not retry the slot or resurrect it from later
                            // evidence; never strand untouched siblings.
                            append(index,A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW)
                            continue
                        }
                        SearchResult.Stop -> {
                            mayRescueLateSlots=false
                            append(index,A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW)
                            for(next in index+1 until count)
                                append(next,A0AutoBatchExecutor.TrackStatus.NOT_STARTED)
                            break
                        }
                    }
                }
                val (choices,truncated)=checkNotNull(pooled.slotEvidence(index))
                // Do not reuse a physical peer file that has already been
                // published for another canonical sibling in this album.
                val unique=choices.filterNot {
                    (it.peerUsername to it.remotePath) in publishedPaths
                }
                // Preserve peer evidence before attempting automatic P/F;
                // review screens must not force a second identical search.
                // Callback is observational, not a transport command.
                try {onSlotEvidence(index,unique.toList())}catch(_:Exception){}
                val (selected,decision)=select(index,
                    allowedSources(unique),truncated,true)
                // Counts and reason only: never expose peer/user/path to logs.
                try { onCandidateDecision(index,decision) }catch(_:Exception){}
                val outcome=batchExecutor.run(
                    entries=listOf(entries[index]),
                    observedBySlot=listOf(selected),
                    truncatedSlots=if(truncated)setOf(0) else emptySet(),
                    profile=quality,userRequested=true,optedIn=true,
                    isCancelled=isCancelled,stillAuthorized=stillAuthorized,
                    onSlotResolved={_,status -> onSlotResolved(index,status)},
                )
                attempts+=outcome.attempts
                publishedPaths.addAll(outcome.publishedSourceKeys)
                for(peer in outcome.preAcceptFailedPeers)
                    peerPreAcceptFailures[peer]=
                        (peerPreAcceptFailures[peer] ?: 0).plus(1).coerceAtMost(2)
                val status=outcome.results.singleOrNull()
                    ?: A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW
                // The executor already delivered this slot's progress.
                statuses.add(status)
                if(status==A0AutoBatchExecutor.TrackStatus.PUBLISHED &&
                    index < count-1 && !shouldStop()) {
                    // A correct publication is a useful source hint, not
                    // blanket permission for any other file in its folder.
                    // Query only when an upcoming slot actually lacks an
                    // individually eligible, untruncated cached candidate.
                    val needsSiblingEvidence=(index+1 until count).any { next ->
                        val (offers,clipped)=checkNotNull(pooled.slotEvidence(next))
                        // Account for release-cohesion-based eligibility,
                        // not just a single-track preview.
                        // The upcoming slot also needs independent unspent
                        // evidence; the already published file is not it.
                        val unspent=offers.filterNot {
                            (it.peerUsername to it.remotePath) in publishedPaths
                        }
                        val (_,choice)=select(next,allowedSources(unspent),clipped,false)
                        choice.status!=
                            A0AutoCandidateSelectionPolicy.Status.PROVISIONAL
                    }
                    if(needsSiblingEvidence && folderQueries<folderQueryBudget) {
                        val source=selected.firstOrNull {
                            (it.peerUsername to it.remotePath) in outcome.publishedSourceKeys
                        }
                        val directory=source?.remotePath?.replace('\\','/')
                            ?.substringBeforeLast('/',"")
                        val folderKey=if(source!=null && !directory.isNullOrBlank())
                            source.peerUsername to directory else null
                        if(source!=null && folderKey!=null &&
                            inspectedFolders.add(folderKey)) {
                            folderQueries++
                            val browsed=try {
                                discovery.inspectPublishedFolder(
                                    source,entries,::shouldStop)
                            }catch(_:Exception){SearchResult.NoReply}
                            if(!shouldStop()) {
                                val fromExactFolder=browsed is SearchResult.Found &&
                                    browsed.choices.all { candidate ->
                                        candidate.peerUsername==source.peerUsername &&
                                            candidate.remotePath.replace('\\','/')
                                                .substringBeforeLast('/',"")==directory
                                    }
                                // An optional browse must never poison the
                                // complete public-search evidence with a
                                // clipped response or ambiguous extra folder.
                                if(browsed is SearchResult.Found && fromExactFolder &&
                                    browsed.truncatedSlots.isEmpty()) {
                                    pooled.add(browsed.choices)
                                    try {onCacheEvidence(index,CacheEvidence(
                                        CacheAction.FOLDER_BROWSE_ADDED,
                                        browsed.choices.size.coerceIn(0,512),
                                        browsed.truncatedSlots.isNotEmpty(),null
                                    ))}catch(_:Exception){}
                                }else {
                                    try {onCacheEvidence(index,CacheEvidence(
                                        CacheAction.FOLDER_BROWSE_EMPTY,
                                        0,false,null
                                    ))}catch(_:Exception){}
                                }
                            }
                        }
                    }
                }
                if(status==A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW &&
                    decision.status==A0AutoCandidateSelectionPolicy.Status.MANUAL_REVIEW &&
                    decision.reason in setOf(
                        A0AutoCandidateSelectionPolicy.Reason.NO_STRONG_CANDIDATE,
                        A0AutoCandidateSelectionPolicy.Reason.AMBIGUOUS_EDITION,
                        A0AutoCandidateSelectionPolicy.Reason.INCOMPLETE_SAMPLE,
                        // No P/F was attempted: a later sibling search may
                        // reveal a READY source for the same canonical song.
                        A0AutoCandidateSelectionPolicy.Reason.NO_READY_SOURCE) &&
                    outcome.attempts==0)
                    reviewWithoutTransfer.add(index)
                if(status in setOf(
                    A0AutoBatchExecutor.TrackStatus.CANCELLED,
                    A0AutoBatchExecutor.TrackStatus.RECONCILE)) {
                    mayRescueLateSlots=false
                    for(next in index+1 until count)
                        append(next,A0AutoBatchExecutor.TrackStatus.NOT_STARTED)
                    break
                }
            }
            // ONE bounded late-evidence pass: no additional searches, no
            // reconnections unless a newly eligible slot begins its FIRST P/F.
            // Prior PUBLISHED, RECONCILE, CANCELLED and attempted slots are
            // never revisited. A3 still requires REQUESTED before transfer.
            if(statuses.size==count && mayRescueLateSlots && !shouldStop()) {
                for(index in reviewWithoutTransfer) {
                    if(shouldStop())break
                    val (choices,truncated)=checkNotNull(pooled.slotEvidence(index))
                    val unspent=choices.filterNot {
                        (it.peerUsername to it.remotePath) in publishedPaths
                    }
                    // A clipped global sample is NOT evidence of a unique
                    // winner. Only an already durably published sibling's
                    // exact peer AND directory permits a bounded second
                    // look at individually revalidated candidates. No
                    // guessed folder names or cross-peer promotion.
                    fun parent(path:String)=path.replace('\\','/')
                        .substringBeforeLast('/')
                    val publishedFolders=publishedPaths.map {
                        it.first to parent(it.second)
                    }.toSet()
                    val publishedPeers=publishedPaths.map { it.first }.toSet()
                    val samePeerOffers=unspent.count {
                        it.peerUsername in publishedPeers
                    }
                    val samePeerFolderOffers=unspent.count {
                        (it.peerUsername to parent(it.remotePath)) in publishedFolders
                    }
                    val lateOffers=if(truncated)unspent.filter {
                        (it.peerUsername to parent(it.remotePath)) in publishedFolders
                    } else unspent
                    val eligibleLateOffers=allowedSources(lateOffers)
                    if(eligibleLateOffers.isEmpty()) {
                        if(truncated)try { onCacheEvidence(index,CacheEvidence(
                            CacheAction.SIBLING_RESCUE_SKIPPED,0,true,
                            A0AutoCandidateSelectionPolicy.Reason.INCOMPLETE_SAMPLE,
                            publishedFolders.size,samePeerOffers,samePeerFolderOffers
                        )) }catch(_:Exception) {}
                        continue
                    }
                    val (selected,late)=select(index,eligibleLateOffers,false,true)
                    if(late.status!=A0AutoCandidateSelectionPolicy.Status.PROVISIONAL) {
                        if(truncated)try { onCacheEvidence(index,CacheEvidence(
                            CacheAction.SIBLING_RESCUE_SKIPPED,
                            eligibleLateOffers.size.coerceIn(0,512),true,late.reason,
                            publishedFolders.size,samePeerOffers,samePeerFolderOffers
                        )) }catch(_:Exception) {}
                        continue
                    }
                    if(truncated)try { onCacheEvidence(index,CacheEvidence(
                        CacheAction.SIBLING_RESCUE,eligibleLateOffers.size.coerceIn(0,512),
                        true,late.reason,
                        publishedFolders.size,samePeerOffers,samePeerFolderOffers
                    )) }catch(_:Exception) {}
                    try {onCandidateDecision(index,late)}catch(_:Exception){}
                    try {onSlotEvidence(index,unspent.toList())}catch(_:Exception){}
                    val recovery=batchExecutor.run(
                        entries=listOf(entries[index]),
                        observedBySlot=listOf(selected),
                        truncatedSlots=emptySet(),
                        profile=quality,userRequested=true,optedIn=true,
                        isCancelled=isCancelled,stillAuthorized=stillAuthorized,
                        onSlotResolved={_,status -> onSlotResolved(index,status)},
                    )
                    attempts+=recovery.attempts
                    publishedPaths.addAll(recovery.publishedSourceKeys)
                    for(peer in recovery.preAcceptFailedPeers)
                        peerPreAcceptFailures[peer]=
                            (peerPreAcceptFailures[peer] ?: 0).plus(1).coerceAtMost(2)
                    val status=recovery.results.singleOrNull()
                        ?: A0AutoBatchExecutor.TrackStatus.MANUAL_REVIEW
                    statuses[index]=status
                    if(status in setOf(A0AutoBatchExecutor.TrackStatus.CANCELLED,
                            A0AutoBatchExecutor.TrackStatus.RECONCILE))
                        break
                }
            }
            return finish()
        }finally {
            pooled.stop()
            try {discovery.close()}catch(_:Exception){}
        }
    }
}
