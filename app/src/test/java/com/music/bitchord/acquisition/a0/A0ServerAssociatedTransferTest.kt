package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Complete server-client/P/F real sockets, synthetic owner WAV, IPv4 localhost only. */
class A0ServerAssociatedTransferTest {
    @get:Rule val temp=TemporaryFolder()
    private val host=InetAddress.getByAddress(byteArrayOf(127,0,0,1))
    private val own=wav()
    private val digest=MessageDigest.getInstance("SHA-256").digest(own)
        .joinToString(""){"%02x".format(it.toInt() and 255)}
    private val path="MySongs\\owner-tone.wav"

    @Test(timeout=18000)
    fun directAddressPeerInitAndSeparateFVerifyOnlyAuthorizedFile() {
        ServerSocket(0,2,host).use{fakeServer->
            ServerSocket(0,1,host).use{fakePeer->
                val pool=Executors.newFixedThreadPool(2)
                try {
                    val server=pool.submit<Int> {
                        fakeServer.accept().use{login->
                            login.soTimeout=5000
                            val waitPort=body(login)
                            assertEquals(2,le32(waitPort))
                            val fPort=le32(waitPort)
                            val addressQuery=body(login)
                            assertEquals(3,le32(addressQuery))
                            assertEquals("owner",field(addressQuery))
                            login.getOutputStream().apply {
                                write(frame(64,byteArrayOf(0)))
                                write(frame(3,field("owner")+byteArrayOf(1,0,0,127)+
                                    le(fakePeer.localPort)+le(0)+byteArrayOf(0,0)))
                                flush()
                            }
                            // Keep the server connection alive for the P/F transfer.
                            Thread.sleep(1100)
                            fPort
                        }
                    }
                    val peer=pool.submit {
                        fakePeer.accept().use{p->
                            p.soTimeout=5000
                            val init=readInit(p)
                            assertEquals(1,init.read())
                            assertEquals("testaccount",field(init))
                            assertEquals("P",field(init))
                            assertEquals(0,le32(init))
                            val queue=body(p)
                            assertEquals(43,le32(queue))
                            assertEquals(path,field(queue))
                            p.getOutputStream().apply {
                                write(frame(40,le(1)+le(88)+field(path)+le64(own.size.toLong())))
                                flush()
                            }
                            val response=body(p)
                            assertEquals(41,le32(response))
                            assertEquals(88,le32(response))
                            assertEquals(1,response.read())
                        }
                    }
                    // One third socket drives F after P40, but server needs to
                    // provide SetWaitPort data to this test worker, so use future.
                    val serverSocket=Socket(host,fakeServer.localPort)
                    serverSocket.use{login->
                        val fileUploader=Thread {
                            val port=server.get(4,TimeUnit.SECONDS)
                            Socket(host,port).use{f->
                                f.soTimeout=4000
                                f.getOutputStream().apply { write(le(88)+own);flush() }
                                assertArrayEquals(ByteArray(8),readExactly(f.getInputStream(),8))
                            }
                        }
                        // Delay until the main flow announces file port; worker
                        // then connects after other TCP handshake is complete.
                        fileUploader.start()
                        val result=A0ServerAssociatedTransfer(allowLoopbackForLocalTest=true)
                            .runOnce(login,"testaccount","owner",reference(),listing(),temp.root)
                        assertEquals(digest,result.sha256Hex)
                        assertArrayEquals(own,result.file.readBytes())
                        fileUploader.join(4500)
                        assertFalse(fileUploader.isAlive)
                        peer.get(4,TimeUnit.SECONDS)
                        assertFalse(temp.root.listFiles().orEmpty().any{it.name.endsWith(".partial")})
                    }
                } finally {pool.shutdownNow()}
            }
        }
    }

    @Test(timeout=5000)
    fun noRightsPreventsServerAddressDiscoveryAndAnyNetworking() {
        ServerSocket(0,1,host).use{server->
            val dummy=Socket(host,server.localPort)
            server.accept().use{backend->
                dummy.use { s ->
                    try {
                        A0ServerAssociatedTransfer(allowLoopbackForLocalTest=true).runOnce(
                            s,"testaccount","owner",
                            reference().copy(rightsReviewedOutsideSoulseek=false),
                            listing(),temp.root
                        )
                        fail("Must reject unlicensed metadata")
                    } catch (_:IllegalArgumentException){}
                    backend.soTimeout=350
                    try {backend.getInputStream().read();fail("No SetWaitPort or GetPeerAddress before gate")}
                    catch (_:java.net.SocketTimeoutException){}
                }
            }
        }
    }

