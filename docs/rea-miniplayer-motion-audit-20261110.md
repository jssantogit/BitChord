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

## Terceira implementação: indicador de carga acessível nas variantes

O spinner indeterminado rodava continuamente no miniplayer Apple ativo, no MiniPlayer legado e no GlassNowPlaying alternativo, mesmo com Reduzir animações habilitado. O helper MiniPlayerLoadingIndicator compartilhado mantém o carregamento visível: normal = spinner existente, reduzido = arco determinate fixo 0,75, sem rotação. Não altera isLoading, tempos do áudio nem controla avanço de downloads. O arco fixo é decisão BitChord, não mecanismo recuperado do Apple.

O miniplayer Apple continua único no fluxo MainActivity. Testes unitários confirmam a opção de indicador e a regressão no modo reduzido.

## Acessibilidade do arco estático

Um arco de 0,75 é apenas **desenho**; não existe medição percentual do progresso da faixa. Como o `CircularProgressIndicator(progress={0.75f})` reportaria 75% em semântica padrão, aplica-se `clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }` apenas ao modo reduzido. O leitor de tela continua sabendo que está carregando, sem afirmar conclusão fictícia. Teste Compose Robolectric verifica a semântica.


## Quarta implementação — troca de entrada durante arrasto horizontal

A auditoria posterior encontrou um caso não coberto pelos seis commits iniciais: o reconhecedor compartilhado `miniPlayerGestures` usava `pointerInput(Unit)`, conservando corretamente o gesto quando os metadados da faixa mudavam, **mas também** quando o item em reprodução era substituído por uma nova entrada na fila durante o contato do dedo. O resultado poderia aplicar `onNext/onPrevious` à nova entrada após o dedo levantar. Além disso, a acomodação `swipeSettle` da faixa anterior podia continuar visível sobre a arte seguinte.

A API do detector recebeu o parâmetro `gestureIdentity` (com `Unit` por padrão para compatibilidade). Os três miniplayers — `AppleMusicMiniPlayer` ativo, `MiniPlayer` legado e `GlassNowPlaying` opcional — usam a identidade estável `appleMiniPlayerContentKey(song)` (fila em primeiro lugar, fallback por mídia/URI). O detector reinicia **somente** quando essa identidade muda; atualizações de artwork, duração, qualidade, artista ou outras propriedades do mesmo item não reiniciam o drag nem disparam outra navegação. O miniplayer Apple limpa a acomodação anterior com `LaunchedEffect(trackIdentity)`; a leitura visual continua em `graphicsLayer`.

Dois testes de gesto Robolectric/Compose simulam o dedo tocando e arrastando, uma recomposição intermediária, e a soltura: **troca real de fila cancela skip antigo; simples atualização de texto preserva um único skip normal**. Essa é uma correção de concorrência/experiência do BitChord baseada no mecanismo existente, e não uma curva ou gesto inferido do Apple Music. A fonte `am-descomp/docs/motion/player-expansion.md` continua sendo a referência geométrica da expansão; não alteramos progressos, curvas ou retângulo do PlayerDock. Verificação física de skip rápido e primeiro/último frame ainda recomendada. Sem smoke.


## Quinta implementação — liberação garantida de pull vertical interrompido

A alteração de chave de `pointerInput` por item da fila permite cancelar swipes antigos, mas também cancela o mesmo detector durante um **pull vertical**, quando a reprodução muda enquanto a sheet está sendo puxada. Antes, a rotina de `MiniPlayerPull.release` ficava depois do `drag(pointer)` suspensivo; uma `CancellationException` ao alterar a identidade sairia sem liberar a sheet e sem devolver o mini ao repouso.

O caminho vertical agora delimita o arrasto em `try/finally` e garante `target.release(0f)` no cancelamento, ou velocidade real quando o dedo termina normalmente; o settle visual é lançado pela corrotina de composição após a liberação. Assim, troca de faixa/saída do detector não mantém um pull órfão. O código mantém os mesmos parâmetros de spring e não altera o gesto/callback de expansão do player.

