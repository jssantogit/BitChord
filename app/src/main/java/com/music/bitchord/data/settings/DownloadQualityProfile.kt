package com.music.bitchord.data.settings

/**
 * One user-facing preference for downloads from every source.
 *
 * Providers apply the best supported rendition, never fabricate a codec,
 * perform an implicit conversion or claim that a lossy M4A is ALAC.
 */
enum class DownloadQualityProfile(val label:String,val explanation:String) {
    HI_RES("Hi-Res", "Sem perdas: FLAC/ALAC, preferencialmente 24-bit/96–192 kHz; apenas quando disponível"),
    LOSSLESS("Sem perdas", "FLAC/ALAC, até 24-bit/48 kHz no alvo; sem conversão de arquivo lossy"),
    HIGH_QUALITY("Alta qualidade", "AAC 256 kbps ou MP3 320 kbps, conforme suporte da fonte; sem exigência de FLAC"),
    CUSTOM("Personalizado", "Escolher formato, bitrate, frequência em kHz e profundidade em bits"),
    ;
    companion object {
        fun parse(raw:String?):DownloadQualityProfile =
            entries.firstOrNull { it.name==raw } ?: LOSSLESS
        fun fromLegacy(legacy:DownloadQuality):DownloadQualityProfile=when(legacy) {
            DownloadQuality.STANDARD,DownloadQuality.HIGH->HIGH_QUALITY
            DownloadQuality.LOSSLESS->LOSSLESS
        }
    }

    /** Adapter only for providers still consuming the historic three-rung enum. */
    fun legacyQuality(custom:DownloadCustomOptions):DownloadQuality=when(this) {
        HI_RES,LOSSLESS->DownloadQuality.LOSSLESS
        HIGH_QUALITY->DownloadQuality.HIGH
        CUSTOM->if(custom.format in setOf("flac","wav","alac"))
            DownloadQuality.LOSSLESS else DownloadQuality.HIGH
    }
}

data class DownloadCustomOptions(
    val format:String="mp3",
    val bitrateKbps:Int?=320,
    val sampleRateHz:Int?=null,
    val bitDepth:Int?=null,
) {
    init {
        require(format in setOf("mp3","flac","wav","aac","m4a"))
        require(bitrateKbps==null || bitrateKbps in 32..1536)
        require(sampleRateHz==null || sampleRateHz in 8000..384000)
        require(bitDepth==null || bitDepth in 8..32)
    }
    val isCurrentlySupportedForSoulseek:Boolean get() = format in setOf("mp3","flac","wav","aac")
    val description:String get() = buildString {
        append(format.uppercase())
        bitrateKbps?.let { append(" · $it kbps") }
        sampleRateHz?.let { append(" · ${it/1000} kHz") }
        bitDepth?.let { append(" · $it bits") }
    }
}
