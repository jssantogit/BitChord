package com.music.bitchord.acquisition.a0

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.diagnostics.DiagnosticsShare
import com.music.bitchord.observability.TraceOutcome
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.security.SecureRandom

/**
 * Only compiled into app/src/devDebug, NOT into prod/dev release.
 *
 * Manual one-shot login check from the owner's Android phone. No credentials
 * from GitHub Actions, no persistence, no automatic retries, no search, no P2P.
 * Network protocol sends the password in cleartext; test-only credentials ONLY.
 */
class A0ManualLoginActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeListener: ServerSocket? = null
    @Volatile private var activeDirectPeer: Socket? = null
    @Volatile private var activeIndirectPeer: Socket? = null
    @Volatile private var stopping = false
    @Volatile private var activeTrace: A0SmokeTrace? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        fun text(value: String): TextView = TextView(this).also {
            it.text = value
            it.textSize = 16f
            it.setPadding(0, 12, 0, 12)
            page.addView(it)
        }
        text("BitChord A0 — teste de conectividade P2P Soulseek")
        text("Somente para uma conta exclusiva de TESTE. " +
             "O protocolo Soulseek envia a senha por TCP SEM TLS. " +
             "Não reutilize senhas de outros serviços.")
        text("O primeiro botão testa somente login. O segundo envia uma única " +
             "busca diagnóstica naruto* (normalizada para naruto pelo protocolo) " +
             "e tenta receber respostas P2P diretas ou indiretas. " +
             "Resultados não comprovam direitos autorais ou disponibilidade. " +
             "Nada será compartilhado ou baixado. Desconecte o Seeker primeiro.")
        val user = EditText(this).apply {
            hint = "Usuário dedicado"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        page.addView(user)
        val password = EditText(this).apply {
            hint = "Senha exclusiva de testes"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        page.addView(password)
        val acknowledge = CheckBox(this).apply {
            text = "Confirmo que é uma senha exclusiva de testes e aceito " +
                "a transmissão TCP sem TLS exigida pelo protocolo."
        }
        page.addView(acknowledge)
        val action = Button(this).apply { text = "Testar login uma única vez" }
        page.addView(action)
        val searchAction = Button(this).apply {
            text = "Pesquisar naruto* (sem baixar)"
        }
        page.addView(searchAction)
        val exportAction = Button(this).apply { text = "Exportar logs A0 (TXT)" }
        page.addView(exportAction)
        text("Captura temporária iniciada automaticamente ao testar. " +
            "Depois do resultado, exporte os logs. Revise o relatório antes de compartilhar. " +
            "Não envie credenciais, capturas de rede ou logcat bruto.")
        val status = text("Nenhuma conexão iniciada.")

        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)

        fun start(performSearch: Boolean) {
            if (!acknowledge.isChecked) {
                status.text = "Leia e confirme o aviso de segurança."
                return
            }
            val username = user.text.toString()
            val secret = password.text.toString().toCharArray()
            // Clear both controls before any packet creation or network action.
            user.text.clear()
            password.text.clear()
            acknowledge.isChecked = false
            action.isEnabled = false
            searchAction.isEnabled = false
            status.text = if (performSearch) {
                "Autenticando e preparando uma única busca sem downloads."
            } else {
                "Verificando uma sessão; nenhum arquivo será transferido."
            }
            worker.execute {
                // Existing Diagnostics capture and structured tracing; no raw inputs.
                val captureEnabled = runCatching {
                    Diagnostics.startCapture(180_000L).active
                }.getOrDefault(false)
                val trace = A0SmokeTrace.start(Diagnostics.structuredTracer())
                activeTrace = trace
                val transport = runCatching {
                    val manager = getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager
                    val network = manager.activeNetwork
                    val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                    when {
                        network == null -> A0SmokeTrace.Transport.DISCONNECTED
                        capabilities == null -> A0SmokeTrace.Transport.UNKNOWN
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                            A0SmokeTrace.Transport.VPN
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                            A0SmokeTrace.Transport.WIFI
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                            A0SmokeTrace.Transport.CELLULAR
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                            A0SmokeTrace.Transport.ETHERNET
                        else -> A0SmokeTrace.Transport.OTHER
                    }
                }.getOrDefault(A0SmokeTrace.Transport.UNKNOWN)
                trace.networkTransport(transport)
                trace.note(
                    A0SmokeTrace.Stage.CAPTURE_READY,
                    if (captureEnabled) TraceOutcome.SUCCEEDED else TraceOutcome.DEGRADED
                )
                trace.note(A0SmokeTrace.Stage.LOGIN_STARTED)
                var finalOutcome = TraceOutcome.FAILED
                var finalReason: A0SmokeTrace.Reason? = null
                var packet: ByteArray? = null
                var result = "Falha ao iniciar a verificação."
                var searchSubmitted = false
                var loginReplyProgress: A0LoginReadProgress? = null
                try {
                    packet = A0ExperimentalLoginCodec.encode(username, secret)
                    trace.note(A0SmokeTrace.Stage.SERVER_CONNECT_STARTED)
                    Socket().use { connection ->
                        activeSocket = connection
                        // One fixed, documented SoulseekQt server endpoint.
                        // No user-controlled URL, proxy, redirects or fallback.
                        connection.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), 7000)
                        trace.note(A0SmokeTrace.Stage.SERVER_CONNECTED)
                        // One bounded login attempt: allow slower server replies on Wi-Fi,
                        // without automatic reconnect/relogin or credential replay.
                        connection.soTimeout = 12_000
                        connection.getOutputStream().write(packet)
                        connection.getOutputStream().flush()
                        trace.note(A0SmokeTrace.Stage.LOGIN_PACKET_SENT)
                        // Wipe before receiving a reply, which might stall.
                        packet.fill(0)
                        packet = null
                        loginReplyProgress = A0LoginReadProgress(
                            connection.getInputStream()
                        ) {
                            trace.note(A0SmokeTrace.Stage.LOGIN_RESPONSE_FIRST_BYTE)
                        }
                        A0ExperimentalLoginCodec.readResponse(checkNotNull(loginReplyProgress))
                        trace.note(A0SmokeTrace.Stage.LOGIN_ACCEPTED)
                        if (!performSearch) {
                            result = "LOGIN CONFIRMADO. Sessão encerrada, sem buscas ou downloads."
                            finalOutcome = TraceOutcome.SUCCEEDED
                        } else {
                            ServerSocket(0, 1).use { listener ->
                                activeListener = listener
                                trace.note(A0SmokeTrace.Stage.SEARCH_LISTENER_OPEN)
                                listener.soTimeout = 12_000
                                val token = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
                                connection.getOutputStream().apply {
                                    write(A0OneShotPublicSearchWire.setWaitPort(listener.localPort))
                                    write(A0OneShotPublicSearchWire.fileSearch(token))
                                    flush()
                                }
                                searchSubmitted = true
                                trace.note(A0SmokeTrace.Stage.SEARCH_PORT_ANNOUNCED)
                                trace.note(A0SmokeTrace.Stage.SEARCH_SENT)
                                trace.note(A0SmokeTrace.Stage.SEARCH_WAIT_STARTED)
                                // The cellular network may reject inbound P. Modern peers
                                // can fall back to server code 18 and outbound PierceFirewall.
                                // Separate readers prevent blocking on accept() while the
                                // server is offering an indirect P connection.
                                val inbox = LinkedBlockingQueue<String>(1)
                                val attemptedIndirect = AtomicBoolean(false)
                                val failedServerReader = AtomicBoolean(false)
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                                val readers = Executors.newFixedThreadPool(2)
                                try {
                                    readers.execute {
                                        listener.soTimeout = 1000
                                        var directWaits = 0
                                        while (!Thread.currentThread().isInterrupted &&
                                            !listener.isClosed && System.nanoTime() < deadline &&
                                            inbox.isEmpty()
                                        ) {
                                            try {
                                                listener.accept().use { peer ->
                                                    trace.note(A0SmokeTrace.Stage.DIRECT_ACCEPTED)
                                                    activeDirectPeer = peer
                                                    peer.soTimeout = 4000
                                                    val observation =
                                                        A0OneShotPublicSearchWire.readOneDirectPeerReply(
                                                            peer.getInputStream(), token
                                                        )
                                                    trace.note(A0SmokeTrace.Stage.DIRECT_REPLY_ACCEPTED)
                                                    inbox.offer(
                                                        "RESPOSTA P2P DIRETA: " +
                                                            "${observation.untrustedResultCount} entradas " +
                                                            "declaradas, não verificadas. Nenhum download."
                                                    )
                                                }
                                            } catch (_: SocketTimeoutException) {
                                                if (++directWaits <= 2) trace.note(
                                                    A0SmokeTrace.Stage.DIRECT_READ_TIMEOUT,
                                                    TraceOutcome.SKIPPED,
                                                    A0SmokeTrace.Reason.READ_TIMEOUT
                                                )
                                            } catch (_: IOException) {
                                                if (listener.isClosed) break
                                                trace.note(
                                                    A0SmokeTrace.Stage.DIRECT_REPLY_REJECTED,
                                                    TraceOutcome.DEGRADED,
                                                    A0SmokeTrace.Reason.PEER_IO_FAILURE
                                                )
                                            } finally {
                                                activeDirectPeer = null
                                            }
                                        }
                                    }
                                    readers.execute {
                                        connection.soTimeout = 12_000
                                        var attempts = 0
                                        try {
                                            while (!Thread.currentThread().isInterrupted &&
                                                System.nanoTime() < deadline &&
                                                inbox.isEmpty() && attempts < 2
                                            ) {
                                                val offer = try {
                                                    A0IndirectSearchWire.readServerEvent(
                                                        connection.getInputStream()
                                                    ) { code -> trace.ignoredServerFrame(code) }
                                                } catch (_: A0IndirectSearchWire.RejectedPeerOffer) {
                                                    trace.note(
                                                        A0SmokeTrace.Stage.SERVER_OFFER_REJECTED,
                                                        TraceOutcome.DEGRADED,
                                                        A0SmokeTrace.Reason.WIRE_REJECTED
                                                    )
                                                    continue
                                                }
                                                if (offer == null) continue
                                                trace.note(A0SmokeTrace.Stage.SERVER_OFFER_RECEIVED)
                                                attemptedIndirect.set(true)
                                                attempts++
                                                try {
                                                    trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECT_STARTED)
                                                    Socket().use { peer ->
                                                        activeIndirectPeer = peer
                                                        peer.connect(
                                                            InetSocketAddress(offer.address, offer.port), 2500
                                                        )
                                                        peer.soTimeout = 4000
                                                        trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECTED)
                                                        peer.getOutputStream().apply {
                                                            write(A0IndirectSearchWire.pierceFirewall(offer.token))
                                                            flush()
                                                        }
                                                        trace.note(A0SmokeTrace.Stage.PIERCE_SENT)
                                                        val observation = A0IndirectSearchWire.readIndirectReply(
                                                            peer.getInputStream(), token, offer.username
                                                        )
                                                        trace.note(A0SmokeTrace.Stage.INDIRECT_REPLY_ACCEPTED)
                                                        inbox.offer(
                                                            "RESPOSTA P2P INDIRETA: " +
                                                                "${observation.untrustedResultCount} entradas " +
                                                                "declaradas, não verificadas. Nenhum download."
                                                        )
                                                    }
                                                } catch (_: SocketTimeoutException) {
                                                    trace.note(
                                                        A0SmokeTrace.Stage.INDIRECT_READ_TIMEOUT,
                                                        TraceOutcome.DEGRADED,
                                                        A0SmokeTrace.Reason.READ_TIMEOUT
                                                    )
                                                } catch (_: ConnectException) {
                                                    trace.note(
                                                        A0SmokeTrace.Stage.INDIRECT_CONNECT_FAILED,
                                                        TraceOutcome.DEGRADED,
                                                        A0SmokeTrace.Reason.CONNECT_FAILED
                                                    )
                                                } catch (_: IOException) {
                                                    trace.note(
                                                        A0SmokeTrace.Stage.INDIRECT_REPLY_REJECTED,
                                                        TraceOutcome.DEGRADED,
                                                        A0SmokeTrace.Reason.PEER_IO_FAILURE
                                                    )
                                                } finally {
                                                    activeIndirectPeer = null
                                                }
                                            }
                                        } catch (_: SocketTimeoutException) {
                                            trace.note(
                                                A0SmokeTrace.Stage.SERVER_READ_TIMEOUT,
                                                TraceOutcome.DEGRADED,
                                                A0SmokeTrace.Reason.READ_TIMEOUT
                                            )
                                        } catch (_: IOException) {
                                            trace.note(
                                                A0SmokeTrace.Stage.SERVER_READ_FAILURE,
                                                TraceOutcome.DEGRADED,
                                                A0SmokeTrace.Reason.SERVER_IO_FAILURE
                                            )
                                            failedServerReader.set(true)
                                        }
                                    }
                                    val peerStatus = inbox.poll(12, TimeUnit.SECONDS)
                                    result = peerStatus ?: when {
                                        attemptedIndirect.get() ->
                                            "NEGOCIAÇÃO INDIRETA TENTADA; NENHUMA RESPOSTA P2P " +
                                                "RECEBIDA. Nenhum download."
                                        failedServerReader.get() ->
                                            "ERRO DE REDE OU PROTOCOLO APÓS BUSCA. Nenhum download."
                                        else ->
                                            "BUSCA ENVIADA; NENHUMA RESPOSTA P2P RECEBIDA. " +
                                                "A rede pode bloquear conexões de entrada."
                                    }
                                    if (peerStatus == null) {
                                        trace.note(
                                            A0SmokeTrace.Stage.NO_REPLY,
                                            TraceOutcome.DEGRADED,
                                            A0SmokeTrace.Reason.NO_PEER_REPLY
                                        )
                                        finalOutcome = TraceOutcome.DEGRADED
                                        finalReason = A0SmokeTrace.Reason.NO_PEER_REPLY
                                    } else {
                                        finalOutcome = TraceOutcome.SUCCEEDED
                                    }
                                    trace.note(A0SmokeTrace.Stage.SEARCH_COMPLETED, finalOutcome)
                                } finally {
                                    listener.close()
                                    activeDirectPeer?.close()
                                    activeIndirectPeer?.close()
                                    readers.shutdownNow()
                                }
                            }
                            activeListener = null
                        }
                    }
                } catch (_: A0ExperimentalLoginCodec.LoginDenied) {
                    finalReason = A0SmokeTrace.Reason.LOGIN_DENIED
                    trace.note(A0SmokeTrace.Stage.LOGIN_DENIED, TraceOutcome.FAILED, finalReason)
                    result = "LOGIN RECUSADO. Confira a conta e desconecte o Seeker."
                } catch (_: SocketTimeoutException) {
                    finalOutcome = TraceOutcome.DEGRADED
                    finalReason = A0SmokeTrace.Reason.READ_TIMEOUT
                    val readProgress = loginReplyProgress
                    if (!searchSubmitted && readProgress != null) {
                        trace.loginResponseProblem(readProgress.phase, timedOut = true)
                    } else {
                        trace.note(A0SmokeTrace.Stage.SERVER_READ_TIMEOUT, TraceOutcome.DEGRADED, finalReason)
                    }
                    result = if (searchSubmitted) {
                        "BUSCA ENVIADA; NENHUMA RESPOSTA P2P RECEBIDA. " +
                            "A rede móvel pode bloquear conexões de entrada."
                    } else {
                        "TIMEOUT. A conexão ou resposta do servidor não chegou."
                    }
                } catch (_: IllegalArgumentException) {
                    finalReason = A0SmokeTrace.Reason.INVALID_INPUT
                    trace.note(A0SmokeTrace.Stage.UNEXPECTED_FAILURE, TraceOutcome.FAILED, finalReason)
                    result = "ENTRADA INVÁLIDA. Confira usuário e senha de teste."
                } catch (error: IOException) {
                    val readProgress = loginReplyProgress
                    if (!searchSubmitted && readProgress != null) {
                        trace.loginResponseProblem(readProgress.phase, timedOut = false)
                    }
                    finalReason = when (error) {
                        is UnknownHostException -> A0SmokeTrace.Reason.DNS_FAILURE
                        is ConnectException -> A0SmokeTrace.Reason.CONNECT_FAILED
                        is SocketException -> A0SmokeTrace.Reason.SOCKET_CLOSED
                        else -> A0SmokeTrace.Reason.SERVER_IO_FAILURE
                    }
                    trace.note(A0SmokeTrace.Stage.IO_FAILURE, TraceOutcome.FAILED, finalReason)
                    result = "ERRO DE REDE OU PROTOCOLO. Nenhum arquivo foi baixado."
                } catch (_: RuntimeException) {
                    finalReason = A0SmokeTrace.Reason.UNKNOWN_FAILURE
                    trace.note(A0SmokeTrace.Stage.UNEXPECTED_FAILURE, TraceOutcome.FAILED, finalReason)
                    result = "ERRO NO TESTE. Nenhuma credencial foi armazenada."
                } finally {
                    activeListener?.close()
                    activeListener = null
                    packet?.fill(0)
                    secret.fill('\u0000')
                    activeSocket?.close()
                    activeSocket = null
                    trace.note(A0SmokeTrace.Stage.SOCKETS_CLOSED)
                    trace.finish(finalOutcome, finalReason)
                    activeTrace = null
                    if (captureEnabled) runCatching { Diagnostics.stopCapture() }
                }
                if (!stopping && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (!stopping && !isFinishing && !isDestroyed) {
                            status.text = result
                            action.isEnabled = true
                            searchAction.isEnabled = true
                        }
                    }
                }
            }
        }
        action.setOnClickListener { start(false) }
        searchAction.setOnClickListener { start(true) }
        exportAction.setOnClickListener {
            exportAction.isEnabled = false
            worker.execute {
                val report = runCatching { Diagnostics.createExport() }
                runOnUiThread {
                    exportAction.isEnabled = true
                    if (!stopping && !isFinishing && !isDestroyed) {
                        report.onSuccess { file ->
                            startActivity(Intent.createChooser(
                                DiagnosticsShare.createIntent(this@A0ManualLoginActivity, file),
                                "Compartilhar relatório de diagnóstico"
                            ))
                        }.onFailure {
                            status.text = "Falha ao exportar logs. Nenhum dado foi enviado."
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        stopping = true
        activeTrace?.note(
            A0SmokeTrace.Stage.STOP_REQUESTED,
            TraceOutcome.CANCELLED,
            A0SmokeTrace.Reason.CANCELLED
        )
        activeListener?.close() // Interrupt any pending direct peer result.
        activeDirectPeer?.close()
        activeIndirectPeer?.close()
        activeSocket?.close() // Interrupt unfinished server response.
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        stopping = false
    }

    override fun onDestroy() {
        activeListener?.close()
        activeDirectPeer?.close()
        activeIndirectPeer?.close()
        activeSocket?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val SERVER_HOST = "server.slsknet.org"
        const val SERVER_PORT = 2416
    }
}