Teste Robolectric Compose em `MiniPlayerGestureIdentityTest`: inicia pull vertical, troca a identidade da faixa antes de levantar o dedo e exige uma única liberação com velocidade zero. O teste reproduziria o bug em código anterior ao `finally`. Monitorar no dispositivo a trajetória da sheet sob troca de faixa durante o pull; nenhum efeito Apple novo foi presumido.


## Sexta implementação — reduzir movimento também no pull vertical

`miniPlayerGestures` tinha preferência de movimento reduzido nas camadas do miniplayer, mas seu próprio `lift` e spring vertical permaneciam sempre animados. O detector compartilhado agora recebe `reduceMotion` fornecido pelos três composables de miniplayer. A preferência é lida por `rememberUpdatedState` durante o gesto sem reiniciar `pointerInput`: com redução ativa o deslocamento visual é sempre 0, e a liberação da sheet continua a receber drag/release normalmente, porém o mini volta instantaneamente ao repouso. Se a preferência for ligada enquanto a spring está em andamento, a corrotina é cancelada e a barra volta a zero.

O helper `miniPlayerLiftForMotion` permite testar matematicamente o mesmo cálculo normal/régua reduzida sem mudar a curva elástica já existente. Esta é uma correção de acessibilidade BitChord; não atribuímos ao Apple Music um comportamento não evidenciado.


## Sétima implementação — cancelar não é soltar sem velocidade

Inspeção do consumidor real `PlayerSheetMotion.release(velocity)` demonstrou que `release(0f)` ainda calcula `average=(held - pullStartAt)/elapsed` e escolhe o maior valor em módulo. Assim, o primeiro `finally` de cancelamento ainda podia disparar flick/abertura involuntária. Corrigido com uma operação distinta: `MiniPlayerPull.cancel()` (default legado `release(0f)` por compatibilidade) e override explícito `PlayerSheetMotion.cancel()` que utiliza `close()` quando a sheet está sob o controle do pull; o encerramento não reaproveita a velocidade média. Em `miniPlayerGestures`, conclusão normal chama `release(velocity)`, cancelamento de `pointerInput` ou ponteiro chama `cancel()`, seguido do settle visual do mini.

O teste Compose simula troca de item durante pull e exige exatamente um `cancel()` e **nenhum** `release()` normal. A intenção é garantir que uma troca de faixa não abra inesperadamente o player. Sem alterar as curvas existentes de abrir/fechar, parâmetros da sheet ou regra de flick normal. A API ganhou um método padrão para não quebrar implementações antigas. TDD de integração da sheet real continua coberto pela Android CI; o gesto exato ainda requer validação física.


## Oitava implementação — encerramento garantido de swipe horizontal interrompido

O reconhecedor compartilhado tinha proteção de identidade e `finally` no pull vertical, mas o swipe horizontal chamava `onHorizontalSwipe(0f, false)` **apenas depois** de `drag(pointer)` terminar. Quando `pointerInput(gestureIdentity)` era cancelado no meio desse `drag`, a corrotina saía diretamente e deixava de emitir o encerramento do feedback visual. O `LaunchedEffect(trackIdentity)` do miniplayer Apple era uma segunda proteção, mas não substituía o contrato de término do gesto compartilhado.

Agora o segmento horizontal também usa `try/finally`: em qualquer saída, publica `onHorizontalSwipe(0f, false)`; se o gesto **não** terminou normalmente, sinaliza antes `onHorizontalCancel()`, método opcional e sem efeito nos consumidores legados. No `AppleMusicMiniPlayer` ativo, esse cancelamento interrompe qualquer settle antigo, limpa o estado de arrasto e recoloca o `Animatable` no offset zero sem a spring de liberação normal. Isso evita animar o swipe interrompido sobre a entrada seguinte. Uma conclusão normal conserva **um único** skip, haptic e spring atuais. O reconhecedor não altera thresholds, decisão de eixo, tap para expandir, velocidade nem `PlayerDock`.

