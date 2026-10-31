package com.music.bitchord.acquisition.a0

import com.music.bitchord.data.DebugLog as Log
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded, anonymous, process-local correlation for acquisition failures.
 * Never takes path, peer, query, filename, socket address, exception message,
 * stack trace, secret, URI, or raw dynamic user text.
 */
internal object A0AcquisitionDiagnostics {
    private val next=AtomicInteger(0)
    fun newScope():Int=next.updateAndGet { if(it==Int.MAX_VALUE)1 else it+1 }

    enum class Fault {
        PEER_ADDRESS, TIMEOUT, CONNECT, EOF, SOCKET, CANCELLED,
        IO, STATE, SECURITY, ARGUMENT, PROTOCOL, OTHER,
    }
    enum class Phase {
        SEARCH_START, SEARCH_LOGIN_STAGE, SEARCH_LOGIN_OK, SEARCH_READER_READY,
        SEARCH_RECOVERY_SENT, SEARCH_STOP, SEARCH_EXCEPTION,
        TRANSPORT_START, TRANSPORT_LOGIN_STAGE, TRANSPORT_LOGIN_OK, TRANSPORT_PEER_PHASE,
        TRANSPORT_RECEIPT, TRANSPORT_FAILURE, TRANSPORT_CLOSE,
        REVIEW_START, REVIEW_STAGE, REVIEW_EXCEPTION,
        ENGINE_REFUSAL, ENGINE_STAGE,
        PROBE_START, PROBE_REJECT, PROBE_ACCEPT,
        A3_FETCH, A3_STAGED, A3_INSPECT, A3_PUBLISH,
        A3_RESULT, A3_CLEANUP,
    }

    fun fault(error:Throwable):Fault=when(error) {
        is A0ServerAssociatedTransfer.PeerAddressFailure -> Fault.PEER_ADDRESS
        is CancellationException, is InterruptedException -> Fault.CANCELLED
        is SocketTimeoutException -> Fault.TIMEOUT
        is ConnectException -> Fault.CONNECT
        is EOFException -> Fault.EOF
        is SocketException -> Fault.SOCKET
        is SecurityException -> Fault.SECURITY
        is IllegalStateException -> Fault.STATE
        is IllegalArgumentException -> Fault.ARGUMENT
        is IOException -> Fault.IO
        else -> Fault.OTHER
    }

    /** The callback can run in a network worker; no exception can block P/F. */
    fun emit(
        scope:Int,
        phase:Phase,
        reason:Enum<*>?=null,
        elapsedMillis:Long=-1L,
        count:Int=-1,
        slot:Int=-1,
    ) {
        if(scope<=0)return
        runCatching {
            Log.d("BitChord",
                "soulseek_trace scope=$scope phase=${phase.name}" +
                " reason=${reason?.name ?: "NONE"}" +
                " elapsed_ms=${elapsedMillis.coerceIn(-1L,600_000L)}" +
                " count=${count.coerceIn(-1,100_000)}" +
                " slot=${slot.coerceIn(-1,500)}")
        }
    }
}