    @Test(timeout=5000)
    fun noCryptographicIdentityClaimAndPrivateRemoteRefusedByDefault() {
        assertFalse(A0PeerRendezvousWire.isRoutableIpv4(byteArrayOf(127,0,0,1)))
        assertTrue(A0PeerRendezvousWire.isRoutableIpv4(byteArrayOf(8,8,8,8)))
        try {A0ServerAssociatedTransfer.requireEndpoint(
            host,allowLoopbackForLocalTest=false)
            fail("Loopback must never be a real peer from the public server")
        }catch(_:IOException){}
        A0ServerAssociatedTransfer.requireEndpoint(host,allowLoopbackForLocalTest=true)
    }

    @Test(timeout=19000)
    fun failedDirectConnectRequestsOneIndirectPAndOnlyAcceptsMatchingPierceToken() {
        ServerSocket(0,1,host).use{fakeServer->
            val pool=Executors.newSingleThreadExecutor()
            try {
                val remote=pool.submit {
                    fakeServer.accept().use{server->
                        server.soTimeout=5500
                        val wait=body(server)
                        assertEquals(2,le32(wait))
                        val fPort=le32(wait)
                        val query=body(server)
                        assertEquals(3,le32(query))
                        assertEquals("owner",field(query))
                        // Port 1 is a deliberately closed direct endpoint.
                        server.getOutputStream().apply {
                            write(frame(3,field("owner")+byteArrayOf(1,0,0,127)+
                                le(1)+le(0)+byteArrayOf(0,0)))
                            flush()
                        }
                        val request=body(server)
                        assertEquals(18,le32(request))
                        val token=le32(request)
                        assertTrue(token>0)
                        assertEquals("owner",field(request))
                        assertEquals("P",field(request))
                        Socket(host,fPort).use{peer->
                            peer.soTimeout=4000
                            peer.getOutputStream().apply {
                                write(le(5)+byteArrayOf(0)+le(token))
                                flush()
                            }
                            val queued=body(peer)
                            assertEquals(43,le32(queued))
                            assertEquals(path,field(queued))
                            peer.getOutputStream().apply {
                                write(frame(40,le(1)+le(1001)+field(path)+
                                    le64(own.size.toLong())))
                                flush()
                            }
                            val accepted=body(peer)
                            assertEquals(41,le32(accepted))
                            assertEquals(1001,le32(accepted))
                            assertEquals(1,accepted.read())
                            Socket(host,fPort).use{file->
                                file.soTimeout=4000
                                file.getOutputStream().apply { write(le(1001)+own);flush() }
                                assertArrayEquals(ByteArray(8),readExactly(file.getInputStream(),8))
                            }
                        }
                    }
                }
                Socket(host,fakeServer.localPort).use{server->
                    val receipt=A0ServerAssociatedTransfer(allowLoopbackForLocalTest=true)
                        .runOnce(server,"testaccount","owner",reference(),listing(),temp.root)
                    assertEquals(digest,receipt.sha256Hex)
                    assertArrayEquals(own,receipt.file.readBytes())
                    remote.get(4,TimeUnit.SECONDS)
                    assertFalse(temp.root.listFiles().orEmpty().any{it.name.endsWith(".partial")})
                }
            } finally { pool.shutdownNow() }
        }
    }

    @Test(timeout=15000)
    fun wrongIndirectPierceTokenAbortsWithoutAnyFile() {
        ServerSocket(0,1,host).use{fakeServer->
            val pool=Executors.newSingleThreadExecutor()
            try {
                val remote=pool.submit {
                    fakeServer.accept().use{server->
                        server.soTimeout=4500
                        val wait=body(server)
                        assertEquals(2,le32(wait))
                        val port=le32(wait)
                        body(server)
                        server.getOutputStream().write(frame(3,field("owner")+
                            byteArrayOf(1,0,0,127)+le(1)+le(0)+byteArrayOf(0,0)))
                        server.getOutputStream().flush()
                        val indirect=body(server)
                        assertEquals(18,le32(indirect))
                        val token=le32(indirect)
                        Socket(host,port).use{peer->
                            peer.getOutputStream().write(le(5)+byteArrayOf(0)+le(token+1))
                            peer.getOutputStream().flush()
                        }
                    }
                }
                Socket(host,fakeServer.localPort).use{server->
                    try {
                        A0ServerAssociatedTransfer(allowLoopbackForLocalTest=true).runOnce(
                            server,"testaccount","owner",reference(),listing(),temp.root
                        )
                        fail("Incorrect PierceFirewall token is not this rendezvous")
                    } catch (_:IOException){}
                    remote.get(4,TimeUnit.SECONDS)
                    assertTrue(temp.root.listFiles().isNullOrEmpty())
                }
            } finally {pool.shutdownNow()}
        }
    }

