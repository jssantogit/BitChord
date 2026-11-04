package com.music.bitchord.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.BuildConfig
import com.music.bitchord.acquisition.a0.A0AndroidAutoConsent
import com.music.bitchord.acquisition.a0.SoulseekActiveSession
import com.music.bitchord.acquisition.a0.SoulseekAccountProbe
import com.music.bitchord.acquisition.a0.SoulseekCredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Account settings belong to Settings > Downloads, never Sources or Library.
 * "Authenticated" means a one-shot check succeeded, NOT a live TCP session.
 * The devDebug A0 manual picker is the only authorized transfer executor.
 */
@Composable
internal fun SoulseekAccountSettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val vault = remember(context.applicationContext) {
        SoulseekCredentialVault(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    var savedUsername by remember {
        mutableStateOf(vault.read()?.let { saved ->
            saved.username.also { saved.erase() }
        })
    }
    var username by remember { mutableStateOf(savedUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (savedUsername != null) "Credenciais protegidas; nenhuma sessão TCP ativa."
            else "Nenhuma conta Soulseek configurada."
        )
    }
    val pilot = BuildConfig.FLAVOR == "dev" && BuildConfig.DEBUG
    var automatic by remember { mutableStateOf(pilot && A0AndroidAutoConsent.enabled(context)) }

    // Prevent screenshots and Android recents thumbnails while a password can be typed.
    val activity = context as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val previouslySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!previouslySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun testAndMaybeSave(save: Boolean) {
        if (busy) return
        if (!pilot) {
            status = "Integração indisponível nesta variante; nenhum download será iniciado."
            return
        }
        if (!acknowledged) {
            status = "Confirme o aviso de transmissão TCP sem TLS antes de continuar."
            return
        }
        val requestedUser = username.trim()
        val typed = password
        if (requestedUser.isBlank()) {
            status = "Informe o usuário da conta dedicada."
            return
        }
        // Clear transient UI input before network I/O. Saved credentials are only
        // reusable for the SAME username; account A never authenticates account B.
        password = ""
        busy = true
        status = "Testando autenticação, sem iniciar downloads..."
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                val existing = if (typed.isEmpty() && savedUsername == requestedUser) {
                    vault.read()
                } else null
                val secret = if (existing?.username == requestedUser) {
                    existing.password.copyOf()
                } else typed.toCharArray()
                existing?.erase()
                try {
                    if (secret.isEmpty()) false
                    else {
                        val connected = SoulseekAccountProbe.test(requestedUser, secret)
                        if (connected && save) vault.save(requestedUser, secret)
                        connected
                    }
                } catch (_: Exception) {
                    false
                } finally {
                    secret.fill('\u0000')
                }
            }
            if (success) {
                if (save) savedUsername = requestedUser
                status = if (save) {
                    "Conta salva com Android Keystore. Login testado; socket encerrado."
                } else "Login confirmado; socket de teste encerrado, sem download."
            } else status = "Autenticação ou rede indisponível. Nenhum arquivo transferido."
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(contentPadding).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Soulseek", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Configure uma conta dedicada para pesquisar arquivos manualmente. " +
                "Os arquivos publicados aparecem como músicas locais comuns.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (savedUsername == null) "Conta: não configurada"
            else "Conta guardada: " + savedUsername,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (!pilot) {
            Text("Recurso experimental disponível apenas no BitChord devDebug.")
        } else {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(256) },
                label = { Text("Usuário Soulseek") },
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(256) },
                label = { Text("Senha (deixe vazia para reutilizar a conta salva)") },
                visualTransformation = PasswordVisualTransformation(),
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Aviso: o protocolo Soulseek envia credenciais por TCP sem TLS. " +
                    "Use somente uma conta e senha exclusivas de testes. " +
                    "Não há conexão nem download automático ao abrir o aplicativo.",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.foundation.layout.Row {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it },
                    enabled = !busy,
                )
                Text(
                    "Entendo e autorizo o teste de autenticação sem TLS.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(
                onClick = { testAndMaybeSave(false) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Testar conexão") }
            Button(
                onClick = { testAndMaybeSave(true) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Conectar e salvar conta") }
            Text(
                "Seleção automática de candidatos (experimental). Desligada por padrão. " +
                    "Ao tocar em Baixar, só candidatos de alta confiança podem ser " +
                    "transferidos; faixas incertas continuam manuais. " +
                    "O protocolo Soulseek utiliza TCP sem TLS.",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.foundation.layout.Row {
                Checkbox(
                    checked = automatic,
                    enabled = !busy,
                    onCheckedChange = { requested ->
                        if (requested && (savedUsername == null || !acknowledged)) {
                            status = "Configure a conta dedicada e confirme o aviso TCP antes de ativar."
                        } else {
                            if (!requested) SoulseekActiveSession.revoke()
                            val committed = A0AndroidAutoConsent.updateFromUserAction(context, requested)
                            automatic = A0AndroidAutoConsent.enabled(context)
                            status = if (!committed || automatic != requested) {
                                "Não foi possível confirmar a alteração. Confira o estado exibido."
                            } else if (requested) {
                                "Automação habilitada apenas no piloto devDebug; nenhuma busca iniciada."
                            } else "Automação desligada; seleções manuais preservadas."
                        }
                    },
                )
                Text(
                    if (automatic) "Seleção automática: ligada" else "Seleção automática: desligada",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (savedUsername != null) {
                OutlinedButton(
                    onClick = { confirmForget = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Desconectar e esquecer credenciais") }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Esquecer conta Soulseek?") },
            text = { Text("A credencial cifrada e sua chave serão apagadas. Os arquivos locais já salvos serão preservados.") },
            confirmButton = {
                TextButton(onClick = {
                    vault.forget()
                    A0AndroidAutoConsent.updateFromUserAction(context, false)
                    automatic = false
                    savedUsername = null
                    username = ""
                    password = ""
                    acknowledged = false
                    status = "Conta esquecida. Nenhuma sessão ativa."
                    confirmForget = false
                }) { Text("Esquecer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancelar") }
            },
        )
    }
}
