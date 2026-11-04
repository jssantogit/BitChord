# Especificação de tela — Adicionar condição

> Etapa: **Catálogo de categorias** · Status: **direção visual aprovada; implementação pendente** · Plataforma: **Android/Compose**.  
> Mockup de referência discutido em 28/10/2026; não confundir pixels de render com métricas medidas.  
> [Índice](../README.md) · [Tensões da tela](tensions.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md) · [Design do app](../../../../DESIGN.md)

## Objetivo

Descoberta rápida de um universo grande de regras sem expor a configuração de todas na primeira tela.

## Correspondência com o mockup

Tela com busca e cartões individuais de Músicas e metadados, Avaliações e favoritos, Reprodução e histórico, Datas, Biblioteca e pastas, Áudio e qualidade, Outros.

## Anatomia da tela

### Cabeçalho

Voltar; título 'Adicionar condição'; explicação curta; campo de pesquisa no topo rolável/acessível.

### Categorias

Cards grandes por domínio, cada um com ícone, título, exemplo de tipos e chevron; a ordem é estável e não governada por ranking desconhecido.

### Busca global

Pesquisa por nome de condição, categoria, sinônimos e termos técnicos conhecidos (ex. FLAC, nunca reproduzida), sem criar opções inexistentes.

### Disponibilidade

Condições sem cobertura pela fonte selecionada ficam indisponíveis com motivo, ou ocultas por decisão explícita; não permitir selecionar regra que não pode ser executada.

## Interações e navegação

- Tocar em categoria abre lista correspondente (06 como exemplo de Biblioteca e pastas).
- Busca com termo específico pode abrir diretamente a condição configurável (07 ou editor tipado correspondente), preservando breadcrumb.
- Voltar retorna a 04 sem perder edições.
- Limpar pesquisa restaura categorias e posição coerente.

## Estados e tratamento de erros

- Pesquisa vazia: cards de categorias.
- Pesquisa sem resultado: mensagem e opção limpar.
- Permissões/fonte indisponíveis: desabilitar seletivamente e explicar.
- Catálogo longo: rolagem, teclado, scroll restoration e agrupamento.

## Critérios de aceite (implementação futura)

1. O mesmo identificador de regra funciona entrando pela categoria ou pela busca.
2. Pesquisa é tolerante a caixa/acentos e usa aliases sem duplicar regras.
3. Nenhum card leva a editor que salve regra sem suporte.
4. Tela abre sem selecionar automaticamente uma regra.

## Restrições transversais

- Toda ação de navegação preserva o rascunho até descarte explicitamente confirmado.
- Coluna rolável e CTA com safe area e IME; tamanho de fonte ampliado não pode ocultar ação importante; alvos de toque preferencialmente `>=48dp`.
- `MaterialTheme` e tokens de [DESIGN.md](../../../../DESIGN.md); usar vermelho dos mockups como proposta de estado/CTA local, não redefinir a paleta global.
- Registrar estados de carregamento, falha e ausência com texto acessível, nunca dados artificiais.
- Não supor infraestrutura de regras/fonte/playlist implementada só por constar da tela.

## Pendências e decisões

Ver [tensions.md](tensions.md).