    @Test(timeout=19000)
    fun cancelDuringPartialFClosesSocketsAndDeletesUnverifiedBytes() {
        ServerSocket(0,1,host).use{fakeServer->
            ServerSocket(0,1,host).use{fakePeer->
                val dataStarted=java.util.concurrent.CountDownLatch(1)
                val releaseSender=java.util.concurrent.CountDownLatch(1)
                val pool=Executors.newFixedThreadPool(2)
                val transfer=A0ServerAssociatedTransfer(allowLoopbackForLocalTest=true)
                try {
                    val remote=pool.submit {
                        fakeServer.accept().use{server->
                            server.soTimeout=5000
                            val wait=body(server)
                            assertEquals(2,le32(wait))
                            val p=le32(wait)
                            body(server)
                            server.getOutputStream().apply{
                                write(frame(3,field("owner")+byteArrayOf(1,0,0,127)+
                                    le(fakePeer.localPort)+le(0)+byteArrayOf(0,0)))
                                flush()
                            }
                            fakePeer.accept().use{peer->
                                peer.soTimeout=5000
                                readInit(peer)
                                body(peer)
                                peer.getOutputStream().apply{
                                    write(frame(40,le(1)+le(117)+field(path)+
                                        le64(own.size.toLong())))
                                    flush()
                                }
                                val response=body(peer)
                                assertEquals(41,le32(response))
                                assertEquals(117,le32(response))
                                assertEquals(1,response.read())
                                Socket(host,p).use{f->
                                    f.getOutputStream().apply{
                                        write(le(117)+own.copyOfRange(0,24))
                                        flush()
                                    }
                                    dataStarted.countDown()
                                    releaseSender.await(4,TimeUnit.SECONDS)
                                }
                            }
                        }
                    }
                    val result=pool.submit {
                        Socket(host,fakeServer.localPort).use{server->
                            transfer.runOnce(server,"testaccount","owner",
                                reference(),listing(),temp.root)
                        }
                    }
                    assertTrue(dataStarted.await(7,TimeUnit.SECONDS))
                    transfer.cancel()
                    releaseSender.countDown()
                    try {
                        result.get(5,TimeUnit.SECONDS)
                        fail("Cancellation must not return a verified file")
                    }catch(error:java.util.concurrent.ExecutionException){
                        assertTrue(error.cause is IOException)
                    }
                    remote.get(5,TimeUnit.SECONDS)
                    assertTrue(temp.root.listFiles().isNullOrEmpty())
                }finally{
                    transfer.cancel()
                    releaseSender.countDown()
                    pool.shutdownNow()
                }
            }
        }
    }

    private fun reference()=A0OpenAudioCandidateGate.ReviewedReference(
        "https://example.org/test/owned","CC0-1.0","owner-tone.wav",own.size.toLong(),digest,true
    )
    private fun listing()=A0OpenAudioCandidateGate.PublicPeerListing(
        path,own.size.toLong(),false,true
    )
    private fun frame(code:Int,payload:ByteArray)=(le(code)+payload).let{le(it.size)+it}
    private fun field(s:String)=s.toByteArray(Charsets.US_ASCII).let{le(it.size)+it}
    private fun field(input:ByteArrayInputStream):String{
        val n=le32(input);val b=ByteArray(n);assertEquals(n,input.read(b))
        return String(b,Charsets.US_ASCII)
    }
    private fun body(s:Socket):ByteArrayInputStream{
        val n=le32(s.getInputStream())
        if(n !in 4..4096)throw IOException("invalid body")
        return ByteArrayInputStream(readExactly(s.getInputStream(),n))
    }
    private fun readInit(s:Socket):ByteArrayInputStream{
        val n=le32(s.getInputStream());if(n !in 1..4096)throw IOException()
        return ByteArrayInputStream(readExactly(s.getInputStream(),n))
    }
    private fun readExactly(i:InputStream,n:Int):ByteArray{
        val b=ByteArray(n);var k=0
        while(k<n){val count=i.read(b,k,n-k);if(count<=0)throw EOFException();k+=count}
        return b
    }
    private fun le32(s:InputStream):Int{
        val b=readExactly(s,4)
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun le(n:Int)=ByteArray(4){(n ushr (8*it)).toByte()}
    private fun le64(n:Long)=ByteArray(8){(n ushr (8*it)).toByte()}
    private fun wav():ByteArray{
        val h=ByteArray(44)
        fun ascii(i:Int,s:String){s.toByteArray(Charsets.US_ASCII).copyInto(h,i)}
        fun u32(i:Int,n:Int){repeat(4){h[i+it]=(n ushr (8*it)).toByte()}}
        fun u16(i:Int,n:Int){repeat(2){h[i+it]=(n ushr (8*it)).toByte()}}
        ascii(0,"RIFF");u32(4,36+256);ascii(8,"WAVE")
        ascii(12,"fmt ");u32(16,16);u16(20,1);u16(22,1)
        u32(24,8000);u32(28,16000);u16(32,2);u16(34,16)
        ascii(36,"data");u32(40,256)
        return h+ByteArray(256){(it and 0x7f).toByte()}
    }
}
