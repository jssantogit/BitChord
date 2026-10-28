# Tensões de design — Editar grupo de regras

> **Registro de decisões e hipóteses**, não uma segunda especificação nem checklist de bugs.  
> [Spec desta tela](spec.md) · [Índice](../README.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md)

| Situação | Tensão | Encaminhamento | Verificação |
| --- | --- | --- | --- |
| **Decidido** | Detalhe da condição × densidade da lista | Linha compacta mostra somente resumo, editor específico em página separada. | Evitar editor inline gigante. |
| **Decidido** | Poder lógico × jargão booleano | 'Todas as condições'/'Qualquer condição', explicação imediata. | Inspeção com leitor de tela. |
| **Aberto** | Grupos sem condições × prévia | Bloquear gravação de grupo vazio ou tratá-lo explicitamente, sem semântica vacuamente verdadeira oculta. | Exigir decisão no motor. |
| **Aberto** | Reordenação × acesso por teclado/TalkBack | Oferecer alternativa 'Mover acima/abaixo' ao drag handle. | A11y no Compose. |
| **Decidido** | Salvar grupo × criar playlist | Salvar grupo retorna ao passo 03; playlist ainda não é persistida. | Teste navegação de subfluxo. |

## Regra de interpretação

- **Decidido:** escolha de UX consolidada nas conversas de revisão; não equivale a código implementado.
- **Aberto:** risco ou decisão de produto/arquitetura pendente. Não preencher com suposições ou atribuir suporte a provedor sem verificar.
- Prioridade: funcionamento correto e dados autênticos antes de polimento; interface Android deve adaptar-se ao motor verificado ou o motor ser ampliado conscientemente, **sem falsificar preview**.
