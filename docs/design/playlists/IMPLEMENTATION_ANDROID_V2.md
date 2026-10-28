# Android V2 — aplicação da especificação de playlists #192

Os `spec.md` e `tensions.md` das oito telas e o contrato da pasta
`docs/design/playlists/` foram copiados sem alteração da branch
`docs/playlists-ui-tensions-20261028`. São a **referência aprovada**
para o design Android, junto ao `DESIGN.md` do app. Textos de "pendente"
nessas specs registram o estado da documentação original, não contradizem
o progresso posterior da PR #193.

| Tela aprovada | Estado na branch `listening/smart-playlists-v2-192` |
| --- | --- |
| 01 Nova playlist | Nome, apresentação da origem canônica e escolha do ramo inteligente; capa e descrição requerem persistência própria |
| 02 Adicionar músicas | Ramo normal não entregue; não há simulação enganosa de seleção |
| 03 Regras | Grupos resumidos, lógica entre grupos, ordenação e limite |
| 04 Editar grupo | E/OU, editar, remover, mover, grupo aninhado, referências |
| 05 Adicionar condição | Categorias com pesquisa local tolerante a acentos |
| 06 Categoria | Lista dos campos V2 realmente disponíveis |
| 07 Editor tipado | Texto, números, duração em segundos, origem, NOT; pastas/SAF ainda indisponíveis |
| 08 Revisar e criar | Revisão de regras, card informativo de fontes, prévia Room read-only e salvar |

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
