# Android V2 — aplicação da especificação de playlists #192

Os `spec.md` e `tensions.md` das oito telas e o contrato da pasta
`docs/design/playlists/` foram copiados sem alteração da branch
`docs/playlists-ui-tensions-20261028`. São a **referência aprovada**
para o design Android, junto ao `DESIGN.md` do app. Textos de "pendente"
nessas specs registram o estado da documentação original, não contradizem
o progresso posterior da PR #193.

| Tela aprovada | Estado na branch `listening/smart-playlists-v2-192` |
| --- | --- |
| 01 Nova playlist | Nome, informação sobre fontes configuradas e escolha Normal/Inteligente; capa e descrição personalizadas ainda não são persistidas |
| 02 Adicionar músicas | Busca limitada a 200 gravações por consulta, filtradas por fonte atual; escolha, exclusão e preservação da ordem por RecordingId |
| 03 Regras | Grupos resumidos, lógica entre grupos, ordenação e limite |
| 04 Editar grupo | E/OU, editar, remover, mover, grupo aninhado, referências |
| 05 Adicionar condição | Categorias com pesquisa local tolerante a acentos |
| 06 Categoria | Lista dos campos V2 realmente disponíveis |
| 07 Editor tipado | Texto, números, duração em segundos, origem, NOT; pastas/SAF ainda indisponíveis |
| 08 Revisar e criar | Inteligente: regras e prévia; Normal: membros selecionados com botões Subir/Descer, salvar/editar e exclusão confirmada |

## Playlists normais canônicas — implementação funcional

- Persistidas em `canonical_static_playlist` (Room v4; migração explícita v3→v4),
  distintas das playlists inteligentes V1/V2 e de `LocalPlaylistStore`
  legado, baseado em `Song/videoId`. Nenhum ID sintético representa uma música.
- Cada playlist normal salva uma **lista ordenada de RecordingIds**; nomes e
  componentes podem ser editados sem alterar o ID da playlist. A biblioteca
  apresenta as playlists normais canônicas, inteligentes e legadas juntas.
- As músicas pesquisáveis devem ter `MediaAsset` vinculado ao provedor local
  ou à conta OpenSubsonic atualmente configurada. A consulta é limitada no
  SQL e não transforma título/artista em identidade.
- Ao tocar em Reproduzir, os RecordingIds salvos são revalidados contra o
  catálogo atual e passam pelo mesmo planejador de lote e protocolo Media3
  das coleções inteligentes. Arquivos e credenciais são reavaliados, sem
  forçar fallback para vídeo ou para outra conta.
- Membros temporariamente indisponíveis permanecem persistidos, mas não
  ganham autorização de reprodução. Uma playlist vazia pode ser salva.
- Esta rodada **não implementa** capa personalizada, descrição, exportação
  M3U, seleção explícita de fonte persistida ou telas próprias completas de
  gerenciamento em massa. O editor de revisão é o primeiro caminho para
  alterar ordem, editar e excluir.

## Restrições que não podem ser mascaradas

- **Fontes:** o motor V2 consulta biblioteca canônica disponível e aceita
  regra `SOURCE_KIND`, mas **não** persiste uma seleção explícita de fontes
  por playlist. O card explica o escopo; não anuncia fontes escolhidas.
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
