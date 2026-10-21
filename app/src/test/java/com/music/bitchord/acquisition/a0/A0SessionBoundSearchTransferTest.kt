package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Test-first: parsed search response, pinned session, selected licensed candidate -> modern P/F. */
class A0SessionBoundSearchTransferTest {
    @get:Rule val temp=TemporaryFolder()
    private val host=InetAddress.getByAddress(byteArrayOf(127,0,0,1))
    private val wav=wav()
    private val digest=MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") {
        "%02x".format(it.toInt() and 255)
    }
    private val remote="Verified\\owned-tone.wav"

    @Test fun onlyMatchingPeerTokenExactSizeAndSingleUnlockedFileCanBeSelected() {
        val server=Socket()
        val handoff=A0SessionBoundSearchTransfer(server,"our_account",81,reference(),true)
        assertEquals(A0SessionBoundSearchTransfer.Selection.READY,
            handoff.offerSearchResult(response(81,"trusted_peer",remote,wav.size.toLong()),"trusted_peer"))
        assertEquals(A0SessionBoundSearchTransfer.Selection.READY,
            handoff.selectionStatus())
        handoff.markReadersStopped()
        assertEquals(A0SessionBoundSearchTransfer.Selection.READY,handoff.selectionStatus())
        // No raw username, path, search token or file metadata in debug string.
        assertFalse(handoff.toString().contains("trusted_peer"))
        assertFalse(handoff.toString().contains("owned-tone.wav"))
    }

    @Test fun wrongSearchTokenClaimedPeerLockedOrUnlicensedPathNeverBecomesTransfer() {
        val cases=listOf(
            response(82,"trusted_peer",remote,wav.size.toLong()) to "trusted_peer",
            response(81,"trusted_peer",remote,wav.size.toLong()) to "impostor",
            response(81,"trusted_peer","Anime\\other.mp3",wav.size.toLong()) to "trusted_peer",
            response(81,"trusted_peer",remote,wav.size.toLong()+1) to "trusted_peer",
            response(81,"trusted_peer",remote,wav.size.toLong(),locked=true) to "trusted_peer",
            response(81,"trusted_peer",remote,wav.size.toLong(),freeSlot=false) to "trusted_peer"
        )
        for((frame,peer) in cases) {
            val handoff=A0SessionBoundSearchTransfer(Socket(),"our_account",81,reference(),true)
            val status=handoff.offerSearchResult(frame,peer)
            assertEquals(A0SessionBoundSearchTransfer.Selection.REJECTED,status)
            assertEquals(A0SessionBoundSearchTransfer.Selection.NONE,handoff.selectionStatus())
            try {
                handoff.transferOnce(temp.root)
                fail("No search candidate must prohibit network transfer")
            } catch (_:IllegalStateException){}
        }
    }

    @Test fun duplicateReviewedCandidateAndConflictingPeerAreRejectedRatherThanSwitched() {
        val server=Socket()
        val handoff=A0SessionBoundSearchTransfer(server,"our_account",81,reference(),true)
        assertEquals(A0SessionBoundSearchTransfer.Selection.REJECTED,
            handoff.offerSearchResult(response(81,"trusted_peer",remote,wav.size.toLong(),
                duplicate=true),"trusted_peer"))
        assertEquals(A0SessionBoundSearchTransfer.Selection.NONE,handoff.selectionStatus())
        assertEquals(A0SessionBoundSearchTransfer.Selection.READY,
            handoff.offerSearchResult(response(81,"trusted_peer",remote,wav.size.toLong()),"trusted_peer"))
        assertEquals(A0SessionBoundSearchTransfer.Selection.REJECTED,
            handoff.offerSearchResult(response(81,"different_peer",remote,wav.size.toLong()),"different_peer"))
        assertEquals(A0SessionBoundSearchTransfer.Selection.NONE,handoff.selectionStatus())
    }

    @Test fun handoffRequiresReaderShutdownAndIsIrrevocablySingleUse() {
        val handoff=A0SessionBoundSearchTransfer(Socket(),"our_account",81,reference(),true)
        assertEquals(A0SessionBoundSearchTransfer.Selection.READY,
            handoff.offerSearchResult(response(81,"trusted_peer",remote,wav.size.toLong()),"trusted_peer"))
        try {
            handoff.transferOnce(temp.root)
            fail("Readers must relinquish server socket first")
        }catch(_:IllegalStateException){}
        handoff.markReadersStopped()
        handoff.cancel()
        try {
            handoff.transferOnce(temp.root)
            fail("Cancelled handoff must not transfer")
        }catch(_:IOException){}
        assertTrue(temp.root.listFiles().isNullOrEmpty())
    }

    @Test(timeout=18000)
    fun selectedCandidatePassesIntoSameServerSocketThenPAndFWithShaVerified() {
        ServerSocket(0,1,host).use{serverListener->
            ServerSocket(0,1,host).use{peerListener->
                val listenerPort=ArrayBlockingQueue<Int>(1)
                val done=java.util.concurrent.CountDownLatch(1)
                val pool=Executors.newFixedThreadPool(2)
                try {
                    val server=pool.submit {
                        serverListener.accept().use { s ->
                            s.soTimeout=5000
                            val advertised=body(s)
                            assertEquals(2,le32(advertised))
                            listenerPort.offer(le32(advertised))
                            val lookup=body(s)
                            assertEquals(3,le32(lookup))
                            assertEquals("trusted_peer",field(lookup))
                            s.getOutputStream().apply {
                                write(frame(64,byteArrayOf()))
                                write(frame(3,field("trusted_peer")+
                                    byteArrayOf(1,0,0,127)+le(peerListener.localPort)+le(0)+
                                    byteArrayOf(0,0)))
                                flush()
                            }
                            // Server stays open until the verified P/F receive finishes.
                            assertTrue(done.await(7,TimeUnit.SECONDS))
                        }
                    }
                    val uploader=pool.submit {
                        peerListener.accept().use{p->
                            p.soTimeout=5000
                            val init=readInit(p)
                            assertEquals(1,init.read())
                            assertEquals("our_account",field(init))
                            assertEquals("P",field(init))
                            assertEquals(0,le32(init))
                            val queue=body(p)
                            assertEquals(43,le32(queue))
                            assertEquals(remote,field(queue))
                            p.getOutputStream().apply {
                                write(frame(40,le(1)+le(151)+field(remote)+le64(wav.size.toLong())))
                                flush()
                            }
                            val accept=body(p)
                            assertEquals(41,le32(accept))
                            assertEquals(151,le32(accept))
                            assertEquals(1,accept.read())
                            val port=requireNotNull(listenerPort.poll(4,TimeUnit.SECONDS))
                            Socket(host,port).use{f->
                                f.soTimeout=4000
                                f.getOutputStream().apply{write(le(151)+wav);flush()}
                                assertArrayEquals(ByteArray(8),exact(f.getInputStream(),8))
                            }
                        }
                    }
                    Socket(host,serverListener.localPort).use{authenticated->
                        val handoff=A0SessionBoundSearchTransfer(
                            authenticated,"our_account",81,reference(),true)
                        assertEquals(A0SessionBoundSearchTransfer.Selection.READY,
                            handoff.offerSearchResult(
                                response(81,"trusted_peer",remote,wav.size.toLong()),
                                "trusted_peer"
                            ))
                        handoff.markReadersStopped()
                        val receipt=handoff.transferOnce(temp.root)
                        assertArrayEquals(wav,receipt.file.readBytes())
                        assertEquals(digest,receipt.sha256Hex)
                        uploader.get(5,TimeUnit.SECONDS)
                        done.countDown()
                        server.get(5,TimeUnit.SECONDS)
                        assertTrue(temp.root.listFiles().orEmpty().none{it.name.endsWith(".partial")})
                        try{handoff.transferOnce(temp.root);fail("must not replay")}
                        catch(_:IllegalStateException){}
                    }
                } finally{done.countDown();pool.shutdownNow()}
            }
        }
    }

    private fun reference()=A0OpenAudioCandidateGate.ReviewedReference(
        "https://example.org/own-wav","CC0-1.0","owned-tone.wav",wav.size.toLong(),digest,true
    )
    private fun response(token:Int,peer:String,name:String,size:Long,
        locked:Boolean=false,freeSlot:Boolean=true,duplicate:Boolean=false):ByteArray {
        val file=ByteArrayOutputStream().apply{
            write(1);write(field(name));write(le64(size));write(field("wav"));write(le(0))
        }.toByteArray()
        val raw=ByteArrayOutputStream().apply {
            write(field(peer));write(le(token))
            write(le(if(locked)0 else if(duplicate)2 else 1))
            if(!locked){write(file);if(duplicate)write(file)}
            write(if(freeSlot)1 else 0);write(le(9000));write(le(0))
            write(le(0));write(le(if(locked)1 else 0));if(locked)write(file)
        }.toByteArray()
        val z=ByteArrayOutputStream().apply{DeflaterOutputStream(this).use{it.write(raw)}}.toByteArray()
        return frame(9,z)
    }
    private fun field(s:String)=s.toByteArray(Charsets.UTF_8).let{le(it.size)+it}
    private fun field(s:ByteArrayInputStream):String{
        val n=le32(s);val b=ByteArray(n);assertEquals(n,s.read(b));return String(b,Charsets.UTF_8)
    }
    private fun le(n:Int)=ByteArray(4){(n ushr (8*it)).toByte()}
    private fun le64(n:Long)=ByteArray(8){(n ushr (8*it)).toByte()}
    private fun frame(code:Int,b:ByteArray)=(le(code)+b).let{le(it.size)+it}
    private fun body(s:Socket):ByteArrayInputStream{
        val n=le32(s.getInputStream());if(n !in 4..4096)throw IOException()
        return ByteArrayInputStream(exact(s.getInputStream(),n))
    }
    private fun readInit(s:Socket):ByteArrayInputStream{
        val n=le32(s.getInputStream());if(n !in 1..4096)throw IOException()
        return ByteArrayInputStream(exact(s.getInputStream(),n))
    }
    private fun exact(input:java.io.InputStream,n:Int):ByteArray{
        val bytes=ByteArray(n);var k=0
        while(k<n){val r=input.read(bytes,k,n-k);if(r<=0)throw EOFException();k+=r}
        return bytes
    }
    private fun le32(i:java.io.InputStream):Int{
        val b=exact(i,4);return (b[0].toInt() and 255) or
            ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun wav():ByteArray{
        val h=ByteArray(44)
        fun str(i:Int,s:String){s.toByteArray(Charsets.US_ASCII).copyInto(h,i)}
        fun n32(i:Int,n:Int){repeat(4){h[i+it]=(n ushr (8*it)).toByte()}}
        fun n16(i:Int,n:Int){repeat(2){h[i+it]=(n ushr (8*it)).toByte()}}
        str(0,"RIFF");n32(4,36+256);str(8,"WAVE");str(12,"fmt ")
        n32(16,16);n16(20,1);n16(22,1)
        n32(24,8000);n32(28,16000);n16(32,2);n16(34,16)
        str(36,"data");n32(40,256)
        return h+ByteArray(256){(it and 0x7f).toByte()}
    }
}
