package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

/** Server code 18 F is a separate indirect FILE channel, never a P peer message. */
class A0IndirectFileOfferWireTest {
    private val local = InetAddress.getByAddress(byteArrayOf(127,0,0,1))

    @Test fun acceptsOnlyFileOfferFromExpectedPeerAndSameAddress() {
        val event=A0IndirectFileOfferWire.readServerEvent(
            ByteArrayInputStream(frame("owned_peer","F",byteArrayOf(1,0,0,127),38891,84)),
            "owned_peer",local
        )
        assertTrue(event is A0IndirectFileOfferWire.Event.Offer)
        val offer=event as A0IndirectFileOfferWire.Event.Offer
        assertEquals(38891,offer.port)
        assertEquals(84,offer.token)
        assertEquals(local,offer.address)
    }

    @Test fun acceptsLegacyOffersWithoutModernNineByteTail() {
        val full=frame("owned_peer","F",byteArrayOf(1,0,0,127),38891,84)
        val legacy=full.copyOf(full.size-9).also { bytes ->
            val newLength=bytes.size-4
            for(i in 0..3) bytes[i]=(newLength ushr (8*i)).toByte()
        }
        val offered=A0IndirectFileOfferWire.readServerEvent(
            ByteArrayInputStream(legacy),"owned_peer",local)
        assertTrue(offered is A0IndirectFileOfferWire.Event.Offer)
        assertEquals(38891,(offered as A0IndirectFileOfferWire.Event.Offer).port)
    }

    @Test fun rejectsPartialModernTailEvenIfIdentityIsCorrect() {
        val full=frame("owned_peer","F",byteArrayOf(1,0,0,127),38891,84)
        val broken=full.copyOf(full.size-4).also { bytes ->
            val newLength=bytes.size-4
            for(i in 0..3) bytes[i]=(newLength ushr (8*i)).toByte()
        }
        try {
            A0IndirectFileOfferWire.readServerEvent(ByteArrayInputStream(broken),"owned_peer",local)
            fail("Partial tail must not become valid F offer")
        }catch(_:IOException){}
    }

    @Test fun wrongPeerWrongAddressAndUnexpectedTypeCannotAuthorizeFileSocket() {
        for (input in listOf(
            frame("someone_else","F",byteArrayOf(1,0,0,127),38891,84),
            frame("owned_peer","F",byteArrayOf(2,0,0,127),38891,84),
            frame("owned_peer","P",byteArrayOf(1,0,0,127),38891,84),
            frame("owned_peer","D",byteArrayOf(1,0,0,127),38891,84),
            frame("owned_peer","F",byteArrayOf(1,0,0,127),0,84),
            frame("owned_peer","F",byteArrayOf(1,0,0,127),38891,0),
            frame("owned_peer","F",byteArrayOf(1,0,0,127),38891,84,1)
        )) {
            try {
                val event=A0IndirectFileOfferWire.readServerEvent(
                    ByteArrayInputStream(input),"owned_peer",local
                )
                assertFalse("Wrong type must not be offered",event is A0IndirectFileOfferWire.Event.Offer)
            } catch (_: IOException) { }
        }
    }

    @Test fun completeUnrelatedPeerFOffersAreIgnoredWithoutSelectingTheirSocket() {
        val event=A0IndirectFileOfferWire.readServerEvent(
            ByteArrayInputStream(frame(
                "other_peer","F",byteArrayOf(1,0,0,127),38891,84
            )),"owned_peer",local
        )
        assertEquals(A0IndirectFileOfferWire.Event.Unrelated,event)
    }

    @Test fun matchingPeerWithUnexpectedAddressRemainsRejected() {
        try {
            A0IndirectFileOfferWire.readServerEvent(
                ByteArrayInputStream(frame(
                    "owned_peer","F",byteArrayOf(2,0,0,127),38891,84
                )),"owned_peer",local
            )
            fail("A different IP must not authorize selected peer's F socket")
        }catch (_:IOException){}
    }

    @Test fun ignoresOtherCompleteServerFramesButFailsOnTruncatedOrOversized() {
        assertEquals(A0IndirectFileOfferWire.Event.Unrelated,
            A0IndirectFileOfferWire.readServerEvent(
                ByteArrayInputStream(le(6)+le(64)+byteArrayOf(7,6)),
                "owned_peer",local
            ))
        for (bytes in listOf(
            le(1)+byteArrayOf(3),
            le(1_500_000)+le(18),
            frame("owned_peer","F",byteArrayOf(1,0,0,127),38891,84).copyOf(14)
        )) {
            try {
                A0IndirectFileOfferWire.readServerEvent(
                    ByteArrayInputStream(bytes),"owned_peer",local)
                fail("Malformed F event must not be accepted")
            } catch (_:IOException) { }
        }
    }

    private fun frame(username:String,type:String,ip:ByteArray,port:Int,token:Int,
        obfuscation:Int=0):ByteArray {
        val payload=field(username)+field(type)+ip+le(port)+le(token)+byteArrayOf(0)+
            le(obfuscation)+le(0)
        val body=le(18)+payload
        return le(body.size)+body
    }
    private fun field(s:String)=s.toByteArray(Charsets.UTF_8).let{le(it.size)+it}
    private fun le(v:Int)=ByteArray(4){(v ushr (it*8)).toByte()}
}
