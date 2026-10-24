package com.music.bitchord.acquisition.a0

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.diagnostics.DiagnosticsShare
import com.music.bitchord.observability.TraceOutcome
import com.music.bitchord.download.A3AcquisitionLedger
import com.music.bitchord.download.A3AndroidAcquisitionLedger
import android.text.InputType
import android.text.InputFilter
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
import java.util.UUID

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
    @Volatile private var activeTransfer: A0ObservedCandidateHandoff? = null
    @Volatile private var activeSelectionQueue: LinkedBlockingQueue<Int>? = null

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
        text("A pesquisa digitável não baixa arquivos. No teste separado de transporte, " +
             "você escolhe manualmente um arquivo público anunciado (até 64 MiB) " +
             "para o qual tenha autorização. Os bytes ficam só temporariamente: " +
             "tamanho e SHA-256 observado são medidos, depois apagados. " +
             "O hash NÃO comprova autenticidade da fonte. Soulseek usa TCP sem TLS. " +
             "Existe um botão SEPARADO para salvar UMA música na biblioteca local; " +
             "é opcional e requer confirmação de autorização.")
        val user = EditText(this).apply {
            hint = "Usuário dedicado"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        page.addView(user)
        val accountVault = SoulseekCredentialVault(this)
        val previouslySaved = accountVault.read()
        if (previouslySaved != null) {
            user.setText(previouslySaved.username)
            previouslySaved.erase()
        }
        val password = EditText(this).apply {
            hint = "Senha exclusiva de testes"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        page.addView(password)
        val searchInput = EditText(this).apply {
            hint = "O que deseja pesquisar? (ex.: newjeans - omg)"
            setText(intent.getStringExtra("bitchord.soulseek.manual.query")
                ?.take(120).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_NORMAL
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(120))
            isSaveEnabled = false // Neither the search term nor credentials persist.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        page.addView(searchInput)
        val acknowledge = CheckBox(this).apply {
            text = "Confirmo que é uma senha exclusiva de testes e aceito " +
                "a transmissão TCP sem TLS exigida pelo protocolo."
        }
        page.addView(acknowledge)
        val action = Button(this).apply { text = "Testar login uma única vez" }
        page.addView(action)
        val searchAction = Button(this).apply {
            text = "Pesquisar termo digitado (sem baixar)"
        }
        page.addView(searchAction)
        val transferAction = Button(this).apply {
            text = "Teste de transporte: escolher 1 arquivo (até 64 MiB)"
        }
        page.addView(transferAction)
        val saveConsent=CheckBox(this).apply {
            text="Confirmo que posso armazenar o arquivo escolhido. " +
                "O áudio ficará em Music/BitChord ou na pasta selecionada."
        }
        page.addView(saveConsent)
        val saveAction=Button(this).apply {
            text="Baixar 1 arquivo autorizado como música local (piloto devDebug)"
        }
        page.addView(saveAction)
        val exportAction = Button(this).apply { text = "Exportar logs A0 (TXT)" }
        page.addView(exportAction)
        text("Captura temporária iniciada automaticamente ao testar. " +
            "Depois do resultado, exporte os logs. Revise o relatório antes de compartilhar. " +
            "Não envie credenciais, capturas de rede ou logcat bruto.")
        val status = text("Nenhuma conexão iniciada.")

        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
        val observedScratchDirectory=getDir("a0-observed-probe",Context.MODE_PRIVATE)
        val observedScratchReady=runCatching {
            A0ObservedScratchRecovery.clean(observedScratchDirectory)
        }.isSuccess
        if(!observedScratchReady) {
            transferAction.isEnabled=false
            saveAction.isEnabled=false
            status.text="Falha ao limpar arquivos temporários do teste. Transferência bloqueada."
        }

        fun start(
            performSearch:Boolean,transferObserved:Boolean=false,saveToDevice:Boolean=false
        ) {
            require(!transferObserved || performSearch)
            require(!saveToDevice || transferObserved)
            if(saveToDevice && !A5ManualSaveGate.canSave(transferObserved,saveConsent.isChecked)) {
                status.text="Confirme antes que tem autorização para armazenar o arquivo."
                return
            }
            if(transferObserved && !observedScratchReady) {
                status.text="Armazenamento temporário inseguro. Nenhuma transferência permitida."
                return
            }
            if (!acknowledge.isChecked) {
                status.text = "Leia e confirme o aviso de segurança."
                return
            }
            // Validate on the UI thread BEFORE login or server I/O.
            // Never place search text in traces, diagnostics or exception messages.
            val requestedSearch = if (performSearch) {
                try {
                    A0SearchQuery.normalize(searchInput.text.toString())
                } catch (_: IllegalArgumentException) {
                    status.text = "Pesquisa inválida: digite termos curtos, sem controles."
                    return
                }
            } else null
            val username = user.text.toString()
            val savedAccount = if (password.text.isEmpty()) accountVault.read() else null
            val secret = if (savedAccount?.username == username) {
                savedAccount.password.copyOf()
            } else password.text.toString().toCharArray()
            savedAccount?.erase()
            if (username.isBlank() || secret.isEmpty()) {
                secret.fill('\u0000')
                status.text = "Configure ou conecte sua conta em Configurações → Downloads → Soulseek."
                return
            }
            // Clear both controls before any packet creation or network action.
            user.text.clear()
            password.text.clear()
            acknowledge.isChecked = false
            saveConsent.isChecked = false
            action.isEnabled = false
            searchAction.isEnabled = false
            transferAction.isEnabled = false
            saveAction.isEnabled = false
            status.text = if(saveToDevice) {
                "Buscando WAV/FLAC/MP3 autorizados; salva somente após escolha manual."
            } else if (transferObserved) {
                "Buscando candidatos pequenos; escolha manual obrigatória, sem download automático."
            } else if (performSearch) {
                "Autenticando e preparando uma única busca sem downloads."
            } else {
                "Verificando uma sessão; nenhum arquivo será transferido."
            }
            val accountGeneration = SoulseekActiveSession.generation()
            worker.execute {
                // Existing Diagnostics capture and structured tracing; no raw inputs.
                val captureEnabled = runCatching {
                    Diagnostics.startCapture(180_000L).active
                }.getOrDefault(false)
                val trace = A0SmokeTrace.start(
                    Diagnostics.structuredTracer(), observedTransfer = transferObserved
                )
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
                if(saveToDevice)trace.note(A0SmokeTrace.Stage.LOCAL_SAVE_REQUESTED)
                var finalOutcome = TraceOutcome.FAILED
                var finalReason: A0SmokeTrace.Reason? = null
                var transferAttempted=false
                var lastTransferPhase:A0ServerAssociatedTransfer.Phase?=null
                var packet: ByteArray? = null
                var result = "Falha ao iniciar a verificação."
                var searchSubmitted = false
                var loginReplyProgress: A0LoginReadProgress? = null
                var saveLedger:A3AcquisitionLedger?=null
                var saveJobId:String?=null
                try {
                    if(saveToDevice) {
                        val ledger=A3AndroidAcquisitionLedger.get(this@A0ManualLoginActivity)
                        val id=UUID.randomUUID().toString()
                        ledger.create(id)
                        ledger.advance(id,A3AcquisitionLedger.Phase.SEARCHING)
                        saveLedger=ledger
                        saveJobId=id
                    }
                    packet = A0ExperimentalLoginCodec.encode(username, secret)
                    trace.note(A0SmokeTrace.Stage.SERVER_CONNECT_STARTED)
                    Socket().use { connection ->
                        if (!SoulseekActiveSession.register(connection, accountGeneration)) {
                            throw IOException("Soulseek account was revoked")
                        }
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
                                val handoff = if (transferObserved) {
                                    A0ObservedCandidateHandoff(
                                        connection, username, token
                                    ).also { transfer ->
                                        if (!SoulseekActiveSession.registerTransfer(
                                                transfer, accountGeneration
                                            )) throw IOException("Soulseek account revoked")
                                        activeTransfer = transfer
                                    }
                                } else null
                                connection.getOutputStream().apply {
                                    write(A0OneShotPublicSearchWire.setWaitPort(listener.localPort))
                                    write(A0OneShotPublicSearchWire.fileSearch(
                                        token, checkNotNull(requestedSearch)
                                    ))
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
                                // Sticky completion: polling a valid reply must not restart peer reads.
                                val readerGate = A0SearchReaderGate()
                                val attemptedIndirect = AtomicBoolean(false)
                                val receivedValidP2pReply = AtomicBoolean(false)
                                val failedServerReader = AtomicBoolean(false)
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                                val readers = Executors.newFixedThreadPool(2)
                                try {
                                    readers.execute {
                                        listener.soTimeout = 1000
                                        var directWaits = 0
                                        while (!Thread.currentThread().isInterrupted &&
                                            !listener.isClosed && System.nanoTime() < deadline &&
                                            readerGate.isOpen()
                                        ) {
                                            try {
                                                listener.accept().use { peer ->
                                                    if (!readerGate.isOpen()) return@use
                                                    trace.note(A0SmokeTrace.Stage.DIRECT_ACCEPTED)
                                                    activeDirectPeer = peer
                                                    peer.soTimeout = 4000
                                                    val observation =
                                                        A0OneShotPublicSearchWire.readOneDirectPeerReply(
                                                            peer.getInputStream(), token
                                                        ) { verifiedFrame, claimedPeer ->
                                                            receivedValidP2pReply.set(true)
                                                            handoff?.offerSearchResult(
                                                                verifiedFrame, claimedPeer
                                                            )
                                                        }
                                                    trace.note(A0SmokeTrace.Stage.DIRECT_REPLY_ACCEPTED)
                                                    if (!transferObserved ||
                                                        handoff?.hasCandidates() == true
                                                    ) {
                                                        readerGate.publish(
                                                            inbox, "RESPOSTA P2P DIRETA: " +
                                                                "${observation.untrustedResultCount} entradas " +
                                                                "declaradas, não verificadas."
                                                        )
                                                    }
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
                                        // A bounded socket timeout lets this reader
                                        // relinquish server ownership before P/F routing.
                                        connection.soTimeout = 750
                                        val framedServer = A0FrameReadProgress(
                                            connection.getInputStream()
                                        )
                                        var attempts = 0
                                        try {
                                            while (!Thread.currentThread().isInterrupted &&
                                                System.nanoTime() < deadline &&
                                                readerGate.isOpen() && attempts < 6
                                            ) {
                                                framedServer.nextFrame()
                                                val offer = try {
                                                    A0IndirectSearchWire.readServerEvent(
                                                        framedServer
                                                    ) { code -> trace.ignoredServerFrame(code) }
                                                } catch (_: SocketTimeoutException) {
                                                    if (!framedServer.atFrameBoundary) {
                                                        // A frame was only partially consumed.
                                                        // Never hand its corrupted stream to P/F.
                                                        failedServerReader.set(true)
                                                        trace.note(
                                                            A0SmokeTrace.Stage.SERVER_READ_FAILURE,
                                                            TraceOutcome.DEGRADED,
                                                            A0SmokeTrace.Reason.SERVER_IO_FAILURE
                                                        )
                                                        break
                                                    }
                                                    continue
                                                } catch (_: A0IndirectSearchWire.RejectedPeerOffer) {
                                                    trace.note(
                                                        A0SmokeTrace.Stage.SERVER_OFFER_REJECTED,
                                                        TraceOutcome.DEGRADED,
                                                        A0SmokeTrace.Reason.WIRE_REJECTED
                                                    )
                                                    continue
                                                }
                                                if (offer == null) continue
                                                if (!readerGate.isOpen()) break
                                                trace.note(A0SmokeTrace.Stage.SERVER_OFFER_RECEIVED)
                                                attemptedIndirect.set(true)
                                                attempts++
                                                try {
                                                    trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECT_STARTED)
                                                    Socket().use { peer ->
                                                        activeIndirectPeer = peer
                                                        if (!readerGate.isOpen()) return@use
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
                                                        ) { verifiedFrame, claimedPeer ->
                                                            receivedValidP2pReply.set(true)
                                                            handoff?.offerSearchResult(
                                                                verifiedFrame, claimedPeer
                                                            )
                                                        }
                                                        trace.note(A0SmokeTrace.Stage.INDIRECT_REPLY_ACCEPTED)
                                                        if (!transferObserved ||
                                                            handoff?.hasCandidates() == true
                                                        ) {
                                                            readerGate.publish(
                                                                inbox, "RESPOSTA P2P INDIRETA: " +
                                                                    "${observation.untrustedResultCount} entradas " +
                                                                    "declaradas, não verificadas."
                                                            )
                                                        }
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
                                    readerGate.stop()
                                    result = peerStatus ?: when {
                                        transferObserved ->
                                            "NENHUM CANDIDATO DE ÁUDIO DISPONÍVEL ATÉ 64 MiB. " +
                                                "Nenhuma transferência iniciada."
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
                                        val noCandidate = transferObserved &&
                                            receivedValidP2pReply.get()
                                        trace.note(
                                            if (noCandidate)
                                                A0SmokeTrace.Stage.NO_ELIGIBLE_CANDIDATE
                                            else A0SmokeTrace.Stage.NO_REPLY,
                                            TraceOutcome.DEGRADED,
                                            if (noCandidate)
                                                A0SmokeTrace.Reason.NO_ELIGIBLE_CANDIDATE
                                            else A0SmokeTrace.Reason.NO_PEER_REPLY
                                        )
                                        finalOutcome = TraceOutcome.DEGRADED
                                        finalReason = if (noCandidate)
                                            A0SmokeTrace.Reason.NO_ELIGIBLE_CANDIDATE
                                        else A0SmokeTrace.Reason.NO_PEER_REPLY
                                    } else {
                                        finalOutcome = TraceOutcome.SUCCEEDED
                                    }
                                    trace.note(A0SmokeTrace.Stage.SEARCH_COMPLETED, finalOutcome)
                                } finally {
                                    readerGate.stop()
                                    if(transferObserved && !stopping) {
                                        // Do not interrupt a server Socket read then
                                        // reuse that very socket for P/F on Android.
                                        A0SearchReaderHandoff.stop(
                                            readers,
                                            closeSearchSockets={
                                                listener.close()
                                                activeDirectPeer?.close()
                                                activeIndirectPeer?.close()
                                            },
                                            timeoutMillis=5000
                                        )
                                        trace.note(A0SmokeTrace.Stage.SEARCH_READERS_JOINED)
                                    } else {
                                        listener.close()
                                        activeDirectPeer?.close()
                                        activeIndirectPeer?.close()
                                        readers.shutdownNow()
                                    }
                                }
                                if (transferObserved && !stopping) {
                                    if (failedServerReader.get()) {
                                        handoff?.cancel()
                                        throw IOException("A0 authenticated server frame was interrupted")
                                    }
                                    val report=handoff?.screening()
                                    if(report!=null) trace.candidateScreening(report)
                                    if (handoff?.hasCandidates() == true) {
                                        checkNotNull(handoff).markReadersStopped()
                                        val choices=handoff.choices()
                                        val selectableIndices=choices.indices.filter { i ->
                                            !saveToDevice || A5ManualSaveGate.safeDestinationName(
                                                choices[i].displayName,choices[i].sizeBytes
                                            )!=null
                                        }
                                        trace.note(A0SmokeTrace.Stage.CANDIDATES_AVAILABLE)
                                        if(saveToDevice)saveLedger?.advance(
                                            checkNotNull(saveJobId),
                                            A3AcquisitionLedger.Phase.AWAITING_SELECTION
                                        )
                                        val choiceQueue=LinkedBlockingQueue<Int>(1)
                                        activeSelectionQueue=choiceQueue
                                        runOnUiThread {
                                            if (stopping || isFinishing || isDestroyed ||
                                                selectableIndices.isEmpty()) {
                                                if(selectableIndices.isEmpty())status.text=
                                                    "Nenhum WAV/FLAC/MP3 compatível nesta busca para salvar."
                                                choiceQueue.offer(-1)
                                            } else {
                                                status.text = if(saveToDevice)
                                                    "Escolha UM arquivo autorizado para salvar como música local."
                                                else "Selecione UM arquivo autorizado. Nenhum download automático."
                                                val titles=selectableIndices.map { i ->
                                                    val candidate=choices[i]
                                                    val peer = candidate.peerUsername.filter {
                                                        it.code in 33..126
                                                    }.take(32)
                                                    val format = candidate.displayName
                                                        .substringAfterLast('.', "desconhecido")
                                                        .uppercase().take(6)
                                                    "${candidate.displayName} — formato indicado ${format} — " +
                                                        "${candidate.sizeBytes} bytes — peer: ${peer}"
                                                }.toTypedArray()
                                                // A list dialog must not also setMessage():
                                                // Android's content controller may hide its list.
                                                AlertDialog.Builder(this@A0ManualLoginActivity)
                                                    .setTitle("Escolha um arquivo autorizado (até 64 MiB)")
                                                    .setItems(titles) { _, index ->
                                                        choiceQueue.offer(selectableIndices[index])
                                                    }
                                                    .setNegativeButton("Cancelar") { _, _ ->
                                                        choiceQueue.offer(-1)
                                                    }
                                                    .setOnCancelListener {
                                                        choiceQueue.offer(-1)
                                                    }
                                                    .show()
                                            }
                                        }
                                        // Retain exclusive server-stream ownership while
                                        // the user is choosing a file. Unrelated inbound
                                        // search offers must not pile up and consume the
                                        // P/F peer-address lookup budget.
                                        val selection=A0SelectionServerDrain.awaitChoice(
                                            connection,choiceQueue,35_000L,
                                            isCancelled={
                                                stopping || Thread.currentThread().isInterrupted
                                            }
                                        )
                                        trace.selectionDrain(selection.discardedFrames)
                                        val chosen=selection.index
                                        activeSelectionQueue=null
                                        if (chosen==null || chosen<0 || stopping) {
                                            result = "TESTE CANCELADO OU EXPIRADO. Nenhum arquivo recebido."
                                            finalOutcome=TraceOutcome.CANCELLED
                                            trace.note(
                                                A0SmokeTrace.Stage.MANUAL_SELECTION_CANCELLED,
                                                TraceOutcome.CANCELLED
                                            )
                                        } else {
                                            if(saveToDevice) {
                                                val id=checkNotNull(saveJobId)
                                                saveLedger?.advance(id,A3AcquisitionLedger.Phase.SELECTED)
                                                saveLedger?.advance(id,A3AcquisitionLedger.Phase.TRANSFERRING)
                                            }
                                            trace.note(A0SmokeTrace.Stage.P_F_TRANSFER_STARTED)
                                            transferAttempted=true
                                            val receipt=handoff.transferChoice(
                                                chosen,observedScratchDirectory
                                            ) { phase ->
                                                lastTransferPhase=phase
                                                trace.transferPhase(phase)
                                            }
                                            try {
                                                trace.note(A0SmokeTrace.Stage.P_F_TRANSFER_COMPLETED)
                                                if(saveToDevice) {
                                                    val id=checkNotNull(saveJobId)
                                                    saveLedger?.advance(id,A3AcquisitionLedger.Phase.STAGED)
                                                    trace.note(A0SmokeTrace.Stage.LOCAL_SAVE_STAGED)
                                                    val name=checkNotNull(
                                                        A5ManualSaveGate.safeDestinationName(
                                                            choices[chosen].displayName,receipt.byteCount
                                                        )
                                                    )
                                                    val saved=A3AndroidAcquisitionLedger.publishStaged(
                                                        this@A0ManualLoginActivity,
                                                        id,receipt.file,name,
                                                        receipt.byteCount,receipt.sha256Hex,
                                                        isCancelled={
                                                            stopping || Thread.currentThread().isInterrupted ||
                                                                !SoulseekActiveSession.isCurrent(accountGeneration)
                                                        },
                                                        commitGuard={ commit ->
                                                            SoulseekActiveSession.commitIfCurrent(
                                                                accountGeneration, commit
                                                            )
                                                        }
                                                    )
                                                    trace.note(A0SmokeTrace.Stage.LOCAL_SAVE_PUBLISHED)
                                                    result="MÚSICA LOCAL SALVA: ${saved.byteCount} bytes. " +
                                                        "Confira Biblioteca → No dispositivo. " +
                                                        "SHA-256 observado não comprova autenticidade."
                                                }else {
                                                    result="TRANSPORTE CONCLUÍDO: ${receipt.byteCount} bytes. " +
                                                        "SHA-256 observado: ${receipt.sha256Hex}. " +
                                                        "Não comprova autenticidade. Arquivo temporário apagado."
                                                }
                                                finalOutcome=TraceOutcome.SUCCEEDED
                                                finalReason=null
                                            } finally {
                                                if (!receipt.file.delete()) {
                                                    throw IOException("A0 observed scratch cleanup failed")
                                                }
                                                trace.note(A0SmokeTrace.Stage.SCRATCH_CLEANUP)
                                            }
                                        }
                                    } else {
                                        val screened=checkNotNull(report)
                                        val classified=A0CandidateOutcome.classify(
                                            screened,receivedValidP2pReply.get()
                                        )
                                        result=if(classified ==
                                            A0CandidateOutcome.Kind.NO_ELIGIBLE_CANDIDATE) {
                                            "RESPOSTA P2P RECEBIDA: ${screened.responses} respostas, " +
                                            "${screened.publicFiles} arquivos anunciados, " +
                                            "0 elegíveis (tamanho: ${screened.tooLarge}, " +
                                            "formato: ${screened.unsupportedFormat}, " +
                                            "sem slot: ${screened.noSlot}, " +
                                            "caminho inválido: ${screened.unsafePath}, " +
                                            "bloqueados: ${screened.lockedFiles}). " +
                                            "Nenhuma transferência iniciada."
                                        } else {
                                            "BUSCA ENVIADA; NENHUMA RESPOSTA P2P VÁLIDA. " +
                                            "Nenhuma transferência iniciada."
                                        }
                                        finalOutcome=TraceOutcome.DEGRADED
                                        finalReason=if(classified ==
                                            A0CandidateOutcome.Kind.NO_ELIGIBLE_CANDIDATE)
                                            A0SmokeTrace.Reason.NO_ELIGIBLE_CANDIDATE
                                        else A0SmokeTrace.Reason.NO_PEER_REPLY
                                    }
                                } else if (!transferObserved && finalOutcome ==
                                    TraceOutcome.SUCCEEDED) {
                                    result += " Nenhum download."
                                }
                            }
                            activeListener = null
                        }
                    }
                } catch (_: A0ExperimentalLoginCodec.LoginDenied) {
                    finalOutcome=TraceOutcome.FAILED
                    finalReason = A0SmokeTrace.Reason.LOGIN_DENIED
                    trace.note(A0SmokeTrace.Stage.LOGIN_DENIED, TraceOutcome.FAILED, finalReason)
                    result = "LOGIN RECUSADO. Confira a conta e desconecte o Seeker."
                } catch (error: SocketTimeoutException) {
                    if(transferAttempted) {
                        trace.transferFailure(lastTransferPhase,error)
                    }
                    finalOutcome = if(transferAttempted) TraceOutcome.FAILED
                        else TraceOutcome.DEGRADED
                    finalReason = A0SmokeTrace.Reason.READ_TIMEOUT
                    val readProgress = loginReplyProgress
                    if (!searchSubmitted && readProgress != null) {
                        trace.loginResponseProblem(readProgress.phase, timedOut = true)
                    } else {
                        trace.note(A0SmokeTrace.Stage.SERVER_READ_TIMEOUT, TraceOutcome.DEGRADED, finalReason)
                    }
                    result = if(transferAttempted) {
                        "TIMEOUT NA NEGOCIAÇÃO P/F. Nenhum arquivo concluído."
                    } else if (searchSubmitted) {
                        "BUSCA ENVIADA; NENHUMA RESPOSTA P2P RECEBIDA. " +
                            "A rede móvel pode bloquear conexões de entrada."
                    } else {
                        "TIMEOUT. A conexão ou resposta do servidor não chegou."
                    }
                } catch (error: IllegalArgumentException) {
                    if(transferAttempted)trace.transferFailure(lastTransferPhase,error)
                    finalOutcome=TraceOutcome.FAILED
                    finalReason = A0SmokeTrace.Reason.INVALID_INPUT
                    trace.note(A0SmokeTrace.Stage.UNEXPECTED_FAILURE, TraceOutcome.FAILED, finalReason)
                    result = "ENTRADA INVÁLIDA. Confira usuário e senha de teste."
                } catch (error: IOException) {
                    if(transferAttempted)trace.transferFailure(lastTransferPhase,error)
                    finalOutcome=TraceOutcome.FAILED
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
                    result = if(saveToDevice && transferAttempted)
                        "TRANSFERÊNCIA OU GRAVAÇÃO FALHOU. Confira pasta/permissões; " +
                            "não foi confirmado salvamento no histórico."
                    else "ERRO DE REDE OU PROTOCOLO. Nenhum arquivo foi baixado."
                } catch (error: RuntimeException) {
                    if(transferAttempted)trace.transferFailure(lastTransferPhase,error)
                    finalOutcome=TraceOutcome.FAILED
                    finalReason = A0SmokeTrace.Reason.UNKNOWN_FAILURE
                    trace.note(A0SmokeTrace.Stage.UNEXPECTED_FAILURE, TraceOutcome.FAILED, finalReason)
                    result = "ERRO NO TESTE. Nenhuma credencial foi armazenada."
                } finally {
                    val id=saveJobId
                    val journal=saveLedger
                    if(id!=null && journal!=null)runCatching {
                        val phase=journal.get(id)?.phase
                        if(phase!=null && phase !in setOf(
                            A3AcquisitionLedger.Phase.AVAILABLE,
                            A3AcquisitionLedger.Phase.INTERRUPTED,
                            A3AcquisitionLedger.Phase.FAILED,
                            A3AcquisitionLedger.Phase.CANCELLED
                        )) journal.advance(
                            id,if(finalOutcome==TraceOutcome.CANCELLED)
                                A3AcquisitionLedger.Phase.CANCELLED
                            else A3AcquisitionLedger.Phase.FAILED
                        )
                    }
                    if(saveToDevice && finalOutcome==TraceOutcome.FAILED &&
                        (id==null || journal?.get(id)?.phase!=A3AcquisitionLedger.Phase.AVAILABLE)
                    )trace.note(
                        A0SmokeTrace.Stage.LOCAL_SAVE_FAILED,TraceOutcome.FAILED
                    )
                    activeSelectionQueue?.offer(-1)
                    activeSelectionQueue=null
                    activeTransfer?.let { transfer ->
                        SoulseekActiveSession.detachTransfer(transfer)
                        transfer.cancel()
                    }
                    activeTransfer = null
                    activeListener?.close()
                    activeListener = null
                    packet?.fill(0)
                    secret.fill('\u0000')
                    activeSocket?.let { SoulseekActiveSession.detach(it) }
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
                            val availableAccount = accountVault.read()
                            if (availableAccount != null) {
                                user.setText(availableAccount.username)
                                availableAccount.erase()
                            }
                            searchAction.isEnabled = true
                            transferAction.isEnabled = observedScratchReady
                            saveAction.isEnabled = observedScratchReady
                        }
                    }
                }
            }
        }
        action.setOnClickListener { start(false) }
        searchAction.setOnClickListener { start(true) }
        transferAction.setOnClickListener { start(true, transferObserved = true) }
        saveAction.setOnClickListener {
            start(true,transferObserved=true,saveToDevice=true)
        }
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
        activeSelectionQueue?.offer(-1)
        activeTransfer?.cancel()
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
        activeSelectionQueue?.offer(-1)
        activeTransfer?.cancel()
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
