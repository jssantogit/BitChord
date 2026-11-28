package com.music.aria.acquisition.live

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.aria.acquisition.a0.A0CandidateRank
import com.music.aria.acquisition.a0.A0AutoCandidateSelectionPolicy
import com.music.aria.acquisition.a0.A0AutoDiscoveryCoordinator
import com.music.aria.acquisition.a0.A0CanonicalAcquisitionIntent
import com.music.aria.acquisition.a0.A0FreshAutoSearchTransport
import com.music.aria.acquisition.a0.A0AutoBatchExecutor
import com.music.aria.acquisition.a0.A0AutoAcquisitionExecutor
import com.music.aria.acquisition.a0.A0ObservedCandidateHandoff
import com.music.aria.acquisition.a0.A0VerifiedByteStager
import com.music.aria.acquisition.a0.SoulseekActiveSession
import com.music.aria.acquisition.a0.SoulseekCredentialVault
import com.music.aria.download.SoulseekBatchPlan
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Frozen single-track calibration through the real coordinator and executor, stopping before P/F. */
@RunWith(AndroidJUnit4::class)
class SoulseekLiveSelectionTest {
    private data class Target(val id:String,val query:String,val album:String,val seconds:Int)
    // Frozen K-pop v1. Durations are reference seconds pending independent
    // catalog verification; do not adjust targets to match peer claims.
    private val kpop1=listOf(
        Target("illit_magnetic","ILLIT - Magnetic","SUPER REAL ME",160),
        Target("illit_lucky_girl","ILLIT - Lucky Girl Syndrome","SUPER REAL ME",140),
        Target("illit_cherish","ILLIT - Cherish (My Love)","I'LL LIKE YOU",176),
        Target("newjeans_hype_boy","NewJeans - Hype Boy","New Jeans",179),
        Target("newjeans_ditto","NewJeans - Ditto","OMG",185),
        Target("newjeans_super_shy","NewJeans - Super Shy","Get Up",154),
        Target("bts_dynamite","BTS - Dynamite","BE",199),
        Target("bts_butter","BTS - Butter","Butter",164),
        Target("bts_boy_with_luv","BTS - Boy With Luv (feat. Halsey)","MAP OF THE SOUL : PERSONA",229),
        Target("blackpink_ddududdudu","BLACKPINK - DDU-DU DDU-DU","SQUARE UP",209),
        Target("blackpink_how_you_like_that","BLACKPINK - How You Like That","THE ALBUM",181),
        Target("blackpink_pink_venom","BLACKPINK - Pink Venom","BORN PINK",186),
        Target("twice_fancy","TWICE - FANCY","FANCY YOU",215),
        Target("twice_feel_special","TWICE - Feel Special","Feel Special",206),
        Target("twice_what_is_love","TWICE - What is Love?","What is Love?",208),
        Target("aespa_next_level","aespa - Next Level","Next Level",221),
        Target("aespa_supernova","aespa - Supernova","Armageddon",178),
        Target("aespa_drama","aespa - Drama","Drama",214),
        Target("ive_love_dive","IVE - LOVE DIVE","LOVE DIVE",177),
        Target("ive_after_like","IVE - After LIKE","After LIKE",176),
        Target("ive_i_am","IVE - I AM","I've IVE",183),
        Target("lesserafim_antifragile","LE SSERAFIM - ANTIFRAGILE","ANTIFRAGILE",184),
        Target("lesserafim_unforgiven","LE SSERAFIM - UNFORGIVEN (feat. Nile Rodgers)","UNFORGIVEN",182),
        Target("lesserafim_perfect_night","LE SSERAFIM - Perfect Night","Perfect Night",159),
        Target("straykids_gods_menu","Stray Kids - God's Menu","GO LIVE",167),
        Target("straykids_maniac","Stray Kids - MANIAC","ODDINARY",182),
        Target("straykids_s_class","Stray Kids - S-Class","5-STAR",195),
        Target("seventeen_super","SEVENTEEN - Super","FML",200),
        Target("seventeen_hot","SEVENTEEN - HOT","Face the Sun",197),
        Target("seventeen_very_nice","SEVENTEEN - VERY NICE","Love & Letter Repackage Album",192),
    )
    // Independent frozen pop/K-pop corpus. Reference metadata provisional.
    private val mixed2=listOf(
        Target("mj_billie_jean","Michael Jackson - Billie Jean","Thriller",294),
        Target("mj_beat_it","Michael Jackson - Beat It","Thriller",258),
        Target("mj_thriller","Michael Jackson - Thriller","Thriller",357),
        Target("mj_bad","Michael Jackson - Bad","Bad",247),
        Target("mj_smooth_criminal","Michael Jackson - Smooth Criminal","Bad",257),
        Target("mj_dirty_diana","Michael Jackson - Dirty Diana","Bad",280),
        Target("mj_black_or_white","Michael Jackson - Black or White","Dangerous",256),
        Target("mj_they_dont_care","Michael Jackson - They Don't Care About Us","HIStory: Past, Present and Future, Book I",284),
        Target("illit_my_world","ILLIT - My World","SUPER REAL ME",107),
        Target("illit_midnight_fiction","ILLIT - Midnight Fiction","SUPER REAL ME",168),
        Target("illit_ill_like_you","ILLIT - I'll Like You","I'LL LIKE YOU",131),
        // Reference correction from MusicBrainz digital release 918de30d-2034-4aa8-8bbf-9b17b3e83bcd; not peer-derived.
        Target("illit_iykyk","ILLIT - IYKYK (If You Know You Know)","I'LL LIKE YOU",144),
        Target("illit_tick_tack","ILLIT - Tick-Tack","I'LL LIKE YOU",129),
        Target("illit_pimple","ILLIT - Pimple","I'LL LIKE YOU",167),
        Target("illit_almond_chocolate","ILLIT - Almond Chocolate","Almond Chocolate",201),
        Target("redvelvet_psycho","Red Velvet - Psycho","The ReVe Festival 'Finale'",210),
        Target("redvelvet_feel_my_rhythm","Red Velvet - Feel My Rhythm","The ReVe Festival 2022 - Feel My Rhythm",210),
        Target("exo_love_shot","EXO - Love Shot","LOVE SHOT – The 5th Album Repackage",200),
        // Apple Music release 854908236: Growl, disc 1 track 1, 3:27.
        // The original XOXO release is not the requested repackage.
        Target("exo_growl","EXO - Growl","The 1st Album 'XOXO' (Repackage)",207),
        Target("snsd_gee","Girls' Generation - Gee","Gee",201),
        Target("shinee_ring_ding_dong","SHINee - Ring Ding Dong","2009, Year of Us",234),
        Target("iu_blueming","IU - Blueming","Love poem",217),
        Target("bigbang_bang_bang_bang","BIGBANG - BANG BANG BANG","MADE",220),
        Target("madonna_like_a_prayer","Madonna - Like a Prayer","Like a Prayer",341),
        Target("gaga_bad_romance","Lady Gaga - Bad Romance","The Fame Monster",294),
        Target("britney_toxic","Britney Spears - Toxic","In the Zone",198),
        Target("weeknd_blinding_lights","The Weeknd - Blinding Lights","After Hours",200),
        Target("ariana_into_you","Ariana Grande - Into You","Dangerous Woman",244),
        Target("taylor_blank_space","Taylor Swift - Blank Space","1989",231),
        Target("bruno_locked_out","Bruno Mars - Locked Out of Heaven","Unorthodox Jukebox",233),
    )
    // Independent holdout: short/repeated titles, credits, punctuation and editions.
    private val mixed3=listOf(
        Target("twice_tt","TWICE - TT","TWICEcoaster : LANE 1",214),
        Target("twice_yes_or_yes","TWICE - YES or YES","YES or YES",237),
        Target("blackpink_lovesick_girls","BLACKPINK - Lovesick Girls","THE ALBUM",192),
        Target("bts_fake_love","BTS - FAKE LOVE","LOVE YOURSELF 轉 'Tear'",242),
        Target("aespa_black_mamba","aespa - Black Mamba","Black Mamba",174),
        Target("ive_eleven","IVE - ELEVEN","ELEVEN",178),
        Target("newjeans_attention","NewJeans - Attention","New Jeans",180),
        Target("lesserafim_fearless","LE SSERAFIM - FEARLESS","FEARLESS",168),
        Target("straykids_back_door","Stray Kids - Back Door","IN LIFE",189),
        Target("seventeen_dont_wanna_cry","SEVENTEEN - Don't Wanna Cry","Al1",203),
        Target("redvelvet_bad_boy","Red Velvet - Bad Boy","The Perfect Red Velvet",210),
        Target("exo_monster","EXO - Monster","EX'ACT",221),
        Target("gidle_tomboy","(G)I-DLE - TOMBOY","I NEVER DIE",174),
        Target("taeyeon_i","TAEYEON - I (feat. Verbal Jint)","I",206),
        Target("stayc_asap","STAYC - ASAP","STAYDOM",194),
        Target("mj_rock_with_you","Michael Jackson - Rock with You","Off the Wall",219),
        Target("mj_man_in_the_mirror","Michael Jackson - Man in the Mirror","Bad",319),
        Target("mj_remember_the_time","Michael Jackson - Remember the Time","Dangerous",240),
        // Apple Music release 388151892: original soundtrack, 4:31.
        Target("whitney_i_will_always_love_you","Whitney Houston - I Will Always Love You","The Bodyguard (Original Soundtrack Album)",271),
        Target("prince_purple_rain","Prince - Purple Rain","Purple Rain",521),
        Target("beyonce_halo","Beyoncé - Halo","I AM... SASHA FIERCE",261),
        Target("rihanna_umbrella","Rihanna - Umbrella (feat. JAY-Z)","Good Girl Gone Bad",275),
        Target("adele_hello","Adele - Hello","25",295),
        Target("sia_chandelier","Sia - Chandelier","1000 Forms of Fear",216),
        Target("gaga_poker_face","Lady Gaga - Poker Face","The Fame",237),
        Target("dua_levitating","Dua Lipa - Levitating","Future Nostalgia",203),
        Target("arianagrande_no_tears","Ariana Grande - no tears left to cry","Sweetener",205),
        Target("weeknd_save_your_tears","The Weeknd - Save Your Tears","After Hours",215),
        Target("justin_timberlake_mirrors","Justin Timberlake - Mirrors","The 20/20 Experience",485),
        Target("backstreet_i_want_it_that_way","Backstreet Boys - I Want It That Way","Millennium",213),
    )
    // Signal only the actual executor's first selected candidate. This Error
    // passes its Exception handlers; finally blocks still close all readers.
    // Never fabricate a transfer result or touch audio bytes.
    private class SelectionBoundary:Error()
    @Test(timeout=3600000)
    fun discoverAndEvaluateWithoutTransfer() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val secretFile=File(context.filesDir,"aria-live-credentials")
        // Ordinary instrumentation suites must never contact Soulseek.
        assumeTrue("Dedicated live account not provisioned",secretFile.isFile)
        val vault=SoulseekCredentialVault(context)
        val bytes=secretFile.readBytes()
        try {
            assertTrue("Cannot remove temporary account input",secretFile.delete())
            val separator=bytes.indexOf(0.toByte())
            assertTrue("Invalid CI credential envelope",separator>0 && separator<bytes.lastIndex)
            val username=String(bytes,0,separator,Charsets.UTF_8)
            val password=String(bytes,separator+1,bytes.size-separator-1,Charsets.UTF_8).toCharArray()
            try { vault.save(username,password) }
            finally { password.fill('\u0000') }
        } finally { bytes.fill(0) }
        try {
            val generation=SoulseekActiveSession.generation()
            val profile=A0CanonicalAcquisitionIntent.QualityProfileSnapshot(
                preset=A0CanonicalAcquisitionIntent.QualityPreset.LOSSLESS,strict=true)
            var completed=0
            var observations=0
            val args=InstrumentationRegistry.getArguments()
            val targets=when(args.getString("aria_live_corpus") ?: "kpop1") {
                "kpop1" -> kpop1
                "mixed2" -> mixed2
                "mixed3" -> mixed3
                else -> error("Unknown corpus")
            }
            val includeNames=args.getString("aria_live_names")=="true"
            val requested=InstrumentationRegistry.getArguments().getString("aria_live_cases").orEmpty()
            val ids=requested.takeUnless { it=="all" }.orEmpty().split(',').filter { it.isNotBlank() }
            require(ids.distinct().size==ids.size && ids.all { id -> targets.any { it.id==id } })
            val cases=if(ids.isEmpty())targets else targets.filter { it.id in ids }
            fun <E:Enum<E>> counts(values:Map<E,Int>?):String =
                values.orEmpty().entries.sortedBy { it.key.name }
                    .joinToString(",") { "${it.key.name}:${it.value.coerceIn(0,512)}" }
                    .ifEmpty { "NONE" }
            for(target in cases) {
                val entry=SoulseekBatchPlan.Entry("ci-"+target.id,target.query,target.album,target.seconds)
                val entries=listOf(entry)
                var searchAudit:A0FreshAutoSearchTransport.SearchAudit?=null
                val transport=A0FreshAutoSearchTransport(context,generation,profile,
                    onSearchAudit={ searchAudit=it })
                val start=SystemClock.elapsedRealtime()
                var search="NOT_STARTED"
                var decision:A0AutoCandidateSelectionPolicy.Decision?=null
                var reachedBoundary=false
                var winner:A0ObservedCandidateHandoff.Choice?=null
                val evidence=linkedMapOf<A0ObservedCandidateHandoff.Choice,A0AutoCandidateSelectionPolicy.CandidateAudit?>()
                // Observe the real discovery result without changing it.
                val observed=object:A0AutoDiscoveryCoordinator.SearchPort {
                    override fun search(entry:SoulseekBatchPlan.Entry,
                        releaseSlots:List<SoulseekBatchPlan.Entry>,isCancelled:()->Boolean
                    ):A0AutoDiscoveryCoordinator.SearchResult {
                        val result=transport.search(entry,releaseSlots,isCancelled)
                        search=when(result) {
                            is A0AutoDiscoveryCoordinator.SearchResult.Found -> "FOUND"
                            A0AutoDiscoveryCoordinator.SearchResult.NoReply -> "NO_REPLY"
                            A0AutoDiscoveryCoordinator.SearchResult.ScreenedOut -> "SCREENED_OUT"
                            A0AutoDiscoveryCoordinator.SearchResult.QueryRejected -> "QUERY_REJECTED"
                            A0AutoDiscoveryCoordinator.SearchResult.TeardownIncomplete -> "TEARDOWN_INCOMPLETE"
                            A0AutoDiscoveryCoordinator.SearchResult.Stop -> "STOP"
                        }
                        return result
                    }
                    override fun close()=transport.close()
                }
                val executor=A0AutoBatchExecutor {
                    object:A0AutoAcquisitionExecutor.Port {
                        override fun fetch(candidate:A0ObservedCandidateHandoff.Choice,
                            isCancelled:()->Boolean):A0AutoAcquisitionExecutor.FetchResult {
                            reachedBoundary=true
                            winner=candidate
                            throw SelectionBoundary()
                        }
                        override fun inspect(receipt:A0VerifiedByteStager.Receipt,
                            expectedExtension:String,isCancelled:()->Boolean):Nothing =
                            error("Selection live cannot inspect bytes")
                        override fun publish(receipt:A0VerifiedByteStager.Receipt,
                            safeName:String,isCancelled:()->Boolean):Nothing =
                            error("Selection live cannot publish")
                        override fun cleanup(receipt:A0VerifiedByteStager.Receipt):Unit =
                            error("Selection live cannot stage bytes")
                    }
                }
                try {
                    A0AutoDiscoveryCoordinator(observed,executor).run(
                        entries,profile,userRequested=true,optedIn=true,
                        isCancelled={Thread.currentThread().isInterrupted},
                        stillAuthorized={SoulseekActiveSession.isCurrent(generation)},
                        onCandidateDecision={_,value -> decision=value},
                        onSlotEvidence={_,offers -> offers.forEach { evidence.putIfAbsent(it,null) }},
                        onCandidateAudit={_,candidate,audit -> evidence[candidate]=audit})
                } catch(_:SelectionBoundary) {
                    check(reachedBoundary)
                } finally { transport.close() }
                val selected=decision
                val truncated=selected?.reason==A0AutoCandidateSelectionPolicy.Reason.INCOMPLETE_SAMPLE
                val row=listOf(target.id,search,
                    if(reachedBoundary)"SELECTED" else if(selected==null)"NO_DECISION" else "ABSTAINED",
                    selected?.reason?.name?:"NONE",
                    selected?.examined?:0,selected?.eligible?:0,
                    selected?.funnel?.rankRejected?:0,selected?.funnel?.preflightRejected?:0,
                    selected?.funnel?.albumRejected?:0,selected?.funnel?.qualityRejected?:0,
                    if(truncated)1 else 0,SystemClock.elapsedRealtime()-start,
                    counts(selected?.rejectionDetails?.rank),
                    counts(selected?.rejectionDetails?.preflight),
                    counts(selected?.rejectionDetails?.album),
                    counts(selected?.rejectionDetails?.quality),
                    counts(selected?.rejectionDetails?.artistCreditGaps),
                    selected?.rejectionDetails?.missingTitleTerms?:0,
                    selected?.rejectionDetails?.missingArtistCredit?:0,
                    selected?.tieResolution?.name?:"NONE",
                    selected?.tieGroups?:0).joinToString("\t")
                instrument.sendStatus(0,Bundle().apply { putString("aria_live",row) })
                val discoveryRow=searchAudit?.let { a ->
                    listOf(target.id,a.initialKind,a.fallbackOne,a.fallbackTwo,
                        a.tickets,a.budgetSkipped,a.initialReplies,a.fallbackOneReplies,
                        a.fallbackTwoReplies,a.initialFiles,a.fallbackOneFiles,a.fallbackTwoFiles,
                        a.reachability,a.directAccepted,a.indirectOffers,a.indirectConnected,
                        a.rawResponses,a.shadowTitleFiltered,a.admitted,a.unsupportedFormat,
                        a.truncatedSlots,a.connectFailed,a.initFailed,a.replyFailed,
                        a.replyTimeout,a.replyEof,a.replyRejected)
                } ?: listOf(target.id,"NOT_COMPLETED","NONE","NONE",0,0,0,0,0,0,0,0,
                    "UNKNOWN",0,0,0,0,0,0,0,0,0,0,0,0,0,"NONE")
                instrument.sendStatus(0,Bundle().apply {
                    putString("aria_search",discoveryRow.joinToString("\t"))
                })
                // One structured row per candidate, including those that were
                // not scored because the whole sample failed closed.
                evidence.entries.forEachIndexed { index,(candidate,audit) ->
                    val payload=org.json.JSONObject().apply {
                        put("case",target.id)
                        put("candidate",index+1)
                        if(includeNames) {
                            val leaf=candidate.claim.filename.substringAfterLast('/').substringAfterLast('\\')
                            val safe=A0CandidateRank.safeDisplayName(leaf)
                                .filterNot { it.isISOControl() }.take(240)
                            // Peer filenames are untrusted. Suppress suspicious
                            // account/URL/address-like strings in public artifacts.
                            val sensitive=Regex(
                                "@|https?[:/]|www[.]|(?:[0-9]{1,3}[.]){3}[0-9]{1,3}|[0-9]{9,}|(?:token|password|api[_ -]?key)[=:]",
                                RegexOption.IGNORE_CASE)
                            put("filename",if(sensitive.containsMatchIn(safe))
                                "[REDACTED]" else safe)
                        }
                        put("selected",candidate===winner)
                        put("stage",audit?.stage?:"NOT_EVALUATED")
                        put("reason",audit?.reason?:selected?.reason?.name?:"NOT_EVALUATED")
                        put("score",audit?.score?:org.json.JSONObject.NULL)
                        put("match",audit?.match?.name?:"NOT_EVALUATED")
                        put("warnings",audit?.warnings?.joinToString(",") { it.name }.orEmpty())
                        put("preflight",audit?.preflight?:"NOT_EVALUATED")
                        put("album",audit?.album?:"NOT_EVALUATED")
                        put("quality",audit?.quality?:"NOT_EVALUATED")
                        put("availability",candidate.peerAvailability.tier.name)
                        put("format",candidate.claim.format.lowercase().takeIf {
                            it in setOf("flac","wav","mp3","aac","opus","ogg","m4a")
                        }?:"OTHER")
                        put("duration",candidate.claim.durationSeconds?:org.json.JSONObject.NULL)
                        put("bitrate",candidate.claim.bitrateKbps?:org.json.JSONObject.NULL)
                        put("sample_rate",candidate.claim.sampleRateHz?:org.json.JSONObject.NULL)
                        put("bit_depth",candidate.claim.bitDepth?:org.json.JSONObject.NULL)
                        put("tie_resolution",selected?.tieResolution?.name?:"NONE")
                    }
                    val encoded=android.util.Base64.encodeToString(
                        payload.toString().toByteArray(Charsets.UTF_8),android.util.Base64.NO_WRAP)
                    instrument.sendStatus(0,Bundle().apply { putString("aria_candidate",encoded) })
                }
                completed++
                if(search=="FOUND" || search=="SCREENED_OUT")observations++
                if(search=="STOP")break // no automatic reconnect on account/protocol failure
            }
            assertTrue("Live corpus stopped early",completed==cases.size)
            assertTrue("No real peer observations",observations>0)
            // Success means telemetry collected, NOT proof of selection accuracy.
        } finally { vault.forget() }
    }
}
