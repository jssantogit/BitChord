package com.music.bitchord.acquisition.a0

import android.content.Context
import com.music.bitchord.BuildConfig
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Common one-shot authenticated server connection for the future search and
 * transfer workers. No startup login, reconnect, credential persistence,
 * URL selection or silent password replay. Plaintext TCP is inherent to the
 * protocol; only the dedicated user-saved Soulseek vault may be consulted.
 *
 * Fresh searches and fresh P/F MUST each own a separate server socket.
 * SoulseekActiveSession revokes and closes all registered sockets on logout.
 */
internal object A0AutoAuthenticatedServer {
    class Session internal constructor(
        val socket:Socket,
        val username:String,
    ):Closeable {
        override fun close() {
            SoulseekActiveSession.detach(socket)
            runCatching {socket.close()}
        }
        override fun toString()="A0AutoAuthenticatedServerSession(redacted)"
    }

    fun open(
        context:Context,
        accountGeneration:Long,
        isCancelled:()->Boolean,
    ):Session {
        if(BuildConfig.FLAVOR!="dev" || !BuildConfig.DEBUG)
            throw IOException("Soulseek autonomous login not enabled")
        fun revoked()=isCancelled() ||
            !SoulseekActiveSession.isCurrent(accountGeneration) ||
            Thread.currentThread().isInterrupted
        if(revoked())throw IOException("Soulseek login cancelled")
        val credentials=SoulseekCredentialVault(context.applicationContext).read()
            ?:throw IOException("Soulseek account not configured")
        var packet:ByteArray?=null
        val server=Socket()
        var success=false
        try {
            // Password must be wiped from mutable JVM arrays even if connect
            // or read times out; immutable UTF-8 intermediates are protocol
            // inherent and never logged, retained or copied to diagnostics.
            packet=A0ExperimentalLoginCodec.encode(
                credentials.username,credentials.password)
            credentials.erase()
            if(!SoulseekActiveSession.register(server,accountGeneration))
                throw IOException("Soulseek session revoked")
            server.connect(InetSocketAddress("server.slsknet.org",2416),7000)
            server.soTimeout=12000
            if(revoked())throw IOException("Soulseek login cancelled")
            server.getOutputStream().apply {
                write(checkNotNull(packet))
                flush()
            }
            checkNotNull(packet).fill(0)
            packet=null
            if(!A0ExperimentalLoginCodec.readResponse(server.getInputStream()))
                throw IOException("Soulseek login refused")
            if(revoked())throw IOException("Soulseek account revoked")
            success=true
            return Session(server,credentials.username)
        }finally {
            packet?.fill(0)
            credentials.erase()
            if(!success) {
                SoulseekActiveSession.detach(server)
                runCatching{server.close()}
            }
        }
    }
}
