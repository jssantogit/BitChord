package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** TDD contract: never mix inbound code-18 offers with our outbound code-18 request. */
class A0PeerRendezvousWireTest {
    @Test fun clientAsksServerForAddressBeforeContactingOneChosenPeer() {
        val request = A0PeerRendezvousWire.getPeerAddress("peer_name")
        val r=ByteArrayInputStream(request)
        assertEquals(request.size-4,le32(r))
        assertEquals(3,le32(r))
        assertEquals("peer_name",str(r))
        assertEquals(-1,r.read())
    }

    @Test fun parsesServerAddressWithReversedIpAndUInt16ObfuscatedPort() {
        val address=serverAddress("peer_name",byteArrayOf(8,8,4,4),41234,0,0)
        val event=A0PeerRendezvousWire.readServerEvent(
            ByteArrayInputStream(address),"peer_name",pendingToken=44
        )
        assertTrue(event is A0PeerRendezvousWire.ServerEvent.Address)
        val x=event as A0PeerRendezvousWire.ServerEvent.Address
        assertEquals("8.8.4.4",x.address.hostAddress)
        assertEquals(41234,x.port)
        assertFalse(x.obfuscated)
    }

    @Test fun loopbackIsParsedForOwnedFixtureButTransportGateMustRejectItInPublicMode() {
        val event=A0PeerRendezvousWire.readServerEvent(
            ByteArrayInputStream(serverAddress(
                "peer_name",byteArrayOf(127,0,0,1),41234,0,0
            )), "peer_name", 41
        )
        assertTrue(event is A0PeerRendezvousWire.ServerEvent.Address)
        val parsed=event as A0PeerRendezvousWire.ServerEvent.Address
        assertFalse(A0PeerRendezvousWire.isRoutableIpv4(parsed.address.address))
        try {
            A0ServerAssociatedTransfer.requireEndpoint(
                parsed.address,allowLoopbackForLocalTest=false
            )
            fail("Public runtime must reject loopback despite successfully framed response")
        } catch (_:IOException){}
    }

    @Test fun mismatchedUsernameAndUnsupportedObfuscationMustFailClosed() {
        val wrong=serverAddress("another_user",byteArrayOf(8,8,4,4),41234,0,0)
        val mismatch=A0PeerRendezvousWire.readServerEvent(ByteArrayInputStream(wrong),"peer_name",44)
        assertTrue(mismatch is A0PeerRendezvousWire.ServerEvent.Unrelated)
        val obfuscated=serverAddress("peer_name",byteArrayOf(8,8,4,4),41234,1,1234)
        val blocked=A0PeerRendezvousWire.readServerEvent(ByteArrayInputStream(obfuscated),"peer_name",44)
        assertTrue(blocked is A0PeerRendezvousWire.ServerEvent.UnsupportedAddress)
    }

    @Test fun directPInitUsesLocalAccountAndPeerConnectionType() {
        val packet=A0PeerRendezvousWire.directPeerInit("our_test_account")
        val r=ByteArrayInputStream(packet)
        assertEquals(packet.size-4,le32(r))
        assertEquals(1,r.read())
        assertEquals("our_test_account",str(r))
        assertEquals("P",str(r))
        assertEquals(0,le32(r))
        assertEquals(-1,r.read())
    }

    @Test fun indirectRequestContainsTokenUsernamePAndNoAddressOrSecret() {
        val packet=A0PeerRendezvousWire.connectToPeer(375,"peer_name")
        val r=ByteArrayInputStream(packet)
        assertEquals(packet.size-4,le32(r))
        assertEquals(18,le32(r))
        assertEquals(375,le32(r))
        assertEquals("peer_name",str(r))
        assertEquals("P",str(r))
        assertEquals(-1,r.read())
    }

    @Test fun unrelatedFramesAreConsumedAndCantConnectRequiresExactToken() {
        val ignored=A0PeerRendezvousWire.readServerEvent(
            ByteArrayInputStream(frame(64,byteArrayOf(1,2,3))),"peer_name",5)
        assertTrue(ignored is A0PeerRendezvousWire.ServerEvent.Unrelated)
        val wrong=A0PeerRendezvousWire.readServerEvent(
            ByteArrayInputStream(frame(1001,le(67))),"peer_name",66)
        assertTrue(wrong is A0PeerRendezvousWire.ServerEvent.Unrelated)
        val failure=A0PeerRendezvousWire.readServerEvent(
            ByteArrayInputStream(frame(1001,le(66))),"peer_name",66)
        assertTrue(failure is A0PeerRendezvousWire.ServerEvent.PeerUnreachable)
    }

    @Test fun peerFirewallResponseMustMatchOnlyTheLocallyIssuedToken() {
        val msg=A0PeerRendezvousWire.readIndirectPierce(
            ByteArrayInputStream(le(5)+byteArrayOf(0)+le(91)),expectedToken=91
        )
        assertTrue(msg)
        reject {
            A0PeerRendezvousWire.readIndirectPierce(
                ByteArrayInputStream(le(5)+byteArrayOf(0)+le(93)),expectedToken=91
            )
        }
        reject {
            A0PeerRendezvousWire.readIndirectPierce(
                ByteArrayInputStream(le(5)+byteArrayOf(1)+le(91)),expectedToken=91
            )
        }
    }

    @Test fun malformedAndOversizedServerFramesAreRejectedBeforeNetworkUse() {
        for (bytes in listOf(
            le(1)+byteArrayOf(3),
            le(2_000_000)+le(3),
            le(6)+le(3)+byteArrayOf(9,9),
            frame(3,le(500)+byteArrayOf(1,2,3))
        )) reject { A0PeerRendezvousWire.readServerEvent(ByteArrayInputStream(bytes),"peer_name",77) }
    }

    @Test fun emptyInvalidAndOversizedUsernamesCannotIssueRequests() {
        for (name in listOf("", "hi friend", "a".repeat(31), "../", "mañana", "bad\npeer")) {
            try { A0PeerRendezvousWire.getPeerAddress(name);fail("Bad peer name") }
            catch (_:IllegalArgumentException){}
        }
        for (token in listOf(0,-1)) {
            try { A0PeerRendezvousWire.connectToPeer(token,"peer_name");fail("Bad token") }
            catch (_:IllegalArgumentException){}
        }
    }

    private fun serverAddress(user:String,ip:ByteArray,port:Int,obf:Int,obfPort:Int):ByteArray {
        return frame(3,field(user)+ip.reversedArray()+le(port)+le(obf)+
            byteArrayOf(obfPort.toByte(),(obfPort ushr 8).toByte()))
    }
    private fun frame(code:Int,payload:ByteArray):ByteArray{
        val b=le(code)+payload
        return le(b.size)+b
    }
    private fun field(s:String):ByteArray{
        val b=s.toByteArray(Charsets.US_ASCII)
        return le(b.size)+b
    }
    private fun str(i:ByteArrayInputStream):String{
        val n=le32(i);val b=ByteArray(n)
        assertEquals(n,i.read(b));return String(b,Charsets.US_ASCII)
    }
    private fun le(n:Int)=ByteArray(4){(n ushr (8*it)).toByte()}
    private fun le32(s:ByteArrayInputStream):Int{
        val b=ByteArray(4);assertEquals(4,s.read(b))
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun reject(f:()->Unit) {
        try { f();fail("Must fail closed") } catch (_:IOException){}
    }
}
