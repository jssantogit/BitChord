package com.music.bitchord.acquisition.a0

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Regression: a P/F failure must identify the last safe stage, not generic server I/O. */
class A0TransferPhaseTest {
    private val host=InetAddress.getByAddress(byteArrayOf(127,0,0,1))
    @Test(timeout=8000)
    fun closingSelectedServerAfterGetPeerAddressRequestProducesRedactedPhase() {
        val events=mutableListOf<A0ServerAssociatedTransfer.Phase>()
        ServerSocket(0,1,host).use {listener ->
            val pool=Executors.newSingleThreadExecutor()
            try {
                val server=pool.submit {
                    listener.accept().use {peer ->
                        peer.soTimeout=3000
                        val first=readFrame(peer)
                        assertEquals(2,first)
                        val second=readFrame(peer)
                        assertEquals(3,second)
                        // This recreates an early server-side failure, not a file failure.
                    }
                }
                Socket(host,listener.localPort).use {authenticated ->
                    val listing=A0OpenAudioCandidateGate.PublicPeerListing(
                        "Music\\owned-tone.wav",2048,false,true
                    )
                    val transfer=A0ServerAssociatedTransfer(allowLoopbackForLocalTest=true)
                    try {
                        transfer.runObservedOnce(
                            authenticated,"testaccount","owner",listing,
                            java.nio.file.Files.createTempDirectory("a0-test").toFile(),
                            onPhase={events.add(it)}
                        )
                        fail("Closed authenticated server must not complete transfer")
                    }catch (_:IOException) {
                        assertTrue(events.contains(A0ServerAssociatedTransfer.Phase.GATE_PASSED))
                        assertTrue(events.contains(A0ServerAssociatedTransfer.Phase.PEER_ADDRESS_REQUESTED))
                        assertFalse(events.contains(A0ServerAssociatedTransfer.Phase.P_ESTABLISHED))
                        assertFalse(events.contains(A0ServerAssociatedTransfer.Phase.F_ESTABLISHED))
                    }
                }
                server.get(3,TimeUnit.SECONDS)
            }finally{pool.shutdownNow()}
        }
    }
    private fun readFrame(socket:Socket):Int{
        val i=socket.getInputStream()
        fun le():Int {
            val b=ByteArray(4)
            var o=0
            while(o<4) {val n=i.read(b,o,4-o);if(n<1)throw IOException("incomplete");o+=n}
            return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
                ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
        }
        val length=le()
        if(length !in 4..1024)throw IOException("frame size")
        val code=le()
        val body=ByteArray(length-4)
        var o=0
        while(o<body.size){val n=i.read(body,o,body.size-o);if(n<1)throw IOException();o+=n}
        return code
    }
}
