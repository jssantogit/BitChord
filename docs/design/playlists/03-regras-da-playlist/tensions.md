# Tensões de design — Regras da playlist

> **Registro de decisões e hipóteses**, não uma segunda especificação nem checklist de bugs.  
> [Spec desta tela](spec.md) · [Índice](../README.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md)

| Situação | Tensão | Encaminhamento | Verificação |
| --- | --- | --- | --- |
| **Decidido** | Poder expressivo × simplicidade | Grupos com resumos colapsáveis e operador entre grupos em seção própria. | Validar contra mockup aprovado. |
| **Decidido** | OR de grupos × AND de condições | Mostrar ambos separadamente e explicar o significado; nenhuma precedência implícita. | Contrato formal em RULES_ENGINE_CONTRACT.md. |
| **Aberto** | Regras dinâmicas × custo de prévia | Debounce/cancelamento e limites de avaliação conforme capacidade e origem, sem número inventado. | Benchmark do motor. |
| **Aberto** | Limite e ordenação × resultados faltantes | Exibir política de ordenação estável, desempate e semântica de 'contagem encontrada'. | Definir no avaliador. |
| **Aberto** | Grupo vazio × progresso do fluxo | Exigir pelo menos uma condição válida para criação inteligente, sujeito a confirmação de UX. | Critério proposto. |

## Regra de interpretação

- **Decidido:** escolha de UX consolidada nas conversas de revisão; não equivale a código implementado.
- **Aberto:** risco ou decisão de produto/arquitetura pendente. Não preencher com suposições ou atribuir suporte a provedor sem verificar.
- Prioridade: funcionamento correto e dados autênticos antes de polimento; interface Android deve adaptar-se ao motor verificado ou o motor ser ampliado conscientemente, **sem falsificar preview**.
