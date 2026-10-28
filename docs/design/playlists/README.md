# Playlists — especificação visual e tensões de UX (Android)

> **Estado:** documentação de design / proposta de implementação. As telas são mockups aprovados como direção visual em 28/10/2026; **não são funcionalidades entregues**. Esta documentação não altera os motores, a main, DSP, catálogo ou avaliações.

## Referências e alcance

- Design system implementado: [DESIGN.md](../../../DESIGN.md) — SF Pro Display, tema escuro, superfícies neutras, insets, cantos arredondados e composição existente. O acento vermelho dos mockups é uma proposta local; `AccentRed` existente não é o `primary` global do tema.
- Mockups visuais: oito etapas distintas discutidas no projeto BitChord Fork em 28/10/2026; imagens fornecidas na conversa de planejamento. **Os PNGs não estão versionados neste diretório**; estes arquivos registram suas decisões em texto, sem inventar links para anexos.
- Código atual: `app/src/main/java/com/music/bitchord/ui/screens/SmartPlaylistBuilderScreen.kt` (construtor simples) e `SmartPlaylistRatingSources.kt` (prévia canônica). O modelo atual cobre poucos campos e predicados combinados por AND: **não presumir grupos aninhados, OR, caminho de pasta, arte/descrição ou criação normal como já implementados**.
- Documento transversal: [RULES_ENGINE_CONTRACT.md](RULES_ENGINE_CONTRACT.md). Não integrar diretamente o código de outras PRs abertas enquanto estas não forem adaptadas à base da `main`.

## Telas e arquivos por página

| Etapa | Página | Spec funcional/visual | Tensões de design |
| --- | --- | --- | --- |
| Entrada comum | Nova playlist | [spec.md](01-nova-playlist/spec.md) | [tensions.md](01-nova-playlist/tensions.md) |
| Ramo Normal | Adicionar músicas | [spec.md](02-adicionar-musicas/spec.md) | [tensions.md](02-adicionar-musicas/tensions.md) |
| Ramo Inteligente | Regras da playlist | [spec.md](03-regras-da-playlist/spec.md) | [tensions.md](03-regras-da-playlist/tensions.md) |
| Subfluxo de grupo | Editar grupo de regras | [spec.md](04-editar-grupo-de-regras/spec.md) | [tensions.md](04-editar-grupo-de-regras/tensions.md) |
| Catálogo de categorias | Adicionar condição | [spec.md](05-adicionar-condicao/spec.md) | [tensions.md](05-adicionar-condicao/tensions.md) |
| Opções de uma categoria | Biblioteca e pastas | [spec.md](06-biblioteca-e-pastas/spec.md) | [tensions.md](06-biblioteca-e-pastas/tensions.md) |
| Editor tipado da condição | Pasta de origem | [spec.md](07-pasta-de-origem/spec.md) | [tensions.md](07-pasta-de-origem/tensions.md) |
| Confirmação comum | Revisar e criar | [spec.md](08-revisar-e-criar/spec.md) | [tensions.md](08-revisar-e-criar/tensions.md) |

## Mapa de navegação

```text
01 Nova playlist
   ├─ Normal → 02 Adicionar músicas ─────────────────────┐
   └─ Inteligente → 03 Regras da playlist               │
                     └─ 04 Editar grupo de regras        │
                         └─ 05 Adicionar condição        │
                             └─ 06 Biblioteca e pastas   │
                                 └─ 07 Pasta de origem   │
                                     ↩ 04 → 03 ──────────┤
                                                        ▼
                                                 08 Revisar e criar
                                                        ▼
                                                 Playlist criada
```

A tela 06 representa uma **categoria**, e a 07 é um **exemplo de editor tipado**. Outras categorias e regras usam o mesmo mecanismo, mas terão editores específicos. A busca da 05 pode abrir o editor diretamente com contexto de retorno.

## Convenções de entrega

- **`spec.md`** descreve objetivo, anatomia, ações, estados, critérios de aceite e limites de implementação.
- **`tensions.md`** guarda decisões e conflitos reais; `Decidido` é orientação aprovada/derivada, `Aberto` exige validação funcional. Não transformar hipótese em funcionalidade existente.
- Escopo: Android Compose. Fluxo em tela cheia; título grande e botão inferior fixo respeitando safe area, IME, rolagem e TalkBack. Sem aba inferior ou mini-player visíveis sobre o formulário, sem interromper reprodução ativa.
- Os mockups foram feitos para celular convencional; dimensões do PNG não são tokens de design. Testar no Galaxy A54, fontes ampliadas e dispositivos menores.
- Uma playlist **Normal** guarda seleção e ordem explícitas. Uma **Inteligente** guarda fontes, grupos de condições, combinação de grupos, ordenação e limite; avaliação sempre usa a mesma semântica em preview e execução.
- Não chamar 'fonte do catálogo' de 'destino de salvamento'; são conceitos distintos. Não anunciar fontes, regras ou atualizações automáticas não suportadas.
- Itens, IDs, arte, notas, histórico e disponibilidade devem ser tratados de modo canônico; nenhuma referência a reprodução de vídeo do YouTube é desejada na UI. Capas animadas musicais continuam suportadas.
- Qualquer mudança de fonte/regras invalida a prévia correspondente; prévia é read-only e não publica músicas nem altera avaliações.

## Sequência recomendada de implementação

1. Contrato de rascunho e navegação/voltar; 01, 02 e 08 para playlist normal primeiro.
2. Modelo tipado de regras, avaliação AND/OR, ordenação e preview idênticos à execução.
3. 03 e 04 (grupos), 05 e 06 (catálogo pesquisável e capability-based).
4. 07 e demais editores de regra apenas conforme disponibilidade real de metadados e permissões.
5. Testes unitários de serialização/predicados, Compose de fluxo e smoke físico antes de declarar funcional.

## Pendências transversais

- Onde a playlist será persistida (dispositivo, fonte remota ou ambos) precisa de contrato separado da origem das músicas.
- Definir política de atualização de playlist inteligente (evento, reabertura, agendamento e casos offline).
- Definir fontes simultâneas, identidades canônicas/deduplicação e cobertura parcial.
- Definir persistência e recuperação de rascunhos, edição posterior e migração de definições inteligentes já salvas.
