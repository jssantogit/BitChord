package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** The UI must not leave server-directed search offers unread while user selects a file. */
class A0SelectionServerDrainTest {
    private val loopback=InetAddress.getByAddress(byteArrayOf(127,0,0,1))

    @Test(timeout=7000)
    fun drainsSearchOffersDuringManualChoiceThenLeavesPeerAddressReplyIntact() {
        val choices=LinkedBlockingQueue<Int>(1)
        val manyOffers=384
        ServerSocket(0,1,loopback).use { listener ->
            val workers=Executors.newSingleThreadExecutor()
            try {
                val fakeServer=workers.submit {
                    listener.accept().use { remote ->
                        remote.soTimeout=3000
                        remote.getOutputStream().apply {
                            repeat(manyOffers) { write(frame(18,byteArrayOf(1,2,3,4))) }
                            flush()
                        }
                        // The real P/F owner sends GetPeerAddress after selection.
                        val size=readLe32(remote.getInputStream())
                        assertEquals(3,readLe32(remote.getInputStream()))
                        assertEquals(size-4,readFully(remote.getInputStream(),size-4).size)
                        remote.getOutputStream().apply {
                            write(frame(3,field("owner")+
                                byteArrayOf(1,0,0,127)+le(4444)+le(0)+byteArrayOf(0,0)))
                            flush()
                        }
                    }
                }
                Socket(loopback,listener.localPort).use { client ->
                    var observed=0
                    val choice=A0SelectionServerDrain.awaitChoice(
                        client,choices,3000,{false},
                        onFrameDrained={
                            observed++
                            if(observed==manyOffers)choices.offer(0)
                        }
                    )
                    assertEquals(0,choice.index)
                    assertEquals(manyOffers,choice.discardedFrames)
                    client.getOutputStream().apply {
                        write(A0PeerRendezvousWire.getPeerAddress("owner"));flush()
                    }
                    val reply=A0PeerRendezvousWire.readServerEvent(
                        client.getInputStream(),"owner"
                    )
                    assertTrue("GetPeerAddress must not be buried by old search offers",
                        reply is A0PeerRendezvousWire.ServerEvent.Address)
                }
                fakeServer.get(3,TimeUnit.SECONDS)
            }finally{workers.shutdownNow()}
        }
    }

    @Test(timeout=6500)
    fun partialServerOfferDuringManualChoiceResumesWithoutBreakingNextFrame() {
        val choices=LinkedBlockingQueue<Int>(1)
        ServerSocket(0,1,loopback).use { listener ->
            val workers=Executors.newSingleThreadExecutor()
            try {
                val fakeServer=workers.submit {
                    listener.accept().use { remote ->
                        val first=frame(18,byteArrayOf(1,2,3,4))
                        remote.getOutputStream().apply {
                            write(first,0,3)
                            flush()
                            Thread.sleep(920) // Exceeds former 750ms frame timeout.
                            write(first,3,first.size-3)
                            write(frame(64,byteArrayOf(5,6)))
                            flush()
                        }
                        Thread.sleep(100)
                    }
                }
                Socket(loopback,listener.localPort).use { client ->
                    var drained=0
                    val choice=A0SelectionServerDrain.awaitChoice(
                        client,choices,3000,{false},
                        onFrameDrained={ if(++drained==2) choices.offer(4) })
                    assertEquals(4,choice.index)
                    assertEquals(2,choice.discardedFrames)
                }
                fakeServer.get(3,TimeUnit.SECONDS)
            }finally {workers.shutdownNow()}
        }
    }

    @Test(timeout=4000)
    fun partialServerFrameTimeoutFailsClosedInsteadOfDesynchronizingStream() {
        ServerSocket(0,1,loopback).use { listener ->
            val workers=Executors.newSingleThreadExecutor()
            try {
                val fakeServer=workers.submit {
                    listener.accept().use { remote ->
                        remote.getOutputStream().apply {
                            write(le(8)+le(18)+byteArrayOf(1));flush()
                        }
                        Thread.sleep(1350)
                    }
                }
                Socket(loopback,listener.localPort).use { client ->
                    try {
                        A0SelectionServerDrain.awaitChoice(
                            client,LinkedBlockingQueue<Int>(1),1000,{false}
                        )
                        fail("Incomplete framing must never be reused for P/F")
                    }catch(_:IOException){}
                }
                fakeServer.get(2,TimeUnit.SECONDS)
            }finally{workers.shutdownNow()}
        }
    }

    private fun frame(code:Int,payload:ByteArray)=
        (le(code)+payload).let { le(it.size)+it }
    private fun field(text:String)=text.toByteArray(Charsets.US_ASCII)
        .let{ le(it.size)+it }
    private fun le(n:Int)=ByteArray(4){(n ushr (it*8)).toByte()}
    private fun readLe32(input:java.io.InputStream):Int {
        val b=readFully(input,4)
        return (b[0].toInt() and 255) or
            ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or
            ((b[3].toInt() and 255) shl 24)
    }
    private fun readFully(input:java.io.InputStream,n:Int):ByteArray {
        val result=ByteArray(n);var index=0
        while(index<n) {val k=input.read(result,index,n-index);if(k<1)throw IOException();index+=k}
        return result
    }
}
