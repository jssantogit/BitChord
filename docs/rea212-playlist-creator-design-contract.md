# REA #212 — Criador de playlists: contrato visual Android

**Estado:** implementação própria do BitChord; não é clonagem do editor de playlists do Apple Music. **Plataforma:** Android, PR #212. **Referência documental:** [AM-DECOMP Design Bible](https://github.com/jssantogit/am-descomp/blob/fase-3-investigacao-visual/docs/DESIGN.md) (Android 7.0.0, build 1614), [receitas para superfícies novas](https://github.com/jssantogit/am-descomp/blob/fase-3-investigacao-visual/docs/design/new-surface-patterns.md), [tipografia aplicada](https://github.com/jssantogit/am-descomp/blob/fase-3-investigacao-visual/docs/design/typography-components.md), [composição de superfícies](https://github.com/jssantogit/am-descomp/blob/fase-3-investigacao-visual/docs/design/composition-surfaces.md).

## O que foi comprovado na referência

- Hierarquia de títulos, seções editoriais, categorias em linhas, contornos e superfícies por componente; acento azul de seleção (night `#0091FF`, light `#0088FF`) nos caminhos documentados.
- Campo de busca com raiz e stroke separados (raio XML de `10dp`), popup de aba escuro/arredondado e folha contextual distinta.
- **Não há** no AM-DECOMP reconstrução completa, medição runtime nem hierarquia visual comprovada para `action_new_playlist` / `PlaylistEditFragment`. A documentação classifica layouts e comportamento desse editor como `unknown`.

## Adaptação deliberada do BitChord

1. **Hierarquia da página:** título editorial, capa editável, nome e descrição, escolha do tipo e só então fontes de áudio. A escolha de fonte depende do modo Normal/Inteligente.
2. **Campos:** `PlaylistWizardTextEntry` compartilha contraste de repouso, label destacado, erro próximo ao campo e contorno azul de foco. Geometria e opacidades são decisões locais, não extrações Apple.
3. **Tipo:** duas opções descritivas `Normal` e `Inteligente`, com semântica de escolha única, estado selecionado e check azul. Os cartões são uma solução de produto do BitChord, não uma tela observada no Apple Music.
4. **Fonte no resumo:** um grupo editorial curto mostra nome, descrição, ícone que corresponde à fonte real e ação `Alterar`. Em instalações com vários servidores, o resumo preserva o rótulo diferenciador (`Navidrome 1`, `Navidrome 2`). Fonte desconectada não deve aparecer como servidor disponível.
5. **Escolha de fonte:** folha com explicação fora do grupo e **uma única lista agrupada**, linhas inteiras clicáveis, descrições, divisórias discretas, estado de seleção semântico e check azul. Sem cartões independentes e sem controles de rádio visuais legados.
6. **Modelo:** somente áudio local ou OpenSubsonic/Navidrome entra como fonte; iTunes, Discogs e MusicBrainz continuam metadados, sem playback fabricado. IDs de provedores e playlists salvos não mudam com o refinamento da UI.
7. **Acessibilidade e movimento:** área clicável na linha, labels explícitos e estado selecionado para leitor de tela; manutenção dos comportamentos de Back/IME e preferência por movimento reduzido compartilhados. O fluxo precisa de smoke para confirmar foco, scroll, teclado, descartar alterações e densidade em telas pequenas.

## Limites e gate de aceitação

Não declarar paridade de medidas do editor com a Apple: não existe captura validada dessa superfície na referência. Os valores locais (`16dp` de cantos no seletor, `110dp` mínimo nos cartões de tipo, `64dp` mínimo nas linhas do seletor) são **adaptação**, não `static_verified` de `am-descomp`.

Antes de considerar a composição aprovada, comparar capturas do BitChord Android (tema claro/escuro, fonte normal/ampliada, com e sem servidores, IME aberto, modo Normal/Inteligente) para revisar hierarquia, densidade, contraste e clipping. A CI verifica código/comportamento; não substitui aceite visual físico. Não distribuir fontes ou assets proprietários recuperados da referência.
