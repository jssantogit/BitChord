# Biblioteca — integração visual nativa e smoke acumulado (PR #206)

## Estado

- Implementação em **draft**, baseada na [PR #205](https://github.com/jssantogit/BitChord/pull/205); por sua vez #205 depende de #193.
- **Nenhum APK, smoke físico novo ou merge autorizado nesta etapa.** Aprovação de CI não equivale a paridade visual no Galaxy A54.
- Referências externas (Apple Music, Symfonium) indicam apenas interações/estrutura. A UI é composta com os elementos já presentes no BitChord, sem novas escalas/paletas/componentes genéricos.

## Inventário de reaproveitamento — conferir em cada revisão

| Funcionalidade | Elemento nativo reaproveitado | Regra de integridade |
| --- | --- | --- |
| ⋮ de músicas, álbuns, artistas | `RowMoreButton`, `BrowseActionsSheet`, `ActionRow`, `CanonicalUnifiedArtwork` | Playback por RecordingId+MediaAsset elegível, não por URL/Song fictício |
| Escolha de playlist | `SettingsGroupSurface`, `SettingsRow`, `RowDivider`, `PlaylistWizardSearchEntry`, `PlaylistWizardTextEntry`, `PlaylistWizardPrimaryAction`, capa `CoverPlaceholder`/Coil | Só playlists normais. Salvar ordem canônica, deduplicar RecordingId, bloquear fonte indisponível |
| Seleção múltipla | `CanonicalTrackRow`, `LibraryRow`, `NativeLibraryEntityCard` e indicadores `IconButton` nativos | Seleção tipada por entidade, preservada ao trocar filtros, sem deduplicar por nome |
| Bandeja contextual | `GroupShape`, `MaterialTheme`, `PlaylistWizardCompactAction` existentes | Recolhível, contador acessível e posicionamento respeitando padding do host/miniplayer |
| Avaliação de álbum/artista | `CanonicalRecordingFeedbackPanel`, `ActionRow` do cabeçalho, `Star` nativa | ArtistId/ReleaseId reais; notas próprias em `canonical_entity_rating`, separadas das gravações |
| Adições recentes → Ver todos | `UnifiedLibraryToolbarActions`, `FrostedUnifiedLibrarySourceMenu`/`SortMenu`, `SearchField`, `LibraryRow`, `gridRows` | Somente adições MediaStore comprovadas; filtros sobre cache, timestamps reais e rating RecordingId |

## Testes físicos no Galaxy A54 — após autorização de APK

1. **Biblioteca → Músicas:** menu ⋮ oferece ações contextuais sem regressão de espaçamento/alinhamento. Avaliação abre o editor existente. Fila usa apenas faixa disponível.
2. **Álbuns e Artistas:** lista e grade usam o mesmo overflow, arte e tipografia. Abrir páginas continua nativo. Em AtHeart/aespa/ablume, cards de lançamentos e linhas compactas aceitam ações sem navegar ao fluxo legado.
3. **Playlist normal:** ao selecionar faixa, álbum ou artista, o seletor mostra playlists normais, pesquisa e Nova playlist. Artista expande todas as gravações elegíveis; álbum expande seus membros. Não inserir entrada simbólica nem perder ordem/versões corretas. Playlist inteligente não aparece como destino manual.
4. **Indisponibilidade:** desconectar Navidrome ou trocar filtro de fonte depois de selecionar. Seleção deve persistir até descarte explícito. Salvar com fonte incompatível deve exigir revisão, sem alegar sucesso de itens descartados.
5. **Seleção múltipla:** marcar/desmarcar música, álbum e artista; conferir indicador, contador por entidade, navegação, scroll, filtros e bandeja recolhível acima do player, safe-area/IME e alvos de toque.
6. **Avaliações:** atribuir notas 0,5 em 0,5 a artista e álbum; voltar e conferir persistência/ícone; alterar nota de artista não altera músicas. Artista apenas por nome de exibição, sem ArtistId canônico, não pode ser avaliado.
7. **Adições recentes:** preservar inicialização/caching já aprovados; Ver todos tem pesquisa, ordenação por títulos e timestamps MediaStore reais, filtro de estrelas locais e lista/grade. Não anunciar eventos recentes do Navidrome sem evidência desse provedor.
8. **Regressões anteriores:** 594 músicas/29h20m, Logs internos, adições recentes visíveis, artistas com fotografia/degradê e Álbuns/Singles/EPs separados.

## Pendências não implementadas nesta PR

- **Fixar itens gerais da Biblioteca:** o mecanismo atual `AppSettings.pinnedPlaylists` só suporta playlists; não apresentar uma ação Pin que não tenha destino/canonização realmente implementados.
- Paridade de **Adições recentes remotas:** a projeção atual é MediaStore local. Exibir Navidrome nessa seção exige evidência de data de inclusão no provedor e arte autenticada, não ordenar por data de sincronização.
- Validar a **semântica de seleção parcialmente disponível** entre fontes com QA físico; o salvamento já bloqueia seleção totalmente indisponível ou gravações elegíveis ausentes, mas não reivindicar exaustividade para toda edição parcialmente disponível sem teste específico.
- Paginador e revisão formal de playlists conforme `docs/design/playlists/02-adicionar-musicas/spec.md`, que é outra tela; não confundir esta bandeja contextual de Biblioteca com a tela inteira do wizard.

**Fonte de verdade da implementação:** commits/CI da PR #206. Este documento é checklist e rationale, não prova de aprovação física.
