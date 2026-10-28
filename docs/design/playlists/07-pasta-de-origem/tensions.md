# Tensões de design — Pasta de origem

> **Registro de decisões e hipóteses**, não uma segunda especificação nem checklist de bugs.  
> [Spec desta tela](spec.md) · [Índice](../README.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md)

| Situação | Tensão | Encaminhamento | Verificação |
| --- | --- | --- | --- |
| **Decidido** | Campo livre × seletor autorizado | Priorizar navegador Android/da fonte; texto do caminho é apresentação, não token de acesso. | Requisito de privacidade/SAF. |
| **Aberto** | SAF URI × MediaStore/index local | Projetar persistência segura e matcher por fonte; evitar acesso a caminhos absolutos não autorizados. | Investigação no motor local. |
| **Aberto** | Subpastas × semântica remota | Suportar apenas onde existe hierarquia consultável e normalizada, caso contrário desabilitar. | Capability por provider. |
| **Decidido** | Estado offline × inclusão indevida | Dados sem evidência de caminho não devem ser tratados como correspondentes à pasta. | Teste de desconhecidos. |
| **Aberto** | Permissões revogadas × regras salvas | Estados de recuperação devem ser explicitados após reinstalação/sincronização. | Fluxo de reautorização. |

## Regra de interpretação

- **Decidido:** escolha de UX consolidada nas conversas de revisão; não equivale a código implementado.
- **Aberto:** risco ou decisão de produto/arquitetura pendente. Não preencher com suposições ou atribuir suporte a provedor sem verificar.
- Prioridade: funcionamento correto e dados autênticos antes de polimento; interface Android deve adaptar-se ao motor verificado ou o motor ser ampliado conscientemente, **sem falsificar preview**.
