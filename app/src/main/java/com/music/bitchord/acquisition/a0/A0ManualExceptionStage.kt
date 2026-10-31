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

    fun category(error:Exception):Category = when(
        A0AcquisitionDiagnostics.fault(error)) {
        A0AcquisitionDiagnostics.Fault.PEER_ADDRESS -> Category.PEER_ADDRESS
        A0AcquisitionDiagnostics.Fault.CANCELLED -> Category.CANCELLED
        A0AcquisitionDiagnostics.Fault.TIMEOUT -> Category.TIMEOUT
        A0AcquisitionDiagnostics.Fault.CONNECT -> Category.CONNECTION
        A0AcquisitionDiagnostics.Fault.EOF -> Category.EOF
        A0AcquisitionDiagnostics.Fault.SOCKET -> Category.SOCKET
        A0AcquisitionDiagnostics.Fault.IO -> Category.IO
        A0AcquisitionDiagnostics.Fault.STATE -> Category.STATE
        A0AcquisitionDiagnostics.Fault.SECURITY -> Category.SECURITY
        A0AcquisitionDiagnostics.Fault.ARGUMENT -> Category.ARGUMENT
        else -> Category.OTHER
    }

    fun publicationMayBeUncertain(publicationAttempted:Boolean)=publicationAttempted
}
