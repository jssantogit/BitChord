package com.music.bitchord.acquisition.a0

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException

class A0AcquisitionDiagnosticsTest {
    @Test fun typedNetworkFailuresDoNotRequireExceptionMessageOrPeer() {
        val d=A0AcquisitionDiagnostics
        assertEquals(A0AcquisitionDiagnostics.Fault.TIMEOUT,d.fault(SocketTimeoutException("private peer")))
        assertEquals(A0AcquisitionDiagnostics.Fault.CONNECT,d.fault(ConnectException("private ip")))
        assertEquals(A0AcquisitionDiagnostics.Fault.EOF,d.fault(EOFException("private path")))
        assertEquals(A0AcquisitionDiagnostics.Fault.IO,d.fault(IOException("credentials")))
        assertEquals(A0AcquisitionDiagnostics.Fault.ARGUMENT,d.fault(IllegalArgumentException("path")))
        assertEquals(A0AcquisitionDiagnostics.Fault.TIMEOUT,d.fault(IOException(
            "wrapper with private filename",SocketTimeoutException("peer"))))
        assertEquals(A0AcquisitionDiagnostics.Fault.CONNECT,d.fault(IOException(
            "wrapped",IOException("layer 2",ConnectException("ip")))))
    }
    @Test fun scopesArePositiveAndDistinctForSuccessiveAttempts() {
        val a=A0AcquisitionDiagnostics.newScope()
        val b=A0AcquisitionDiagnostics.newScope()
        assertTrue(a>0)
        assertTrue(b>0)
        assertNotEquals(a,b)
    }
}
