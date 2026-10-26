package com.music.bitchord.acquisition.a0

/**
 * Interprets only stable, internal A0/A2/A3 error prefixes AFTER the complete
 * P/F receipt has been staged. Never stores or emits Throwable.message.
 * Unknown providers/exceptions must stay opaque, not expose names or paths.
 */
internal object A0LocalSaveFailure {
    enum class Kind {
        DESTINATION_COLLISION,
        DESTINATION_UNAVAILABLE,
        UNSUPPORTED_AUDIO,
        DURABLE_COMMIT,
        SCRATCH_CLEANUP,
        PUBLISHING_FAILED,
        OTHER,
    }

    fun classify(error:Throwable):Kind {
        val message=error.message.orEmpty()
        return when {
            message.startsWith("A2 destination filename already exists") ->
                Kind.DESTINATION_COLLISION
            message.startsWith("A2 unsupported audio signature or extension") ||
                message.startsWith("A2 incomplete audio header") ->
                Kind.UNSUPPORTED_AUDIO
            message.startsWith("A2 destination requires folder re-selection") ||
                message.startsWith("A2 MediaStore") ||
                message.startsWith("A2 SAF") ||
                message.startsWith("A2 cannot inspect SAF") ||
                message.startsWith("A2 cannot check existing MediaStore") ||
                message.startsWith("A2 could not reserve pending audio row") ||
                message.startsWith("A2 cannot publish pending audio") ->
                Kind.DESTINATION_UNAVAILABLE
            message.startsWith("A3 failed to commit published URI") ||
                message.startsWith("A3 published artifact rollback failed") ->
                Kind.DURABLE_COMMIT
            message.startsWith("A0 observed scratch cleanup failed") ->
                Kind.SCRATCH_CLEANUP
            message.startsWith("A2 ") -> Kind.PUBLISHING_FAILED
            else -> Kind.OTHER
        }
    }
}
