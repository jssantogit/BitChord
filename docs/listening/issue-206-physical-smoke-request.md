# PR #206 — autorização de APK Dev Debug para smoke físico

Autorização explícita do usuário em **03/11/2026**: **“Dispare apk”** após a conclusão do gate Android da funcionalidade **Fixados na Biblioteca**.

## Código e validação

- PR: [#206](https://github.com/jssantogit/BitChord/pull/206), aberta e em draft; encadeamento `main → #193 → #205 → #206` preservado.
- Código aprovado: `297181769ed2afdc481bcbe160ba45ecdbd0eeaf`.
- [Android CI #41728477968](https://github.com/jssantogit/BitChord/actions/runs/41728477968): **COMPLETED / SUCCESS** no SHA acima, incluindo testes Android e planejamento Automix.
- O commit `e10eef7cddd3f7f7147a5837800418f67c435af0` habilitou um gatilho de push restrito a esta branch e a este documento. Nenhuma alteração no código de produção ocorreu depois da aprovação.

## Disparo único autorizado

A criação deste documento na branch `feat/205-library-native-actions-20261102` dispara o workflow existente `.github/workflows/physical-smoke-apk.yml` (evento `push`, filtro de branch + caminho). O workflow valida a presença desta autorização e a identidade do código-fonte em relação ao SHA aprovado, executa `:app:testDevDebugUnitTest`, compila `:app:assembleDevDebug` e publica somente o artifact `bitchord-physical-smoke-dev-debug-${GITHUB_SHA}` com retenção de três dias.

**Escopo:** APK Android Dev Debug exclusivamente para testes no Galaxy A54. **Não autoriza** merge, release de produção, assinatura de publicação, alteração de `main`, encerramento de issue, nem aprovação antecipada da interface.

## Smoke físico — principais verificações

1. **Grade de fixados:** uma faixa local pelo `⋮`, um álbum Navidrome pelo `+` e um artista pelo `⋮`; confirmar 3 colunas, capa, tipo de entidade e ordem persistente depois de reiniciar o app.
2. **Abrir/desafixar:** tocar pin de faixa inicia reprodução da gravação correta; álbum/artista abre detalhe nativo; voltar retorna à raiz da Biblioteca. Desafixar pelo menu e pelo detalhe (`+ / ✓`) atualiza a grade e persiste.
3. **Fontes:** testar MediaStore/Navidrome mistos, conexão remota indisponível, pin indisponível retido e recuperação sem remapeamento falso.
4. **Separação de ações:** `PlaylistAdd` superior adiciona álbum a playlist normal; `+` do álbum apenas fixa/desafixa; estrela de artista continua sendo avaliação. Menus de pins não mostram ações de fila sem implementação.
5. **Limites/visual:** mais de seis pins — expandir/recolher —, alinhamento, espaçamento, arte, títulos, safe area e acessibilidade no Galaxy A54.
6. **Regressões da PR:** Biblioteca unificada, detalhes nativos de álbum/artista, playlists normais/inteligentes, avaliação, filtros, Adições recentes, seleção por segurar/arrastar, miniplayer e botão de perfil.

Guardar screenshots e diagnósticos ao encontrar falhas. Aprovação da CI e criação do APK **não** substituem smoke instalado.
