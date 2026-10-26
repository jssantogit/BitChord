package com.music.bitchord.acquisition.a0

import java.io.File
import java.io.IOException
import java.net.Socket

/**
 * Manual devDebug selection from untrusted search results.
 * No automatic transfer and no claims about copyright or authentic source hash.
 *
 * A result is displayed to the user only, never logged/serialized. The sole
 * authenticated Soulseek server socket passes to P/F AFTER search readers end.
 */
internal class A0ObservedCandidateHandoff(
    private val authenticatedServer:Socket,
    private val localUsername:String,
    private val searchToken:Int,
    allowLoopbackForLocalTest:Boolean=false,
    private val searchPhrase:String?=null,
    private val saveFormatsOnly:Boolean=false,
    private val expectedDurationSeconds:Int?=null,
    private val requestedAlbum:String?=null,
    private val qualityProfile:A0CanonicalAcquisitionIntent.QualityProfileSnapshot=
        A0CanonicalAcquisitionIntent.QualityProfileSnapshot(),
) {
    class Choice internal constructor(
        val displayName:String,
        val sizeBytes:Long,
        internal val peerUsername:String,
        internal val remotePath:String,
        val matchScore:Int,
        val matchSignal:A0CandidateRank.Match,
        val declaredBitrateKbps:Int?,
        val quality:A0CandidateRank.Quality,
        val warnings:List<A0CandidateRank.Warning>,
        val declaredDurationSeconds:Int?,
        val declaredSampleRateHz:Int?,
        val declaredBitDepth:Int?,
        val preflight:A0CandidatePreflight.Assessment,
        val peerAvailability:A0PeerAvailability.Assessment,
        val albumEvidence:A0CanonicalAlbumPolicy.Assessment,
        val identityEvidence:A0CanonicalIdentityPolicy.Decision,
        val qualityEvidence:A0DownloadQualityPolicy.Assessment,
        internal val claim:A0CandidateClaimClusters.Claim,
    ) {
        override fun toString()="A0 observed candidate (redacted)"
    }

    /** Only sanitized counters. No usernames, search strings or paths. */
    data class Screening(
        val responses:Int,
        val publicFiles:Int,
        val lockedFiles:Int,
        val tooLarge:Int,
        val unsupportedFormat:Int,
        val unsafePath:Int,
        val noSlot:Int,
        val accepted:Int,
        val validBeforeLimit:Int=0,
        val duplicates:Int=0,
        val capacityDropped:Int=0,
        val perPeerDropped:Int=0,
        val claimBudgetDropped:Int=0,
        val strongMatches:Int=0,
        val weakMatches:Int=0,
        val lowQuality:Int=0,
        val preflightStrong:Int=0,
        val preflightReview:Int=0,
        val preflightUnsafe:Int=0,
        val peersReady:Int=0,
        val peersUnknown:Int=0,
        val peersCongested:Int=0,
        val simulatedSuggested:Int=0,
        val simulatedAmbiguous:Int=0,
        val simulatedNoStrong:Int=0,
        val claimsDistinct:Int=0,
        val claimsRepeated:Int=0,
        val albumMatches:Int=0,
        val albumUnknown:Int=0,
        val albumConflicts:Int=0,
        val qualityMismatches:Int=0,
    )
    private val transport=A0ServerAssociatedTransfer(allowLoopbackForLocalTest)
    private val candidates=ArrayList<Choice>(384)
    private val canonicalManualTarget by lazy {
        // Badly formatted untrusted/search text must not crash the collector.
        // An invalid album claim loses confidence; it never grants one.
        runCatching {
            val pieces=searchPhrase?.split(Regex("\\s+[-–—]\\s+"),limit=2).orEmpty()
            A0CanonicalAcquisitionIntent.manual(
                title=pieces.lastOrNull()?.takeIf(String::isNotBlank) ?: "Manual search",
                artistCredits=if(pieces.size==2 && pieces[0].isNotBlank())
                    listOf(pieces[0]) else emptyList(),
                requestedAlbum=requestedAlbum,
            )
        }.getOrElse { A0CanonicalAcquisitionIntent.manual(title="Pesquisa manual") }
    }
    private val albumOrQualitySelected =
        !requestedAlbum.isNullOrBlank() ||
            qualityProfile.preset!=A0CanonicalAcquisitionIntent.QualityPreset.LEGACY_UNSPECIFIED

    private fun claimRisk(choice:Choice):Int = if(!albumOrQualitySelected) 0 else when {
        choice.preflight.confidence==A0CandidatePreflight.Confidence.UNSAFE -> 4
        choice.identityEvidence.verdict==A0CanonicalIdentityPolicy.Verdict.INCOMPATIBLE -> 3
        choice.qualityEvidence.status in setOf(
            A0DownloadQualityPolicy.Status.INCOMPATIBLE,
            A0DownloadQualityPolicy.Status.INVALID_PROFILE,
            A0DownloadQualityPolicy.Status.UNAVAILABLE) -> 2
        choice.albumEvidence.status==A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL ||
            choice.qualityEvidence.status==A0DownloadQualityPolicy.Status.INCONCLUSIVE_MANUAL -> 1
        else -> 0
    }

    private val choiceOrder = compareBy<Choice> { claimRisk(it) }
        .thenByDescending {
        when(it.preflight.confidence) {
            A0CandidatePreflight.Confidence.STRONG -> 2
            A0CandidatePreflight.Confidence.REVIEW -> 1
            A0CandidatePreflight.Confidence.UNSAFE -> 0
        }
    }.thenByDescending { it.matchScore }
        .thenByDescending { it.declaredBitrateKbps ?: 0 }
        // Availability is a source property, not musical identity. Its
        // priority applies only within equal metadata AND equal risk.
        .thenComparator { a,b ->
            A0CandidateClaimClusters.compareEvidence(
                a.claim,a.preflight,b.claim,b.preflight)
        }
        .thenComparator { a,b ->
            A0PeerAvailability.compareSource(b.peerAvailability,a.peerAvailability)
        }
        .thenBy { it.displayName.lowercase(java.util.Locale.ROOT) }
        .thenBy { it.peerUsername }
        .thenBy { it.remotePath }
    private var eligible=0
    private var duplicateCount=0
    private var capDropped=0
    private var perPeerDropped=0
    private var claimBudgetDropped=0
    private val seen=HashSet<Pair<String,String>>()
    private var replies=0
    private var publicSeen=0
    private var lockedSeen=0
    private var oversized=0
    private var unsupported=0
    private var unsafe=0
    private var noSlotSeen=0
    private var readersStopped=false
    private var cancelled=false
    private var spent=false

    init {require(searchToken>0)}

    fun offerSearchResult(frame:ByteArray,claimedPeerUsername:String) {
        val result=try {
            A0SoulseekSearchResponseParser.parse(frame,searchToken)
        } catch (_:IOException) {return} catch (_:IllegalArgumentException) {return}
        offerParsed(result,claimedPeerUsername)
    }

    @Synchronized
    fun offerParsed(result:A0SoulseekSearchResponse,claimedPeerUsername:String) {
        if(cancelled||spent||readersStopped||result.token!=searchToken ||
            result.peerUsername!=claimedPeerUsername) return
        try { A0PeerRendezvousWire.getPeerAddress(result.peerUsername) }
        catch (_:IllegalArgumentException) { return }
        replies=(replies+1).coerceAtMost(100000)
        publicSeen=(publicSeen+result.files.size).coerceAtMost(100000)
        lockedSeen=(lockedSeen+result.lockedFiles.size).coerceAtMost(100000)

        val validExtensions=if(saveFormatsOnly) setOf("mp3","flac","wav","aac")
            else setOf("mp3","flac","wav","ogg","m4a","aac","opus")
        val locked=result.lockedFiles.map{it.remoteFilename}.toHashSet()
        for(file in result.files) {
            val path=file.remoteFilename
            // Reject unsafe metadata before any UI or disk handoff. The full
            // untrusted remote path is never exported through diagnostics.
            if(!A0ModernIncomingTransfer.isSafeRemoteName(path)) {
                unsafe=(unsafe+1).coerceAtMost(100000)
                continue
            }
            val basename=path.replace('\\','/').substringAfterLast('/')
            val ext=basename.substringAfterLast('.',missingDelimiterValue="").lowercase()
            val advertised=file.extension.lowercase().removePrefix(".")
            if(ext !in validExtensions ||
                (advertised.isNotEmpty() && advertised!=ext)) {
                unsupported=(unsupported+1).coerceAtMost(100000)
                continue
            }
            if(file.sizeBytes !in A0ObservedTransferLimits.MIN_AUDIO_BYTES..A0ObservedTransferLimits.MAX_AUDIO_BYTES) {
                oversized=(oversized+1).coerceAtMost(100000)
                continue
            }
            if(!result.hasFreeUploadSlot || path in locked) {
                noSlotSeen=(noSlotSeen+1).coerceAtMost(100000)
                continue
            }
            val identity=result.peerUsername to path
            if(!seen.add(identity)) {
                duplicateCount=(duplicateCount+1).coerceAtMost(100000)
                continue
            }
            // Cap untrusted identity storage, never persist it or print it.
            if(seen.size>8192) seen.clear()
            eligible=(eligible+1).coerceAtMost(100000)
            // Preserve out-of-range claims for the codec conflict gate: dropping
            // an impossible MP3 bitrate would quietly downgrade UNSAFE to
            // REVIEW/UNKNOWN. Only the UI exposure is bounded.
            val bitrateRaw=file.attributes[0]
            val bitrate=bitrateRaw?.takeIf { it in 32..1536 }
            val duration=file.attributes[1]?.takeIf { it in 1..14400 }
            val sampleRate=file.attributes[4]?.takeIf { it in 8000..384000 }
            val bitDepth=file.attributes[5]?.takeIf { it in 8..32 }
            val ranking=A0CandidateRank.rank(
                searchPhrase,basename,bitrateRaw,remotePath=path,format=ext,
                declaredDurationSeconds=duration,expectedDurationSeconds=expectedDurationSeconds,
                declaredSampleRateHz=sampleRate,declaredBitDepth=bitDepth,
                sizeBytes=file.sizeBytes,queueLength=result.queueLength
            )
            val preflight=A0CandidatePreflight.assess(
                searchPhrase,ranking,bitrateRaw,
                declaredDurationSeconds=duration,expectedDurationSeconds=expectedDurationSeconds,
                format=ext,sizeBytes=file.sizeBytes,
                declaredSampleRateHz=sampleRate,declaredBitDepth=bitDepth
            )
            // Only these (already admitted) peer claims affect a source
            // tie-breaker. They do not contribute to preflight confidence.
            val peerAvailability=A0PeerAvailability.assess(
                hasFreeUploadSlot=result.hasFreeUploadSlot,locked=path in locked,
                queueLength=result.queueLength,uploadSpeedBytesPerSecond=result.uploadSpeed
            )
            // A0's original take(82) could cut off ".mp3" and hide an
            // otherwise eligible file from the save selector.
            val suffix=".$ext"
            // The peer's remote path is still passed verbatim to P/F; only
            // on-screen/saved labels mask bidi and zero-width spoofing controls.
            val safeBasename=A0CandidateRank.safeDisplayName(basename)
            val displayName=if(safeBasename.length<=82) safeBasename
                else safeBasename.take(82-suffix.length)+suffix
            val immediateFolder=path.replace('\\','/').substringBeforeLast('/',"")
                .substringAfterLast('/',"")
            val album=A0CanonicalAlbumPolicy.assess(
                A0CanonicalAlbumPolicy.Target(
                    requestedAlbum=requestedAlbum,
                    expectedArtist=canonicalManualTarget.artistCredits.firstOrNull()
                ),
                A0CanonicalAlbumPolicy.Claim(immediateFolder,
                    A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT)
            )
            // The live search knows only a user-entered title/album.
            // Never turn its text into a confirmed canonical relationship.
            val identityDecision=A0CanonicalIdentityPolicy.assess(
                canonicalManualTarget,
                A0CanonicalAcquisitionResolver.Resolution(
                    A0CanonicalAcquisitionResolver.Status.MISSING),
                A0CanonicalAlbumPolicy.Claim(immediateFolder,
                    A0CanonicalAlbumPolicy.ClaimSource.DIRECTORY_HINT),
                preflight,
            )
            val codec=when(ext) {
                "mp3" -> A0CanonicalAcquisitionIntent.Codec.MP3
                "flac" -> A0CanonicalAcquisitionIntent.Codec.FLAC
                "wav" -> A0CanonicalAcquisitionIntent.Codec.PCM_WAV
                "aac" -> A0CanonicalAcquisitionIntent.Codec.AAC_ADTS
                else -> null
            }
            val qualityAssessment=A0DownloadQualityPolicy.evaluate(
                qualityProfile,
                A0DownloadQualityPolicy.Offer(codec,ext,bitrateRaw,sampleRate,bitDepth),
                setOf(A0CanonicalAcquisitionIntent.Codec.MP3,
                    A0CanonicalAcquisitionIntent.Codec.FLAC,
                    A0CanonicalAcquisitionIntent.Codec.PCM_WAV,
                    A0CanonicalAcquisitionIntent.Codec.AAC_ADTS),
            )
            candidates.add(Choice(
                displayName=displayName,sizeBytes=file.sizeBytes,
                peerUsername=result.peerUsername,remotePath=path,
                matchScore=ranking.score, matchSignal=ranking.match,
                declaredBitrateKbps=bitrate,quality=ranking.quality,
                warnings=ranking.warnings,declaredDurationSeconds=duration,
                declaredSampleRateHz=sampleRate,declaredBitDepth=bitDepth,
                preflight=preflight,peerAvailability=peerAvailability,
                albumEvidence=album,identityEvidence=identityDecision,
                qualityEvidence=qualityAssessment,
                claim=A0CandidateClaimClusters.Claim(
                    filename=basename,format=ext,sizeBytes=file.sizeBytes,
                    bitrateKbps=bitrateRaw,durationSeconds=duration,
                    sampleRateHz=sampleRate,bitDepth=bitDepth
                )
            ))
            candidates.sortWith(choiceOrder)
            // A single enthusiastic peer cannot displace every other source.
            val fromPeer=candidates.withIndex()
                .filter { it.value.peerUsername==result.peerUsername }
            if(fromPeer.size>10) {
                candidates.removeAt(fromPeer.last().index)
                perPeerDropped=(perPeerDropped+1).coerceAtMost(100000)
            }
            // Up to six apparent copies are useful as alternate peers;
            // allowing dozens of identical claims would hide other song versions.
            // This is strictly claimed metadata equality, never a hash.
            val sameClaim=candidates.withIndex().filter {
                it.value.preflight==preflight &&
                A0CandidateClaimClusters.sameClaim(it.value.claim,
                    A0CandidateClaimClusters.Claim(
                        filename=basename,format=ext,sizeBytes=file.sizeBytes,
                        bitrateKbps=bitrateRaw,durationSeconds=duration,
                        sampleRateHz=sampleRate,bitDepth=bitDepth
                    ))
            }
            if(sameClaim.size>6) {
                candidates.removeAt(sameClaim.last().index)
                claimBudgetDropped=(claimBudgetDropped+1).coerceAtMost(100000)
            }
            if(candidates.size>384) {
                candidates.removeAt(candidates.lastIndex)
                capDropped=(capDropped+1).coerceAtMost(100000)
            }
        }
    }

    @Synchronized
    fun screening():Screening {
        // A decision *simulation*, only one sanitized outcome per search.
        // Never yields transfer authority or stores candidate identities.
        val preview=claimPreview()
        return Screening(
        responses=replies,
        publicFiles=publicSeen,
        lockedFiles=lockedSeen,
        tooLarge=oversized,
        unsupportedFormat=unsupported,
        unsafePath=unsafe,
        noSlot=noSlotSeen,
        accepted=candidates.size,
        validBeforeLimit=eligible,
        duplicates=duplicateCount,
        capacityDropped=capDropped,
        perPeerDropped=perPeerDropped,
        claimBudgetDropped=claimBudgetDropped,
        strongMatches=candidates.count {
            it.matchScore>=65 && it.warnings.isEmpty() &&
                it.quality!=A0CandidateRank.Quality.LOW_ADVERTISED
        },
        weakMatches=candidates.count {
            it.matchScore<40 || A0CandidateRank.Warning.DURATION_MISMATCH in it.warnings
        },
        lowQuality=candidates.count {
            it.quality==A0CandidateRank.Quality.LOW_ADVERTISED
        },
        preflightStrong=candidates.count {
            it.preflight.confidence==A0CandidatePreflight.Confidence.STRONG
        },
        preflightReview=candidates.count {
            it.preflight.confidence==A0CandidatePreflight.Confidence.REVIEW
        },
        preflightUnsafe=candidates.count {
            it.preflight.confidence==A0CandidatePreflight.Confidence.UNSAFE
        },
        peersReady=candidates.count {
            it.peerAvailability.tier==A0PeerAvailability.Tier.READY
        },
        peersUnknown=candidates.count {
            it.peerAvailability.tier==A0PeerAvailability.Tier.UNKNOWN
        },
        peersCongested=candidates.count {
            it.peerAvailability.tier==A0PeerAvailability.Tier.CONGESTED
        },
        simulatedSuggested=if(preview.status==A0CandidatePreflight.SimulationStatus.SUGGESTED) 1 else 0,
        simulatedAmbiguous=if(preview.status==A0CandidatePreflight.SimulationStatus.AMBIGUOUS) 1 else 0,
        simulatedNoStrong=if(preview.status==A0CandidatePreflight.SimulationStatus.NO_STRONG) 1 else 0,
        claimsDistinct=preview.distinctClaims,
        claimsRepeated=preview.repeatedClaims,
        albumMatches=candidates.count {
            it.albumEvidence.status==A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED
        },
        albumUnknown=candidates.count {
            it.albumEvidence.status==A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL
        },
        albumConflicts=candidates.count {
            it.albumEvidence.status==A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM
        },
        qualityMismatches=candidates.count {
            it.qualityEvidence.status in setOf(
                A0DownloadQualityPolicy.Status.INCOMPATIBLE,
                A0DownloadQualityPolicy.Status.UNAVAILABLE,
                A0DownloadQualityPolicy.Status.INVALID_PROFILE)
        }
        )
    }

    // Pure shadow suggestion. Never called from transferChoice() or any
    // side-effectful operation. Candidate data remains in-memory only.
    @Synchronized
    fun claimPreview():A0CandidateClaimClusters.Preview=
        A0CandidateClaimClusters.preview(candidates.map {
            A0CandidateClaimClusters.Entry(
                it.claim,it.preflight,it.matchScore,it.peerAvailability
            )
        })

    /**
     * Never display a legacy v4 "most suggested" label when the caller added
     * edition/quality constraints that the legacy preflight never evaluated.
     * Candidates are still ranked for a HUMAN, not auto-selected.
     */
    @Synchronized
    fun manualSuggestionIndex():Int? =
        if(albumOrQualitySelected) null else claimPreview().preferredIndex

    @Synchronized
    fun choices():List<Choice> = if(cancelled||spent)emptyList() else candidates.toList()

    @Synchronized
    fun hasCandidates()=candidates.isNotEmpty()&&!cancelled&&!spent

    @Synchronized
    fun markReadersStopped() {
        check(!spent&&!cancelled)
        readersStopped=true
    }

    fun transferChoice(
        index:Int,
        privateDirectory:File,
        onPhase:(A0ServerAssociatedTransfer.Phase)->Unit={}
    ):A0VerifiedByteStager.Receipt {
        val selected:Choice
        synchronized(this){
            if(cancelled)throw IOException("A0 transfer cancelled")
            check(readersStopped) { "A0 server reader must stop before P/F" }
            check(!spent) { "A0 manual transfer already attempted" }
            require(index in candidates.indices) { "Invalid selected file" }
            selected=candidates[index]
            spent=true
            candidates.clear()
        }
        return transport.runObservedOnce(
            authenticatedServer,localUsername,selected.peerUsername,
            A0OpenAudioCandidateGate.PublicPeerListing(
                selected.remotePath,selected.sizeBytes,locked=false,freeSlot=true
            ),privateDirectory,onPhase
        )
    }

    @Synchronized
    fun cancel(){
        cancelled=true
        candidates.clear()
        transport.cancel()
    }

    override fun toString()="A0ObservedCandidateHandoff(redacted)"
}
