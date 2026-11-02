# Issue #192 — autorização de APK para smoke físico

Autorizado pelo usuário em 28/10/2026: **“Dispare o apk”**.

Executar uma única compilação `assembleDevDebug` para validar o estado atual
da PR draft #193 (`listening/smart-playlists-v2-192`) no aparelho Android.

Esta solicitação NÃO autoriza merge, release de produção, alteração da main
nem encerramento da issue. O workflow tem gatilho de push restrito exatamente
a este arquivo e a esta branch para não produzir APKs nos próximos commits
funcionais.

O smoke físico deve inspecionar: playlists V1/V2, seleção de referência,
grupos/NOT, filtros, preview, ordem de álbuns, execução Play/Fila até 500,
troca de conta, integridade de fontes, responsividade e logs.

A versão foi validada preliminarmente por Android CI #40737254340.
O APK terá seu próprio build e resultado de CI no SHA do gatilho.

## Correção de execução

O primeiro gatilho encontrou uma restrição de parsing YAML: dentro de
um scalar sem aspas, ` #192` foi interpretado como comentário.
A expressão do ambiente foi corrigida para `issue 192` (sem `#`),
preservando a autorização original e disparando apenas uma nova execução.

## UI redesign — segundo smoke autorizado

Autorizado explicitamente em 28/10/2026: **“Então vamos pro apk”**.

