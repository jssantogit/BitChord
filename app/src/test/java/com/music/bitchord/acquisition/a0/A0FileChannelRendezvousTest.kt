package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** JVM socket tests, 127.0.0.1 ONLY: direct F and server-relayed indirect F. */
class A0FileChannelRendezvousTest {
    private val host=InetAddress.getByAddress(byteArrayOf(127,0,0,1))

    @Test(timeout=9500)
    fun serverFOfferEstablishesOutgoingSocketAndPierceTokenBeforeFileBytes() {
        ServerSocket(0,1,host).use {fakeServer ->
            ServerSocket(0,1,host).use {fakeUploader ->
                ServerSocket(0,1,host).use {fileListener ->
                    val pool=Executors.newFixedThreadPool(2)
                    try {
                        val uploader=pool.submit {
                            fakeUploader.accept().use { peer ->
                                peer.soTimeout=4000
                                val header=readExactly(peer,9)
                                assertArrayEquals(le(5)+byteArrayOf(0)+le(443),header)
                                peer.getOutputStream().write(le(721))
                                peer.getOutputStream().flush()
                            }
                        }
                        val server=pool.submit {
                            fakeServer.accept().use { channel ->
                                channel.soTimeout=4500
                                channel.getOutputStream().write(fileOffer(
                                    "source_peer",filePort=fakeUploader.localPort,token=443
                                ))
                                channel.getOutputStream().flush()
                                Thread.sleep(350)
                            }
                        }
                        Socket(host,fakeServer.localPort).use {loggedInServer ->
                            val socket=A0FileChannelRendezvous.awaitFileSocket(
                                server=loggedInServer,
                                listener=fileListener,
                                selectedPeerUsername="source_peer",
                                selectedPeerAddress=host,
                                absoluteDeadlineNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(5),
                                allowLoopbackForLocalTest=true
                            )
                            socket.use { f ->
                                assertEquals(host,f.inetAddress)
                                assertArrayEquals(le(721),readExactly(f,4))
                            }
                        }
                        uploader.get(3,TimeUnit.SECONDS);server.get(3,TimeUnit.SECONDS)
                    }finally{pool.shutdownNow()}
                }
            }
        }
    }

    @Test(timeout=9500)
    fun normalDirectFConnectionWorksWithoutAnyServerOffer() {
        ServerSocket(0,1,host).use {fakeServer ->
            ServerSocket(0,1,host).use {fileListener ->
                val pool=Executors.newSingleThreadExecutor()
                try {
                    val server=pool.submit {
                        fakeServer.accept().use {
                            Socket(host,fileListener.localPort).use { uploader ->
                                uploader.getOutputStream().write(le(901))
                                uploader.getOutputStream().flush()
                                Thread.sleep(350)
                            }
                        }
                    }
                    Socket(host,fakeServer.localPort).use {s ->
                        A0FileChannelRendezvous.awaitFileSocket(
                            s,fileListener,"source_peer",host,
                            System.nanoTime()+TimeUnit.SECONDS.toNanos(4),
                            allowLoopbackForLocalTest=true
                        ).use{file ->
                            assertArrayEquals(le(901),readExactly(file,4))
                        }
                    }
                    server.get(4,TimeUnit.SECONDS)
                }finally{pool.shutdownNow()}
            }
        }
    }

    @Test(timeout=7500)
    fun partialServerFOfferTimeoutFailsClosedEvenIfDirectPeerAppearsLater() {
        ServerSocket(0,1,host).use{fakeServer ->
            ServerSocket(0,1,host).use {fileListener ->
                val pool=Executors.newSingleThreadExecutor()
                try {
                    val server=pool.submit {
                        fakeServer.accept().use{s ->
                            s.getOutputStream().write(byteArrayOf(12,0))
                            s.getOutputStream().flush()
                            Thread.sleep(750)
                        }
                    }
                    Socket(host,fakeServer.localPort).use{s ->
                        try {
                            A0FileChannelRendezvous.awaitFileSocket(
                                s,fileListener,"source_peer",host,
                                System.nanoTime()+TimeUnit.SECONDS.toNanos(2),
                                allowLoopbackForLocalTest=true
                            )
                            fail("Must not resume after partially read framing")
                        }catch (_:IOException){}
                    }
                    server.get(3,TimeUnit.SECONDS)
                }finally{pool.shutdownNow()}
            }
        }
    }

    @Test(timeout=7500)
    fun deadlineAndCancellationReleaseListenerWithoutAFile() {
        ServerSocket(0,1,host).use{fakeServer ->
            ServerSocket(0,1,host).use{listener ->
                val pool=Executors.newSingleThreadExecutor()
                try {
                    val server=pool.submit {
                        fakeServer.accept().use{Thread.sleep(1500)}
                    }
                    Socket(host,fakeServer.localPort).use{s ->
                        val start=System.nanoTime()
                        try {
                            A0FileChannelRendezvous.awaitFileSocket(
                                s,listener,"source_peer",host,
                                start+TimeUnit.MILLISECONDS.toNanos(850),
                                allowLoopbackForLocalTest=true
                            )
                            fail("No peer must timeout")
                        }catch (_:SocketTimeoutException){}
                        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2400)
                    }
                    server.get(3,TimeUnit.SECONDS)
                }finally{pool.shutdownNow()}
            }
        }
    }

    private fun fileOffer(name:String,filePort:Int,token:Int):ByteArray {
        val body=le(18)+field(name)+field("F")+byteArrayOf(1,0,0,127)+
            le(filePort)+le(token)+byteArrayOf(0)+le(0)+le(0)
        return le(body.size)+body
    }
    private fun field(s:String)=s.toByteArray(Charsets.UTF_8).let{le(it.size)+it}
    private fun le(v:Int)=ByteArray(4){(v ushr (8*it)).toByte()}
    private fun readExactly(s:Socket,n:Int):ByteArray{
        val bytes=ByteArray(n);var i=0
        while(i<n){val r=s.getInputStream().read(bytes,i,n-i);if(r<=0)throw EOFException();i+=r}
        return bytes
    }
}
