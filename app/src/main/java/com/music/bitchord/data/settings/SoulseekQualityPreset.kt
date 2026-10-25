package com.music.bitchord.data.settings

/**
 * Dedicated Soulseek preference. Kept separate from DownloadQuality so the
 * existing providers never inherit a new codec gate or fallback policy.
 * Unknown previous values fail closed to a neutral MANUAL ranking preference.
 */
internal enum class SoulseekQualityPreset(
    val label: String,
    val explanation: String,
) {
    ANY("Sem filtro específico", "Sem novos bloqueios; todos os formatos seguros permanecem manuais"),
    HI_RES("Hi-Res", "FLAC/WAV declarado com 24 bits e frequência acima de 48 kHz"),
    LOSSLESS("Sem perdas", "FLAC/WAV declarado; nunca converter MP3 para FLAC"),
    HIGH_QUALITY("Alta qualidade", "MP3 ≥256 kbps ou formato sem perdas declarado"),
    HIGH_EFFICIENCY("Alta eficiência (indisponível)", "AAC/Opus não são suportados no salvamento piloto"),
    MP3_256("Personalizado: MP3 ≥256 kbps", "MP3 sem transcodificação, bitrate informado pelo peer"),
    FLAC_24_96("Personalizado: FLAC 24 bits / 96 kHz", "Parâmetros declarados e não autenticados"),
    ;

    companion object {
        fun parse(raw: String?): SoulseekQualityPreset =
            entries.firstOrNull { it.name == raw } ?: ANY
    }
}
