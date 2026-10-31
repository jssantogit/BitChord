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
        assertEquals(d.Fault.TIMEOUT,d.fault(SocketTimeoutException("private peer")))
        assertEquals(d.Fault.CONNECT,d.fault(ConnectException("private ip")))
        assertEquals(d.Fault.EOF,d.fault(EOFException("private path")))
        assertEquals(d.Fault.IO,d.fault(IOException("credentials")))
        assertEquals(d.Fault.ARGUMENT,d.fault(IllegalArgumentException("path")))
    }
    @Test fun scopesArePositiveAndDistinctForSuccessiveAttempts() {
        val a=A0AcquisitionDiagnostics.newScope()
        val b=A0AcquisitionDiagnostics.newScope()
        assertTrue(a>0)
        assertTrue(b>0)
        assertNotEquals(a,b)
    }
}
