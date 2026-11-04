# Tensões de design — Nova playlist

> **Registro de decisões e hipóteses**, não uma segunda especificação nem checklist de bugs.  
> [Spec desta tela](spec.md) · [Índice](../README.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md)

| Situação | Tensão | Encaminhamento | Verificação |
| --- | --- | --- | --- |
| **Decidido** | Formulário simples × catálogo heterogêneo | Mostrar somente identidade, fontes e tipo; esconder capacidades avançadas até o ramo escolhido. | Visual compatível com mockup, motor não exposto. |
| **Aberto** | Capa manual × mosaico gerado | Oferecer estado sem capa e opção de imagem; especificar pipeline de mosaico, persistência e direitos de uso antes de implementar. | Definir com implementação de artwork. |
| **Aberto** | Uma fonte × múltiplas fontes | Card único como entrada; UI pode suportar múltiplas fontes, mas apenas se o motor der identidade/deduplicação e cobertura confiáveis. | Validar com motor de catálogo. |
| **Decidido** | Fonte consultada × destino da playlist | Não usar o mesmo seletor para fonte musical e local de armazenamento/publicação. | Campos semânticos separados. |
| **Aberto** | Limites de descrição e permissões | Mockup exibe contador, mas o limite depende do destino; validar antes de fixar 300 como contrato. | Confirmar backend de playlists. |

## Regra de interpretação

- **Decidido:** escolha de UX consolidada nas conversas de revisão; não equivale a código implementado.
- **Aberto:** risco ou decisão de produto/arquitetura pendente. Não preencher com suposições ou atribuir suporte a provedor sem verificar.
- Prioridade: funcionamento correto e dados autênticos antes de polimento; interface Android deve adaptar-se ao motor verificado ou o motor ser ampliado conscientemente, **sem falsificar preview**.
