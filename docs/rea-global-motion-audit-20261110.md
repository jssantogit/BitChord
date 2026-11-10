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
