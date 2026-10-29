package com.music.bitchord.acquisition.a0

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SoulseekBatchPayload
import com.music.bitchord.download.SoulseekBatchPlan
import com.music.bitchord.download.SoulseekManualRecoveryTermination
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit human review of candidates ALREADY found by auto discovery.
 *
 * Peer username/path never crosses Intent or savedInstanceState. The original
 * one-use Downloads lease grants access to in-process handles. The selected
 * candidate opens a fresh authenticated P/F connection, performs technical
 * checks and uses A3/A2 publication; this Activity does NOT repeat search.
 * Process death, backgrounding or uncertain publication fail closed.
 */
class A0RetainedCandidateReviewActivity:Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val stopped=AtomicBoolean(false)
    @Volatile private var transport:A0FreshAuthenticatedAutoTransport?=null
    private var ownsLease=false
    private var sessionId:String?=null
    private var leaseToken:String?=null

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState!=null) {
            Toast.makeText(this,
                "Revisão interrompida. Confira a biblioteca antes de tentar novamente.",
                Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val session=intent.getStringExtra(
            "bitchord.soulseek.manual.review_session").orEmpty()
        val token=intent.getStringExtra(
            "bitchord.soulseek.manual.review_token").orEmpty()
        val indices=intent.getIntArrayExtra(
            "bitchord.soulseek.manual.review_indices")?.toList().orEmpty()
        val entries=if(intent.hasExtra("bitchord.soulseek.batch.queries")) {
            SoulseekBatchPayload.decode(
                intent.getStringArrayListExtra("bitchord.soulseek.batch.queries"),
                intent.getStringArrayListExtra("bitchord.soulseek.batch.albums"),
                intent.getIntArrayExtra("bitchord.soulseek.batch.durations"))
        }else {
            listOf(SoulseekBatchPlan.Entry(
                "single-review",
                intent.getStringExtra("bitchord.soulseek.manual.query").orEmpty(),
                intent.getStringExtra("bitchord.soulseek.manual.album")
                    ?.takeIf { it.isNotBlank() },
                intent.getIntExtra("bitchord.soulseek.manual.duration_seconds",0)
                    .takeIf { it in 15..7200 }))
        }
        if(session.isBlank() || token.isBlank() || entries==null ||
            indices.size!=entries.size || indices.distinct().size!=indices.size ||
            !Downloads.validSoulseekManualReview(
                session,token,indices,entries)) {
            Toast.makeText(this,
                "Revisão expirada. Nenhum arquivo será transferido.",
                Toast.LENGTH_LONG).show()
            finish()
            return
        }
        ownsLease=true
        sessionId=session
        leaseToken=token
        val initial=Downloads.claimedSoulseekReviewPlan(session,token)
        if(initial==null || !initial.hasRetainedCandidates ||
            initial.qualityProfile==null) {
            finish()
            return
        }

        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(30,30,30,36)
        }
        fun label(text:String,size:Float=15f):TextView=TextView(this).also {
            it.text=text
            it.textSize=size
            it.setPadding(0,10,0,10)
            layout.addView(it)
        }
        label("Revisar candidatos encontrados",22f)
        label("Resultados da busca automática, sem pesquisar novamente. " +
            "Toque em um arquivo para conferir e autorizar o salvamento. " +
            "Nome, álbum e qualidade são declarações do peer, não garantias " +
            "da gravação. Arquivos já publicados não aparecem aqui.")
        val actionButtons=mutableListOf<Pair<Int,Button>>()
        val attempted=HashSet<Int>()
        val global=label("Nenhuma transferência iniciada.")
        for(index in initial.manualIndices) {
            val row=initial.rows[index]
            val choices=initial.candidatesForManual(index)
            label(row.entry.query,19f)
            label(row.entry.album.orEmpty()+
                " · "+choices.size+" candidato(s) da pesquisa anterior")
            if(choices.isEmpty()) {
                label("Nenhum candidato adequado foi preservado para esta faixa. " +
                    "Uma nova pesquisa manual será necessária.")
                continue
            }
            val outcome=label("Aguardando sua revisão.")
            choices.forEachIndexed { position,choice ->
                val claim=choice.claim
                val sizeMiB=choice.sizeBytes/1_048_576L
                val folder=A0CandidateRank.safeDisplayName(
                    choice.remotePath.replace('\\','/')
                        .substringBeforeLast('/').substringAfterLast('/')
                ).take(80)
                val details=buildString {
                    append(choice.displayName)
                    append("\n")
                    append(claim.format.uppercase())
                    append(" · ")
                    append(sizeMiB)
                    append(" MiB")
                    claim.bitrateKbps?.let { append(" · ");append(it);append(" kbps") }
                    claim.durationSeconds?.let { append(" · ");append(it);append(" s") }
                    append(" · fonte Soulseek")
                    if(folder.isNotBlank()) {
                        append("\nPasta/edição declarada: ")
                        append(folder)
                    }
                }
                val button=Button(this).apply {
                    isAllCaps=false
                    text=details
                }
                actionButtons.add(index to button)
                layout.addView(button)
                button.setOnClickListener {
                    if(stopped.get() || busy.get() || index in attempted)return@setOnClickListener
                    val live=Downloads.claimedSoulseekReviewPlan(session,token)
                    if(live?.candidatesForManual(index)?.getOrNull(position)!==choice) {
                        outcome.text="Revisão expirada. Nenhuma transferência iniciada."
                        return@setOnClickListener
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Salvar candidato selecionado?")
                        .setMessage(
                            "Faixa: ${row.entry.query}\n\nArquivo: ${choice.displayName}" +
                                "\nFormato: ${claim.format.uppercase()}" +
                                "\nTamanho: ${sizeMiB} MiB" +
                                "\nPasta/edição declarada: $folder" +
                                "\n\nConfira se a edição corresponde ao álbum solicitado. " +
                                "O protocolo Soulseek utiliza TCP sem TLS. " +
                                "Confirme que tem autorização para armazenar este arquivo.")
                        .setNegativeButton("Voltar",null)
                        .setPositiveButton("Confirmar e baixar") { _,_ ->
                            if(stopped.get() || !busy.compareAndSet(false,true))
                                return@setPositiveButton
                            attempted.add(index)
                            actionButtons.forEach { it.second.isEnabled=false }
                            outcome.text="Conectando à fonte selecionada, sem nova busca…"
                            worker.execute {
                                val lastStage=AtomicReference<
                                    A0ManualRetainedCandidateTransfer.Stage?>(null)
                                val lastReason=AtomicReference<
                                    A0AutoPostTransferTechnicalGate.Reason?>(null)
                                val status=try {
                                    val song=Downloads.claimedSoulseekReviewSong(
                                        session,token,index)
                                        ?: Song(
                                            videoId=row.entry.trackId,
                                            title=row.entry.query.substringAfter(
                                                " - ",row.entry.query),
                                            artist=row.entry.query.substringBefore(
                                                " - ","Artista"),
                                            thumbnailUrl=null,
                                            albumName=row.entry.album)
                                    // Never reinterpret a retained candidate
                                    // using Settings changed since the search.
                                    val profile=checkNotNull(initial.qualityProfile)
                                    A0ManualRetainedCandidateTransfer.run(
                                        this@A0RetainedCandidateReviewActivity,
                                        row.entry,choice,profile,song,
                                        isCancelled={stopped.get()},
                                        onTransport={transport=it},
                                        onDiagnostic={stage,reason ->
                                            lastStage.set(stage)
                                            lastReason.set(reason)
                                            Log.d("BitChord",
                                                "soulseek_manual_review stage=${stage.name}" +
                                                    " reason=${reason?.name ?: "NONE"}")
                                        })
                                }catch(_:Exception) {
                                    Log.d("BitChord",
                                        "soulseek_manual_review stage=UNEXPECTED_WORKER_FAILURE")
                                    A0ManualRetainedCandidateTransfer.Status.VERIFY_LIBRARY
                                }finally {
                                    transport=null
                                }
                                if(status==A0ManualRetainedCandidateTransfer.Status.PUBLISHED) {
                                    Downloads.confirmSoulseekManualPublication(
                                        session,index,row.entry.query)
                                }
                                runOnUiThread {
                                    if(stopped.get() || isDestroyed || isFinishing)
                                        return@runOnUiThread
                                    outcome.text=when(status) {
                                        A0ManualRetainedCandidateTransfer.Status.PUBLISHED ->
                                            "Salvo na biblioteca local."
                                        A0ManualRetainedCandidateTransfer.Status.PRECHECK_REJECTED ->
                                            "A seleção foi recusada antes da transferência: " +
                                                "o candidato não atende aos requisitos da " +
                                                "solicitação original. Nenhum arquivo baixado."
                                        A0ManualRetainedCandidateTransfer.Status.TECHNICAL_REJECTION ->
                                            if(lastStage.get()==
                                                A0ManualRetainedCandidateTransfer.Stage.PROBE_UNAVAILABLE)
                                                "O arquivo foi recebido, mas o Android não " +
                                                    "conseguiu concluir a inspeção técnica. " +
                                                    "Nenhuma publicação autorizada."
                                            else "O arquivo foi recebido, mas a inspeção " +
                                                "técnica ou a qualidade não foi aprovada " +
                                                "(${lastReason.get()?.name ?: "SEM_DETALHE"}). " +
                                                "Nenhuma publicação autorizada."
                                        A0ManualRetainedCandidateTransfer.Status.TRANSFER_FAILED ->
                                            "Transferência falhou. Confira a biblioteca antes de " +
                                                "tentar baixar esta faixa novamente."
                                        A0ManualRetainedCandidateTransfer.Status.VERIFY_LIBRARY ->
                                            "Estado de publicação incerto. Confira a biblioteca " +
                                                "antes de qualquer novo download."
                                    }
                                    global.text=if(status==
                                        A0ManualRetainedCandidateTransfer.Status.PUBLISHED)
                                        "Uma faixa concluída. Revise as restantes."
                                    else "Uma tentativa foi encerrada; esta faixa não será " +
                                        "repetida automaticamente."
                                    busy.set(false)
                                    if(status!=A0ManualRetainedCandidateTransfer.Status.VERIFY_LIBRARY) {
                                        val remaining=Downloads.claimedSoulseekReviewPlan(
                                            session,token)?.manualIndices.orEmpty()
                                        actionButtons.forEach { (slot,button) ->
                                            button.isEnabled=slot in remaining &&
                                                slot !in attempted
                                        }
                                        if(remaining.isEmpty())finish()
                                    }
                                }
                            }
                        }.show()
                }
            }
        }
        val close=Button(this).apply {text="Fechar revisão"}
        close.setOnClickListener {finish()}
        layout.addView(close)
        setContentView(ScrollView(this).apply {addView(layout)})
    }

    override fun onStop() {
        stopped.set(true)
        runCatching {transport?.cancel()}
        super.onStop()
        // Activity backgrounding revokes its one-shot P/F authorization.
        if(!isFinishing)finish()
    }

    override fun onDestroy() {
        stopped.set(true)
        runCatching {transport?.cancel()}
        val id=sessionId
        val token=leaseToken
        if(ownsLease && id!=null && token!=null) {
            SoulseekManualRecoveryTermination.releaseAfterStop(worker) {
                Downloads.closeSoulseekManualReview(id,token)
            }
        }else worker.shutdownNow()
        super.onDestroy()
    }
}
