package com.music.bitchord.acquisition.a0

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress

/**
 * A0-only bounded decoder for incoming server code-18 connection offers of
 * type F. Does not accept unsolicited P or D messages as file connections.
 *
 * A matching IP and username are correlations supplied by legacy plain-TCP
 * Soulseek, NOT cryptographic peer authentication. Caller also validates the
 * P40/F raw transfer token before receiving any bytes.
 */
internal object A0IndirectFileOfferWire {
    private const val MAX_SERVER_FRAME = 1_048_576
    private const val MAX_FIELD_BYTES = 128

    /** Sanitized error class; never include remote offer fields in logs. */
    class MalformedFileOffer : IOException("A0 invalid or unsupported F offer")

    sealed class Event {
        data class Offer(val address: InetAddress, val port: Int, val token: Int) : Event()
        object Unrelated : Event()
    }

    fun readServerEvent(
        input: InputStream,
        selectedPeerUsername: String,
        selectedServerPeerAddress: InetAddress
    ): Event {
        val len=le32(input)
        if(len !in 4..MAX_SERVER_FRAME) malformed()
        val code=le32(input)
        val body=ByteArrayInputStream(exact(input,len-4))
        if(code==41) malformed() // Replaced login: never retain a file candidate.
        if(code!=18) return Event.Unrelated
        val username=string(body)
        val connType=string(body)
        // This authenticated-server frame is already completely consumed.
        // A late SEARCH/P offer belongs to a different flow, and its optional
        // fields should not be allowed to abort the selected upload's F wait.
        // Never follow its endpoint or accept it as an F channel.
        if(connType!="F" || username!=selectedPeerUsername)
            return Event.Unrelated
        val ip=exact(body,4).reversedArray()
        val port=le32(body)
        val token=le32(body)
        // Legacy clients omit the optional modern nine-byte tail.
        // Both complete shapes are valid, but partial tails are never safe.
        val tailBytes=body.available()
        if(tailBytes != 0 && tailBytes != 9)malformed()
        val privileged=if(tailBytes == 9)body.read() else 0
        val obfuscated=if(tailBytes == 9)le32(body) else 0
        val obfuscatedPort=if(tailBytes == 9)le32(body) else 0
        if(body.available()!=0 || privileged !in 0..1 ||
            port !in 1..65535 || token<=0 ||
            obfuscated!=0 || obfuscatedPort!=0
        ) malformed()

        // Search-related code-18 offers may arrive after selection, even
        // though the selected user has already queued a single P/F request.
        // Ignore unrelated senders without ever opening their sockets.
        // A claimed offer FROM the selected peer at the wrong server-address
        // still fails closed; never follow its endpoint.
        if(!ip.contentEquals(selectedServerPeerAddress.address)) malformed()
        return Event.Offer(
            address=InetAddress.getByAddress(ip),port=port,token=token
        )
    }

    private fun string(s:InputStream):String {
        val n=le32(s)
        if(n !in 1..MAX_FIELD_BYTES)malformed()
        return String(exact(s,n),Charsets.UTF_8)
    }
    private fun le32(s:InputStream):Int {
        val b=exact(s,4)
        return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) or
            ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 24)
    }
    private fun exact(s:InputStream,n:Int):ByteArray {
        val b=ByteArray(n);var p=0
        while(p<n) {
            val r=s.read(b,p,n-p)
            if(r<=0)throw EOFException("A0 incomplete server file offer")
            p+=r
        }
        return b
    }
    private fun malformed():Nothing=throw MalformedFileOffer()
}