Regressões no `MiniPlayerGestureIdentityTest` verificam troca de entrada durante swipe: houve feedback ativo, não houve skip posterior, há **exatamente um** evento de encerramento, e o callback de cancelamento ocorre **uma vez**. A atualização simples de metadados continua permitindo um skip e **não** emite cancelamento. O teste de reprodução foi publicado antes da correção para exercitar a lacuna. Verificação de toque/offset no aparelho permanece pendente; não houve smoke, merge nem alteração geométrica.


## Nona implementação — zerar lift vertical no cancelamento ou troca de faixa

A correção anterior do `try/finally` liberava a sheet corretamente via `MiniPlayerPull.cancel()`, mas ainda iniciava a spring do `lift` do miniplayer sobre a música seguinte quando `pointerInput(gestureIdentity)` era cancelado. Um settle iniciado por uma liberação **anterior** também podia atravessar a mudança de entrada.

O detector agora zera `lift` imediatamente se o drag vertical terminou por cancelamento; apenas a liberação normal, sem Reduzir animações, mantém a spring de retorno. `LaunchedEffect(gestureIdentity)` também cancela e zera um settle anterior ao receber uma nova identidade, mesmo se não houver dedo na tela. `PlayerSheetMotion.cancel()` segue independente e fecha a sheet interrompida sem inferir fling. As regras são explicitadas por `miniPlayerShouldAnimateLiftSettle` e testadas em `AppleMusicChromeMotionPolicyTest` para cancelamento, conclusão normal e preferência reduzida.

Nenhum threshold, velocidade do gesto normal, curva de spring de liberação legítima, geometria da sheet ou `PlayerDock` foi alterado. Fidelidade do primeiro/último frame, Haze/blur e skips consecutivos permanecem dependentes de aparelho.


## Décima implementação — propriedade do pull versus animação de tap-open

Revisão do consumidor real `PlayerSheetMotion`: `holding` significa que a sheet é posicionada por `held` enquanto abre, fecha **ou** segue o dedo; **não** identifica que um detector do miniplayer seja dono do movimento. Assim, a proteção anterior `cancel() { if (holding) close() }` ainda podia fechar um player cuja abertura havia sido iniciada por toque, caso o cancelamento de um detector antigo chegasse quando `open()` já definira `held = travel()`. O mesmo risco existia em `release()`, pois a velocidade/posição de um gesto inexistente podia disputar o estado da abertura.

`PlayerSheetMotion` agora mantém um sinal privado `pullingFromMini`, ativado **somente** quando o primeiro `drag(delta < 0)` realmente assume uma sheet fechada. Os deltas seguintes do mesmo pull continuam funcionando mesmo depois de `shown=true`. `release()` e `cancel()` só têm efeito quando esse sinal está ativo; ambos abandonam a propriedade antes de encerrar o gesto. Os comandos independentes `open()`, `close()` e `onSheetGone()` também invalidam o dono anterior. Isso impede que uma soltura/cancelamento tardio sobrescreva abertura por toque ou um fechamento externo.

`PlayerSheetMotionPullOwnershipTest` em Compose/Robolectric cobre três resultados: cancelamento sem propriedade não fecha tap-open, soltura tardia sem propriedade também não fecha tap-open, e cancelamento do pull verdadeiro ainda devolve a sheet ao repouso. No teste, a animação é avançada pelo relógio Compose antes de verificar o estado final. Esses casos são correções de concorrência do BitChord; não foram inferidos como comportamentos Apple Music. A geometria `PlayerDock`, limiar de fling, parâmetros da spring e efeitos de blur seguem intactos. Smoke físico permanece pendente.
