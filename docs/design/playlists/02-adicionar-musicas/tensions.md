# Tensões de design — Adicionar músicas

> **Registro de decisões e hipóteses**, não uma segunda especificação nem checklist de bugs.  
> [Spec desta tela](spec.md) · [Índice](../README.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md)

| Situação | Tensão | Encaminhamento | Verificação |
| --- | --- | --- | --- |
| **Decidido** | Seleção rápida × toque acidental | Checkbox e área de ação claramente separadas de abrir detalhes. | Teste de toque/semântica. |
| **Aberto** | Faixas de álbum × seleção por obra inteira | Definir UX de seleção em lote com contagem e aviso de indisponíveis. | Depende do browse em catálogo. |
| **Decidido** | Filtros voláteis × seleção persistente | Seleção em rascunho, não em estado local do filtro. | Teste regressivo de troca de chips. |
| **Aberto** | Duplicatas entre fontes × versões diferentes | Definir chave canônica e política sobre versões/edições; não deduplicar por título apenas. | Alinhar ao catálogo. |
| **Decidido** | Muitos itens × clareza do rodapé | Bandeja colapsável e botão fora da área rolável, com respeito ao IME/safe area. | Teste Galaxy A54 e acessibilidade. |

## Regra de interpretação

- **Decidido:** escolha de UX consolidada nas conversas de revisão; não equivale a código implementado.
- **Aberto:** risco ou decisão de produto/arquitetura pendente. Não preencher com suposições ou atribuir suporte a provedor sem verificar.
- Prioridade: funcionamento correto e dados autênticos antes de polimento; interface Android deve adaptar-se ao motor verificado ou o motor ser ampliado conscientemente, **sem falsificar preview**.
