# Especificação de tela — Adicionar músicas

> Etapa: **Ramo Normal** · Status: **direção visual aprovada; implementação pendente** · Plataforma: **Android/Compose**.  
> Mockup de referência discutido em 28/10/2026; não confundir pixels de render com métricas medidas.  
> [Índice](../README.md) · [Tensões da tela](tensions.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md) · [Design do app](../../../../DESIGN.md)

## Objetivo

Selecionar explicitamente músicas para uma playlist comum, com seleção em lote previsível e independente da fonte.

## Correspondência com o mockup

Busca e chips Todos/Músicas/Álbuns/Artistas, linhas com capa e checkbox, bandeja '5 músicas selecionadas', CTA Continuar.

## Anatomia da tela

### Pesquisa

Campo de busca persistente com sugestões e mensagens de vazio; pesquisa limitada às fontes escolhidas no passo 01.

### Navegação por tipo

Chips horizontais Todos, Músicas, Álbuns e Artistas; selecionar álbum ou artista leva a resultados por faixas, não representa automaticamente um único item musical.

### Resultados

Linhas compactas com checkbox, capa, título, artista, metadado de origem se necessário, overflow opcional para detalhes; usar identidade estável da faixa.

### Seleção

Bandeja recolhível com miniaturas e contagem real, que permanece acessível após pesquisa/filtros; possibilidade de remover músicas selecionadas.

### Rodapé

CTA Continuar; etapa seguinte é a revisão comum (08).

## Interações e navegação

- Tocar no checkbox seleciona/desseleciona; tocar no título pode abrir detalhes sem mudar seleção por acidente.
- Selecionar álbum/artista em lote oferece confirmação do conjunto e contagem; não confundir álbum com faixa.
- Seleção permanece ao trocar filtros, pesquisar, voltar ou navegar entre fontes.
- Ordenação manual pode ser feita nesta tela via bandeja expandida ou na revisão; a ordem escolhida persiste.

## Estados e tratamento de erros

- Zero itens encontrados: mensagem clara e opção de limpar filtros.
- Busca parcial ou offline: indicar fontes faltantes e resultados incompletos.
- Música indisponível: bloqueio ou aviso antes do avanço, com escolha explícita.
- Muitas músicas: paginação/virtualização e contagem calculada sem travar a UI.
- Mudança de fonte: seleção incompatível marcada para revisão, nunca excluída silenciosamente.

## Critérios de aceite (implementação futura)

1. Nenhuma música é marcada como selecionada apenas por filtrar/pesquisar.
2. Contagem da bandeja reflete quantidade única de faixas e nunca número de linhas/álbuns.
3. Selecionar e remover músicas funciona após mudanças de filtro e rolagem longa.
4. Ordens e escolhas preservadas quando usuário volta da revisão.

## Restrições transversais

- Toda ação de navegação preserva o rascunho até descarte explicitamente confirmado.
- Coluna rolável e CTA com safe area e IME; tamanho de fonte ampliado não pode ocultar ação importante; alvos de toque preferencialmente `>=48dp`.
- `MaterialTheme` e tokens de [DESIGN.md](../../../../DESIGN.md); usar vermelho dos mockups como proposta de estado/CTA local, não redefinir a paleta global.
- Registrar estados de carregamento, falha e ausência com texto acessível, nunca dados artificiais.
- Não supor infraestrutura de regras/fonte/playlist implementada só por constar da tela.

## Pendências e decisões

Ver [tensions.md](tensions.md).
