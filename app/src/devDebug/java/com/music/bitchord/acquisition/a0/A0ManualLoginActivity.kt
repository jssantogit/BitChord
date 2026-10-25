package com.music.bitchord.acquisition.a0

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.music.bitchord.diagnostics.Diagnostics
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SoulseekQualityPreset
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
import android.widget.Spinner
import android.widget.ArrayAdapter
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
    private val activeIndirectPeers=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var activePeerWorkers:A0PeerSearchWorkers?=null
    @Volatile private var stopping = false
    @Volatile private var activeTrace: A0SmokeTrace? = null
    @Volatile private var activeTransfer: A0ObservedCandidateHandoff? = null
    @Volatile private var activeSelectionQueue: LinkedBlockingQueue<Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launched by the in-app song Download action, not the old A0 tool entry.
        val fromSongDownload = intent.hasExtra("bitchord.soulseek.manual.query")
        // The song search/candidates view has no visible credentials; allow
        // screenshots for QA. Legacy username/password tools stay protected.
        if (A0ScreenCapturePolicy.requiresSecureWindow(fromSongDownload)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

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
        text(if (fromSongDownload) "Baixar com Soulseek" else
            "BitChord A0 — teste de conectividade P2P Soulseek")
        text(if (fromSongDownload)
            "Busque arquivos usando sua conta configurada em Configurações → Downloads. " +
                "A pesquisa não baixa arquivos automaticamente: você deverá escolher um " +
                "candidato e confirmar que tem autorização para salvá-lo."
        else
            "Somente para uma conta exclusiva de TESTE. " +
                "O protocolo Soulseek envia a senha por TCP SEM TLS. " +
                "Não reutilize senhas de outros serviços.")
        if (!fromSongDownload) text("A pesquisa digitável não baixa arquivos. No teste separado de transporte, " +
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
        text("Álbum solicitado (opcional)")
        val albumInput=EditText(this).apply {
            hint="Álbum da faixa (deixe vazio se desconhecido)"
            setText(intent.getStringExtra("bitchord.soulseek.manual.album")?.take(120).orEmpty())
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            isSingleLine=true
            filters=arrayOf(InputFilter.LengthFilter(120))
            isSaveEnabled=false
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
        }
        page.addView(albumInput)
        text("Qualidade desejada: declaração do peer, não áudio verificado")
        val qualityLabels=SoulseekQualityPreset.entries.map { it.label }
        val qualityPicker=Spinner(this).apply {
            adapter=ArrayAdapter(this@A0ManualLoginActivity,
                android.R.layout.simple_spinner_item, qualityLabels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            isSaveEnabled=false
            setSelection(AppSettings.soulseekQualityPreset.value.ordinal)
        }
        page.addView(qualityPicker)
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
        if (!fromSongDownload) text("Captura temporária se não existir uma captura manual ativa. " +
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
            // Keep the validated but unnormalized artist/title delimiter for
            // local ranking. Only the normalized term is sent over Soulseek.
            val enteredSearch=if(performSearch) searchInput.text.toString() else null
            val albumConstraint=albumInput.text.toString().trim().takeIf(String::isNotBlank)
            val preset=SoulseekQualityPreset.entries.getOrElse(
                qualityPicker.selectedItemPosition) { SoulseekQualityPreset.ANY }
            // Changing Soulseek quality never mutates legacy DownloadQuality
            // or initiates an automatic transfer.
            AppSettings.setSoulseekQualityPreset(preset)
            val selectedQuality=A0SoulseekQualityProfiles.snapshot(preset)
            if(albumConstraint!=null && runCatching {
                A0CanonicalAcquisitionIntent.manual("Busca manual",requestedAlbum=albumConstraint)
            }.isFailure) {
                status.text="Álbum inválido: remova caracteres de controle."
                return
            }
            val requestedSearch = if (performSearch) {
                try {
                    A0SearchQuery.normalize(checkNotNull(enteredSearch))
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
            // In a legacy manual search, stop exposing editable credentials
            // before allowing screenshots of results and candidate dialogs.
            user.isEnabled = false
            password.isEnabled = false
            if (performSearch && !A0ScreenCapturePolicy.requiresSecureWindow(
                    fromSongDownload, credentialsVisible = false)) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
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
                val featureCapture = runCatching {
                    Diagnostics.beginFeatureCapture(180_000L)
                }.getOrNull()
                val captureEnabled = featureCapture?.active == true
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
                            ServerSocket(0, 32).use { listener ->
                                activeListener = listener
                                trace.note(A0SmokeTrace.Stage.SEARCH_LISTENER_OPEN)
                                listener.soTimeout = 18_000
                                val token = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
                                val expectedDuration=intent.getIntExtra(
                                    "bitchord.soulseek.manual.duration_seconds",0
                                ).takeIf { it in 15..7200 }
                                val handoff = if (transferObserved) {
                                    A0ObservedCandidateHandoff(
                                        connection, username, token,
                                        searchPhrase=checkNotNull(enteredSearch),
                                        saveFormatsOnly=saveToDevice,
                                        expectedDurationSeconds=expectedDuration,
                                        requestedAlbum=albumConstraint,
                                        qualityProfile=selectedQuality,
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
                                fun discoveryProgress() {
                                    val report=handoff?.screening() ?: return
                                    runOnUiThread {
                                        if (!stopping && !isFinishing && !isDestroyed &&
                                            readerGate.isOpen()) {
                                            status.text="Pesquisando Soulseek: " +
                                                report.responses + " respostas, " +
                                                report.publicFiles + " arquivos anunciados, " +
                                                report.accepted + " candidatos disponíveis " +
                                                "(coleta ainda em andamento)."
                                        }
                                    }
                                }
                                // Collect peers over a fixed window; never stop at first match.
                                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
                                // The server reader only parses frames; indirect peer
                                // handshakes run concurrently on peer-only sockets.
                                val peerWorkers=A0PeerSearchWorkers()
                                activePeerWorkers=peerWorkers
                                val peerOfferBackpressure=java.util.concurrent.atomic.AtomicInteger(0)
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
                                                            discoveryProgress()
                                                        }
                                                    trace.note(A0SmokeTrace.Stage.DIRECT_REPLY_ACCEPTED)
                                                    if (!transferObserved) {
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
                                                readerGate.isOpen() && attempts < 36
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
                                                if (!peerWorkers.submit {
                                                    try {
                                                        trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECT_STARTED)
                                                        Socket().use { peer ->
                                                            activeIndirectPeers.add(peer)
                                                            try {
                                                                if (stopping || !readerGate.isOpen()) return@use
                                                                peer.connect(
                                                                    InetSocketAddress(offer.address,offer.port),1800
                                                                )
                                                                peer.soTimeout=2300
                                                                trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECTED)
                                                                peer.getOutputStream().apply {
                                                                    write(A0IndirectSearchWire.pierceFirewall(offer.token))
                                                                    flush()
                                                                }
                                                                trace.note(A0SmokeTrace.Stage.PIERCE_SENT)
                                                                val observation=A0IndirectSearchWire.readIndirectReply(
                                                                    peer.getInputStream(),token,offer.username
                                                                ) { frame, claimedPeer ->
                                                                    receivedValidP2pReply.set(true)
                                                                    handoff?.offerSearchResult(frame,claimedPeer)
                                                                    discoveryProgress()
                                                                }
                                                                trace.note(A0SmokeTrace.Stage.INDIRECT_REPLY_ACCEPTED)
                                                                if (!transferObserved) {
                                                                    readerGate.publish(inbox,
                                                                        "RESPOSTA P2P INDIRETA: " +
                                                                        "${observation.untrustedResultCount} entradas declaradas.")
                                                                }
                                                            } finally {
                                                                activeIndirectPeers.remove(peer)
                                                            }
                                                        }
                                                    } catch (_: SocketTimeoutException) {
                                                        trace.note(A0SmokeTrace.Stage.INDIRECT_READ_TIMEOUT,
                                                            TraceOutcome.DEGRADED,A0SmokeTrace.Reason.READ_TIMEOUT)
                                                    } catch (_: ConnectException) {
                                                        trace.note(A0SmokeTrace.Stage.INDIRECT_CONNECT_FAILED,
                                                            TraceOutcome.DEGRADED,A0SmokeTrace.Reason.CONNECT_FAILED)
                                                    } catch (_: IOException) {
                                                        trace.note(A0SmokeTrace.Stage.INDIRECT_REPLY_REJECTED,
                                                            TraceOutcome.DEGRADED,A0SmokeTrace.Reason.PEER_IO_FAILURE)
                                                    }
                                                }) {
                                                    peerOfferBackpressure.incrementAndGet()
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
                                    val peerStatus = inbox.poll(
                                        if (transferObserved) 18 else 12, TimeUnit.SECONDS
                                    )
                                    readerGate.stop()
                                    result = peerStatus ?: when {
                                        failedServerReader.get() ->
                                            "O canal do servidor foi interrompido durante a busca. " +
                                                "Nenhuma transferência iniciada."
                                        transferObserved && handoff?.hasCandidates() == true ->
                                            "Coleta concluída: " +
                                                (handoff?.screening()?.accepted ?: 0) +
                                                " candidatos disponíveis para seleção manual."
                                        transferObserved && receivedValidP2pReply.get() ->
                                            handoff?.screening()?.let { report ->
                                                "Recebidas " + report.responses + " respostas com " +
                                                    report.publicFiles + " arquivos públicos e " +
                                                    report.lockedFiles + " bloqueados. " +
                                                    "Descartados: " + report.unsupportedFormat +
                                                    " por formato, " + report.tooLarge +
                                                    " por tamanho, " + report.noSlot +
                                                    " por falta de slot, " + report.unsafePath +
                                                    " por nome inválido. Nenhum candidato selecionável."
                                            } ?: "Respostas sem candidatos elegíveis; nada foi baixado."
                                        transferObserved ->
                                            "Nenhuma resposta P2P utilizável na janela de 18 segundos. " +
                                                "Tente outro termo ou outra rede; nada foi baixado."
                                        attemptedIndirect.get() ->
                                            "NEGOCIAÇÃO INDIRETA TENTADA; NENHUMA RESPOSTA P2P " +
                                                "RECEBIDA. Nenhum download."
                                        failedServerReader.get() ->
                                            "ERRO DE REDE OU PROTOCOLO APÓS BUSCA. Nenhum download."
                                        else ->
                                            "BUSCA ENVIADA; NENHUMA RESPOSTA P2P RECEBIDA. " +
                                                "A rede pode bloquear conexões de entrada."
                                    }
                                    if (peerStatus == null && handoff?.hasCandidates() != true) {
                                        val cause = A0CandidateOutcome.classify(
                                            handoff?.screening() ?: A0ObservedCandidateHandoff.Screening(
                                                0,0,0,0,0,0,0,0
                                            ),
                                            validatedPeerReply = receivedValidP2pReply.get(),
                                            serverReaderFailed = failedServerReader.get()
                                        )
                                        val reason = when(cause) {
                                            A0CandidateOutcome.Kind.SERVER_READER_FAILURE ->
                                                A0SmokeTrace.Reason.SERVER_IO_FAILURE
                                            A0CandidateOutcome.Kind.NO_ELIGIBLE_CANDIDATE ->
                                                A0SmokeTrace.Reason.NO_ELIGIBLE_CANDIDATE
                                            else -> A0SmokeTrace.Reason.NO_PEER_REPLY
                                        }
                                        trace.note(
                                            when(cause) {
                                                A0CandidateOutcome.Kind.SERVER_READER_FAILURE ->
                                                    A0SmokeTrace.Stage.SERVER_READ_FAILURE
                                                A0CandidateOutcome.Kind.NO_ELIGIBLE_CANDIDATE ->
                                                    A0SmokeTrace.Stage.NO_ELIGIBLE_CANDIDATE
                                                else -> A0SmokeTrace.Stage.NO_REPLY
                                            },
                                            TraceOutcome.DEGRADED,reason
                                        )
                                        finalOutcome = TraceOutcome.DEGRADED
                                        finalReason = reason
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
                                            },
                                            timeoutMillis=5000
                                        )
                                        // The only remaining work owns separate P sockets.
                                        // Close them before P/F takes the server connection.
                                        activeIndirectPeers.toList().forEach { runCatching { it.close() } }
                                        if (!peerWorkers.stop(4500)) {
                                            throw IOException("A0 indirect peer tasks still active")
                                        }
                                        activePeerWorkers=null
                                        trace.note(A0SmokeTrace.Stage.SEARCH_READERS_JOINED)
                                        trace.peerSearchBackpressure(peerOfferBackpressure.get())
                                    } else {
                                        listener.close()
                                        activeDirectPeer?.close()
                                        activeIndirectPeers.toList().forEach { runCatching { it.close() } }
                                        peerWorkers.cancel()
                                        activePeerWorkers=null
                                        readers.shutdownNow()
                                    }
                                }
                                if (transferObserved && !stopping) {
                                    if (failedServerReader.get()) {
                                        handoff?.cancel()
                                        throw IOException("A0 authenticated server frame was interrupted")
                                    }
                                    val report=handoff?.screening()
                                    if(report!=null) {
                                        trace.candidateScreening(report)
                                        trace.canonicalCandidateScreening(report,preset.name)
                                    }
                                    if (handoff?.hasCandidates() == true) {
                                        checkNotNull(handoff).markReadersStopped()
                                        val choices=handoff.choices()
                                        // Observational hint only. Never initiates a transfer;
                                        // A0 still requires an explicit user-selected index.
                                        val suggestedIndex=handoff.manualSuggestionIndex()
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
                                                // Filters affect only presentation; the full
                                                // bounded ranked set stays in memory for retry.
                                                fun showChoices(formatFilter:String?=null,
                                                    qualityFilter:Int=0) {
                                                    val visible=selectableIndices.filter { i ->
                                                        val file=choices[i]
                                                        val ext=file.displayName.substringAfterLast('.', "")
                                                        val formatOk=formatFilter==null ||
                                                            ext.equals(formatFilter,ignoreCase=true)
                                                        val qualityOk=when(qualityFilter) {
                                                            1 -> file.matchScore>=65 &&
                                                                file.preflight.confidence!=A0CandidatePreflight.Confidence.UNSAFE
                                                            2 -> file.quality==A0CandidateRank.Quality.LOSSLESS_ADVERTISED
                                                            3 -> ext.equals("mp3",ignoreCase=true) &&
                                                                (file.declaredBitrateKbps ?: 0)>=256
                                                            4 -> A0CandidateRank.Warning.VERSION_MISMATCH !in file.warnings
                                                            else -> true
                                                        }
                                                        formatOk && qualityOk
                                                    }
                                                    val titles=visible.map { i ->
                                                        val candidate=choices[i]
                                                        val peer=candidate.peerUsername.filter {
                                                            it.code in 33..126
                                                        }.take(32)
                                                        val format=candidate.displayName
                                                            .substringAfterLast('.', "desconhecido")
                                                            .uppercase().take(6)
                                                        val match=when(candidate.matchSignal) {
                                                            A0CandidateRank.Match.ALL_TERMS ->
                                                                "todos os termos"
                                                            A0CandidateRank.Match.SOME_TERMS ->
                                                                "parte dos termos"
                                                            A0CandidateRank.Match.NO_TERMS ->
                                                                "sem correspondência forte"
                                                        }
                                                        val bitrate=candidate.declaredBitrateKbps?.let {
                                                            " — " + it + " kbps declarados"
                                                        }.orEmpty()
                                                        val quality=when(candidate.quality) {
                                                            A0CandidateRank.Quality.LOSSLESS_ADVERTISED ->
                                                                "lossless declarado"
                                                            A0CandidateRank.Quality.HIGH_ADVERTISED ->
                                                                "alta qualidade declarada"
                                                            A0CandidateRank.Quality.MEDIUM_ADVERTISED ->
                                                                "qualidade média declarada"
                                                            A0CandidateRank.Quality.LOW_ADVERTISED ->
                                                                "qualidade baixa declarada"
                                                            A0CandidateRank.Quality.UNKNOWN_ADVERTISED ->
                                                                "qualidade não confirmada"
                                                        }
                                                        val duration=candidate.declaredDurationSeconds?.let {
                                                            " — " + (it/60) + ":" +
                                                                (it%60).toString().padStart(2,'0')
                                                        }.orEmpty()
                                                        val flags=if(candidate.warnings.isEmpty()) ""
                                                            else " — alerta: " + candidate.warnings.joinToString {
                                                                it.name.lowercase()
                                                            }
                                                        val albumWarning=when(candidate.albumEvidence.status) {
                                                            A0CanonicalAlbumPolicy.Status.UNCONSTRAINED -> ""
                                                            A0CanonicalAlbumPolicy.Status.MATCHING_CLAIM_UNVERIFIED ->
                                                                " — álbum declarado compatível (não verificado)"
                                                            A0CanonicalAlbumPolicy.Status.INCONCLUSIVE_MANUAL ->
                                                                " — álbum não comprovado; conferir"
                                                            A0CanonicalAlbumPolicy.Status.CONFLICTING_CLAIM ->
                                                                " — ALERTA: pasta anuncia outro álbum"
                                                        }
                                                        val qualityWarning=when(candidate.qualityEvidence.status) {
                                                            A0DownloadQualityPolicy.Status.NOT_CONSTRAINED,
                                                            A0DownloadQualityPolicy.Status.COMPATIBLE_CLAIM_UNVERIFIED -> ""
                                                            A0DownloadQualityPolicy.Status.INCONCLUSIVE_MANUAL ->
                                                                " — qualidade desejada inconclusiva"
                                                            A0DownloadQualityPolicy.Status.INCOMPATIBLE ->
                                                                " — perfil solicitado não atendido"
                                                            A0DownloadQualityPolicy.Status.UNAVAILABLE ->
                                                                " — codec/preset indisponível"
                                                            A0DownloadQualityPolicy.Status.INVALID_PROFILE ->
                                                                " — restrição personalizada inválida"
                                                        }
                                                        val preflight=when(candidate.preflight.confidence) {
                                                            A0CandidatePreflight.Confidence.STRONG ->
                                                                "correspondência forte (não verificada)"
                                                            A0CandidatePreflight.Confidence.REVIEW ->
                                                                "conferir dados antes de baixar"
                                                            A0CandidatePreflight.Confidence.UNSAFE ->
                                                                "divergências nos metadados"
                                                        }
                                                        val reasons=when {
                                                            candidate.preflight.reasons.isEmpty() -> ""
                                                            else -> " — " + candidate.preflight.reasons.joinToString(
                                                                separator=", ",limit=3,truncated="..."
                                                            ) { reason -> when(reason) {
                                                                A0CandidatePreflight.Reason.TITLE_MISMATCH ->
                                                                    "título/artista divergentes"
                                                                A0CandidatePreflight.Reason.ARTIST_UNCONFIRMED ->
                                                                    "artista sem confirmação"
                                                                A0CandidatePreflight.Reason.ARTIST_CONFLICT ->
                                                                    "artista divergente no arquivo"
                                                                A0CandidatePreflight.Reason.IDENTITY_AMBIGUOUS ->
                                                                    "termos extras não identificados"
                                                                A0CandidatePreflight.Reason.VERSION_CONFLICT ->
                                                                    "versão alternativa"
                                                                A0CandidatePreflight.Reason.DURATION_UNCONFIRMED ->
                                                                    "duração não comparável"
                                                                A0CandidatePreflight.Reason.DURATION_CONFLICT ->
                                                                    "duração divergente"
                                                                A0CandidatePreflight.Reason.SIZE_CONFLICT ->
                                                                    "tamanho improvável"
                                                                A0CandidatePreflight.Reason.ENCODING_CONFLICT ->
                                                                    "codificação inconsistente"
                                                                A0CandidatePreflight.Reason.QUALITY_UNCONFIRMED ->
                                                                    "qualidade não confirmada"
                                                            } }
                                                        }
                                                        val source=when(candidate.peerAvailability.tier) {
                                                            A0PeerAvailability.Tier.READY ->
                                                                "fonte favorável (anunciada)"
                                                            A0PeerAvailability.Tier.UNKNOWN ->
                                                                "disponibilidade incerta"
                                                            A0PeerAvailability.Tier.CONGESTED ->
                                                                "fonte congestionada/lenta"
                                                            A0PeerAvailability.Tier.UNAVAILABLE ->
                                                                "fonte indisponível"
                                                        }
                                                        val queue=candidate.peerAvailability.queueLength
                                                            ?.let { " — fila anunciada " + it }.orEmpty()
                                                        val speed=candidate.peerAvailability.speedKiBps
                                                            ?.let { " — upload anunciado " + it + " KiB/s" }
                                                            .orEmpty()
                                                        val lead=if(i==suggestedIndex)
                                                            "Mais indicado para conferir — " else ""
                                                        lead + candidate.displayName + " — " + format + bitrate +
                                                            duration + " — " + candidate.sizeBytes + " bytes" +
                                                            " — " + quality + " — relevância " +
                                                            candidate.matchScore + " (" + match + ")" +
                                                            " — " + preflight + reasons + flags +
                                                            albumWarning + qualityWarning +
                                                            " — " + source + queue + speed +
                                                            " — slot livre anunciado — peer: " + peer
                                                    }.toTypedArray()
                                                    AlertDialog.Builder(this@A0ManualLoginActivity)
                                                        .setTitle("Candidatos (" + visible.size +
                                                            "/" + selectableIndices.size + ", até 64 MiB)")
                                                        .setItems(titles) { _, index ->
                                                            choiceQueue.offer(visible[index])
                                                        }
                                                        .setNeutralButton("Filtrar formato") { _, _ ->
                                                            val labels=arrayOf(
                                                                "Todos","Mais relevantes","Lossless declarado",
                                                                "MP3 ≥256 kbps","Ocultar versões alternativas",
                                                                "MP3","FLAC","WAV","OGG","M4A","AAC","OPUS"
                                                            )
                                                            val formats=arrayOf<String?>(
                                                                null,null,null,null,null,
                                                                "mp3","flac","wav","ogg","m4a","aac","opus"
                                                            )
                                                            val qualities=intArrayOf(
                                                                0,1,2,3,4,0,0,0,0,0,0,0
                                                            )
                                                            AlertDialog.Builder(this@A0ManualLoginActivity)
                                                                .setTitle("Filtrar (reversível)")
                                                                .setItems(labels) { _, index ->
                                                                    showChoices(formats[index],qualities[index])
                                                                }
                                                                .setNegativeButton("Voltar") { _, _ ->
                                                                    showChoices(formatFilter,qualityFilter)
                                                                }
                                                                .setOnCancelListener {
                                                                    showChoices(formatFilter,qualityFilter)
                                                                }.show()
                                                        }
                                                        .setNegativeButton("Cancelar") { _, _ ->
                                                            choiceQueue.offer(-1)
                                                        }
                                                        .setOnCancelListener {
                                                            choiceQueue.offer(-1)
                                                        }.show()
                                                }
                                                showChoices()
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
                                            "Não houve resposta útil de peers dentro do prazo. " +
                                            "Verifique a rede ou tente outra pesquisa; nada foi baixado."
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
                    result = when {
                        transferAttempted && error is A0IndirectFileOfferWire.MalformedFileOffer ->
                            "A oferta do canal F não corresponde ao protocolo esperado. " +
                                "O peer foi recusado por segurança; tente outro candidato."
                        transferAttempted &&
                            A0TransferFailureAdvice.classify(error,lastTransferPhase) ==
                            A0TransferFailureAdvice.Kind.SERVER_ADDRESS_PROTOCOL ->
                            "Falha na resposta do servidor ao localizar o peer. " +
                                "Nenhum arquivo foi publicado; tente novamente."
                        transferAttempted &&
                            A0TransferFailureAdvice.classify(error,lastTransferPhase) ==
                            A0TransferFailureAdvice.Kind.CONNECTION_INTERRUPTED ->
                            "A conexão P/F foi interrompida. Nenhum arquivo concluído; " +
                                "verifique a rede ou escolha outro candidato."
                        saveToDevice && transferAttempted ->
                            "TRANSFERÊNCIA OU PUBLICAÇÃO NÃO CONFIRMADA. " +
                                "Verifique a pasta e suas permissões antes de tentar novamente."
                        else -> "ERRO DE REDE OU PROTOCOLO. Nenhum arquivo foi baixado."
                    }
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
                    activePeerWorkers?.cancel()
                    activePeerWorkers=null
                    activeIndirectPeers.toList().forEach { runCatching { it.close() } }
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
                    // Release only the temporary capture this feature owns.
                    // A pre-existing or subsequently restarted manual capture
                    // must remain active with its original expiry.
                    featureCapture?.let { owned ->
                        runCatching { Diagnostics.endFeatureCapture(owned) }
                    }
                }
                if (!stopping && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (!stopping && !isFinishing && !isDestroyed) {
                            status.text = result
                            // Legacy probe exposes credential fields again:
                            // restore protection before enabling/editing them.
                            if (!fromSongDownload) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            }
                            user.isEnabled = true
                            password.isEnabled = true
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
        if (fromSongDownload) {
            // The old A0 diagnostic actions remain available only to internal
            // test callers. A normal Download tap immediately enters a guided
            // search, not a second login form or a technical transport menu.
            listOf<View>(
                user, password, acknowledge, action, searchAction,
                transferAction, saveConsent, saveAction, exportAction,
            ).forEach { it.visibility = View.GONE }

            fun confirmSearch() {
                val saved = accountVault.read()
                if (saved == null) {
                    status.text = "Configure sua conta em Configurações → Downloads → Soulseek."
                    AlertDialog.Builder(this)
                        .setTitle("Conta Soulseek necessária")
                        .setMessage("Conecte e salve uma conta dedicada em Configurações → Downloads → Soulseek antes de pesquisar.")
                        .setPositiveButton("Entendi") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                    return
                }
                user.setText(saved.username)
                saved.erase()
                AlertDialog.Builder(this)
                    .setTitle("Pesquisar candidatos Soulseek")
                    .setMessage(
                        "Esta pesquisa usa TCP sem TLS com sua conta de testes. " +
                        "Confirme que tem autorização para armazenar o arquivo que escolher. " +
                        "Nada será baixado até você selecionar um candidato."
                    )
                    .setPositiveButton("Pesquisar candidatos") { _, _ ->
                        acknowledge.isChecked = true
                        saveConsent.isChecked = true
                        start(true, transferObserved = true, saveToDevice = true)
                    }
                    .setNegativeButton("Cancelar") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
            val retry = Button(this).apply {
                text = "Pesquisar candidatos"
            }
            page.addView(retry, page.childCount - 1)
            retry.setOnClickListener { if (action.isEnabled) confirmSearch() }
            val alternative=Button(this).apply {
                text="Tentar variação da pesquisa"
            }
            page.addView(alternative,page.childCount - 1)
            alternative.setOnClickListener {
                if (!action.isEnabled) return@setOnClickListener
                val suggestions=runCatching {
                    A0SearchQuery.variants(searchInput.text.toString()).drop(1)
                }.getOrDefault(emptyList())
                if (suggestions.isEmpty()) {
                    status.text="Edite o termo acima e use Pesquisar candidatos novamente."
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Tentar outra consulta (manual)")
                        .setItems(suggestions.toTypedArray()) { _, index ->
                            searchInput.setText(suggestions[index])
                            confirmSearch()
                        }
                        .setNegativeButton("Cancelar",null)
                        .show()
                }
            }
            confirmSearch()
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
        activePeerWorkers?.cancel()
        activeIndirectPeers.toList().forEach { runCatching { it.close() } }
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
        activePeerWorkers?.cancel()
        activeIndirectPeers.toList().forEach { runCatching { it.close() } }
        activeSocket?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val SERVER_HOST = "server.slsknet.org"
        const val SERVER_PORT = 2416
    }
}
