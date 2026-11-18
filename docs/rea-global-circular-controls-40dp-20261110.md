# REA #212 — Todos os controles circulares flutuantes em 40dp

**Data:** 2026-11-10  
**Branch:** `experiment/apple-music-bottom-bar-pr209-20261108` (somente PR #212)  
**Fonte:** `jssantogit/am-descomp`, `fase-3-investigacao-visual`, HEAD `7200e849`; capturas reais de 10/11 do BitChord/Apple Music.

## Decisão de produto

O usuário confirmou explicitamente o diâmetro visível **40dp para todos os controles circulares de navegação e ações** no Android. O objetivo é eliminar a diferença de 40/48/50dp entre Biblioteca, Adições Recentes, Downloads, páginas de artistas/álbuns e outras rotas.

O `am-descomp/docs/components/library-toolbar.md` fornece rastreabilidade estática de `profile_foryou=40dp` na ação Mais Opções da Biblioteca, forma com cantos 50% e ressalva explícita: esse dado é um parâmetro do modificador, não um tamanho final de toque ou prova de que *todas* as superfícies Apple são 40dp. `docs/components/overflow-menu.md` cita um candidato alternativo de 48dp sem vínculo com o popup observado. A padronização global em 40dp é **decisão do BitChord**, instruída pelo usuário, não reprodução universal comprovada por DEX.

## Auditoria de rotas/componentes

| Rotas / componente | Antes | Depois |
| --- | --- | --- |
| Biblioteca raiz, categorias e Playlists | 40dp visuais em `FloatingLibraryTopBarCircle` | 40dp compartilhados |
| Adições Recentes e grade Ver todos | `ArtworkPageBackButton` + `ArtworkPageActions` 48dp genéricos | Ambos 40dp visuais |
| Downloads e filtros | círculo Biblioteca 40dp misturado com back genérico em algumas rotas | Mesmo wrapper visual 40dp |
| Detalhes de álbum, artista, playlists e outras páginas empilhadas | back e ações genéricos 48dp | Todos por wrapper/medida 40dp |
| Home e Explorar (ações do topo) | caminho genérico 48dp ou biblioteca 40dp | 40dp |
| Overflow global da conta/Ajustes | `TopBarAccountButton` usava toolbarDiameter 48dp | 40dp visual |
| Ações circulares secundárias de detalhe `CircleIconButton` | `detailDiameter` 50dp | 40dp |
| `ActionPill` de reproduzir/aleatório com texto | altura 50dp | Preservada via `labeledPillHeight` |
| Controles de transporte do player e miniplayer, itens de lista, campos de busca | Regras de tamanho próprias | Intocados |

## Correção de layout

`AppleFloatingControlMetrics.floatingCircleDiameter` passou a ser a fonte de verdade para diâmetros visuais. `ArtworkPageBackButton` não desenha mais dois caminhos diferentes: volta pelo mesmo wrapper circular das ações da Biblioteca, preservando o `isCategory` apenas para semântica/testes e mantendo os callbacks. `ArtworkPageActions` pinta círculos de 40dp, mas deve continuar aceitando filhos `IconButton` medidos até 48dp: reduzir `maxIconBounds` junto com o círculo deixaria de aplicar o material, porque o botão Material3 continuaria medindo 48dp.

Acessibilidade/área clicável, fontes, back navigation, popups, insets, transporte, seleção, filtros e menu não sofrem alterações funcionais. Ajustes físicos de espaçamento/blur ficam dependentes de evidência no aparelho.

## Regressão

- `GlobalAppleVisualTokensTest` impõe 40dp visual a Library, toolbar e circular de detalhe; os tokens de alvo de toque (48dp) e pill com texto (50dp) continuam separados.
- `GlobalAppleTopBarChromeTest` exige círculo de 40dp nos dois e nos três botões genéricos, back genérico e `CircleIconButton` do detalhe.
- `CanonicalExploreGridTest` exercita callback de Voltar categorizado sem permitir override dimensional.

**Sem smoke APK**, conforme pedido. CI Android automática é independente de smoke físico; não declarar verde antes do resultado.
