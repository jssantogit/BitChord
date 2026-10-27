# Issue #187 — Configurações Android: matriz de rastreabilidade

Escopo: somente Android. Referência de origem: `main` no commit `c02fb87ab69b96ad0e7c1d0081b276f9efee448e`, antes desta PR. A tela antiga era uma lista única em `SettingsSheet.kt`. A alteração é de organização/navegação; estados e setters continuam nos proprietários existentes.

| Grupo da tela antiga | Destino na nova raiz | Regra de preservação |
| --- | --- | --- |
| Conta / Ouvir junto (grupo sem título) | Conta e integrações; atalho Ouvir junto | Mantidos callbacks de autenticação, scrobbling e sessão |
| Qualidade de áudio | Fontes; Qualidade de áudio | Entrada Fontes separada dos limites Wi-Fi/dados móveis e Dolby; preferência por versão somente música realocada de Reprodução |
| Downloads | Downloads | Soulseek, qualidade, Wi-Fi, exportação e pasta |
| Reprodução | Reprodução | Modos de áudio/PCM/DAC, normalização, crossfade, Automix, silêncio, espacial e EQ; alinhamento de áudio inteligente realocado de Avançado |
| Aparência | Aparência; Letras | Letras sincronizadas, fontes e tradução passam à categoria própria; o seletor de ícone do aplicativo migra de Avançado para Aparência |
| Desempenho | Desempenho | Preferências e confirmação de alto desempenho |
| Música local | Música local | Pastas e filtro |
| Armazenamento | Armazenamento | Cache, limpeza e opções |
| Wear (condicional, sem título) | Wear (sob Sistema) | Continua oculto quando indisponível |
| Seus dados | Seus dados | Replay, backup e restauração |
| Diversos | Controles e comportamento (Música) | Gestos, indicadores, histórico de fila e encerramento; destino no grupo Música, não Sistema |
| Idioma | Idioma (Experiência) | Seletor de idioma existente; destino no grupo Experiência |
| Avançado | Avançado | Diagnósticos e estatísticas técnicas preservados; alinhamento de áudio movido para Reprodução e ícone do app para Aparência |
| Rodapé de versão/links | Sobre | Conteúdo preservado sem repetir no hub |

O hub agrupa **Dados e Downloads** em lugar do título genérico "Dados". A navegação exibe ícones com `MaterialTheme.colorScheme.onSurface` para manter contraste no modo escuro. Páginas de detalhe não repetem o nome da categoria em um cabeçalho de grupo idêntico ao título grande; a busca global conserva esses cabeçalhos para identificar grupos de resultados. A UI continua reutilizando os mesmos controles e callbacks.

Os destinos são `SettingsSection`, agrupados em `SettingsNavigation.groups` e exibidos em `SettingsHome`. O host Android mantém `settingsSection` via `rememberSaveable`; o botão superior e o Back do sistema fecham primeiro o destino, depois a raiz. A rolagem da raiz é independente da de detalhe.

## Busca

`SettingsSearch` continua selecionando as mesmas linhas por seus nomes, subtítulos e aliases. Na raiz, uma pesquisa mostra **resultados de navegação**, não switches que possam ser ativados acidentalmente. Tocar em um resultado abre a categoria e conserva a consulta como filtro contextual, exibindo o item sem exigir rolagem. `Ver tudo` limpa o filtro na categoria. Itens que o app já oculta condicionalmente continuam condicionais.

## Comparações estáticas e riscos

- A transformação de hierarquia preservou a contagem de chamadas de `row(`, `SettingsRow(`, `SettingsSubRow(`, `SliderRow(` e `AppSettings.set/AppSettings::set` do arquivo de origem; a divisão de grupos não removeu preferências.
- Os estados de negócio e seleção permanecem em `AppSettings`; não foram alterados serviços de reprodução ou aquisição.
- Ainda é necessário smoke no Galaxy A54 (claro/escuro, teclado e busca, voltar/gesto, sheets e pickers, fonte ampliada, idiomas e TalkBack).
- **Conflitos ativos:** PRs #181 e #183 também editam `SettingsSheet.kt` e `MainActivity.kt`; #182 altera `MainActivity.kt`. A PR #187 de UI deve ser reconciliada com essas frentes antes do merge e não pode substituir silenciosamente seus recursos.
- Esta matriz é prova de **retenção estrutural no código**, não é afirmação de aceite visual/funcional no dispositivo.
