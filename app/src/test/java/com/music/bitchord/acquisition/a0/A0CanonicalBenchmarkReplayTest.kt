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
        val id:String,val score:Int,val assessment:A0CandidatePreflight.Assessment
    )
    private fun evaluate(c:JsonObject):JsonObject {
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
            Candidate(offer.field("offer_id"),rank.score,assessed)
        }.sortedWith(
            compareBy<Candidate> { it.assessment.confidence.ordinal }
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
            put("model_version","preflight_v4")
            put("recommendation_policy","strong_only")
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

    @Test fun fixtureReplayEmitsDeveloperOnlyPredictions() {
        val root=File(System.getProperty("user.dir"),"build/benchmark/soulseek")
        assertTrue(root.exists() || root.mkdirs())
        val file=File(root,"bitchord-preflight-v4.jsonl")
        file.writeText(dataset.joinToString(separator="\n",postfix="\n") {
            evaluate(it).toString()
        },Charsets.UTF_8)
        assertEquals(18,file.readLines().size)
    }
}
