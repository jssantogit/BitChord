# Especificação de tela — Editar grupo de regras

> Etapa: **Subfluxo de grupo** · Status: **direção visual aprovada; implementação pendente** · Plataforma: **Android/Compose**.  
> Mockup de referência discutido em 28/10/2026; não confundir pixels de render com métricas medidas.  
> [Índice](../README.md) · [Tensões da tela](tensions.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md) · [Design do app](../../../../DESIGN.md)

## Objetivo

Editar um grupo autocontido de regras de forma incremental e compreensível.

## Correspondência com o mockup

Campo Nome do grupo, Todas/Qualquer condição, lista de condições com ícones e drag handles, botão Adicionar condição e CTA Salvar grupo.

## Anatomia da tela

### Básico

Nome de grupo editável e seletor Todas as condições (AND) / Qualquer condição (OR), seguido de ajuda contextual.

### Condições

Linhas com nome, operador/valor formatados, ícone neutro, abrir edição, opção remover e handle de reorganização.

### Adicionar condição

Ação integrada ao card conduz a 05 → 06 → 07 ou editores equivalentes; retorna à lista com condição configurada.

### Rodapé

Salvar grupo persiste no rascunho da playlist; não salvar playlist global neste momento.

## Interações e navegação

- Tocar na linha abre formulário da condição com valores existentes; retorno sem salvar não a altera.
- Alterar Todas/Qualquer atualiza leitura em linguagem natural e futura prévia.
- A ordem visual pode ser modificada sem alterar lógica booleana de AND/OR.
- Excluir condição atualiza contagem; condições repetidas incompatíveis recebem aviso.

## Estados e tratamento de erros

- Sem condições: explicação e ação destacada; Salvar grupo indisponível.
- Condição inválida ou fonte removida: alerta inline e bloqueio do avanço até corrigir.
- Texto longo/idioma: resumos com quebra sem truncar significado necessário.
- Rascunho não salvo: voltar oferece manter/descartar conforme política global.

## Critérios de aceite (implementação futura)

1. O grupo salva no rascunho com ID, nome, operador e condições tipadas.
2. Editar uma regra não apaga outras nem altera ordenação.
3. A descrição da operação AND/OR acompanha a seleção.
4. TalkBack anuncia ações de editar, remover, reordenar e estado inválido.

## Restrições transversais

- Toda ação de navegação preserva o rascunho até descarte explicitamente confirmado.
- Coluna rolável e CTA com safe area e IME; tamanho de fonte ampliado não pode ocultar ação importante; alvos de toque preferencialmente `>=48dp`.
- `MaterialTheme` e tokens de [DESIGN.md](../../../../DESIGN.md); usar vermelho dos mockups como proposta de estado/CTA local, não redefinir a paleta global.
- Registrar estados de carregamento, falha e ausência com texto acessível, nunca dados artificiais.
- Não supor infraestrutura de regras/fonte/playlist implementada só por constar da tela.

## Pendências e decisões

Ver [tensions.md](tensions.md).
