# Tensões de design — Biblioteca e pastas

> **Registro de decisões e hipóteses**, não uma segunda especificação nem checklist de bugs.  
> [Spec desta tela](spec.md) · [Índice](../README.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md)

| Situação | Tensão | Encaminhamento | Verificação |
| --- | --- | --- | --- |
| **Decidido** | Hierarquia × mais um passo | É um passo necessário porque o conjunto de condições é amplo; busca global permite atalho. | Padrão Samsung, aparência BitChord. |
| **Decidido** | Fonte × diretório × caminho | Regras distintas para procedência, pasta e substring/caminho de arquivo. | Contratos de tipos independentes. |
| **Aberto** | Offline local × disponibilidade remota | 'Disponível offline' deve consultar cache comprovado, não pressupor downloads. | Dependência de biblioteca/downloads. |
| **Aberto** | Data de adição em categorias cruzadas × duplicação | Usar alias visual apontando para a definição canônica da regra. | Registry de regras. |
| **Decidido** | UI mostra regras × motor não implementado | Não ativar linhas sem predicado ou editor/serialização completos. | Verificador de capacidades. |

## Regra de interpretação

- **Decidido:** escolha de UX consolidada nas conversas de revisão; não equivale a código implementado.
- **Aberto:** risco ou decisão de produto/arquitetura pendente. Não preencher com suposições ou atribuir suporte a provedor sem verificar.
- Prioridade: funcionamento correto e dados autênticos antes de polimento; interface Android deve adaptar-se ao motor verificado ou o motor ser ampliado conscientemente, **sem falsificar preview**.
