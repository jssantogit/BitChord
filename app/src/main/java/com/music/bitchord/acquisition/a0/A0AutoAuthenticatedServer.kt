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
    enum class Stage {
        NOT_ENABLED, CANCELLED, VAULT_READ, VAULT_EMPTY,
        CREDENTIALS_READY, SOCKET_REGISTERED, TCP_CONNECTED,
        LOGIN_SENT, LOGIN_REJECTED, LOGIN_ACCEPTED,
    }
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
        onSocketReady:(Socket)->Unit={},
        onStage:(Stage)->Unit={},
    ):Session {
        fun stage(value:Stage) { runCatching {onStage(value)} }
        if(BuildConfig.FLAVOR!="dev" || !BuildConfig.DEBUG) {
            stage(Stage.NOT_ENABLED)
            throw IOException("Soulseek autonomous login not enabled")
        }
        fun revoked()=isCancelled() ||
            !SoulseekActiveSession.isCurrent(accountGeneration) ||
            Thread.currentThread().isInterrupted
        if(revoked()) {
            stage(Stage.CANCELLED)
            throw IOException("Soulseek login cancelled")
        }
        stage(Stage.VAULT_READ)
        val credentials=SoulseekCredentialVault(context.applicationContext).read()
            ?:run {
                stage(Stage.VAULT_EMPTY)
                throw IOException("Soulseek account not configured")
            }
        stage(Stage.CREDENTIALS_READY)
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
            stage(Stage.SOCKET_REGISTERED)
            onSocketReady(server)
            if(revoked())throw IOException("Soulseek login cancelled")
            server.connect(InetSocketAddress("server.slsknet.org",2416),7000)
            stage(Stage.TCP_CONNECTED)
            server.soTimeout=12000
            if(revoked())throw IOException("Soulseek login cancelled")
            server.getOutputStream().apply {
                write(checkNotNull(packet))
                flush()
            }
            checkNotNull(packet).fill(0)
            packet=null
            stage(Stage.LOGIN_SENT)
            if(!A0ExperimentalLoginCodec.readResponse(server.getInputStream())) {
                stage(Stage.LOGIN_REJECTED)
                throw IOException("Soulseek login refused")
            }
            if(revoked())throw IOException("Soulseek account revoked")
            success=true
            stage(Stage.LOGIN_ACCEPTED)
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
