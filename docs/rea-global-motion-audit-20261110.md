# REA #212 — primeira rodada de política global de movimento (2026-11-10)

## Resultado e limite da evidência

Sim, há animações globais possíveis **antes** de terminar a [issue #4 do am-descomp](https://github.com/jssantogit/am-descomp/issues/4), mas não é correto aplicar indiscriminadamente as durações das animações específicas do Apple Music.

Fonte principal: `jssantogit/am-descomp` branch `fase-3-investigacao-visual`, HEAD `7200e849`, `docs/MOTION.md`, `docs/motion/home-and-content.md`, `docs/motion/search-and-library.md` e `docs/motion/bars-and-indicators.md`.

- **Confirmado:** `Loader.c` pode encerrar visibilidade com fade de alpha até zero durante 400ms e `DecelerateInterpolator`, se o flag de fade estiver ativo. Rota de Início e Busca pesquisada. **Não é** uma animação universal de conteúdo nem prova de que um skeleton Compose use essa transição.
- **Somente recurso declarado:** fades `nav_default_*` de 150ms linear; **não** há vínculo comprovado do runtime ao clique nas abas. Não forçar o app todo a 150ms.
- **Não confirmado:** duração de menu overflow, transição de cards, animação global de troca de abas, do cabeçalho, reação de favorito ou seleção da barra inferior.
- **Fluxos específicos:** transformações de player/miniplayer, queue/lyrics, scroll de Busca e fechamento de Busca interna em playlist pertencem aos fluxos condicionais correspondentes; não viram tokens universais.

## Implementado agora, sem inventar movimento Apple

1. `GlobalMotionPolicy` em código compartilhado mantém o papel `loaderExitMillis=400` **documental/preparatório** (não conectado a todos os loaders), `headerChromeMillis=220` e `skeletonSweepMillis=1400` como decisões Bitchord já existentes e não provenientes do Apple. Duração reduzida vira 0; tempos negativos falham explicitamente.
2. A preferência `AppSettings.reduceAnimation` é lida uma única vez no host Android (`MainActivity.setContent`) e distribuída como `LocalReduceAnimations` via `CompositionLocalProvider` aos componentes de `sharedUi` (sem dependência de `AppSettings` do módulo app). Os placeholders animados `ShimmerBox` usados em Home, Explorar, Biblioteca, Busca e páginas de detalhes deixam de executar o loop infinito quando **Reduzir animações** está ativo. No lugar há um placeholder estático com a mesma forma/fundo, sem esconder dados ou criar atraso. Outros hosts podem fornecer a preferência; previews usam movimento normal por padrão.
3. `FrostedTopBar` respeita Reduzir animações nos fades de título/divisor e na animação de tamanho de ações compartilhadas; sua lógica de posicionamento e tamanho continua inalterada.
4. O banner compartilhado respeita Reduzir animações em seu efeito de pressão, conservando o callback de acesso.
5. `AppleMusicChromeMotion.duration` delega ao contrato global sem mudar os valores já testados da barra inferior e do miniplayer. Não foram adicionados timers de navegação, crossfades em listas grandes ou blur de cards.

## Regressão / critérios

- Testes unitários para duração normal/reduzida, shimmer ativo/estático, pressão com redução e durações inválidas.
- Os testes existentes de chrome continuam cobrindo o layout da barra e dos botões.
- Esperado: nunca impedir o conteúdo, playback, gesto, click ou navegação por causa da animação.
- Pendente: aplicar a saída de 400ms ao loader **somente em superfícies que provem ter aquele loader**, investigar condições de tokens de motion para outras superfícies, validar performance/estados Android. A issue #4 é a fonte das futuras animações **dos cards**.

Sem gerar novo smoke. PR #209 não alterada; PR #212 continua isolada em draft. Não declarar paridade pixel/motion 1:1 sem validação em aparelho.


## Segunda rodada — seletores e atualização por gesto

- **Evidência e limite:** o `am-descomp/docs/motion/bars-and-indicators.md` liga a cor selecionada ao recurso de tint do menu inferior, mas registra como **unknown** a animação/duração dessa seleção. `docs/motion/search-and-library.md` não prova interpolação de seleção de filtros. Portanto esta rodada NÃO apresenta 180ms como um timing recuperado do Apple.
- **Implementação BitChord:** `GlobalMotionPolicy.selectionTintMillis=180` reutiliza a duração de transição de cor **já existente** na barra inferior do BitChord. `SearchFilterTabs` (resultados confirmados) e `AppleSearchOriginTabs` (fontes pré-busca) suavizam somente cor do rótulo e da linha inferior. Não alteram geometria, filtros, callbacks, posição, cor-alvo, pesquisa ou carregamento. Com **Reduzir animações** a duração é zero.
- **Atualização por gesto:** o `RefreshPuck` compartilhado preserva a trajetória ligada ao arrasto e a indicação visual de atualização, mas desativa o giro infinito de `CircularProgressIndicator` quando **Reduzir animações** está ativo; usa arco estático de 80%. Isto é uma escolha de acessibilidade BitChord, **não** uma reprodução de animação Apple identificada.
- **Regressões:** testes de contrato (tempo de seleção normal/reduzido e spinner) e teste de seleção entre fontes com redução ativa; testes existentes continuam cobrindo o indicador de largura positiva. Não usar animadores para listas/cards sem evidência adicional e sem teste de performance.
- **Issue #4 fechada:** HEAD `d074855e` do am-descomp publicou `evidence/media-card-closure.json`, mas C1–C3 sustentam apenas aproximação e C4 é `unknown/no_proven_equivalent`; nenhuma duração/efeito global de card ficou comprovada por ela.


## Terceira rodada — saída da atualização compartilhada

A `FrostedTopBar.RefreshPuck` é utilizada em múltiplas rotas. Antes, a saída do indicador era imediata via `if (!refreshing && !pulling) return`, mesmo após uma atualização completa. Agora um refresh **efetivamente iniciado** mantém o indicador em repouso e realiza uma saída de alpha de 400ms, desligando o componente ao fim. O scroll, a chegada dos dados, o toque e a altura efetiva do cabeçalho não são bloqueados; gesto abandonado antes de iniciar refresh não aciona o fade. **Reduzir animações** continua imediato e não mantém spinner rodando.

**Fonte e ressalva:** `am-descomp/docs/motion/home-and-content.md` e `docs/motion/search-and-library.md` vinculam `Loader.c` a fade condicional de 400ms com DecelerateInterpolator em Início/Busca. Eles **não** identificam a mesma classe como `RefreshPuck`; esta saída é portanto **adaptação BitChord**, não paridade recuperada. A curva Compose `LinearOutSlowInEasing` é aproximação controlada; fator exato do interpolador original não foi determinado.

Adicionada cobertura de estado e dois testes Compose para término da atualização normal/reduzida. A migração não cria animações de listas, menus ou cards sem evidência e não altera PR #209. Nenhum smoke APK foi disparado.


## Quarta rodada — rotação e semântica acessível do indicador global de refresh

Auditoria do `FrostedTopBar.RefreshPuck` (Home, Explorar, Biblioteca e demais rotas): o `CircularProgressIndicator` giratório já era substituído por um arco fixo com `reduceAnimation`, mas o próprio `graphicsLayer.rotationZ` **continuava acompanhando o dedo** mesmo nesse modo. Além disso, o arco desenhado em `0.8f` era exposto como **80% de progresso** pelo Material, apesar de não ser percentual de progresso da atualização de rede (o próprio `pullFraction` só controla a affordance gestual).

A política compartilhada agora calcula `refreshPuckRotationDegrees`: mantém rotação limitada de 0 a 360 graus durante o arrasto normal, zera sob Reduzir animações e neutraliza NaN/infinito. O indicador determinate **meramente visual** recebe `clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }`, igual ao precedente do miniplayer: TalkBack pode sinalizar carregamento, sem anunciar conclusão inexistente. A rotação normal, o arrasto, o tamanho 40dp, a saída alpha de 400ms e os callbacks de refresh não mudam.

Primeiro foram publicados testes da função (normal, reduzido, entradas inválidas) e teste Robolectric Compose que exige semântica indeterminada em modo reduzido. A implementação não introduz tempo/curva Apple e não promove efeito para cards; é correção de acessibilidade e robustez do BitChord. Nenhum smoke APK solicitado, PR #209 intocada.


## Quinta rodada — escala das abas sob redução de movimento

A barra inferior Apple usava `appleMusicTabScaleTarget(enabled, selected, pressed)` com alvo de 1,04 para uma aba selecionada e 0,91 para uma pressionada. Ao habilitar **Reduzir animações**, somente a `animationSpec` mudava para `snap()`: o ícone ainda pulava **instantaneamente** de tamanho, ao contrário do transporte do miniplayer e dos controles que já retornavam `scale=1f` sob redução. Trata-se de inconsistência concreta na política de acessibilidade do BitChord, não um comportamento medido no Apple Music.

O helper agora aceita `reducedMotion` (default `false` para preservar seus testes/consumidores anteriores). Abas ativas, selecionadas ou pressionadas mantêm `1f` com redução; no modo normal permanecem os mesmos valores, spring, cores e seleção. Rádio permanece sem ação e sem escala em qualquer modo. O teste foi incluído antes da correção e enumera todas as combinações de selecionada/pressionada, além de Rádio. Nenhuma mudança de rota, tamanho da barra, gesto ou duração de tint.
