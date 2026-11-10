# REA #212 — movimento visível entre telas e ações (10/11/2026)

## Diagnóstico do smoke validado pelo usuário

O smoke anterior (`367f9100`) foi instalado e validado. O retorno foi que as alterações quase não eram perceptíveis e que **menus ⋮, criação de playlists, avaliações, Configurações, troca de abas e Biblioteca** ainda pareciam saltar entre estados. Isso não invalida as correções de concorrência/estado existentes, mas comprova que esse trabalho não entregou a camada de *motion* visual esperada.

## Fronteira de evidência

Fonte principal: `jssantogit/am-descomp`, branch `fase-3-investigacao-visual`, HEAD `d074855e`, `docs/motion/navigation.md`, `docs/motion/sheets-and-menus.md`, `docs/components/overflow-menu.md`, `docs/screens/action_new_playlist.md` e `docs/screens/action_settings.md`.

- O popup de opções, alternância visual entre cinco abas e a navegação para Biblioteca são **observados** nas capturas; **não** há curva/duração/ação do runtime de popup, abas, categorias de Configurações, avaliação ou editor de playlist medida com confiança.
- Os recursos gerais `nav_default_*` (150ms), `abc_popup_enter/exit` (150ms) e Material3 sheets (400/350ms etc.) **não estão vinculados ao fluxo observado**. Não transplantar esses números para toda a interface, nem chamar o resultado de réplica 1:1 Apple.
- A animação de busca inline dentro de playlist possui um fluxo próprio comprovado no Apple e **não** é uma duração geral de navegação.

## Nova referência de composição do am-descomp (issue #5 encerrada)

