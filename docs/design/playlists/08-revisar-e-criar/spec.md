# Especificação de tela — Revisar e criar

> Etapa: **Confirmação comum** · Status: **direção visual aprovada; implementação pendente** · Plataforma: **Android/Compose**.  
> Mockup de referência discutido em 28/10/2026; não confundir pixels de render com métricas medidas.  
> [Índice](../README.md) · [Tensões da tela](tensions.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md) · [Design do app](../../../../DESIGN.md)

## Objetivo

Garantir confirmação informada do que será criado e de quais fontes alimentam a playlist, mostrando apenas dados verdadeiros.

## Correspondência com o mockup

Resumo com capa/nome/descrição, regras compactadas, prévia de músicas, CTA Criar playlist; revisão acordada exige card próprio de FONTES DO CATÁLOGO.

## Anatomia da tela

### Identidade

Capa, nome, descrição e tipo; ação editar devolve ao passo 01 com rascunho íntegro.

### Fontes do catálogo

Card próprio e destacado entre identidade e regras/prévia. Uma linha por fonte efetivamente escolhida, com nome, tipo, estado e caminho opcional; abrir para editar seleção.

### Regras aplicadas (Inteligente)

Card expansível com resumo da lógica entre grupos e grupos compactados; cada grupo pode abrir 03/04. Para Normal, substituir por resumo da seleção manual e ordem.

### Prévia

Lista real de faixas por consulta read-only, capa/título/artista, origem quando necessário; número de candidatos e de itens após limite devem ter rótulos distintos.

### Atualização automática

Somente no ramo Inteligente, com texto fiel à política real de atualização e eventual indisponibilidade de fontes.

### Rodapé

Criar playlist só quando tudo estiver válido e a prévia for coerente; loading/idempotência/erro visíveis.

## Interações e navegação

- Editar identidade, fontes ou grupos faz ida/volta sem perder o restante.
- Expandir/contrair o card de regras sem redesenhar título e fontes.
- Gerar/atualizar prévia após alterações, cancelando consulta anterior e evitando resultados de configuração obsoleta.
- Confirmar criação uma vez: bloquear duplo toque, persistir atomicamente quando possível, indicar sucesso e abrir playlist criada.

## Estados e tratamento de erros

- Carregando prévia: skeleton e rótulo explícito, sem '100 encontradas' de exemplo.
- Nenhuma música encontrada: indicar que a regra é válida mas retornou zero; permitir editar antes de criar; política de playlist vazia deve ser definida.
- Fonte com erro ou incompleta: badge textual e advertência sem fingir completude.
- Identidade ou regras inválidas: CTA desabilitado com caminho para corrigir.
- Falha ao salvar: mensagem recuperável, sem duplicação por retry.

## Critérios de aceite (implementação futura)

1. Fontes aparecem em card próprio; nenhum catálogo não selecionado surge automaticamente.
2. Contagem é derivada de consulta real e identifica efeito de limite/ordenação.
3. Na playlist normal aparecem faixas escolhidas; na inteligente aparecem apenas correspondências ao avaliador.
4. Salvar não gera duas playlists em toque repetido, e preserva rascunho em falha.
5. A prévia mostra sinalização quando a fonte está parcialmente disponível.

## Restrições transversais

- Toda ação de navegação preserva o rascunho até descarte explicitamente confirmado.
- Coluna rolável e CTA com safe area e IME; tamanho de fonte ampliado não pode ocultar ação importante; alvos de toque preferencialmente `>=48dp`.
- `MaterialTheme` e tokens de [DESIGN.md](../../../../DESIGN.md); usar vermelho dos mockups como proposta de estado/CTA local, não redefinir a paleta global.
- Registrar estados de carregamento, falha e ausência com texto acessível, nunca dados artificiais.
- Não supor infraestrutura de regras/fonte/playlist implementada só por constar da tela.

## Pendências e decisões

Ver [tensions.md](tensions.md).
