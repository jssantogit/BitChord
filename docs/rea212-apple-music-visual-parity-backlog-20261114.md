# REA #212 — auditoria visual Apple Music Android e critérios de merge

**Data:** 2026-11-14 · **Escopo:** somente Android · **PR:** [#212](https://github.com/jssantogit/BitChord/pull/212) · **Integração externa pendente:** [iTunes #216](https://github.com/jssantogit/BitChord/pull/216).

## Evidência e limites

- **Capturas físicas do usuário (14/11):** BitChord na Biblioteca/Artistas, filtros de fonte e ordenação, popup ⋯, Home “Lançamentos”/“Escolha a dedo”; Apple Music é a referência com o álbum **In Contact**, de **Caligula's Horse**, nas áreas de Home e Reproduções recentes. A ordem e o conteúdo das seções não podem ser atribuídos a uma regra fixa da Apple por uma captura isolada.
- **Referência de pesquisa:** `jssantogit/am-descomp`, `fase-3-investigacao-visual`, especialmente `docs/screens/top_level_home_page.md`, `docs/components/media-card.md`, `docs/motion/home-and-content.md`, `docs/components/overflow-menu.md`, `docs/library-reference.md`, `docs/design/geometry-components.md`. Distinguir observação de runtime, declaração estática, inferência e desconhecido.
- **Código BitChord inspecionado:** `sharedUi/.../screens/HomeScreen.kt`, `app/.../MainActivity.kt`, `NativeCompactOverflowMenu.kt`, `FrostedTopBar.kt`, `CanonicalCuratedHomeFeed.kt`, `CanonicalRemoteLibraryScreen.kt`, `CanonicalPlaylistDetailScreen.kt`. A implementação retém diferenciais deliberados de BitChord, não representa um cliente Apple Music nem deve reutilizar artes editoriais/licenças/fontes proprietárias.

## Situação por prioridade

| Prioridade | Área | Estado em 14/11 | Lacuna observada / contrato de aceite |
| --- | --- | --- | --- |
| **P0 — bloqueia aceite do corte** | Menus de fonte, ordenação e ⋯ | **Implementado na #212; CI e aparelho pendentes** | Em páginas AMOLED pretas, plate `Black.copy(alpha≈.94-.96)` fica invisível. Unificação de superfícies grafite opacas, elevação tonal em cores de capa, sombra ambiente suave sem contorno branco e scrim mais leve. Verificar fontes, ordenação, menu de Configurações e menus compactos. |
| **P0** | Smoke de regressão física | **Pendente** | No mesmo Android, conferir abertura/fechamento, contraste sobre lista e arte clara/escura, Flip acima/abaixo e RTL, rolagem, teclado, TalkBack, tema claro/escuro e “reduzir blur/animação”. CI não é prova visual. |
| **P0 — integração** | iTunes × MusicBrainz | **Bloqueado pela PR #216** | Preservar origem de dados e rótulos verdadeiros; iTunes só após integração funcional, sem batizar rotas MusicBrainz como Apple. Capas de 1024px e cobertura de discografia dependem da trilha de catálogo, não de inventar tiles na UI. |
| **P0** | Fluxos funcionais críticos | **Revisão física pendente** | Navegação dos cards; pressionamento longo e seleção em Home/Biblioteca/Playlists; rating ancorado à estrela; menus do player; adicionar à playlist/fila sem habilitar reprodução de metadados-only; mini player e aba inferior não interceptarem gestos. |
| **P1 — alto impacto antes de considerar paridade visual** | Home — hierarquia | **Pendente** | A captura Apple mostra `Mais relevantes pra você` com editoriais grandes e abaixo `Reproduções recentes`; a captura BitChord começa por `Lançamentos` e `Escolha a dedo`. Revisar ordem, destaque, mix entre seções e primeiro viewport. Somente promover conteúdo real e verificável, sem fabricar recomendações Apple. |
| **P1** | Home — geometria de cards | **Pendente** | Comparar proporção e recorte do hero, gradiente de legibilidade, tipografia sobre arte, largura da próxima capa parcialmente visível, distância entre heading/card e entre seções; capa quadrada recente com título/subtítulo **abaixo**, hero com legenda **dentro**. O código já tem `HeroShelf`, `HeroCard` e cartões quadrados: refinar os renderizadores existentes. |
| **P1** | Root Home — alinhamento | **Pendente** | Cabeçalho “Início” da captura BitChord parece mais baixo que na referência; checar o inset de status bar, diferença entre primeira e demais abas, toolbar ⋯ e header em repouso/rolagem. Medir em dp no dispositivo, não converter pixels cegamente. |
| **P1** | Biblioteca/categorias | **Parcial** | Grid de pins, categorias, busca e toolbar já usam linguagem Apple; rever distância entre título, campo de busca e primeira linha; tamanhos das miniaturas/linhas; ações circulares e largura dos filtros. A captura Artistas mostra filtro/ordenação sem plate legível, foco imediato deste corte. |
| **P1** | Explorar — gêneros | **Parcial** | Back global foi unificado; rever heading, corredores e espaçamentos, cartões destacados, placeholders quando capa ausente, ausência de pulos ao carregar e alinhamento de ações de categoria. Não trocar capa ausente por arte de edição incorreta. |
| **P1** | Álbuns | **Parcial** | Validar hierarquia da arte, nome/artista, pills Tocar/Embaralhar, qualidade, espaços entre header/lista, numeração/separadores, créditos/rodapé, overflow/rating e conteúdo metadata-only. Testar títulos longos/EP/single/capas claras/escuras. |
| **P1** | Artistas | **Parcial** | Refinar foto/hero, blocos Discografia, Singles/EPs, “Incluído em”, listagem de faixas, menus por hold, seções de participações e estabilidade da rolagem. Identidade/artistas compostos e classificação dependem da #216, não de heurística visual. |
| **P1** | Playlists | **Parcial** | Capa própria/mosaico, título e descrição, distinção smart/normal, botões, seleção e overflow, linhas de faixas, estados de vazio e edição de filtros/fontes. Evitar menus legados isolados; preservar regras de salvamento. |
| **P1** | Tipografia e ritmo | **Parcial** | Harmonizar título de página, seção, nome de card, subtítulo e opacidades, truncamento e baseline em Home/Explorar/Detalhes. `am-descomp` tem tokens estáticos, mas não prova fonte/baseline final; usar fontes licenciadas existentes, não redistribuir SF Pro. |
| **P1** | Transições e microinterações | **Parcial** | Validar card→detalhe, entrada de artista/álbum/playlist, abertura de menus ancorados, seleção/drag, fade do title ao rolar e continuidade do player. Respeitar redução de animações, evitar bounce/reflows. Timing Apple exato não comprovado. |
| **P1** | Carregamento e estados | **Parcial** | Skeleton e placeholders da mesma geometria do card final, retries sem voltar ao topo, superfícies vazias elegantes e sem carrossel piscando; não usar arte errada como fallback. A integração iTunes alterará caminhos de carregamento. |
| **P2 — polimento incremental após estabilizar integração** | Busca | **Parcial** | Refinar origem de catálogo, tabs de resultados, autofoco/IME, typeahead armazenado, separadores e menus de resultados sem confundir proveniência Apple/MusicBrainz. |
| **P2** | Player, fila e letras | **Parcial** | Ajustes de blur gradiente conforme arte, controles, ícones, timestamps, gesto do miniplayer, fila e legendas, conferindo transições com teclado/acessibilidade. Não apresentar Lossless/Atmos sem evidência de decodificação. |
| **P2** | Responsividade e acessibilidade | **Requer matriz física** | Font scale 1.0/1.3, dispositivos pequenos e tablets, rotação, TalkBack, navegação por teclado, RTL, baixo desempenho, light/dark, reduzir blur/motion. |

## Escopo realista de fechamento

**Pode bloquear merge:** bugs funcionais novos, regressões na navegação, texto de origem falso, menus ilegíveis, falha de CI Android/JVM, crash em uso comum, perda de acessibilidade ou incompatibilidade grave com o catálogo quando a PR #216 for incorporada.

**Não precisa bloquear por tempo indefinido:** reprodução literal de editoriais Apple protegidos, recomendações personalizadas sem sinais suficientes, transições sem evidência runtime, medidas exatas do motor Apple e toda a matriz P2. Esses pontos permanecem backlog distinto.

## Roteiro de smoke visual antes do merge

1. **Home:** conferir heading superior e alinhamento ⋯; primeiro hero e 2ª seção; rolar e alternar abas sem balanço; capas claras/escuras.
2. **Biblioteca:** menu ⋯ de Configurações; selecionar fonte local/todas; ordenar e filtrar estrelas em Artistas/Álbuns/Músicas, sem perder posição/seleção; observar plate e fundo.
3. **Explorar:** entrar em Jazz e outro gênero, voltar à esquerda, ⋯ à direita; carregar card com arte e card sem arte; retornar sem salto da grade.
4. **Detalhes:** álbum local + metadata online, artista com catálogo grande, playlist normal/smart; menus de hold e opções; classificação de faixas sem audio local.
5. **Avaliação:** estrela de cabeçalho abre card ancorado; dentro de ⋯ abre editor modal; cancelar não persiste; salvar/limpar atualiza UI.
6. **Condições:** claro/escuro, redução de blur, redução de movimento, fonte maior e TalkBack; mini player/barra inferior sem colisões.

**Critério de aceite:** gates Android + shared JVM do HEAD final, e comparação física documentada. PR #212 permanece draft e sem merge automático.
