package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real P and inbound F sockets, localhost-only, owner-created WAV, no public peer. */
class A0ModernLoopbackSocketTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ownWav = ownAudio()
    private val sha = MessageDigest.getInstance("SHA-256").digest(ownWav)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val name = "Owned\\SynthTone.wav"

    @Test(timeout = 16000)
    fun exactOwnerWavCrossesModernTwoSocketPAndFAndIsVerified() {
        val ready = CountDownLatch(1)
        val filePort = IntArray(1)
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127,0,0,1))).use { peer ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val uploader = pool.submit {
                    peer.accept().use { p ->
                        p.soTimeout = 3500
                        val request = ByteArrayInputStream(frameBody(p))
                        assertEquals(43, readInt(request))
                        assertEquals(name, readString(request))
                        assertEquals(-1, request.read())
                        p.getOutputStream().write(frame(40, le(1) + le(72) + str(name) + long(ownWav.size.toLong())))
                        p.getOutputStream().flush()
                        val reply = ByteArrayInputStream(frameBody(p))
                        assertEquals(41, readInt(reply))
                        assertEquals(72, readInt(reply))
                        assertEquals(1, reply.read())
                        assertEquals(-1, reply.read())
                        assertTrue(ready.await(3, TimeUnit.SECONDS))
                        Socket(InetAddress.getByAddress(byteArrayOf(127,0,0,1)), filePort[0]).use { f ->
                            f.soTimeout = 3500
                            f.getOutputStream().write(le(72) + ownWav)
                            f.getOutputStream().flush()
                            assertArrayEquals(ByteArray(8), readExact(f, 8))
                        }
                    }
                }
                val result = A0ModernLoopbackSocketTransfer.receiveOwnedFixture(
                    pPort = peer.localPort, reference = reference(),
                    listing = listing(), privateDirectory = temporary.root,
                    onFileListenerReady = { port -> filePort[0] = port; ready.countDown() }
                )
                assertTrue(result is A0ModernLoopbackSocketTransfer.Outcome.Completed)
                val receipt = (result as A0ModernLoopbackSocketTransfer.Outcome.Completed).receipt
                assertEquals(sha, receipt.sha256Hex)
                assertArrayEquals(ownWav, receipt.file.readBytes())
                assertTrue(receipt.file.name.endsWith(".verified"))
                assertFalse(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                uploader.get(4, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        }
    }

    @Test(timeout = 12000)
    fun nonLicensedFileMustNotEvenConnectToP() {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127,0,0,1))).use { peer ->
            peer.soTimeout = 400
            val blocked = A0ModernLoopbackSocketTransfer.receiveOwnedFixture(
                pPort = peer.localPort,
                reference = reference().copy(rightsReviewedOutsideSoulseek = false),
                listing = listing(), privateDirectory = temporary.root
            )
            assertTrue(blocked is A0ModernLoopbackSocketTransfer.Outcome.Blocked)
            try { peer.accept().close(); fail("Invalid rights must prevent any P socket") }
            catch (_: SocketTimeoutException) { }
            assertTrue(temporary.root.listFiles().isNullOrEmpty())
        }
    }

    @Test(timeout = 16000)
    fun corruptFBytesCauseNoVerifiedOrPartialFile() {
        val ready = CountDownLatch(1)
        val filePort = IntArray(1)
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127,0,0,1))).use { peer ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val uploader = pool.submit {
                    peer.accept().use { p ->
                        p.soTimeout = 3500
                        frameBody(p)
                        p.getOutputStream().write(frame(40, le(1) + le(92) + str(name) + long(ownWav.size.toLong())))
                        p.getOutputStream().flush()
                        frameBody(p)
                        assertTrue(ready.await(3, TimeUnit.SECONDS))
                        Socket(InetAddress.getByAddress(byteArrayOf(127,0,0,1)), filePort[0]).use { f ->
                            f.soTimeout = 3500
                            val tampered = ownWav.copyOf().apply { this[lastIndex] = 81 }
                            f.getOutputStream().write(le(92) + tampered)
                            f.getOutputStream().flush()
                            assertArrayEquals(ByteArray(8), readExact(f, 8))
                        }
                    }
                }
                try {
                    A0ModernLoopbackSocketTransfer.receiveOwnedFixture(
                        peer.localPort, reference(), listing(), temporary.root,
                        onFileListenerReady = { port -> filePort[0] = port; ready.countDown() }
                    )
                    fail("Digest mismatch must fail closed")
                } catch (_: IOException) { }
                uploader.get(4, TimeUnit.SECONDS)
                assertTrue(temporary.root.listFiles().isNullOrEmpty())
            } finally { pool.shutdownNow() }
        }
    }

    @Test(timeout = 12000)
    fun invalidPortIsRejectedBeforeAnyNetworkAccess() {
        try {
            A0ModernLoopbackSocketTransfer.receiveOwnedFixture(
                0, reference(), listing(), temporary.root
            )
            fail("Invalid P port must fail")
        } catch (_: IllegalArgumentException) { }
    }

    private fun reference() = A0OpenAudioCandidateGate.ReviewedReference(
        "https://example.org/own-synthetic-tone",
        "CC0-1.0",
        "SynthTone.wav",
        ownWav.size.toLong(),
        sha,
        true
    )
    private fun listing() = A0OpenAudioCandidateGate.PublicPeerListing(
        name, ownWav.size.toLong(), false, true
    )
    private fun frame(code: Int, bytes: ByteArray) = (le(code) + bytes).let { le(it.size) + it }
    private fun str(s: String) = s.toByteArray(Charsets.UTF_8).let { le(it.size) + it }
    private fun le(v: Int) = ByteArray(4) { (v ushr (8*it)).toByte() }
    private fun long(v: Long) = ByteArray(8) { (v ushr (8*it)).toByte() }
    private fun readInt(s: ByteArrayInputStream): Int {
        val b = ByteArray(4)
        if(s.read(b)!=4) throw EOFException()
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun readString(s: ByteArrayInputStream): String {
        val n=readInt(s); if(n !in 1..1024) throw IOException()
        val b=ByteArray(n); if(s.read(b)!=n) throw EOFException()
        return String(b,Charsets.UTF_8)
    }
    private fun frameBody(socket: Socket): ByteArray {
        val i = socket.getInputStream()
        val b = readExact(socket,4)
        val n = (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
        if (n !in 4..4096) throw IOException()
        return readExact(socket,n)
    }
    private fun readExact(socket: Socket, count:Int):ByteArray {
        val bytes=ByteArray(count)
        var got=0
        val stream=socket.getInputStream()
        while(got<count) {
            val n=stream.read(bytes,got,count-got)
            if(n<=0)throw EOFException()
            got+=n
        }
        return bytes
    }
    private fun ownAudio():ByteArray {
        val h=ByteArray(44)
        fun ascii(offset:Int,t:String){t.toByteArray(Charsets.US_ASCII).copyInto(h,offset)}
        fun i32(offset:Int,n:Int){repeat(4){h[offset+it]=(n ushr (8*it)).toByte()}}
        fun i16(offset:Int,n:Int){repeat(2){h[offset+it]=(n ushr (8*it)).toByte()}}
        ascii(0,"RIFF");i32(4,36+256);ascii(8,"WAVE")
        ascii(12,"fmt ");i32(16,16);i16(20,1);i16(22,1)
        i32(24,8000);i32(28,16000);i16(32,2);i16(34,16)
        ascii(36,"data");i32(40,256)
        return h+ByteArray(256){(it and 0x7f).toByte()}
    }
}