Novo build físico do **editor de playlists inteligentes redesenhado**, na
mesma branch de trabalho e com a Android CI do HEAD `05cb105210bd0f04045464bf78b6874642da8412`
aprovada ([#40789880437](https://github.com/jssantogit/BitChord/actions/runs/40789880437)).

Smoke no Galaxy A54, em particular:
- identificar a diferença entre “Nova playlist” e “Minhas playlists”;
- avançar Identidade → Regras → Grupo → Categorias → Condição → Revisão,
  inclusive rolando uma página antes de voltar e abrir a próxima;
- validar grupos aninhados, `NÃO`, E/OU e reordenação;
- pesquisar condição, usar numéricos/duração, aplicar origem local e
  referências a playlists salvas;
- conferir a prévia real e os cards de fontes sem seleção de provedor falsa;
- abrir as abas Inteligentes/Filtros/Clássicas, seus menus Mais, confirmação
  de exclusão e criação/revisão;
- testar Play/Fila e observar se mini-player/tab bar não se sobrepõem;
- guardar diagnóstico se houver erro.

Disparo limitado a este arquivo pelo workflow
`.github/workflows/physical-smoke-apk.yml`. Esta edição documental não
altera o comportamento do app. O commit deste gatilho será o SHA do APK,
e o próprio workflow repetirá os testes e fará o `assembleDevDebug`.

Nenhum merge, release ou alteração da `main` autorizado.

## Smoke físico — fidelidade visual BitChord, 29/10/2026

Novo APK **explicitamente autorizado** pelo usuário: **“Dispare apk”**.

Esta rodada verifica a nova linguagem visual Android implementada após as
capturas do Galaxy A54: controles próprios em vez de chips e inputs contornados
Material 3, listas agrupadas para categorias/condições, CTA com AccentRed do
BitChordTheme, escolhas em folha inferior, capa neutra, primeira condição não
pré-selecionada, revisão, retorno/rolagem entre etapas e menus de grupos.

Código-base: `d265f26a07d11663644abe237ae151a2cdb47249`.
CI Android #40798528075: SUCCESS nesse SHA. O commit de disparo acrescenta
**apenas esta autorização documental** e não modifica código nem temas.
O próprio workflow testa novamente e compila `assembleDevDebug`; confirmar
conclusão/upload antes de anunciar APK pronto.

Inspecionar no Galaxy A54:
- identidade inicial sem regra obrigatória implícita; primeira condição;
- layout contra specs/tensions e `DESIGN.md`: tipografia, recuos, altura,
  agrupamento das linhas, botões vermelho/escuro, acesso com teclado;
- E/OU, NÃO, referência salva, catálogo de categorias, campo e valor;
- ordenação, folha inferior, botão de revisão e edição do rascunho;
- prévia real e persistência, fontes sem afirmações falsas, Play/Fila;
- biblioteca de filtros/playlist, exclusão confirmada e logs.

**Proibido:** merge, release pública, alterações da main ou encerramento
automático da issue. O APK é somente **Dev Debug** para teste físico.


## Smoke físico — playlists normais canônicas e integração na Biblioteca, 29/10/2026

Autorização direta nesta conversa: **“Gere o apk”**.

Disparar o workflow físico da PR draft #193 a partir do estado validado
`9fb634771b20fac5c936c64deabb217b4e48a9e3`. A
[Android CI #40880420633](https://github.com/jssantogit/BitChord/actions/runs/40880420633)
terminou **SUCCESS** nesse código. Esta edição é **somente o gatilho documental**;
o workflow executará novos testes e `assembleDevDebug` no SHA final.

Checklist para o Galaxy A54:

- Identidade: seleção **Normal/Inteligente**, nome e fluxo de volta sem perda do rascunho.
- Normal: pesquisa de gravações locais e OpenSubsonic/Navidrome, inclusão e remoção
  manual, ausência de duplicados, ordem de seleção, botões Subir/Descer, revisão e capas.
- Salvar playlist vazia/com faixas, reabrir para edição, alterar nome/membros,
  reproduzir pelo planejamento canônico/Media3, exclusão confirmada.
- Biblioteca: lista vertical compacta de playlists normais, inteligentes e legadas;
  expandir para grade de duas colunas; nenhuma seção “Minhas playlists” duplicada.
- Inteligente: editor de condições, legibilidade no modo escuro, prévia com capas,
  capa da criação em 224dp, Play/Fila e preservação dos filtros.
- Troca de conta, referências de fontes, diagnósticos de playback e falhas de arte.

**Escopo:** APK Dev Debug exclusivamente para smoke físico. Sem merge,
sem alteração da `main`, sem assinatura/release de produção, sem fechamento
da issue. O código desse build não é uma `main` reconciliada.


## Smoke físico — paridade funcional e extração universal, 01/11/2026

Autorizado explicitamente na conversa pelo usuário: **“Ok, dispara o apk.”**

**Base de código previamente validada:** `99788fd4cd65021017584419ded2dda119836384`.
- [Android CI #41374189224](https://github.com/jssantogit/BitChord/actions/runs/41374189224): SUCCESS.
- [Integration Queue Tests #41374189200](https://github.com/jssantogit/BitChord/actions/runs/41374189200): SUCCESS.
- A PR #193 continua draft e a issue #192 continua aberta.

**Único disparo autorizado nesta rodada:** push deste arquivo-gatilho
`docs/listening/issue-192-physical-smoke-request.md` na branch
`listening/smart-playlists-v2-192`, conforme
`.github/workflows/physical-smoke-apk.yml`. O workflow deve repetir
`:app:testDevDebugUnitTest`, compilar `:app:assembleDevDebug` e
publicar o APK como artifact associado ao SHA exato deste commit.

**Plano do Galaxy A54:**
- Tags reais em MP3/ID3, FLAC, Ogg/Opus e M4A: gênero, mood, estilo, BPM e compositor.
- Pasta e caminho do arquivo: separadores Windows/Linux, diretórios semelhantes e tags desconhecidas.
- Regras aninhadas E/OU/NÃO, valores múltiplos, `IS_PRESENT`/`IS_MISSING`,
  fonte remota vs MediaStore local, troca de conta sem vazamentos.
- Filtros reutilizáveis, prévia ao editar/reabrir, cache após varredura,
  listas de playlists normais/inteligentes e ações Media3 Play/Fila até 500.
- Capturar diagnósticos `local_tag_enrichment_v1` e
  `smart_playlist_preview_v2`, incluindo contadores e tempos.
- Se houver biblioteca grande, conferir responsividade e repetição de prévia;
  benchmark CI de 50.123 ativos NÃO substitui teste físico.

**Fora do escopo:** merge da PR, assinatura de produção, alteração da
`main`, fechamento de #192 e afirmação de paridade Symfonium completa.


## Smoke físico — reconciliação visual e funcional das playlists, 02/11/2026

**Autorização explícita do usuário nesta conversa:** “Pode gerar”.

**Código-base confirmado:** `6038bbb9269c3702fb359b422a5095ce919e291d` na PR draft #193; fast-forward incorporou a PR #204 (interface da #202 + filtros da #193). Branch #193 e #204 com conteúdo idêntico no checkpoint anterior.

**Gates prévios no HEAD exato:**
- [Android CI #41426285333](https://github.com/jssantogit/BitChord/actions/runs/41426285333): SUCCESS.
- [Integration Queue #41426285137](https://github.com/jssantogit/BitChord/actions/runs/41426285137): SUCCESS.

**Disparo único deste ciclo:** commit somente deste documento-gatilho na branch `listening/smart-playlists-v2-192`, conforme `.github/workflows/physical-smoke-apk.yml`. O workflow exige o ancestral da interface #202, executa testes Android e `:app:assembleDevDebug` no novo SHA, publica um artefato por commit. Nenhum código do app será alterado neste gatilho.

**Plano de aceite Galaxy A54:**
- Home com título “Início”, botão do perfil e menu funcionais, sem duplicação de chrome.
- Biblioteca → Playlists: lista vazia, criar pelo CTA, lista preenchida, criar pela toolbar, busca e ordenação.
- Criar playlist Normal e Inteligente, editar por ID, salvar, reabrir, voltar e descartar; nome, descrição, capa manual, revisão, fontes do catálogo.
- Filtros Symfonium já implementados na branch, metadados locais/Navidrome, caminho/pasta, regras E/OU/NÃO, prévia, seleção de fontes sem vazamentos, Play/Fila até 500.
- Responsividade da UI e logs de `playlist_create_requested`, `canonical_playlist_library_entered` e `playlist_wizard_entered`; observar `main_thread_stall` sem atribuir causa sem evidência.

**Não autorizado por este pedido:** mesclar na `main`, release assinada de produção, fechamento da issue #192 ou declaração de paridade Symfonium completa. APK Dev Debug apenas para smoke físico.


## Smoke físico autorizado — playlists V2 pós-correções, 02/11/2026

**Autorização do owner:** “Ok. Dispare apk” após aceite da CI e solicitação de registrar separadamente pendências de UI de Logs e Configurações no Notion. O registro no backlog NÃO integra este APK.

**Base de código exata:** `845fc3e3d9e51dd8affcde782edb562acb9a38a3` no HEAD da [PR #193](https://github.com/jssantogit/BitChord/pull/193), única candidata futura ao merge, ainda OPEN/DRAFT. A #204 e #202 estão contidas nesta branch. Não mesclar `main`.

**Gates previamente aprovados para esta mesma árvore:**
- [Android CI #41447949910](https://github.com/jssantogit/BitChord/actions/runs/41447949910): COMPLETED/SUCCESS.
- [Integration Queue #41447950102](https://github.com/jssantogit/BitChord/actions/runs/41447950102): COMPLETED/SUCCESS.

**Disparo único solicitado:** alterar exclusivamente este documento de autorização na branch `listening/smart-playlists-v2-192` para ativar `.github/workflows/physical-smoke-apk.yml` pelo filtro de push. O workflow deve checar a integração UI #202, reexecutar testes e compilar `:app:assembleDevDebug`, publicando APK Dev Debug como artifact do novo SHA. O commit de gatilho não altera código de produção.

**Plano de smoke Galaxy A54 — nove ajustes:**
1. Playlist “elite” com regra Navidrome 5 estrelas: exibir avaliações no detalhe com `CanonicalRatingStars`, notas da gravação/MediaAsset correto, sem supor que starred=five; verificar casos com capa local e nota remota e com fonte remota sem capa.
2. Exibir a mesma avaliação na lista de revisão/prévia de playlists normais e inteligentes, sem vazamento entre contas.
3. Página Regras: abrir editor ao tocar no card, expandir/recolher condições em ação independente (sem chevron), nomear grupo, salvar, fechar e reabrir com nome persistido.
4. “Fontes de origem”: seleção por rádio, sem `>` ou `^` redundantes, alterando realmente a fonte e respeitando isolamento.
5. Gênero unificado e metadados locais/Navidrome (gênero, Humor, BPM, estilo, compositor, pasta e caminho); conferir filtro de segmento `ILLIT` versus `ILLIT-Backup`.
6. “Humor” no lugar de Clima/mood; título de álbum não confundir com tipo de lançamento.
7. Operadores por tipo: presença sem campo de valor, “contém” com entrada de texto, comparações numéricas com valores válidos.
8. Revisão/prévia automática ao entrar (sem botão manual), mensagens/latência, sincronização após mudanças.
9. Conservar UI nativa já aprovada, navegação Home/Biblioteca/Playlists/perfil, criação/edição normal e inteligente, fontes, capa e Play/Fila.

Coletar capturas e diagnóstico físico. Mesmo com CI verde, NÃO declarar funcionamento aprovado sem teste. Sem alterações de Configurações/Logs, sem release assinada, sem merge `main` e sem fechar issue #192.


## Smoke físico — cinco refinamentos finais de Playlists V2, 02/11/2026

**Autorização:** continuação da solicitação anterior de APK e da validação dos cinco refinamentos na conversa de Playlists #11/#12. APK exclusivamente Dev Debug para uso no Galaxy A54; sem merge, sem release pública, sem alterações da main.

**Código-base aprovado:** `0d4838937d0f2f47b37974377a657f0389f225bb`, branch `listening/smart-playlists-v2-192` da [PR #193](https://github.com/jssantogit/BitChord/pull/193) (OPEN/DRAFT). Gates neste SHA:
- [Android CI #41472785617](https://github.com/jssantogit/BitChord/actions/runs/41472785617): SUCCESS.
- [Integration Queue #41472785701](https://github.com/jssantogit/BitChord/actions/runs/41472785701): SUCCESS.

**Disparo único:** este commit documental é o gatilho do workflow `.github/workflows/physical-smoke-apk.yml`, cujo checkout exige a UI reconciliada da #202 e reexecuta `:app:testDevDebugUnitTest`, seguido de `:app:assembleDevDebug`. Artefato deve conter o SHA exato desse commit de autorização (mesma árvore de código-fonte do SHA aprovado acima).

**Aceite físico Galaxy A54:**
1. Botões Fechar, Expandir, Recolher e Opções usam ícones nativos, com toque e acessibilidade funcionando; ícone de grupo alterna realmente expandir/recolher.
2. Editor de grupos de regras conserva apenas menu Opções, sem chevron; toque no título abre o grupo sem roubar toques dos ícones.
3. Revisão de músicas selecionadas não oferece Reproduzir nem Adicionar à fila; preserva prévia, avaliação e ordem das faixas.
4. Menu ⋮ exibe capa personalizada de playlist preenchida e vazia, após fechar/reabrir.
5. Excluir grupo e condição exige confirmação, preserva outras regras e não permite salvar grupo vazio inválido; conferir a última condição e E/OU/NÃO.

**Regressão adicional:** preservar Home/perfil, Biblioteca→Playlists, fonte Navidrome/local, avaliação remota cinco estrelas, edição, prévia automática, capa e reprodução de playlist; coletar capturas e novo diagnóstico, observar travamentos recuperados separadamente. Não realizar merge sem aceite expresso do smoke.
