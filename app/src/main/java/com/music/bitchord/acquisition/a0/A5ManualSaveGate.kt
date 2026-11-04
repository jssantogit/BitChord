package com.music.bitchord.acquisition.a0

/**
 * DevDebug A5-only explicit SAVE gate. The default A0 action still observes and
 * deletes bytes. A local export is never implied by search or peer listing.
 *
 * The peer's filename is untrusted display text. This gate never accepts a
 * directory path, traversal, or format the bounded A2 publisher cannot sniff.
 */
internal object A5ManualSaveGate {
    private val badName=Regex("""[\\/:*?"<>|\x00-\x1F\x7F]""")
    private val supported=setOf("wav","flac","mp3","aac","opus","ogg")

    fun canSave(transferObserved:Boolean,userConfirmedSave:Boolean):Boolean=
        transferObserved && userConfirmedSave

    fun safeDestinationName(displayName:String,sizeBytes:Long):String? {
        if(sizeBytes !in A0ObservedTransferLimits.MIN_AUDIO_BYTES..A0ObservedTransferLimits.MAX_AUDIO_BYTES ||
            displayName.length !in 1..160 ||
            displayName!=displayName.trim() ||
            displayName in setOf(".","..") ||
            badName.containsMatchIn(displayName) ||
            displayName.endsWith(".part",ignoreCase=true)
        )return null
        val extension=displayName.substringAfterLast('.',missingDelimiterValue="").lowercase()
        if(extension !in supported || displayName.substringBeforeLast('.',missingDelimiterValue="").isBlank())
            return null
        return displayName
    }
}
