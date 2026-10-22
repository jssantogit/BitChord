package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/**
 * Explicit opt-in TRANSPORT evidence: observed bytes hash is NOT a trusted
 * source hash. Never imports, plays, or persists the selected peer filename.
 */
class A0ObservedOneFileProbeTest {
    @get:Rule val folder=TemporaryFolder()
    private val bytes=ByteArray(300) { it.toByte() }
    private val path="Audio\\short-track.mp3"

    @Test fun selectedPublicSmallFileCanBeReceivedWithoutPretendingToKnowTrustedHash() {
        val listing=A0OpenAudioCandidateGate.PublicPeerListing(path,bytes.size.toLong(),false,true)
        val session=A0LicensedOneFileSession.observedSession(listing)
        val p=ByteArrayOutputStream()
        session.queueUpload(p)
        assertTrue(p.size()>12)
        val reply=ByteArrayOutputStream()
        session.acceptUpload(ByteArrayInputStream(frame(40,
            le(1)+le(212)+str(path)+le64(bytes.size.toLong()))),reply)
        assertArrayEquals(frame(41,le(212)+byteArrayOf(1)),reply.toByteArray())
        val offset=ByteArrayOutputStream()
        val receipt=session.receiveFile(ByteArrayInputStream(le(212)+bytes),offset,folder.root)
        try {
            assertEquals(bytes.size.toLong(),receipt.byteCount)
            assertArrayEquals(ByteArray(8),offset.toByteArray())
            assertArrayEquals(bytes,receipt.file.readBytes())
            assertTrue(receipt.file.name.endsWith(".observed"))
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString(""){"%02x".format(it.toInt() and 255)},receipt.sha256Hex)
        }finally{
            assertTrue(receipt.file.delete())
        }
        assertTrue(folder.root.listFiles().isNullOrEmpty())
    }

    @Test fun truncatedStreamDeletesPartialAndCannotBeReplayed() {
        val session=A0LicensedOneFileSession.observedSession(
            A0OpenAudioCandidateGate.PublicPeerListing(path,bytes.size.toLong(),false,true))
        session.queueUpload(ByteArrayOutputStream())
        session.acceptUpload(ByteArrayInputStream(frame(40,
            le(1)+le(51)+str(path)+le64(bytes.size.toLong()))),ByteArrayOutputStream())
        try{
            session.receiveFile(ByteArrayInputStream(le(51)+bytes.copyOfRange(0,60)),
                ByteArrayOutputStream(),folder.root)
            fail("Truncated peer must not yield receipt")
        }catch (_:IOException){}
        assertTrue(folder.root.listFiles().isNullOrEmpty())
        try{session.queueUpload(ByteArrayOutputStream());fail("Single-use")}
        catch (_:IllegalStateException){}
    }

    @Test fun observedModeRejectsOversizeLockedUnsafeOrUnavailableBeforeQueue() {
        for (listing in listOf(
            A0OpenAudioCandidateGate.PublicPeerListing("../unsafe.wav",300,false,true),
            A0OpenAudioCandidateGate.PublicPeerListing("Track\\song.mp3",0,false,true),
            A0OpenAudioCandidateGate.PublicPeerListing("Track\\song.mp3",65L*1024*1024,false,true),
            A0OpenAudioCandidateGate.PublicPeerListing("Track\\song.mp3",300,true,true),
            A0OpenAudioCandidateGate.PublicPeerListing("Track\\song.mp3",300,false,false)
        )){
            try { A0LicensedOneFileSession.observedSession(listing);fail("unsafe")}
            catch (_:IllegalArgumentException){}
        }
    }

    private fun le(n:Int)=ByteArray(4){(n ushr (it*8)).toByte()}
    private fun le64(n:Long)=ByteArray(8){(n ushr (it*8)).toByte()}
    private fun str(s:String)=s.toByteArray(Charsets.UTF_8).let{le(it.size)+it}
    private fun frame(code:Int,bytes:ByteArray)=(le(code)+bytes).let{le(it.size)+it}
}
