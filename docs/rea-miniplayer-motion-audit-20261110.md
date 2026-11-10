# REA #212 — Mini player: primeira auditoria de estabilidade e movimento (2026-11-10)

**Branch:** `experiment/apple-music-bottom-bar-pr209-20261108`, PR #212 draft. Não alterar PR #209 nem gerar smoke.

## Fonte e escopo

`am-descomp` `fase-3-investigacao-visual` HEAD `d074855e`:
- `docs/design/geometry-components.md`: `mini_player.xml` base declara **48dp** de altura, arte **30dp**, botões **32dp**, `maxWidth=600dp`, padding horizontal **16dp**. `w675dp` e outras variantes declaram dimensões maiores; **qualificador efetivamente usado no aparelho não foi observado**.
- `docs/motion/player-expansion.md`: `PlayerMainFragment$i.d(float)` transforma a capa do player com base no `video_surface_container` do miniplayer (retângulo e raio), sob condições específicas. Esse elemento é a **referência geométrica**, não há prova de animação da própria superfície do miniplayer ou duração de crossfade em troca de faixa.
- `docs/design/composition-surfaces.md`: blur de miniplayer e barra está declarado, mas intensidade/bounds em runtime não estão comprovados.
- `docs/motion/bars-and-indicators.md`: selecionador de abas/controles não fornece durações de mini player.

O BitChord usa **`AppleMusicMiniPlayer` ativo**, não o legado `MiniPlayer` nem `GlassNowPlaying`. O player expandido usa `PlayerDock` e `PlayerSheetMotion` com geometria dinâmica da capa, já existentes; não alterar a animação da sheet sem evidência do gesto/progresso.

## Problemas concretos encontrados e corrigidos

1. **Crossfades recomeçavam em atualizações da mesma faixa.** Os dois `Crossfade(targetState = song)` observavam todo o `Song` (qualidade, ID de artista, URI, duração, dados de catálogo e progresso de hidratação), mesmo sem troca de faixa; isso podia fazer o miniplayer piscar ou manter imagens transitórias além do necessário. Agora um único `updateTransition` alimenta arte e legendas via `Transition.Crossfade(contentKey = ::appleMiniPlayerContentKey)`, priorizando `queueEntryId` e fallback por `videoId`/`localUri`. Alterações de metadados no mesmo item atualizam o conteúdo sem abrir novo slot de crossfade; **troca de item real mantém a transição de 220ms** (Bitchord, duração Apple unknown).
2. **Pressão ainda alterava escala sob Reduzir animações.** O mini usava `snap()` mas `targetValue=0.9f`, causando um salto visual instantâneo. Agora delega a `GlobalMotionPolicy.pressedScale`: sob redução o valor é **1.0**. O fallback `GlassNowPlaying` também respeita a mesma preferência; não foi reativado como tela paralela.
3. **Scrim de rodapé dessincronizado.** `BottomFadeScrim` animava 220ms tanto para entrada quanto para saída, mesmo que o mini desaparecesse em 180ms, e ignorava Reduzir animações. Agora o fundo acompanha **220ms ao entrar**, **180ms ao sair**, e **0ms quando reduzido**; apenas a altura do gradiente muda, sem blur adicional ou modificações na faixa.

## Regressões e limites

- Testes de chaves: hidratação/artwork/qualidade da mesma entrada não reinicia crossfade; IDs de fila diferentes reiniciam; filas antigas sem entrada usam identidade de mídia/local.
- Testes de acessibilidade: pressionar não escala sob redução, scrim respeita tempos de entrada/saída e zera no modo reduzido.
- Sem alterações de tamanho: o mini mantém 48dp e artwork 34dp (este último **calibração Bitchord por screenshot**, não input XML base), botões 40dp do BitChord. A demanda de 40dp para círculos globais **não** altera o contêiner do miniplayer.
- Não introduz animações em cards, novos provedores, scroll automático, streaming, scripts de APK ou views paralelas. **Verificação visual em Android físico continua necessária**; CI só prova lógica e compilação.

## Auditoria restante, não confundida com esta entrega

Acompanhar performance do feedback por gesto horizontal (estado de offset atualizado por pointer), hit targets de 48dp de acessibilidade com affordances visuais 40dp, interrupção de crossfade em skips muito rápidos, alinhamento de insets e o arco real da sheet. Não trocar por valores Apple não documentados. Também investigar temporalidade da restauração de arte no retorno do player para o miniplayer, se reproduzível no aparelho.

## Segunda implementação: feedback horizontal sem recomposição por pixel

A barra Apple ativa tinha `swipeDistance` como alvo lido em `animateFloatAsState` durante a composição. Cada evento de ponteiro alterava o estado observado no corpo de `AppleMusicMiniPlayer`, podendo recompor Crossfade, AsyncImage, texto e transporte **para mover somente a linha de conteúdo em ±12px/dp** (cálculo real em px). A cadeia compartilhada `miniPlayerGestures` já decide eixo e um único skip por gesto e não foi reescrita.

Agora o deslocamento `miniPlayerSwipeVisualOffset` é consultado apenas dentro de `graphicsLayer`, e a acomodação usa `Animatable`/corrotina após soltar, cancelável por novo arrasto. Isso remove a dependência de composição por amostra de pointer, sem alterar threshold de skip, haptics, callbacks, PlayerDock ou posição de pill/artwork. A redução de movimento zera o offset mesmo durante o gesto e cancela a acomodação. Contratos unitários testam clamp, simetria e valores inválidos.

É otimização Bitchord, não afirmação sobre a implementação original da UI Apple. Monitorar abertura com gesto rápido e mudança de faixa no mesmo frame em aparelho.
