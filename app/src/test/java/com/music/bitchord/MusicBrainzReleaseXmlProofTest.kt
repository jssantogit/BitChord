package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogReleaseDetail
import com.music.bitchord.data.catalog.metadata.MusicBrainzCatalogTrack
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseJsonCompleteness
import com.music.bitchord.data.catalog.metadata.MusicBrainzReleaseXmlProof
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzReleaseXmlProofTest {
    private val release = "541adf5a-48d4-4f7b-a7bb-afd3cea5af27"
    private val trackA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val trackB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val recording = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private fun xml(count: Int = 2, offset: String? = null, ids: Pair<String, String> = trackA to trackB) =
        """<metadata><release id="$release"><medium-list count="1"><medium><position>1</position><track-list count="$count"${offset?.let { " offset=\"$it\"" }.orEmpty()}><track id="${ids.first}"><position>1</position><recording id="$recording"/></track><track id="${ids.second}"><position>2</position><recording id="$recording"/></track></track-list></medium></medium-list></release></metadata>"""

    private fun detail(completeness: MusicBrainzReleaseJsonCompleteness = MusicBrainzReleaseJsonCompleteness(true, true, true)) =
        MusicBrainzCatalogReleaseDetail(release, null, "Edition", null, null, null, listOf(
            MusicBrainzCatalogTrack(trackA, recording, "A", 1, 1, null),
            MusicBrainzCatalogTrack(trackB, recording, "B", 1, 2, null),
        ), completeness = completeness)

    @Test fun acceptsDistinctTracksSharingRecordingAndRequiresExactListCountsAndOffsets() {
        assertTrue(MusicBrainzReleaseXmlProof.proves(xml(), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml(count = 1), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml(offset = "1"), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml(offset = "bad"), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml().replace("<medium-list count=\"1\">", "<medium-list count=\"1\" offset=\"1\">"), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml().replace("<position>2</position>", "<position>3</position>"), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml().replace("<position>2</position>", "<position>1</position>"), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml(), release, detail(MusicBrainzReleaseJsonCompleteness())))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml().replace("id=\"$trackB\"", "id=\"$trackA\""), release, detail()))
    }

    @Test fun rejectsDtdAndEmptyOrMismatchedReleaseDocuments() {
        assertFalse(MusicBrainzReleaseXmlProof.proves("<!DOCTYPE metadata [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + xml(), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves(xml().replace(release, trackA), release, detail()))
        assertFalse(MusicBrainzReleaseXmlProof.proves("", release, detail()))
    }

    @Test fun rejectsTrackListsOutsideReleaseEvenWhenRowsAndCountsMatch() {
        val movedList = """<metadata><release id="$release"/><medium-list count="1"><medium><position>1</position><track-list count="2"><track id="$trackA"><position>1</position><recording id="$recording"/></track><track id="$trackB"><position>2</position><recording id="$recording"/></track></track-list></medium></medium-list></metadata>"""
        assertFalse(MusicBrainzReleaseXmlProof.proves(movedList, release, detail()))
    }

    @Test fun acceptsTheSameStrictHierarchyWithAnInheritedXmlNamespace() {
        val namespaced = xml().replace("<metadata>", "<metadata xmlns=\"https://musicbrainz.org/ns/mmd-2.0#\">")
        assertTrue(MusicBrainzReleaseXmlProof.proves(namespaced, release, detail()))
    }

    @Test fun zeroPositionsAreExplicitValidPositionsNotMissingValues() {
        val zeroXml = xml().replace("<position>1</position>", "<position>0</position>")
            .replace("<position>2</position>", "<position>1</position>")
        val zeroDetail = detail().copy(tracks = listOf(
            MusicBrainzCatalogTrack(trackA, recording, "A", 0, 0, null),
            MusicBrainzCatalogTrack(trackB, recording, "B", 0, 1, null),
        ))
        assertTrue(MusicBrainzReleaseXmlProof.proves(zeroXml, release, zeroDetail))
    }
}
