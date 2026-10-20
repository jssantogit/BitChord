package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream

class A0IndirectSearchWireTest {
    @Test fun serverConnectToPeerPHasPublicReversedIpv4AndBoundedFields() {
        val event = requireNotNull(A0IndirectSearchWire.readServerEvent(ByteArrayInputStream(offer())))
        assertEquals("peer", event.username)
        assertEquals("8.8.4.4", event.address.hostAddress)
        assertEquals(4815, event.port)
        assertEquals(31415, event.token)
    }

    @Test fun ignoresOtherServerFramesAndKeepsFrameAlignment() {
        val stream = ByteArrayInputStream(frame(64, byteArrayOf(1, 2)) + offer())
        val ignored = mutableListOf<Int>()
        assertNull(A0IndirectSearchWire.readServerEvent(stream) { ignored.add(it) })
        assertEquals(listOf(64), ignored)
        assertEquals(31415, A0IndirectSearchWire.readServerEvent(stream)!!.token)
    }

    @Test fun rejectsFChannelsPrivateAddressesObfuscationAndZeroToken() {
        val unsafe = listOf(
            offer(type="F"), offer(ip=byteArrayOf(1, 0, 0, 127)),
            offer(ip=byteArrayOf(1, 1, 168.toByte(), 192.toByte())),
            offer(ip=byteArrayOf(1, 64, 64, 100)),
            offer(ip=byteArrayOf(8, 8, 8, 10)),
            offer(ip=byteArrayOf(1, 2, 3, 224.toByte())),
            offer(token=0), offer(port=0), offer(obfuscation=1),
            offer(extra=byteArrayOf(7)),
            offer(username="p".repeat(31))
        )
        for (packet in unsafe) reject { A0IndirectSearchWire.readServerEvent(ByteArrayInputStream(packet)) }
    }

    @Test fun malformedOrTooLargeServerEventFailsClosed() {
        for (packet in listOf(le(1), le(1_048_577), le(30) + le(18) + byteArrayOf(1))) {
            reject { A0IndirectSearchWire.readServerEvent(ByteArrayInputStream(packet)) }
        }
        reject { A0IndirectSearchWire.readServerEvent(ByteArrayInputStream(frame(41, byteArrayOf()))) }
    }

    @Test fun rejectedIndirectOfferDoesNotLoseAlignmentForSubsequentFrame() {
        val source = ByteArrayInputStream(offer(type="F") + offer())
        try {
            A0IndirectSearchWire.readServerEvent(source)
            fail("Invalid P negotiation was accepted")
        } catch (_: A0IndirectSearchWire.RejectedPeerOffer) {
            // A complete rejected frame must not poison the remaining stream.
        }
        assertEquals(31415, A0IndirectSearchWire.readServerEvent(source)!!.token)
    }

    @Test fun ignoredEventCallbackReceivesOnlyCodeNotPayload() {
        val source = ByteArrayInputStream(frame(102, byteArrayOf(27, 45, 87)))
        val codes = mutableListOf<Int>()
        assertNull(A0IndirectSearchWire.readServerEvent(source, codes::add))
        assertEquals(listOf(102), codes)
    }

    @Test fun pierceFirewallHasSingleByteInitCodeAndTokenButDoesNotRequestFile() {
        assertArrayEquals(le(5) + byteArrayOf(0) + le(31415), A0IndirectSearchWire.pierceFirewall(31415))
        try { A0IndirectSearchWire.pierceFirewall(0); fail("zero token") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun indirectResponseUsesSearchTokenAndServerClaimedUsernameWithoutLeakingMetadata() {
        val result = A0IndirectSearchWire.readIndirectReply(
            ByteArrayInputStream(searchReply("peer", 72, "secret\\Ringingsound.wav")), 72, "peer"
        )
        assertEquals(1, result.untrustedFilenameMatches)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("peer"))
        for (packet in listOf(
            searchReply("peer", 73, "secret\\Ringingsound.wav"),
            searchReply("other", 72, "secret\\Ringingsound.wav"),
            le(1_500_000),
            searchReply("peer", 72, "secret\\different.wav").copyOf(7)
        )) reject { A0IndirectSearchWire.readIndirectReply(ByteArrayInputStream(packet), 72, "peer") }
        assertEquals(0, A0IndirectSearchWire.readIndirectReply(
            ByteArrayInputStream(searchReply("peer", 72, "other.wav")), 72, "peer"
        ).untrustedFilenameMatches)
    }

    private fun offer(
        username:String="peer", type:String="P", ip:ByteArray=byteArrayOf(4,4,8,8),
        port:Int=4815, token:Int=31415, obfuscation:Int=0,
        extra:ByteArray=byteArrayOf()
    ):ByteArray = frame(18,
        field(username) + field(type) + ip + le(port) + le(token) + byteArrayOf(0) +
            le(obfuscation) + le(0) + extra)

    private fun searchReply(user:String, token:Int, filename:String):ByteArray {
        val payload = field(user) + le(token) + le(1) + byteArrayOf(1) + field(filename) +
            long(27000) + field("wav") + le(0) + byteArrayOf(1) + le(120000) +
            le(0) + le(0) + le(0)
        val zipped = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(payload) }
        }.toByteArray()
        return frame(9, zipped)
    }
    private fun frame(code:Int, body:ByteArray)=le(4+body.size)+le(code)+body
    private fun le(n:Int)=ByteArray(4){(n ushr (it*8)).toByte()}
    private fun long(n:Long)=ByteArray(8){(n ushr (it*8)).toByte()}
    private fun field(s:String):ByteArray = s.toByteArray(Charsets.UTF_8).let { le(it.size)+it }
    private fun reject(f:()->Unit) {
        try { f(); fail("must reject") }
        catch (_:IOException) {}
    }
}
