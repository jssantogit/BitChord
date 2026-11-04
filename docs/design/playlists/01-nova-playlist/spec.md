# Especificação de tela — Nova playlist

> Etapa: **Entrada comum** · Status: **direção visual aprovada; implementação pendente** · Plataforma: **Android/Compose**.  
> Mockup de referência discutido em 28/10/2026; não confundir pixels de render com métricas medidas.  
> [Índice](../README.md) · [Tensões da tela](tensions.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md) · [Design do app](../../../../DESIGN.md)

## Objetivo

Definir a identidade e o tipo da playlist sem expor filtros prematuramente.

## Correspondência com o mockup

Capa central, campos Nome/Descrição, card de Fonte das músicas, seletor Normal/Inteligente e CTA Continuar.

## Anatomia da tela

### Cabeçalho

Voltar; título grande 'Nova playlist'; instrução de uma frase; conteúdo em coluna rolável, sem tab bar ou mini-player visual dentro do fluxo.

### Capa

Artwork quadrado central, cantos arredondados, ação sobreposta de trocar capa; origem manual ou placeholder/mosaico automático, sem presumir acesso a imagens remotas.

### Básico

Nome obrigatório, descrição opcional com contador e limite de caracteres configurável conforme contrato de dados; teclado apropriado e mensagens inline.

### Fonte das músicas

Card próprio para escolher uma ou várias fontes realmente disponíveis; exemplo de mockup 'Catálogo principal · Navidrome' é ilustrativo. Informar se a fonte será pesquisada, não confundir com destino de salvamento.

### Tipo de playlist

Seleção exclusiva Normal / Inteligente; diferença explícita: escolha manual versus seleção dinâmica por regras.

### Rodapé

Botão primário 'Continuar' ancorado à safe area; habilitado somente após validação do mínimo necessário.

## Interações e navegação

- Tocar na capa abre seletor compatível com Android; cancelar não apaga artwork atual.
- Alterar fonte preserva texto e imagem; ao retirar fonte já usada por itens/regras, sinalizar impactos e exigir revisão antes de avançar.
- Selecionar Normal navega para 02; Inteligente navega para 03. Voltar mantém rascunho.
- Fechar o fluxo com alterações não salvas abre escolha: continuar editando ou descartar; não criar playlist parcialmente.

## Estados e tratamento de erros

- Vazio: arte sugerida + nome vazio + tipo a escolher (ou default explícito).
- Carregando fontes: desabilitar somente seleção de fontes, sem bloquear digitação.
- Fonte indisponível/sem permissão: explicação e ação de reconectar; não prometer acesso.
- Erro de nome/tamanho: feedback próximo ao campo e foco no erro.
- Rotação/teclado/tamanho de fonte: conteúdo rolável e rodapé acima do IME.

## Critérios de aceite (implementação futura)

1. É impossível avançar com nome em branco, tipo desconhecido ou fonte não autorizada.
2. Normal e Inteligente usam exatamente o mesmo rascunho de identidade e preservam entrada ao alternar.
3. TalkBack anuncia seleção, função do botão de capa e erros de formulário.
4. Voltar ao passo inicial não perde nome/descrição/capa; reabrir sem descarte restaura rascunho conforme política.

## Restrições transversais

- Toda ação de navegação preserva o rascunho até descarte explicitamente confirmado.
- Coluna rolável e CTA com safe area e IME; tamanho de fonte ampliado não pode ocultar ação importante; alvos de toque preferencialmente `>=48dp`.
- `MaterialTheme` e tokens de [DESIGN.md](../../../../DESIGN.md); usar vermelho dos mockups como proposta de estado/CTA local, não redefinir a paleta global.
- Registrar estados de carregamento, falha e ausência com texto acessível, nunca dados artificiais.
- Não supor infraestrutura de regras/fonte/playlist implementada só por constar da tela.

## Pendências e decisões

Ver [tensions.md](tensions.md).
