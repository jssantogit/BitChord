# Android V2 — aplicação da especificação de playlists #192

Os `spec.md` e `tensions.md` das oito telas e o contrato da pasta
`docs/design/playlists/` foram copiados sem alteração da branch
`docs/playlists-ui-tensions-20261028`. São a **referência aprovada**
para o design Android, junto ao `DESIGN.md` do app. Textos de "pendente"
nessas specs registram o estado da documentação original, não contradizem
o progresso posterior da PR #193.

| Tela aprovada | Estado na branch `listening/smart-playlists-v2-192` |
| --- | --- |
| 01 Nova playlist | Nome, escolha Normal/Inteligente e seletor funcional de fonte; capa e descrição personalizadas ainda não são persistidas |
| 02 Adicionar músicas | Faixas (até 200 no SQL), Álbuns e Artistas por IDs canônicos, seleção persistente e busca por fonte escolhida |
| 03 Regras | Grupos resumidos, lógica entre grupos, ordenação e limite |
| 04 Editar grupo | E/OU, editar, remover, mover, grupo aninhado, referências |
| 05 Adicionar condição | Categorias com pesquisa local tolerante a acentos |
| 06 Categoria | Lista dos campos V2 realmente disponíveis |
| 07 Editor tipado | Texto, números, duração em segundos, origem, NOT; pastas/SAF ainda indisponíveis |
| 08 Revisar e criar | Inteligente: regras, origem escolhida e prévia; Normal: lista compacta sem Subir/Descer e capa da primeira música |

## Playlists normais canônicas — implementação funcional

- Persistidas em `canonical_static_playlist` (Room v5; migrações explícitas v3→v4→v5),
  distintas das playlists inteligentes V1/V2 e de `LocalPlaylistStore`
  legado, baseado em `Song/videoId`. Nenhum ID sintético representa uma música.
- Cada playlist normal salva uma **lista ordenada de RecordingIds**; nomes e
  componentes podem ser editados sem alterar o ID da playlist. A página Playlists
  apresenta as playlists normais canônicas, inteligentes e legadas juntas,
  em lista de uma coluna.
- As músicas pesquisáveis devem ter `MediaAsset` vinculado ao provedor local
  ou à conta OpenSubsonic atualmente configurada. A consulta é limitada no
  SQL e não transforma título/artista em identidade. Álbuns e artistas usam
  vínculos reais de RecordingId com ReleaseId/ArtistId.
- Ao tocar em Reproduzir, os RecordingIds salvos são revalidados contra o
  catálogo atual e passam pelo mesmo planejador de lote e protocolo Media3
  das coleções inteligentes. Arquivos e credenciais são reavaliados, sem
  forçar fallback para vídeo ou para outra conta.
- Membros temporariamente indisponíveis permanecem persistidos, mas não
  ganham autorização de reprodução. Uma playlist vazia pode ser salva.
- As playlists normais persistem sourceProviderKey no Room, com fonte local,
  servidor atual ou todas as fontes. A busca e o planejador filtram por essa
  seleção. Ainda não há capa personalizada, descrição, exportação M3U ou
  reordenação por arraste. A edição continua no fluxo do assistente.

## Restrições que não podem ser mascaradas

- **Fontes:** a normal persiste chave exata do provedor; a inteligente
  persiste uma condição SOURCE_KIND obrigatória na raiz ALL da AST V2.
  Playback restringe MediaAssets antes de resolver a fila. Não há fallback
  entre provedor local e remoto após escolha explícita.
- A AST V2 não persiste nomes de grupo, artwork da playlist, descrição,
  nem caminho de pasta autorizado. Não exibir controles que aparentem salvar
  esses dados.
- Gênero, compositor, favorito, qualidade de áudio e data original exigem
  evidência de origem confiável e avaliação tipada antes de constar do catálogo.
- Cada mudança de condição/ordenação/limite invalida a prévia anterior.
- O editor é apresentado em tela cheia, respeitando `BitChordTheme`,
  SF Pro Display e `PAGE_GUTTER`; não clona a UI do Symfonium.
- A CI valida comportamento JVM/Compose e não substitui smoke visual no A54.

**Validação:** acompanhar a CI do HEAD exato da PR #193. Não realizar
merge, release ou novo APK com base somente nesta documentação.

## Biblioteca e navegação — rodada do Galaxy A54 (29/10/2026)

- A raiz Android reutiliza LibraryLink e exibe Playlists, Artistas, Álbuns,
  Músicas, Rádios (futuro/desabilitado), Minhas avaliações e Downloads.
  Deixa de exibir Replay, rediscovery e a shelf paralela de playlists.
- Adições locais: até seis gravações reais ordenadas por data MediaStore,
  por consulta SQL limitada, sem lançar falsos álbuns. O toque abre
  a página de músicas locais já existente.
- A página Playlists tem lista vertical de uma coluna, abertura de detalhe
  com capa de faixa, metadados e membros canônicos; Editar é ação separada.
  Playlists legadas preservam a navegação existente, sem converter
  RecordingIds canônicos para Song/videoId.
- A revisão normal tem lista compacta, sem Subir/Descer, e mostra arte
  da primeira música como capa provisória.
- O escopo de origem é aplicado na escolha, na persistência e no plano
  de reprodução, inclusive ao revalidar trocas de conta.

**Pendentes:** smoke físico no A54, drag-and-drop de reordenação,
capas personalizadas, acabamento conforme capturas e qualquer ajuste de
navegação detectado no aparelho. Não há APK ou merge autorizado nesta rodada.
