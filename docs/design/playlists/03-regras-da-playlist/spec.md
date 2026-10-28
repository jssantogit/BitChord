# Especificação de tela — Regras da playlist

> Etapa: **Ramo Inteligente** · Status: **direção visual aprovada; implementação pendente** · Plataforma: **Android/Compose**.  
> Mockup de referência discutido em 28/10/2026; não confundir pixels de render com métricas medidas.  
> [Índice](../README.md) · [Tensões da tela](tensions.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md) · [Design do app](../../../../DESIGN.md)

## Objetivo

Permitir compor uma playlist inteligente por blocos lógicos legíveis, com limite e ordenação separados das regras.

## Correspondência com o mockup

Título grande, lista de grupos numerados e resumos, Adicionar grupo, seletor Qualquer/Todos grupos, Limite e Ordenação, CTA Continuar.

## Anatomia da tela

### Grupos de regras

Seção com ação + Adicionar grupo; cards numerados exibem título, quantidade, lógica interna, 2–3 exemplos e menu editar/duplicar/excluir.

### Combinação entre grupos

Segmentado Qualquer grupo (OR) / Todos os grupos (AND), acompanhado de explicação em linguagem natural.

### Limite e ordenação

Controles distintos do filtro: limite opcional e ordem de resultado (ex. popularidade); preferências devem suportar preview e resultado persistido de modo idêntico.

### Rodapé

Continuar leva à revisão 08 após avaliação/preview; não gerar coleção permanente neste passo.

## Interações e navegação

- Adicionar grupo abre 04 com grupo vazio; editar retorna sem perder os demais.
- Duplicar gera novo ID estável; reordenar muda apenas ordem de apresentação, não semântica booleana.
- Remover grupo confirma quando necessário e recalcula prévia; evitar remover o último silenciosamente.
- Alterar AND/OR atualiza explicação, marca prévia como obsoleta e recalcula quando solicitado/seguro.

## Estados e tratamento de erros

- Sem grupos: texto de orientação e CTA para o primeiro grupo, sem Continuar ativo.
- Grupo inválido/incompleto: card com status e ação Editar.
- Preview em processamento: estado claro; evitar contagem fictícia.
- Limite fora da faixa: validação e ajuste antes de continuar.
- Mudança de fontes: sinalizar quais regras ficaram incompatíveis.

## Critérios de aceite (implementação futura)

1. A operação entre grupos é explícita; a aplicação respeita a mesma lógica na prévia e na playlist salva.
2. Não confundir limite com número total de candidatos encontrados.
3. Grupos podem ser editados, duplicados, excluídos sem trocar IDs de outros grupos.
4. A UI preserva formulário em rolagem e retorno do editor.

## Restrições transversais

- Toda ação de navegação preserva o rascunho até descarte explicitamente confirmado.
- Coluna rolável e CTA com safe area e IME; tamanho de fonte ampliado não pode ocultar ação importante; alvos de toque preferencialmente `>=48dp`.
- `MaterialTheme` e tokens de [DESIGN.md](../../../../DESIGN.md); usar vermelho dos mockups como proposta de estado/CTA local, não redefinir a paleta global.
- Registrar estados de carregamento, falha e ausência com texto acessível, nunca dados artificiais.
- Não supor infraestrutura de regras/fonte/playlist implementada só por constar da tela.

## Pendências e decisões

Ver [tensions.md](tensions.md).
