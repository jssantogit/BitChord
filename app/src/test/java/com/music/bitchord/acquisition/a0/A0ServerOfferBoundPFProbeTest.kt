package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A0 integration tests: bounded P channel from an indicated server peer, plus
 * an incoming F connection from the SAME remote address and P40 token.
 * Uses own synthetic WAV and localhost, not a Soulseek file or public peer.
 */
class A0ServerOfferBoundPFProbeTest {
    @get:Rule val files = TemporaryFolder()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127,0,0,1))
    private val audio = ownWav()
    private val sha = MessageDigest.getInstance("SHA-256").digest(audio)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test(timeout = 16000)
    fun serverIndicatedPAndTokenMatchedFReceiveOnlyReviewedOwnerWav() {
        ServerSocket(0,1,loopback).use { peerServer ->
            val onPortReady = CountDownLatch(1)
            val fPort = IntArray(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val remote = executor.submit {
                    peerServer.accept().use { p ->
                        p.soTimeout = 3500
                        val init = frame(p)
                        assertEquals(0, init.read())
                        assertEquals(109, readInt(init))
                        val q = frame(p)
                        assertEquals(43, readInt(q))
                        assertEquals("Own\\tone.wav", readString(q))
                        p.getOutputStream().apply {
                            write(packet(40, le(1) + le(91) + field("Own\\tone.wav") +
                                le64(audio.size.toLong())))
                            flush()
                        }
                        val accept = frame(p)
                        assertEquals(41, readInt(accept))
                        assertEquals(91, readInt(accept))
                        assertEquals(1, accept.read())
                        assertTrue(onPortReady.await(3,TimeUnit.SECONDS))
                        Socket(loopback,fPort[0]).use { f ->
                            f.soTimeout = 3500
                            f.getOutputStream().apply {
                                write(le(91) + audio)
                                flush()
                            }
                            assertArrayEquals(ByteArray(8), readFully(f,8))
                        }
                    }
                }
                val probe = A0ServerOfferBoundPFProbe()
                val result = probe.runOnce(
                    offer = A0IndirectSearchWire.Offer("owner", loopback, peerServer.localPort,109),
                    reference = reviewed(),
                    listing = listing(),
                    privateDirectory = files.root,
                    onListenerReady = { port -> fPort[0]=port;onPortReady.countDown() }
                )
                assertEquals(sha,result.sha256Hex)
                assertArrayEquals(audio,result.file.readBytes())
                assertTrue(result.file.name.endsWith(".verified"))
                remote.get(4,TimeUnit.SECONDS)
                assertFalse(files.root.listFiles().orEmpty().any { it.name.endsWith(".partial") })
                assertThrowsIllegal { probe.runOnce(
                    A0IndirectSearchWire.Offer("owner",loopback,peerServer.localPort,109),
                    reviewed(),listing(),files.root
                ) }
            } finally { executor.shutdownNow() }
        }
    }

    @Test(timeout = 15000)
    fun wrongIncomingFTokenNeverCreatesVerifiedOrPartialFile() {
        ServerSocket(0,1,loopback).use { peerServer ->
            val ready = CountDownLatch(1)
            val fPort=IntArray(1)
            val executor=Executors.newSingleThreadExecutor()
            try {
                val remote=executor.submit {
                    peerServer.accept().use { p ->
                        p.soTimeout=3500
                        frame(p);frame(p)
                        p.getOutputStream().apply {
                            write(packet(40,le(1)+le(93)+field("Own\\tone.wav") +
                                le64(audio.size.toLong())))
                            flush()
                        }
                        frame(p)
                        assertTrue(ready.await(3,TimeUnit.SECONDS))
                        Socket(loopback,fPort[0]).use { f ->
                            f.getOutputStream().write(le(94)+audio) // wrong token
                            f.getOutputStream().flush()
                        }
                    }
                }
                val probe=A0ServerOfferBoundPFProbe()
                try {
                    probe.runOnce(
                        A0IndirectSearchWire.Offer("owner",loopback,peerServer.localPort,101),
                        reviewed(),listing(),files.root,
                        onListenerReady={port->fPort[0]=port;ready.countDown()}
                    )
                    fail("A file connection token must match accepted P40")
                } catch (_:IOException) { }
                remote.get(4,TimeUnit.SECONDS)
                assertTrue(files.root.listFiles().isNullOrEmpty())
            } finally { executor.shutdownNow() }
        }
    }

    @Test(timeout = 10000)
    fun cancelledOrUnlicensedProbeCannotContactPeer() {
        ServerSocket(0,1,loopback).use { peerServer ->
            val offer = A0IndirectSearchWire.Offer("owner",loopback,peerServer.localPort,101)
            val probe = A0ServerOfferBoundPFProbe()
            probe.cancel()
            try {
                probe.runOnce(offer,reviewed(),listing(),files.root)
                fail("Cancelled session must not connect")
            } catch (_:IOException) {}
            val unlicensed = A0ServerOfferBoundPFProbe()
            try {
                unlicensed.runOnce(offer,reviewed().copy(rightsReviewedOutsideSoulseek=false),
                    listing(),files.root)
                fail("Rights must be checked before network access")
            } catch (_:IllegalArgumentException) {}
            assertTrue(files.root.listFiles().isNullOrEmpty())
        }
    }

    @Test fun mismatchBetweenServerPeerAndFSourceIsRejectedWithoutNetworking() {
        A0ServerOfferBoundPFProbe.requireSamePeerAddress(
            loopback, InetAddress.getByAddress(byteArrayOf(127,0,0,1))
        )
        try {
            A0ServerOfferBoundPFProbe.requireSamePeerAddress(
                loopback, InetAddress.getByAddress(byteArrayOf(127,0,0,2))
            )
            fail("Mismatched server peer must be rejected")
        } catch (_:IOException) { }
    }

    private fun reviewed()=A0OpenAudioCandidateGate.ReviewedReference(
        "https://example.org/owned/test-audio","CC0-1.0","tone.wav",
        audio.size.toLong(),sha,true
    )
    private fun listing()=A0OpenAudioCandidateGate.PublicPeerListing(
        "Own\\tone.wav",audio.size.toLong(),false,true
    )
    private fun frame(socket:Socket):ByteArrayInputStream {
        val len=readInt(socket.getInputStream())
        if(len !in 1..4096)throw IOException("invalid frame")
        return ByteArrayInputStream(readFully(socket,len))
    }
    private fun readInt(input:java.io.InputStream):Int {
        val a=ByteArray(4);var n=0
        while(n<4) {val r=input.read(a,n,4-n);if(r<=0)throw EOFException();n+=r}
        return (a[0].toInt() and 255) or ((a[1].toInt() and 255) shl 8) or
            ((a[2].toInt() and 255) shl 16) or ((a[3].toInt() and 255) shl 24)
    }
    private fun readString(input:ByteArrayInputStream):String {
        val len=readInt(input)
        if(len !in 1..1024)throw IOException()
        val b=ByteArray(len)
        if(input.read(b)!=len)throw EOFException()
        return String(b,Charsets.UTF_8)
    }
    private fun readFully(s:Socket,n:Int):ByteArray {
        val b=ByteArray(n);var p=0
        while(p<n){val x=s.getInputStream().read(b,p,n-p);if(x<=0)throw EOFException();p+=x}
        return b
    }
    private fun le(n:Int)=ByteArray(4){(n ushr (8*it)).toByte()}
    private fun le64(n:Long)=ByteArray(8){(n ushr (8*it)).toByte()}
    private fun field(s:String)=s.toByteArray(Charsets.UTF_8).let{le(it.size)+it}
    private fun packet(code:Int,payload:ByteArray)=(le(code)+payload).let{le(it.size)+it}
    private fun ownWav():ByteArray{
        val h=ByteArray(44)
        fun mark(i:Int,s:String){s.toByteArray(Charsets.US_ASCII).copyInto(h,i)}
        fun i32(i:Int,v:Int){repeat(4){h[i+it]=(v ushr (8*it)).toByte()}}
        fun i16(i:Int,v:Int){repeat(2){h[i+it]=(v ushr (8*it)).toByte()}}
        mark(0,"RIFF");i32(4,36+256);mark(8,"WAVE")
        mark(12,"fmt ");i32(16,16);i16(20,1);i16(22,1)
        i32(24,8000);i32(28,16000);i16(32,2);i16(34,16)
        mark(36,"data");i32(40,256)
        return h+ByteArray(256){(it and 0x7f).toByte()}
    }
    private fun assertThrowsIllegal(fn:()->Unit){
        try{fn();fail("already used")}catch(_:IllegalStateException){}
    }
}
