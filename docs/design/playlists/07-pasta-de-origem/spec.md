# Especificação de tela — Pasta de origem

> Etapa: **Editor tipado da condição** · Status: **direção visual aprovada; implementação pendente** · Plataforma: **Android/Compose**.  
> Mockup de referência discutido em 28/10/2026; não confundir pixels de render com métricas medidas.  
> [Índice](../README.md) · [Tensões da tela](tensions.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md) · [Design do app](../../../../DESIGN.md)

## Objetivo

Configurar uma condição de pasta válida, autorizada e entendível sem pedir caminhos arbitrários ao usuário.

## Correspondência com o mockup

Cabeçalho com ícone de pasta, cartão Operador, Fonte das músicas, Pasta selecionada, Incluir subpastas, Resumo da condição e CTA Adicionar condição.

## Anatomia da tela

### Cabeçalho

Voltar à categoria 06; ícone de pasta, título 'Pasta de origem' e rótulo Biblioteca e pastas.

### Operador

Exemplo: Está dentro de / Não está dentro de; semântica precisa incluir ou excluir subpastas conforme opção.

### Fonte das músicas

Escolher fonte que exponha diretórios de modo verificável; por padrão local no mockup. Não inferir path real de Navidrome/WebDAV/SMB sem browse próprio.

### Pasta selecionada

Card com rótulo humanamente legível (ex. Music/K-pop/ILLIT), local da fonte, estado de permissão e botão Escolher outra pasta.

### Opções

Toggle Incluir subpastas, com texto explicativo.

### Resumo

Frase produzida a partir de dados efetivos, sem concatenação ambígua nem exposição de URI de permissão.

### Rodapé

Adicionar condição valida e salva no grupo rascunho, retornando a 04.

## Interações e navegação

- Escolher outra pasta abre navegador de diretórios compatível com a fonte; cancelar preserva seleção.
- Trocar de fonte invalida o diretório selecionado se necessário, mas pede escolha antes de descartar.
- Toggle de subpastas atualiza resumo sem recalcular toda playlist na digitação.
- O operador excludente deve declarar tratamento de músicas com pasta desconhecida; nunca incluir dados inacessíveis por conveniência.

## Estados e tratamento de erros

- Sem pasta: CTA desabilitado e convite de selecionar.
- Permissão revogada: solicitar reautorização, não falhar silenciosamente.
- Pasta excluída/renomeada: marcar referência inválida e reescolher.
- Fonte sem árvore de pastas: explicar indisponibilidade e retorno à seleção.
- Pasta com itens não indexados: explicar que preview abrange somente músicas indexadas/autorizadas.

## Critérios de aceite (implementação futura)

1. Selecionar pasta persiste identificador/URI autorizado e rótulo separado; não usa string de caminho como identidade universal.
2. Sem permissão válida não é possível salvar regra como se funcionasse.
3. A opção incluir subpastas altera o predicado e o resumo de forma coerente.
4. Voltar da seleção cancelada preserva operador/fonte/pasta escolhidos.
5. Os testes cobrem UTF-8, acentos, separadores de caminho e pastas com nomes duplicados.

## Restrições transversais

- Toda ação de navegação preserva o rascunho até descarte explicitamente confirmado.
- Coluna rolável e CTA com safe area e IME; tamanho de fonte ampliado não pode ocultar ação importante; alvos de toque preferencialmente `>=48dp`.
- `MaterialTheme` e tokens de [DESIGN.md](../../../../DESIGN.md); usar vermelho dos mockups como proposta de estado/CTA local, não redefinir a paleta global.
- Registrar estados de carregamento, falha e ausência com texto acessível, nunca dados artificiais.
- Não supor infraestrutura de regras/fonte/playlist implementada só por constar da tela.

## Pendências e decisões

Ver [tensions.md](tensions.md).
