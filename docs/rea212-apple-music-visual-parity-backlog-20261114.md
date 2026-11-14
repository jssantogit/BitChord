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
| **P1 — alto impacto antes de considerar paridade visual** | Home — hierarquia | **Parcial — ordem e fonte de Recentes pendentes** | O hero de Lançamentos mantém identidade verificada, mas a Home canônica atual não recebe um shelf de histórico de reprodução. Não preencher Reproduções recentes com datas de lançamentos, resultados de catálogo ou plays não comprovados. A ordem editorial da Apple é personalizada e não deve ser copiada como dado. |
| **P1** | Home — geometria de cards | **Corte de código publicado; CI/aceite visual pendentes** | Commit `a8c3cc2e`: hero estreito com proporção 0,75 e fração de largura 0,62 (aproximação baseada em V01, não tokens Apple); carregamento usa a mesma geometria; shelf `Recents` em qualquer posição usa capa quadrada de 150 dp e título/artista abaixo, com skeleton e gestos coerentes. Falta verificar em aparelho sombra/gradiente, recorte, título e espaçamentos; a Home canônica ainda não tem uma fonte real de histórico. |
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

## Cortes R3/R4 — Biblioteca e detalhes (14/11/2026)

Este registro acompanha os cortes sem transformar aproximações em afirmações sobre o runtime do Apple Music. A fonte é o [handoff final R1–R6 do am-descomp](https://github.com/jssantogit/am-descomp/blob/fase-3-investigacao-visual/docs/rea212-final-visual-handoff.md); não foi feita coleta nova.

| Corte / situação | Mudança ou auditoria BitChord | Limitações e evidência ainda necessária |
| --- | --- | --- |
| **Biblioteca canônica — implementado, CI física pendente** | Nas categorias Músicas/Álbuns/Artistas em modo lista, o divisor de 0,5dp com inset de 84dp aparece **somente entre as linhas**, sem hairline final. Grid e seleção permanecem intactos. Regressão `CanonicalLibraryRowDividerTest`. | R4/S01–S02 confirmam lista de artistas e grade de álbuns, mas não fornecem dp exatos de título, busca, espaçamento, avatar, baseline, touch targets nem estado de tema claro/RTL. A estrela amarela de S02 **não deve** ser interpretada automaticamente como rating BitChord. Exigir confronto no aparelho. |
| **Álbum — auditado, sem novos dados inventados** | Mantido `NativeReleaseHeaderLayout` com capa, título, crédito, qualidade e metadados reais, faixa numerada e ações próprias; não copiar seções relacionadas sem vínculos verificados. | R3/S07 é visão longa de 289px reescalada. Não prova proporção 76% usada atualmente na capa BitChord, gradiente exato, tipografia/baseline, limiar da toolbar ou seções relacionadas. A identidade de edições/capas 1024px depende da PR #216. |
| **Artista — auditado, composição existente preservada** | `NativeCanonicalArtistContent` já possui hero, fundo `palette.wash`, top release e seções somente com lançamentos verificados; não introduzir catálogo/Playlists inventados. | R3/S06/S04 e V03 mostram hero e rolagem, mas não provam implementação de imagem dinâmica, altura do hero, callback de scroll, fades ou threshold do título. Seções Apple como Videoclipes/Artistas semelhantes não estão autorizadas sem dados equivalentes. |
| **Playlist — implementado, CI física pendente** | `NativePlaylistHeroActions`: quando há ativos reproduzíveis, mantém Play/Shuffle e fixar/desafixar; em playlist vazia, carregando ou metadata-only, **esconde Play/Shuffle** e mantém um controle circular de fixação de 50dp. Regressão `NativePlaylistHeroActionsTest`. | R3/S03/S05 mostram playlist editorial com arte e bloco final de artistas destacados, mas não definem geometria/gradiente em dp ou contrato universal. Não acrescentar artistas em destaque, data de atualização, fonte editorial ou duração sem dados reais. Conferir estados normal/smart, título longo, capa própria, tema e clique físico. |

### Lacunas a acumular para **uma futura issue** de am-descomp (ainda não aberta)

1. **R4:** medir, se houver evidência legítima de runtime, hierarquia título → busca → ações → primeira linha, inset das divisórias, raio/corte dos avatares, estados vazios, fonte grande e variações de largura. Nunca converter pixels de S01/S02 diretamente para dp.
2. **R3 álbum:** confirmar apenas observáveis do layout sob capa clara/escura, placeholder durante loading, collapse da toolbar e relações entre título, capa, botões e primeira faixa. S07 sozinha é insuficiente.
3. **R3 artista:** esclarecer com observação de runtime o comportamento da foto no scroll e o desaparecimento/retorno do título; separar mídia animada efetiva de troca de quadros no vídeo.
4. **R3 playlist:** distinguir seções editoriais específicas de contratos genéricos, e documentar estados de capa manual/mosaico/vazio, descrição longa, ações e rodapé.
5. **Critério de STOP:** registrar `unknown/requires_runtime` quando fonte não sustentar medida; evitar nova exploração ampla do APK ou promessas de pixel-perfect. Esta lista é backlog, **não** autorização para criar a issue agora.

**Gate de aceite destes cortes:** compilar/testar Android e shared UI no HEAD exato e realizar smoke visual no aparelho antes de fechar/mesclar a #212. Nenhum APK novo solicitado por este corte.
