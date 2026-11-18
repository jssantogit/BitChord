# REA #212 — linguagem visual dos cards expansíveis (Android)

Situação: implementação inicial; falta validação em dispositivo real. Referência aprovada pelo usuário: popup de filtro/ordenação na Biblioteca (15/11/2026).

## Contratos

| Papel | Fundo | Blur | Interação |
| --- | --- | --- | --- |
| Filtro / ordenação | neutro #29292D no escuro, #F5F5F7 no claro | Haze do mesmo host quando disponível; fallback sólido | ancorado ao botão, sem deslocar chrome |
| Avaliação | exatamente o neutro dos menus expansíveis (#29292D escuro / #F5F5F7 claro), sem borda | sem textura fosca própria; fallback sólido do host Dialog, sem Haze da página | modal central, título, estrelas e ações de confirmação |
| Seleção múltipla | mesma placa neutra **totalmente opaca** | **sem Haze** da lista interativa | dock fechado por padrão, menu cresce para cima, lista de ações rolável |
| Pressionamento longo | mesma placa neutra **totalmente opaca** | **sem blur** no card, fundo escurecido sutil | manter item elevado e menu sincronizados |

O tom da capa deve dar personalidade à página, mas não colorir arbitrariamente as superfícies de controles. Evitar menus inteiros verdes/roxos e qualquer texto de lista visível através do card de ações.

## Aceite antes de encerrar

1. Selecionar artista: dock compacto não encobre os nomes, ações expandem acima e continuam acessíveis; recolher e limpar seleção.
2. Testar também seleção de faixas e álbuns em telas pequenas e com miniplayer presente.
3. Pressionamento longo em páginas escuras e dinâmicas: cor e contraste estáveis, sem interferência do fundo ou duplicação de texto/capa.
4. Filtros preservam Haze quando disponíveis; avaliação usa a mesma superfície neutra sólida dos menus expansíveis, sem borda ou blur artificial, também sob redução de blur.
5. Abrir e fechar repetidamente sem piscadas, sem alterar a navegação ou o vetor do ⋯ do player.

Essa regra é comum à família de cards, mas cada papel conserva sua semântica: blur nos filtros quando o host permite; avaliação com o fundo neutro canônico (sem textura sintética no Dialog); solidez na seleção e no pressionamento longo.


## Complemento — destaque branco universal (15/11/2026)

Decisão do smoke físico: o **elemento acionado**, e não toda a superfície do menu, recebe um contorno claro consistente. A cor da placa de ações permanece grafite neutro no escuro ou neutro claro no tema claro, com legibilidade independente do artwork.

- `HeldContextMenu`: a prévia elevada mantém a imagem original do item, com contorno branco de 1,5dp (`heldContextSelectionOutline()`). O retorno animado não deve duplicar a linha de origem.
- ⋯ das barras de álbum online e playlists: preservar o vetor real do botão; exibir o mesmo contorno somente enquanto o menu está aberto. A placa popup não ganha borda branca.
- ⋯ do player: preservar a geometria e o vetor am-descomp `MorePlain` em ambos os estados; a sobreposição no diálogo usa o mesmo token de contorno para o launcher destacado, respeitando seu posicionamento original.
- O destaque de seleção múltipla (marcadores azuis) continua separado do estado ativo de menu. Não pintar contorno branco em todos os itens selecionados.
- A migração não altera callbacks de navegação nem torna metadados MusicBrainz/ListenBrainz reproduzíveis.

### Publicado nesta rodada; ainda não validado

- Novos pressionamentos longos em Artistas semelhantes, Incluído em, Playlists e lançamento em destaque da página de artista, ligados a `CanonicalSearchHeldMenus`.
- Estado de contorno ativo nos ⋯ das barras de álbum canônico, playlists públicas e playlists locais da Biblioteca.
- Vetor original e contorno compartilhados com o player.

**Limites:** inspeção textual estrutural não é teste Android. A última CI verde identificada ainda valida o commit `66727ea4`; não houve smoke/CI confirmada do HEAD desta rodada. Persistem auditoria completa dos demais lançadores na Home/Explorar, teste de gestos e validação física em telas pequenas, temas claro/escuro, blur reduzido e TalkBack. Manter PR #212 em draft.
