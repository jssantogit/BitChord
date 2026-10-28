package com.music.bitchord.acquisition.a0

/**
 * Structural fields read from the LOCAL file, never from Soulseek offers.
 * This is deliberately not an audio fingerprint or full bitstream decoder.
 * A future automatic publisher still needs the Android extraction check.
 */
internal object A0LocalAudioHeaderEvidence {
    data class Info(
        val codec:A0CanonicalAcquisitionIntent.Codec,
        val sampleRateHz:Int?=null,
        val bitDepth:Int?=null,
    )
    private fun u8(data:ByteArray,i:Int)=data[i].toInt() and 255
    private fun ascii(data:ByteArray,at:Int,s:String):Boolean=
        at>=0 && at+s.length<=data.size &&
            s.indices.all { u8(data,at+it)==s[it].code }
    private fun le16(b:ByteArray,at:Int)=u8(b,at) or (u8(b,at+1) shl 8)
    private fun le32(b:ByteArray,at:Int)=
        u8(b,at).toLong() or (u8(b,at+1).toLong() shl 8) or
            (u8(b,at+2).toLong() shl 16) or (u8(b,at+3).toLong() shl 24)

    /** Android reports codecs as MIME; never let a .m4a masquerade as ADTS. */
    fun acceptsExtractorMime(
        codec:A0CanonicalAcquisitionIntent.Codec,mime:String,
    ):Boolean = when(codec) {
        A0CanonicalAcquisitionIntent.Codec.MP3 ->
            mime.equals("audio/mpeg",ignoreCase=true)
        A0CanonicalAcquisitionIntent.Codec.FLAC ->
            mime.equals("audio/flac",ignoreCase=true)
        A0CanonicalAcquisitionIntent.Codec.PCM_WAV ->
            mime in setOf("audio/raw","audio/wav","audio/x-wav")
        A0CanonicalAcquisitionIntent.Codec.AAC_ADTS ->
            mime in setOf("audio/mp4a-latm","audio/aac")
    }

    fun inspect(bytes:ByteArray):Info? {
        if(bytes.size<12)return null
        if(ascii(bytes,0,"fLaC")) {
            if(bytes.size<42 || (u8(bytes,4) and 0x7f)!=0 ||
                u8(bytes,5)!=0 || u8(bytes,6)!=0 || u8(bytes,7)!=34)
                return null
            // FLAC STREAMINFO: 20-bit rate / 3-bit channels / 5-bit depth.
            val rate=(u8(bytes,18) shl 12) or (u8(bytes,19) shl 4) or
                (u8(bytes,20) ushr 4)
            val channels=((u8(bytes,20) ushr 1) and 7)+1
            val depth=(((u8(bytes,20) and 1) shl 4) or
                (u8(bytes,21) ushr 4))+1
            if(rate !in 8000..384000 || channels !in 1..8 ||
                depth !in 8..32)return null
            return Info(A0CanonicalAcquisitionIntent.Codec.FLAC,rate,depth)
        }
        if(ascii(bytes,0,"RIFF") && ascii(bytes,8,"WAVE")) {
            var offset=12
            while(offset+8<=bytes.size) {
                val size=le32(bytes,offset+4)
                if(size>bytes.size-offset-8L)return null
                if(ascii(bytes,offset,"fmt ")) {
                    if(size<16)return null
                    val type=le16(bytes,offset+8)
                    val channels=le16(bytes,offset+10)
                    val rate=le32(bytes,offset+12)
                    val depth=le16(bytes,offset+22)
                    // WAVE_FORMAT_PCM only. Float/extensible need a separate
                    // subformat check and remain manual until implemented.
                    if(type!=1 || channels !in 1..8 ||
                        rate !in 8000L..384000L || depth !in setOf(16,24,32))
                        return null
                    return Info(A0CanonicalAcquisitionIntent.Codec.PCM_WAV,
                        rate.toInt(),depth)
                }
                offset+=(8L+size+(size and 1L)).toInt()
            }
            return null
        }
        if(ascii(bytes,0,"ID3") && bytes.size>=16)
            return Info(A0CanonicalAcquisitionIntent.Codec.MP3)
        if(u8(bytes,0)==0xff && (u8(bytes,1) and 0xe0)==0xe0 &&
            (u8(bytes,1) and 0x18)!=0x08 &&
            (u8(bytes,1) and 0x06)!=0 &&
            (u8(bytes,2) and 0xf0) !in setOf(0,0xf0) &&
            (u8(bytes,2) and 0x0c)!=0x0c)
            return Info(A0CanonicalAcquisitionIntent.Codec.MP3)
        if((u8(bytes,0)==0xff && (u8(bytes,1) and 0xf6)==0xf0 &&
            (u8(bytes,2) and 0x3c)!=0x3c)) {
            val frameLength=((u8(bytes,3) and 3) shl 11) or
                (u8(bytes,4) shl 3) or (u8(bytes,5) ushr 5)
            if(frameLength>=7)
                return Info(A0CanonicalAcquisitionIntent.Codec.AAC_ADTS)
        }
        return null
    }
}
