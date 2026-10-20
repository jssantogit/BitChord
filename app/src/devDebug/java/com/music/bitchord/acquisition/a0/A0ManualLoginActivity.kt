package com.music.bitchord.acquisition.a0

import android.app.Activity
import android.os.Bundle
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
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
        text("BitChord A0 — teste de login Soulseek")
        text("Somente para uma conta exclusiva de TESTE. " +
             "O protocolo Soulseek envia a senha por TCP SEM TLS. " +
             "Não reutilize senhas de outros serviços.")
        text("O primeiro botão testa apenas o login. O segundo envia UMA pesquisa " +
             "por um nome de áudio CC0 e tenta resposta P2P direta ou indireta. " +
             "Nenhum botão compartilha ou baixa arquivos. " +
             "Desconecte o Seeker antes de iniciar.")
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
            text = "Pesquisar arquivo CC0 (sem baixar)"
        }
        page.addView(searchAction)
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
                var packet: ByteArray? = null
                var result = "Falha ao iniciar a verificação."
                var searchSubmitted = false
                try {
                    packet = A0ExperimentalLoginCodec.encode(username, secret)
                    Socket().use { connection ->
                        activeSocket = connection
                        // One fixed, documented SoulseekQt server endpoint.
                        // No user-controlled URL, proxy, redirects or fallback.
                        connection.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), 7000)
                        connection.soTimeout = 7000
                        connection.getOutputStream().write(packet)
                        connection.getOutputStream().flush()
                        // Wipe before receiving a reply, which might stall.
                        packet.fill(0)
                        packet = null
                        A0ExperimentalLoginCodec.readResponse(connection.getInputStream())
                        if (!performSearch) {
                            result = "LOGIN CONFIRMADO. Sessão encerrada, sem buscas ou downloads."
                        } else {
                            ServerSocket(0, 1).use { listener ->
                                activeListener = listener
                                listener.soTimeout = 12_000
                                val token = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
                                connection.getOutputStream().apply {
                                    write(A0OneShotPublicSearchWire.setWaitPort(listener.localPort))
                                    write(A0OneShotPublicSearchWire.fileSearch(token))
                                    flush()
                                }
                                searchSubmitted = true
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
                                        while (!Thread.currentThread().isInterrupted &&
                                            !listener.isClosed && System.nanoTime() < deadline &&
                                            inbox.isEmpty()
                                        ) {
                                            try {
                                                listener.accept().use { peer ->
                                                    activeDirectPeer = peer
                                                    peer.soTimeout = 4000
                                                    val observation =
                                                        A0OneShotPublicSearchWire.readOneDirectPeerReply(
                                                            peer.getInputStream(), token
                                                        )
                                                    inbox.offer(if (observation.untrustedFilenameMatches > 0) {
                                                        "RESPOSTA P2P DIRETA: correspondência " +
                                                            "não verificada. Nenhum download."
                                                    } else {
                                                        "RESPOSTA P2P DIRETA: sem correspondência. " +
                                                            "Nenhum download."
                                                    })
                                                }
                                            } catch (_: SocketTimeoutException) {
                                                // Retry accept only within the single search deadline.
                                            } catch (_: IOException) {
                                                if (listener.isClosed) break
                                                // One malformed/unrelated peer cannot end the probe.
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
                                                val offer = A0IndirectSearchWire.readServerEvent(
                                                    connection.getInputStream()
                                                ) ?: continue
                                                attemptedIndirect.set(true)
                                                attempts++
                                                try {
                                                    Socket().use { peer ->
                                                        activeIndirectPeer = peer
                                                        peer.connect(
                                                            InetSocketAddress(offer.address, offer.port), 2500
                                                        )
                                                        peer.soTimeout = 4000
                                                        peer.getOutputStream().apply {
                                                            write(A0IndirectSearchWire.pierceFirewall(offer.token))
                                                            flush()
                                                        }
                                                        val observation = A0IndirectSearchWire.readIndirectReply(
                                                            peer.getInputStream(), token, offer.username
                                                        )
                                                        inbox.offer(if (observation.untrustedFilenameMatches > 0) {
                                                            "RESPOSTA P2P INDIRETA: correspondência " +
                                                                "não verificada. Nenhum download."
                                                        } else {
                                                            "RESPOSTA P2P INDIRETA: sem correspondência. " +
                                                                "Nenhum download."
                                                        })
                                                    }
                                                } catch (_: IOException) {
                                                    // Bounded fallback failed; never expose endpoint/user.
                                                } finally {
                                                    activeIndirectPeer = null
                                                }
                                            }
                                        } catch (_: SocketTimeoutException) {
                                            // No additional server negotiation within 12 seconds.
                                        } catch (_: IOException) {
                                            failedServerReader.set(true)
                                        }
                                    }
                                    result = inbox.poll(12, TimeUnit.SECONDS) ?: when {
                                        attemptedIndirect.get() ->
                                            "NEGOCIAÇÃO INDIRETA TENTADA; NENHUMA RESPOSTA P2P " +
                                                "RECEBIDA. Nenhum download."
                                        failedServerReader.get() ->
                                            "ERRO DE REDE OU PROTOCOLO APÓS BUSCA. Nenhum download."
                                        else ->
                                            "BUSCA ENVIADA; NENHUMA RESPOSTA P2P RECEBIDA. " +
                                                "A rede pode bloquear conexões de entrada."
                                    }
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
                    result = "LOGIN RECUSADO. Confira a conta e desconecte o Seeker."
                } catch (_: SocketTimeoutException) {
                    result = if (searchSubmitted) {
                        "BUSCA ENVIADA; NENHUMA RESPOSTA P2P RECEBIDA. " +
                            "A rede móvel pode bloquear conexões de entrada."
                    } else {
                        "TIMEOUT. A conexão ou resposta do servidor não chegou."
                    }
                } catch (_: IllegalArgumentException) {
                    result = "ENTRADA INVÁLIDA. Confira usuário e senha de teste."
                } catch (_: IOException) {
                    result = "ERRO DE REDE OU PROTOCOLO. Nenhum arquivo foi baixado."
                } catch (_: RuntimeException) {
                    result = "ERRO NO TESTE. Nenhuma credencial foi armazenada."
                } finally {
                    activeListener?.close()
                    activeListener = null
                    packet?.fill(0)
                    secret.fill('\u0000')
                    activeSocket?.close()
                    activeSocket = null
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
    }

    override fun onStop() {
        stopping = true
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
