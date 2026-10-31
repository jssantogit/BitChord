package com.music.bitchord.acquisition.a0

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.SocketException
import java.io.EOFException
import java.util.concurrent.CancellationException

/** Categorizes manual transfer failures without exception messages, paths or
 * peers. An attempt that never called publish cannot have published bytes.
 * This does not release or retry an A3 identity. */
internal object A0ManualExceptionStage {
    enum class Phase { FETCH, PROBE_OR_PREPARE, PUBLISH }
    enum class Category {
        TIMEOUT, CONNECTION, EOF, SOCKET, PEER_ADDRESS,
        IO, CANCELLED, STATE, SECURITY, ARGUMENT, OTHER,
    }

    fun phase(hasReceipt:Boolean,publicationAttempted:Boolean):Phase = when {
        publicationAttempted -> Phase.PUBLISH
        hasReceipt -> Phase.PROBE_OR_PREPARE
        else -> Phase.FETCH
    }

    fun category(error:Exception):Category = when(error) {
        is A0ServerAssociatedTransfer.PeerAddressFailure -> Category.PEER_ADDRESS
        is CancellationException, is InterruptedException -> Category.CANCELLED
        is SocketTimeoutException -> Category.TIMEOUT
        is ConnectException -> Category.CONNECTION
        is EOFException -> Category.EOF
        is SocketException -> Category.SOCKET
        is IOException -> Category.IO
        is SecurityException -> Category.SECURITY
        is IllegalStateException -> Category.STATE
        is IllegalArgumentException -> Category.ARGUMENT
        else -> Category.OTHER
    }

    fun publicationMayBeUncertain(publicationAttempted:Boolean)=publicationAttempted
}
