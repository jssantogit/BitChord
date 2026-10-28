# Tensões de design — Adicionar condição

> **Registro de decisões e hipóteses**, não uma segunda especificação nem checklist de bugs.  
> [Spec desta tela](spec.md) · [Índice](../README.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md)

| Situação | Tensão | Encaminhamento | Verificação |
| --- | --- | --- | --- |
| **Decidido** | Dezenas de filtros × sobrecarga cognitiva | Organizar por categoria e usar busca global com sinônimos. | Visual inspirado no Modos e Rotinas Samsung. |
| **Aberto** | Cores por categoria × design Apple Music/BitChord | Ícones coloridos apenas como orientação local; base tipográfica/superfícies seguem DESIGN.md. | Não declarar nova paleta global. |
| **Decidido** | Capacidade futura × opções indisponíveis | Catálogo deriva de registry de regras suportadas por fonte; mockup não é inventário funcional atual. | Teste de capabilities. |
| **Aberto** | Categorias amplas × descoberta avançada | Sinônimos e possibilidades de categoria secundária sem duplicar identificador. | Testar cenários de busca reais. |
| **Decidido** | Busca global × navegação por categoria | Ambas chegam ao mesmo editor tipado sem exigir passos desnecessários. | Teste de rotas. |

## Regra de interpretação

- **Decidido:** escolha de UX consolidada nas conversas de revisão; não equivale a código implementado.
- **Aberto:** risco ou decisão de produto/arquitetura pendente. Não preencher com suposições ou atribuir suporte a provedor sem verificar.
- Prioridade: funcionamento correto e dados autênticos antes de polimento; interface Android deve adaptar-se ao motor verificado ou o motor ser ampliado conscientemente, **sem falsificar preview**.