A branch `fase-3-investigacao-visual` passou a conter
[`docs/design/new-surface-patterns.md`](https://github.com/jssantogit/am-descomp/blob/bf8322cb24efc1d14ee0223e793eee0cce98f887/docs/design/new-surface-patterns.md)
no commit `bf8322cb`. É a fonte documental mínima e delimitada para cinco famílias:
popup overflow, folha/confirmar, formulário/editor, escolha/avaliação e página agrupada/navegação.
Ela **não comprova** métricas nem tempos para esses fluxos e classifica novos editores de nota
como **adaptação BitChord**. Neste trabalho, o documento passa a orientar como
reutilizar as superfícies existentes e respeitar Back, foco, IME, acessibilidade
e redução de movimento; ele **não justifica** substituir componentes que já
possuem comportamento nativo.

## Implementação visual BitChord — isolada na PR #212

| Superfície | Anterior | Agora | Garantia |
|---|---|---|---|
| Popup de ⋮ compartilhado na barra superior (Home, Biblioteca, detalhes, etc.) | `Popup` montado/desmontado por `if (expanded)` sem transição | `AnimatedVisibility`: 170ms entrada fade+scale a partir de 0,96; 125ms saída fade+scale até 0,98; origem no canto direito superior | Mesma posição, shape, haze, 180dp, ação de Ajustes. `MutableTransitionState` permite manter janela até terminar a saída. Sem cliques durante saída |
| Menus auxiliares de ordenação/filtro na Biblioteca | `FrostedMenuPanel` aparecia instantaneamente, com scrim e cartão estáticos | Entrada somente do painel: alpha 0,30→1, escala 0,94→1 em 160ms a partir do canto superior direito | Mantém scrim, blur Haze, disposição, seleção e callbacks; desativado em Reduzir animações; saída ainda instantânea e não apresentada como cópia Apple |
| Abas principais | `AnimatedContent` deliberadamente com `None` para evitar dip escuro de crossfade (telas transparentes) | Novo *arrival* de 185ms na superfície inteira da aba entrante (0,96→1 de alpha; 12dp→0 deslocamento lateral); continua **sem duas páginas cruzando em alpha** | Rotas, backstack, conteúdos e barra inferior inalterados; não animar mudanças normais de dados/scroll |
| Configurações → categoria e voltar | Conteúdo trocado na hora | Arrasto visual de 12dp + leve opacidade em 185ms para o conteúdo entrante, com sentido reverso no retorno | Mesmos estados `ScrollState`, callbacks e controles; não animar cada toggle ou pesquisa |
| Biblioteca (categoria → álbum/artista e retorno) | `CanonicalRemoteLibraryScreen` troca `selectedContext`/`selectedEntity` sem resposta de entrada | Aplicada a mesma chegada de página sobre a `Box` interna, com `Surface` de fundo estável | Não duplica listas, não muda `RecordingId`, seleção, filtros, estado de back, scroll ou consultas |
| Seletor de playlist ↔ criar nova playlist | Troca imediata de conteúdo | Arrival leve no estado atual do mesmo sheet; sem manter dois formulários abertos | Não interrompe campo de nome que pede foco nem perde marcações da playlist |
| Criador normal/inteligente, etapas do wizard | Cada página troca abruptamente | Arrival do `LazyColumn` da etapa atual com 185ms | Header e botões de ação permanecem estacionários, sem duas listas concorrentes e sem reset de scroll em atualizações que não mudam etapa |
| Avaliação de músicas, álbuns e artistas pelo painel compartilhado | Diálogo aparece sem feedback de entrada e estrelas mudam de ícone instantaneamente | Entrada de diálogo 180ms (alpha 0,96→1, escala 0,97→1), além do pulso elástico `0,88→1` da **estrela efetivamente escolhida**, também na troca metade→inteira | Touch target 42×48dp, semântica, valor salvo só mediante confirmação e banco de dados intactos; avaliação já carregada não pulsa |

`RouteArrivalMotion.kt` contém a política única reutilizada por abas, Configurações, seletor e wizard; não cria novo host/segunda UI. `RouteArrivalMotionTest`, `CanonicalRatingSelectionMotionTest` (incluindo entrada do diálogo) e `GlobalAppleTopBarChromeTest` cobrem guards, direção, redução de movimento, dados inválidos e saída do popup.

Todos esses movimentos ficam **estáticos** quando `Reduzir animações` é ativado. O efeito não é atribuído falsamente ao Apple Music. Sem modificar filtros, dimensões, blur, gestos, dados ou PR #209.

## Ainda não equivalente/validado

- Submenus de ações de faixa/álbum que são `ModalBottomSheet` ou `Dialog` têm suas próprias animações Material; não foram substituídos às cegas. A ausência de percepção nos dispositivos deve ser reproduzida por superfície específica antes de eliminar o mecanismo nativo.
- Abertura de Configurações a partir do ⋮ pode desmontar o popup junto com a mudança de rota, e não promete uma animação completa de saída nesse cenário; dismiss normal permanece animado.
- Layout/IME do criador, retomada de estado na Biblioteca, interações rápidas entre abas, dispositivos lentos e temas claro/escuro precisam de inspeção física.
- Não introduzir *card entrance* global: C1–C3 do am-descomp sustentam só aproximações; C4 permanece `unknown`.
- Não disparar outro smoke automaticamente sem solicitação; esperar CI do novo HEAD antes de declarar verde.

## Regressão CI e correção

O corte `e7d9383d` terminou Android CI [#42861907393](https://github.com/jssantogit/BitChord/actions/runs/42861907393) em **failure apenas por dois testes novos** (3226 executados, 2 falhas, 1 ignorado); compilação Android passou e os gates Desktop/TV/Wear/YouTube ficaram verdes.

- `RouteArrivalMotionTest` comparava `RouteArrivalFrame(alpha=1, translation=+0f)` com um frame de volta com `-0f`. A mudança `b61ffc0e` normaliza o estado final para `0f` em ambas as direções, preservando a posição dos pixels.
- `GlobalAppleTopBarChromeTest` verificava o nó no mesmo primeiro frame agendado por `MutableTransitionState`, antes da janela `Popup` compor o conteúdo animado. `ca3c6a45` avança explicitamente o relógio Compose por 85ms e aguarda estado ocioso antes da asserção. O contrato de manter o popup durante saída continua.
- Outro menu real sem entrada animada foi localizado: `FrostedMenuPanel` (menu de filtros/ordenação da Biblioteca), renderizado pela própria `MainActivity` sobre haze e scrim. `c1ca7807` adicionou testes do frame estático versus animado, `0bd047ce` o helper reutilizável e `525bf7b0` o consumo no painel original, sem substituir ações ou criar segunda superfície.

O novo HEAD precisa de CI integral antes de ser considerado aprovado. Nenhum smoke foi solicitado nesta continuação.
