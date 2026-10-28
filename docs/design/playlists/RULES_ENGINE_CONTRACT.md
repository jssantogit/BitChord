# Contrato transversal — playlists normais e inteligentes

> **Especificação proposta; não implementada por este documento.** Esta é a referência compartilhada das telas [01–08](README.md). A implementação deve obedecer ao [DESIGN.md](../../../DESIGN.md) e à situação funcional real do repositório.

## Identidade de playlist e rascunho

```text
PlaylistDraft {
  id?: PlaylistId
  kind: NORMAL | SMART
  name: NonBlankString
  description?: String
  artwork?: ArtworkRef
  sourceRefs: List<SourceRef>             // de onde vêm as músicas
  destination?: PlaylistDestination      // onde a playlist é salva
  normal?: { orderedTracks: List<TrackRef> }
  smart?: {
    groups: List<RuleGroup>
    groupMode: ALL | ANY                 // AND / OR entre grupos
    sort: SortSpec
    limit?: Int
  }
  revision: Long
}
RuleGroup { id, title, conditionMode: ALL | ANY, conditions: List<RuleInstance> }
RuleInstance { id, definitionId, schemaVersion, operator, typedValue, options }
```

- Forma de contrato, não classe Kotlin final; tipos, IDs e versionamento terão definição em código.
- Não transformar uma playlist normal em inteligente por troca de rótulo sem confirmar como serão tratados os itens e a ordem.
- `sourceRefs` é um conjunto explícito de fontes de leitura. `destination` é uma decisão independente, que não deve inferir publicação remota.
- IDs de grupo e condição são estáveis durante editar/duplicar/mover; salvar precisa ser idempotente por revisão ou chave de operação.

## Semântica lógica

```text
candidatos = tracksOf(authorizedSources)
corresponde(grupo, faixa) = ALL(condições) OU ANY(condições), conforme group.conditionMode
corresponde(playlist, faixa) = ALL(grupos) OU ANY(grupos), conforme smart.groupMode
resultado = ordenar(candidatos que correspondem)
resultado = limitar(resultado)  // política de contagem e desempate explícita
```

- AND/OR são operadores **de conjuntos distintos**; não implicitamente equivalentes.
- Grupo vazio e playlist inteligente sem grupos são inválidos na proposta de UI; não usar verdade vacuamente verdadeira escondida.
- Quando um campo não existe, retorna `UNKNOWN`, não zero/false automaticamente. Avaliador deve especificar semântica conservadora, inclusive para exclusões (`NOT`).
- Desduplicação de gravações/versões entre fontes depende de identidade canônica e política de edição; **nunca somente pelo título**.
- Ordenação determinística e desempate estável evitam playlist mudando a cada reabertura sem mudança na fonte.
- O limite opera depois do filtro e da ordenação. Preview deve nomear claramente 'correspondências totais' versus 'músicas incluídas após limite'.

## Catálogo de condições

Toda definição registrável deve expor:

| Campo | Requisito |
| --- | --- |
| `definitionId` / `schemaVersion` | Identidade estável, migração e compatibilidade de rascunhos antigos |
| Categoria, nome, descrição, aliases | Busca local/global em PT-BR; sem IDs duplicados em categorias cruzadas |
| `sourceCapabilities` | Fontes em que dados e operador são confiáveis, com motivo de indisponibilidade |
| Tipo de valor + operadores | Texto, número, data/intervalo, enum, múltipla escolha, pasta, booleano |
| Validação | Regras de entrada, unidades e campos obrigatórios |
| Editor Compose | UI especializada, não campo genérico para todas as regras |
| Serialização e avaliador | Mesmo predicado para preview, playlist salva e refresh |
| Observabilidade | Distinguir `VALID`, `INVALID`, `UNKNOWN`, `UNAVAILABLE` e falha de consulta |

A experiência visual mostra categorias como Músicas e metadados, Avaliações, Reprodução e histórico, Datas, Biblioteca e pastas, Áudio e qualidade, Outros. **Exibição no mockup não habilita automaticamente qualquer condição.**

## Caminhos e permissões

- `Pasta de origem`: guardar identificador da fonte e referência autorizada persistente (URI/document ID ou chave compatível), separada do nome/caminho mostrado.
- No Android local, avaliar uso de SAF/MediaStore conforme acesso realmente disponível; jamais tratar uma string de caminho como permissão.
- Provedores remotos (Navidrome, WebDAV, SMB) podem precisar de navegadores, caches e matchers próprios; caminho local não pode ser reaproveitado sem mapeamento verificável.
- Subpastas usam relação real de ancestralidade e normalização adequada ao provedor; não usar correspondência ingênua de prefixo, que confundiria `ILLIT` e `ILLIT-Backup`.
- Pastas excluídas, permissionamento revogado e cache incompleto devem produzir estado de recuperação, não resultado fictício.

## Prévia, consistência e fontes

- Preview sempre read-only; não escreve avaliações, downloads, fila ou playlist antes da confirmação.
- Cancelar consultas obsoletas ao mudar filtro, fonte ou ordenação; apresentar estado carregando, indisponível e parcialmente completo.
- Contagem da prévia só é válida para a revisão atual do `PlaylistDraft`.
- A seleção de fontes na revisão tem card próprio, **mesmo se houver apenas uma fonte**; nunca inferir fontes a partir de artistas da prévia.
- A política de atualização automática (quando e com que falhas) deverá ser definida e implementada; não prometer sincronização instantânea sem motor.

## Estado atual e lacunas reais

- `SmartPlaylistBuilderScreen.kt`: editor atual de poucos critérios (ex.: rating, completions, última audição, duração e limite) em consulta plana, predominantemente AND; sem os novos grupos e editores de pastas.
- `SmartPlaylistRatingSources.kt`: provê evidência canônica e leitura remota condicionada à completude do cache; reaproveitar a disciplina de proveniência/conta/ausência.
- Interface de criação normal, rascunho unificado, arte, descrição, grupos AND/OR, múltiplas fontes e editores por categoria **são propostas para implementar**, não garantias da `main`.
- Não adaptar prematuramente PRs de catálogo #183, DSP #188 ou avaliações #182 às telas antes de integrar/validar suas respectivas capacidades.

## Matriz mínima de testes

1. AND/OR em dois níveis, regras negativas e valores ausentes.
2. Migração/versão de regra desconhecida, rascunho incompleto e operações de back/forward.
3. Igualdade entre preview e execução persistida para mesmas fontes/revisão.
4. Zero itens, 5.000+ candidatos, limite, desempate e cancelamento de consulta.
5. Fonte removida, conta trocada, cache incompleto e pasta sem permissão.
6. Seleção normal em lote, deduplicação/versões, ordenação manual e idempotência de salvar.
7. TalkBack, texto ampliado, teclado, alvos de toque, botão fixo com IME/insets.
8. Smoke físico Android (Galaxy A54) com fonte local e fonte remota realmente configuradas.
