package com.music.bitchord.acquisition.a0

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test

class A0PeerFolderContentsWireTest {
    private fun le32(out:ByteArrayOutputStream,value:Int) {
        repeat(4) { out.write((value ushr (8*it)) and 255) }
    }
    private fun le64(out:ByteArrayOutputStream,value:Long) {
        repeat(8) { out.write(((value ushr (8*it)) and 255).toInt()) }
    }
    private fun string(out:ByteArrayOutputStream,value:String) {
        val b=value.toByteArray(Charsets.UTF_8)
        le32(out,b.size);out.write(b)
    }
    private fun response(token:Int,requested:String,folder:String=requested,
        files:List<String> = listOf("01 - Super in Love.flac","02 - More.flac"),
    ):ByteArray {
        val body=ByteArrayOutputStream()
        le32(body,token);string(body,requested);le32(body,1)
        string(body,folder);le32(body,files.size)
        for(file in files) {
            body.write(1)
            string(body,file)
            le64(body,42_000_000L)
            string(body,"flac")
            le32(body,2)
            le32(body,1);le32(body,179)
            le32(body,4);le32(body,44100)
        }
        val zipped=ByteArrayOutputStream()
        DeflaterOutputStream(zipped).use { it.write(body.toByteArray()) }
        val frame=ByteArrayOutputStream()
        le32(frame,4+zipped.size());le32(frame,37)
        frame.write(zipped.toByteArray())
        return frame.toByteArray()
    }
    @Test fun requestUsesCode36AndExactSelectedFolder() {
        val packet=A0PeerFolderContentsWire.request(712,"Music\\ILLIT\\BREAK EVEN")
        assertEquals(36,(packet[4].toInt() and 255))
        assertTrue(packet.size<512)
    }
    @Test fun responseRequiresTokenEchoAndNeverUsesChildFolderEvidence() {
        val folder="Music\\ILLIT\\BREAK EVEN"
        val files=A0PeerFolderContentsWire.parse(
            response(712,folder),712,folder)
        assertEquals(2,files.size)
        assertEquals("Music\\ILLIT\\BREAK EVEN\\01 - Super in Love.flac",
            files.first().remoteFilename)
        assertEquals(179,files.first().attributes[1])
        assertEquals(42_000_000L,files.first().sizeBytes)
        try {
            A0PeerFolderContentsWire.parse(response(713,folder),712,folder)
            fail("wrong token accepted")
        }catch(_:IOException){}
        try {
            A0PeerFolderContentsWire.parse(
                response(712,"Other\\Album"),712,folder)
            fail("wrong folder accepted")
        }catch(_:IOException){}
        val child=A0PeerFolderContentsWire.parse(
            response(712,folder,folder+"\\CD2"),712,folder)
        assertTrue("subdirectory cannot establish same-folder release",child.isEmpty())
    }
    @Test fun boundedFramingRejectsMalformedFileCountAndTraversal() {
        val folder="Music\\ILLIT\\BREAK EVEN"
        try {
            A0PeerFolderContentsWire.parse(
                response(712,folder,files=listOf("../Fake.flac")),712,folder)
            fail("path traversal accepted")
        }catch(_:IOException){}
        val frame=response(712,folder)
        val broken=frame.copyOfRange(0,frame.size-4)
        try {
            A0PeerFolderContentsWire.parse(broken,712,folder)
            fail("truncated frame accepted")
        }catch(_:IOException){}
    }
}
