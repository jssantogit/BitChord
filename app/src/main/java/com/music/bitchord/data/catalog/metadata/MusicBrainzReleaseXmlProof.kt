package com.music.bitchord.data.catalog.metadata

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.ArrayDeque

/** Exact edition track identity/position proof from the official release XML. */
internal object MusicBrainzReleaseXmlProof {
    private const val MAX_XML_CHARS = 2_000_000
    private const val MAX_MEDIA = 100
    private const val MAX_TRACKS = 2_000
    private val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private data class Frame(
        val name: String,
        val namespace: String,
        val attributes: Map<String, String>,
        val text: StringBuilder = StringBuilder(),
        var position: Int? = null,
        var trackId: String? = null,
        var recordingId: String? = null,
        var trackCount: Int? = null,
        var childrenNamed: MutableMap<String, Int> = mutableMapOf(),
    )

    private data class Row(val track: String, val recording: String, val medium: Int, val position: Int)

    fun proves(xml: String, releaseMbid: String, json: MusicBrainzCatalogReleaseDetail): Boolean {
        if (xml.length !in 1..MAX_XML_CHARS || !json.completeness.countsExplicitAndExact ||
            !json.completeness.offsetsValid || !json.completeness.positionsExplicitUnique || json.tracks.isEmpty() ||
            xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)
        ) return false
        return runCatching { parseAndCompare(xml, releaseMbid, json) }.getOrDefault(false)
    }

    private fun parseAndCompare(
        xml: String,
        releaseMbid: String,
        json: MusicBrainzCatalogReleaseDetail,
    ): Boolean {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            // proves() rejects every DOCTYPE lexically before this parser sees
            // the input. Do not configure process-docdecl here: some providers
            // (including KXmlParser) default it to false but throw when asked
            // to set that unsupported feature explicitly.
            setInput(StringReader(xml))
        }
        val stack = ArrayDeque<Frame>()
        val rows = mutableListOf<Row>()
        var namespace: String? = null
        var releaseId: String? = null
        var mediumListCount: Int? = null
        var mediaSeen = 0
        var trackListsSeen = 0
        var rootSeen = false
        var rootClosed = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.DOCDECL -> error("DTD forbidden")
                XmlPullParser.ENTITY_REF -> {
                    if (parser.name !in BUILT_IN_ENTITIES || parser.text == null) error("external/unknown entity")
                    stack.lastOrNull()?.text?.append(parser.text)
                }
                XmlPullParser.START_TAG -> {
                    if (stack.size >= 64) error("XML nesting limit")
                    val name = parser.name
                    val ns = parser.namespace.orEmpty()
                    val parent = stack.lastOrNull()
                    if (parent == null) {
                        if (rootSeen || name != "metadata") error("unexpected root")
                        rootSeen = true
                        namespace = ns
                    }
                    if (name in STRUCTURAL) {
                        if (ns != namespace) error("structural namespace mismatch")
                        if (name == "metadata") {
                            if (parent != null) error("nested metadata")
                        } else if (name == "position") {
                            if (parent?.name !in setOf("medium", "track")) error("misplaced position")
                        } else if (parent?.name != STRUCTURAL_PARENT[name]) {
                            error("misplaced structural element: $name")
                        }
                        parent?.childrenNamed?.let { counts -> counts[name] = (counts[name] ?: 0) + 1 }
                    }
                    val attrs = buildMap {
                        for (i in 0 until parser.attributeCount) {
                            if (parser.getAttributeNamespace(i).orEmpty().isEmpty()) {
                                put(parser.getAttributeName(i), parser.getAttributeValue(i))
                            }
                        }
                    }
                    val frame = Frame(name, ns, attrs)
                    when (name) {
                        "release" -> releaseId = attrs["id"]
                        "medium-list" -> {
                            mediumListCount = attrs["count"]?.toIntOrNull()?.takeIf { it in 0..MAX_MEDIA }
                                ?: error("missing/invalid medium-list count")
                            attrs["offset"]?.let { if (it.toIntOrNull() != 0) error("invalid medium-list offset") }
                        }
                        "medium" -> {
                            mediaSeen++
                            if (mediaSeen > MAX_MEDIA) error("too many media")
                        }
                        "track-list" -> {
                            trackListsSeen++
                            frame.trackCount = attrs["count"]?.toIntOrNull()?.takeIf { it in 0..MAX_TRACKS }
                                ?: error("missing/invalid track-list count")
                            attrs["offset"]?.let { if (it.toIntOrNull() != 0) error("invalid track-list offset") }
                        }
                        "track" -> {
                            frame.trackId = attrs["id"] ?: error("missing Track MBID")
                            if (!MBID.matches(frame.trackId!!)) error("invalid Track MBID")
                        }
                        "recording" -> {
                            frame.recordingId = attrs["id"] ?: error("missing Recording MBID")
                            if (!MBID.matches(frame.recordingId!!)) error("invalid Recording MBID")
                        }
                    }
                    stack.addLast(frame)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> stack.lastOrNull()?.text?.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (stack.isEmpty()) error("unbalanced XML")
                    val frame = stack.removeLast()
                    if (frame.name != parser.name || frame.namespace != parser.namespace.orEmpty()) error("mismatched end tag")
                    val parent = stack.lastOrNull()
                    when (frame.name) {
                        "position" -> {
                            val pos = frame.text.toString().trim().toIntOrNull()?.takeIf { it >= 0 }
                                ?: error("missing/invalid position")
                            val owner = parent ?: error("orphan position")
                            if (owner.position != null) error("duplicate position")
                            owner.position = pos
                        }
                        "recording" -> {
                            val owner = parent?.takeIf { it.name == "track" } ?: error("orphan recording")
                            if (owner.recordingId != null) error("duplicate recording")
                            owner.recordingId = frame.recordingId
                        }
                        "track" -> {
                            val list = parent?.takeIf { it.name == "track-list" } ?: error("orphan track")
                            val medium = stack.elementAtOrNull(stack.size - 2)?.takeIf { it.name == "medium" }
                                ?: error("track-list outside medium")
                            if ((frame.childrenNamed["position"] ?: 0) != 1) error("missing/duplicate track position")
                            val position = frame.position ?: error("missing track position")
                            val recording = frame.recordingId ?: error("missing track recording")
                            val mediumPosition = medium.position ?: error("missing medium position")
                            rows += Row(frame.trackId!!, recording, mediumPosition, position)
                            if (rows.size > MAX_TRACKS) error("too many tracks")
                            if ((frame.childrenNamed["recording"] ?: 0) != 1) error("missing/duplicate recording")
                        }
                        "track-list" -> {
                            val count = frame.childrenNamed["track"] ?: 0
                            if (count != frame.trackCount) error("incomplete track list")
                            val medium = parent?.takeIf { it.name == "medium" } ?: error("orphan track-list")
                            if ((medium.childrenNamed["track-list"] ?: 0) > 1) error("duplicate track-list")
                        }
                        "medium" -> {
                            if (frame.position == null || (frame.childrenNamed["position"] ?: 0) != 1 ||
                                (frame.childrenNamed["track-list"] ?: 0) != 1
                            ) error("incomplete/duplicate medium structure")
                            parent?.takeIf { it.name == "medium-list" } ?: error("orphan medium")
                        }
                        "medium-list" -> {
                            if ((frame.childrenNamed["medium"] ?: 0) != mediumListCount) error("incomplete medium list")
                        }
                        "release" -> {
                            if ((frame.childrenNamed["medium-list"] ?: 0) != 1) error("missing/duplicate medium-list")
                        }
                        "metadata" -> {
                            if ((frame.childrenNamed["release"] ?: 0) != 1) error("missing/duplicate release")
                            rootClosed = true
                        }
                    }
                }
            }
            parser.next()
        }
        if (!rootSeen || !rootClosed || stack.isNotEmpty() || releaseId != releaseMbid ||
            mediumListCount == null || mediumListCount != mediaSeen || trackListsSeen != mediaSeen ||
            rows.isEmpty() || rows.map { it.track }.distinct().size != rows.size ||
            rows.map { it.medium }.distinct().size != mediaSeen ||
            rows.map { it.medium to it.position }.distinct().size != rows.size
        ) return false
        val xmlRows = rows.map { listOf(it.track, it.recording, it.medium.toString(), it.position.toString()) }.toSet()
        val jsonRows = json.tracks.map {
            listOf(it.trackMbid, it.recordingMbid, it.mediumNumber.toString(), it.trackNumber.toString())
        }.toSet()
        return rows.size == json.tracks.size && xmlRows == jsonRows
    }

    private val STRUCTURAL = setOf("metadata", "release", "medium-list", "medium", "track-list", "track", "recording", "position")
    private val STRUCTURAL_PARENT = mapOf(
        "release" to "metadata", "medium-list" to "release", "medium" to "medium-list",
        "track-list" to "medium", "track" to "track-list", "recording" to "track",
    )
    private val BUILT_IN_ENTITIES = setOf("amp", "lt", "gt", "apos", "quot")
}
