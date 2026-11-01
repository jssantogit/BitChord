package com.music.bitchord.acquisition.a0

import java.util.concurrent.TimeUnit

/**
 * The short P/F NEGOTIATION cap must not be reused to time the actual file
 * byte stream. A real Samsung A54 receipt reached F_ESTABLISHED, then failed
 * exactly at the old 15s operation deadline before any BYTES_RECEIVED stage.
 *
 * Negotiation remains 15s. Once F has been verified and accepted, its
 * independent bounded byte-read window is 180s. Socket SO_TIMEOUT and explicit
 * cancellation remain in effect; this does not authorize unbounded I/O.
 */
internal object A0TransferDeadlines {
    private val negotiationNanos=TimeUnit.SECONDS.toNanos(15)
    private val receiveNanos=TimeUnit.SECONDS.toNanos(180)
    // Some peers establish F promptly but delay FileTransferInit. Give the
    // initial token one bounded grace window without changing byte-stream
    // idle limits or allowing a second post-acceptance attempt.
    const val fTokenReadTimeoutMillis=8000
    const val streamIdleTimeoutMillis=4000

    fun negotiate(startedAtNanos:Long):Long=startedAtNanos+negotiationNanos

    fun receive(fEstablishedAtNanos:Long):Long=fEstablishedAtNanos+receiveNanos

    fun isExpired(deadlineNanos:Long,nowNanos:Long):Boolean=nowNanos>=deadlineNanos
}
