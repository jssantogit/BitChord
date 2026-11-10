# REA #212 — Auditoria de composição e efeitos dos cards (2026-11-10)

## Conclusão

A rodada de controles de 40dp **não encerrou** o trabalho global. O código distribui cards em famílias reutilizáveis, cada uma com proporção, moldura, espaçamentos, composição de capas, sobreposição e custo de efeitos distintos. Há oportunidades reais de consolidação, mas não existe evidência para fixar um único tamanho, blur ou contorno em todos os tipos de card.

Fonte: `jssantogit/am-descomp`, `fase-3-investigacao-visual` HEAD `7200e849`, principalmente:
- `docs/components/media-card.md`;
- `docs/design/geometry-components.md`;
- `docs/components/library-category-content.md`;
- `docs/design/composition-surfaces.md`;
- `docs/FINAL-AUDIT.md`.

### Evidência confirmada e seus limites

1. **Tile da busca (`search_landing_tile`):** Controller da busca inflando o layout rastreado em DEX; imagem `centerCrop`; razão declarada de **1.777** (sem comprovação da interpretação final do custom view); raio **7dp**; título inferior e recuo horizontal/inferior **10dp**. A contagem de colunas é base 2, variantes 3/4/5/6/7/10 conforme recursos qualificados; a configuração realmente escolhida não foi observada.
2. **Grade candidata da Biblioteca (`music_grid → grid_a_c`):** capa candidata `SquareFrameLayout`, raio **7dp**, espaço texto de **4dp**, subtítulos **15sp** e espaço final **12dp**. O caminho da classe/tela da captura para esse grid não foi demonstrado: **não** transferir raios/medidas a todos os álbuns.
3. **Cards editoriais de Home/Novidades:** hierarquia de estantes e card parcial observada, mas medidas finais, sombras, gradientes, cores e transições **desconhecidos**. Efeito de blur e scrims de `LyricsBackgroundLayerView` pertencem ao fundo do player, **não** à composição dos cards.
4. **Bordas/pills com `bg_apple_material`:** receita em camadas documentada para miniplayer e barra inferior, **não** para todas as capas. Não transportar um vidro/blurring genérico para cards sem evidência.

### Inventário do BitChord anterior à rodada

| Família | Código compartilhado | Geometria/efeito anterior | Evidência |
| --- | --- | --- | --- |
| Estantes quadradas | `NativeShelfCard` → `ShelfCard`/`NewShelfCard` | 150dp, 1:1, 12dp, recuo do título 10dp; borda discreta em capas verificadas | Política existente do fork; não medida do Apple |
| Adições Recentes locais | `CanonicalRecentAdditionCard` | 142dp fixos, 1:1, arte do SmartPlaylistPreview (8dp), gap texto 4dp | Discrepância de largura comprovada em código |
| Destaques Home | `HeroCard` | 70% da largura, máximo 320dp, razão 0.92, 18dp, scrim preto até 0.78 | Política editorial Bitchord; dimensões Apple não comprovadas |
| Explorar gêneros | `MoodGenreCard`/`NativeExploreCategorySleeve` | Razão 1.72, 10dp, texto inferior com recuo 12dp, duotone + scrim até 0.34 | Equivalente funcional do tile ilustrado da busca, com limite de associação |
| Destaque de lançamento do artista | `NativeArtistTopReleaseCard` | 28dp externo, capa 88dp/12dp, borda translúcida e paleta | Card especial, não transformar em estante |
| Linhas de faixas | `SongRow`, `CompactTrackRow` | Layout horizontal/artwork reduzido | Outro papel, não equiparar ao card quadrado |

### Primeira consolidação segura aplicada

- Novo `NativeMediaCardMetrics` com **papéis separados** para estante quadrada, legenda e descoberta: evita um raio/tamanho global que destruiria cards editoriais e de artistas.
- Estantes e card de criar playlist mantêm o tamanho validado no próprio fork: `SHELF_CARD_WIDTH=150dp`, `squareCoverShape=12dp`, `shelfCaptionGap=10dp`.
- Adições Recentes locais usam a mesma largura 150dp das demais estantes. O 142dp anterior não tinha vínculo no `am-descomp`.
- Explore aplica os valores declarados do tile de busca como **adaptação explícita**: proporção 1.777, raio 7dp e título inferior com recuo 10dp. A origem usa card ilustrado, mas a associação ao tile exato em runtime permanece `inferred`, não `static_verified`.
- Preservados os estilos de hero/artist featured, clipping de capas sem link ao tile, overlay de contraste, estados de play, click/long-press, placeholder e ordem de seções. Nenhum novo blur, sombra dinâmica ou custo de renderização por frame introduzido.

### Próxima rodada global que ainda falta (não declarar concluída)

- Inventariar efeitos, draw order e bordas para variantes square, editorial, featured artist, genre, playlist e local recording, com capturas Android pareadas e precedência de evidências.
- Conferir shapes reais de imagens (placeholders, loading, erro, capas de artista vs álbum), overlays de texto e contraste em arte clara/escura.
- Verificar motion de entrada/tap/long-press, snapping, clipping e placeholders em grid, 1x/2x densidades e tablet.
- Auditar cargas caras por card: `MeshGradientBackground` contínuo em algumas pastas, blur em backdrop de artista e reconstruções de gradientes; preservar reprodução e performance.
- Aferir dimensões de Home editorial, artista destaque e cartões de playlists sem copiar candidatos XML como fatos de runtime; abrir investigação no `am-descomp` se faltarem fontes.

Nenhum smoke APK foi solicitado ou disparado. CI automática valida código, não pixel matching.
