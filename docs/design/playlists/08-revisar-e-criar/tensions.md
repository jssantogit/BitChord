# Tensões de design — Revisar e criar

> **Registro de decisões e hipóteses**, não uma segunda especificação nem checklist de bugs.  
> [Spec desta tela](spec.md) · [Índice](../README.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md)

| Situação | Tensão | Encaminhamento | Verificação |
| --- | --- | --- | --- |
| **Decidido** | Resumo compacto × auditabilidade | Fontes em card próprio; regras expansíveis; prévia abaixo, sem esconder a procedência. | Mudança pedida após mockup inicial. |
| **Decidido** | Prévia atraente × confiabilidade | Nunca inventar capas, contagens ou faixas; '100 encontradas' é somente valor de ilustração. | Consulta read-only. |
| **Aberto** | Zero resultados × permitir criar | Definir se playlist inteligente vazia é válida ou exige confirmação extra. | Política de produto. |
| **Aberto** | Atualização automática × disponibilidade eventual | Explicar gatilhos reais e limites da sincronização; nunca prometer atualização instantânea sem executor. | Definição de scheduler. |
| **Decidido** | Edição tardia × perda de formulário | Todo retorno modifica o mesmo rascunho; estado de prévia invalidado quando critério muda. | Teste de navegação. |

## Regra de interpretação

- **Decidido:** escolha de UX consolidada nas conversas de revisão; não equivale a código implementado.
- **Aberto:** risco ou decisão de produto/arquitetura pendente. Não preencher com suposições ou atribuir suporte a provedor sem verificar.
- Prioridade: funcionamento correto e dados autênticos antes de polimento; interface Android deve adaptar-se ao motor verificado ou o motor ser ampliado conscientemente, **sem falsificar preview**.
