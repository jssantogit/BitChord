package com.music.bitchord.acquisition.a0

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Offline, gold-blind replay of the existing (noncanonical) preflight-v4.
 * These are 18 SYNTHETIC examples, never a real MusicBrainz/peer accuracy claim.
 * This test does not contact a server, choose a transfer, or download audio.
 */
class A0CanonicalBenchmarkReplayTest {
    private val json=Json { ignoreUnknownKeys=false }
    private val dataset:List<JsonObject> by lazy {
        val stream=checkNotNull(javaClass.getResourceAsStream(
            "/soulseek/synthetic-cases.jsonl"))
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.lineSequence().filter(String::isNotBlank)
                .map { json.parseToJsonElement(it).jsonObject }.toList()
        }
    }
    private fun JsonObject.field(name:String):String=
        checkNotNull(this[name]).jsonPrimitive.content
    private fun JsonObject.number(name:String):Int?=
        this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.longNumber(name:String):Long?=
        this[name]?.jsonPrimitive?.longOrNull

    /** Compatible with evaluate.py: case hash deliberately excludes gold. */
    private fun blinded(case:JsonObject):JsonObject=JsonObject(
        case.mapValues { (key,value) ->
            if(key!="observations")value else JsonArray(value.jsonArray.map {
                JsonObject(it.jsonObject.filterKeys { name ->
                    name!="truth" && name!="truth_evidence"
                })
            })
        }
    )
    private fun canonical(v:JsonElement):String=when(v) {
        is JsonObject -> v.entries.sortedBy { it.key }
            .joinToString(prefix="{",postfix="}",separator=",") {
                JsonPrimitive(it.key).toString()+":"+canonical(it.value)
            }
        is JsonArray -> v.joinToString(prefix="[",postfix="]",separator=",") {
            canonical(it)
        }
        else -> v.toString()
    }
    private fun digest(c:JsonObject):String=
        MessageDigest.getInstance("SHA-256")
            .digest(canonical(blinded(c)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Candidate(
        val id:String,
        val score:Int,
        val assessment:A0CandidatePreflight.Assessment,
        val album:A0CanonicalAlbumPolicy.Status?=null,
    )
    private fun evaluateAlbumGated(c:JsonObject)=evaluate(c,albumGated=true)

    /** Compare a pure album-claim prototype, without changing live v4. */
    private fun evaluate(c:JsonObject,albumGated:Boolean=false):JsonObject {
        val request=c["intent"]!!.jsonObject
        val artist=request.field("artist")
        val title=request.field("title")
        val phrase="$artist - $title"
        val offers=c["observations"]!!.jsonArray.map { it.jsonObject }
        val ranked=offers.map { offer ->
            val format=offer.field("format")
            val bitrate=offer.number("bitrate_kbps")
            val duration=offer.number("duration_seconds")
            val size=offer.longNumber("size_bytes") ?: 0L
            val rank=A0CandidateRank.rank(
                phrase,offer.field("filename"),bitrate,
                remotePath=offer.field("directory")+"/"+offer.field("filename"),
                format=format,declaredDurationSeconds=duration,
                expectedDurationSeconds=180,sizeBytes=size
            )
            val assessed=A0CandidatePreflight.assess(
                phrase,rank,bitrate,declaredDurationSeconds=duration,
                expectedDurationSeconds=180,format=format,sizeBytes=size
            )
            val album=if(albumGated) A0CanonicalAlbumPolicy.assess(
                A0CanonicalAlbumPolicy.Target(request["album"]?.jsonPrimitive?.contentOrNull),
                A0CanonicalAlbumPolicy.Claim(
                    offer["claimed_album"]?.jsonPrimitive?.contentOrNull)
            ) else null
            val confidence=when(album?.status) {
                A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM ->
                    A0CandidatePreflight.Confidence.UNSAFE
                A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL ->
                    if(assessed.confidence==A0CandidatePreflight.Confidence.UNSAFE)
                        A0CandidatePreflight.Confidence.UNSAFE
                    else A0CandidatePreflight.Confidence.REVIEW
                else -> assessed.confidence
            }
            Candidate(offer.field("offer_id"),rank.score,
                assessed.copy(confidence=confidence),album?.status)
        }.sortedWith(
            compareBy<Candidate> {
                if(!albumGated) 0 else when(it.album) {
                    A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED,
                    A0CanonicalAlbumPolicy.Status.UNCONSTRAINED -> 0
                    A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL -> 1
                    A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM -> 2
                    null -> 0
                }
            }.thenBy { it.assessment.confidence.ordinal }
                .thenByDescending { it.score }.thenBy { it.id }
        )
        val decision=A0CandidatePreflight.simulate(
            ranked.map { A0CandidatePreflight.Weighted(it.assessment,it.score) }
        )
        val recommendation=decision.index?.let { ranked[it].id }
        return buildJsonObject {
            put("case_id",c.field("case_id"))
            put("input_sha256",digest(c))
            put("observed_offer_ids",JsonArray(offers.map {
                JsonPrimitive(it.field("offer_id"))
            }))
            put("ranked_offer_ids",JsonArray(ranked.map { JsonPrimitive(it.id) }))
            put("recommended_offer_id",recommendation?.let { JsonPrimitive(it) } ?: JsonNull)
            put("model_version",if(albumGated) "preflight_v4_album_v1" else "preflight_v4")
            put("recommendation_policy",
                if(albumGated) "strong_album_gate_only" else "strong_only")
        }
    }

    @Test fun identicalInputsAlwaysYieldDeterministicBlindReplay() {
        assertEquals(18,dataset.size)
        assertEquals(6,dataset.map { it.field("stratum") }.distinct().size)
        assertEquals(18,dataset.map { it.field("case_id") }.distinct().size)
        for(case in dataset) {
            val first=evaluate(case)
            assertEquals(first,evaluate(case))
            assertEquals(64,first.field("input_sha256").length)
            val exported=first.toString()
            assertFalse(exported.contains("filename"))
            assertFalse(exported.contains("truth"))
            assertFalse(exported.contains("Music/"))
            assertFalse(exported.contains("Atlas"))
        }
    }

    @Test fun stricterAlbumPolicyDoesNotPromoteGreatestHitsForRequestedAlbum() {
        val fixture=dataset.single { it.field("case_id")=="synthetic_08" }
        val legacy=evaluate(fixture)
        assertEquals("synthetic_08_o1",
            legacy["recommended_offer_id"]?.jsonPrimitive?.content)
        val enhanced=evaluateAlbumGated(fixture)
        assertNull("Wrong Greatest Hits edition cannot be promoted",
            enhanced["recommended_offer_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("synthetic_08_o2",
            enhanced["ranked_offer_ids"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals(2,enhanced["ranked_offer_ids"]!!.jsonArray.size)
        assertEquals(digest(fixture),enhanced.field("input_sha256"))
    }

    @Test fun albumGateDoesNotConcealManualInconclusiveCandidates() {
        val fixture=dataset.single { it.field("case_id")=="synthetic_14" }
        val after=evaluateAlbumGated(fixture)
        assertEquals(1,after["ranked_offer_ids"]!!.jsonArray.size)
        assertEquals("synthetic_14_o1",
            after["ranked_offer_ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(after["recommended_offer_id"] is JsonNull)
    }

    @Test fun fixtureReplayEmitsDeveloperOnlyPredictions() {
        val root=File(System.getProperty("user.dir"),"build/benchmark/soulseek")
        assertTrue(root.exists() || root.mkdirs())
        val file=File(root,"bitchord-preflight-v4.jsonl")
        file.writeText(dataset.joinToString(separator="\n",postfix="\n") {
            evaluate(it).toString()
        },Charsets.UTF_8)
        assertEquals(18,file.readLines().size)
        val albumFile=File(root,"bitchord-album-gate-v1.jsonl")
        albumFile.writeText(dataset.joinToString(separator="\\n",postfix="\\n") {
            evaluateAlbumGated(it).toString()
        },Charsets.UTF_8)
        assertEquals(18,albumFile.readLines().size)
    }
}
