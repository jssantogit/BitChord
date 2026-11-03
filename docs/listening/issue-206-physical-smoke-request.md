# Solicitação explícita de APK físico — Playlists e Biblioteca PR #206

- Data: 2026-11-03.
- Autorização do proprietário: **“Ok dispare apk”**, para smoke físico das últimas correções de Playlists #206.
- Código-base a verificar no pacote: PR #206, SHA **f52b0de8cbbce5747189d50e645ece06dacab19d** (inclui correção do teste antigo que exigia texto de carregamento).
- Branch efêmera exclusivamente de empacotamento: `smoke/206-loading-drag-20261103`. **Não mesclar** esta branch; suas alterações extras se restringem ao workflow de smoke e a este pedido.
- Escopo do smoke no Galaxy A54: (1) spinners em telas de carregamento; (2) segurar e arrastar para selecionar faixas/álbuns/artistas nas listas/grades da Biblioteca; (3) gestos não devem atuar em detalhes ou cabeçalhos; (4) revisão de alinhamentos, cores, ações ⋮ e fluxo modal nativo de playlists; (5) preservar MediaStore+Navidrome unificados e IDs canônicos.
- CI Android final da PR #206 deve passar antes de qualquer merge na main. O workflow de APK executa `:app:testDevDebugUnitTest` como gate antes de `:app:assembleDevDebug` e só publica artefato em caso de sucesso.
- O workflow de CI anterior #41654338625 falhou 1/2795 testes por verificar a string “Loading saved playlists…”; corrigido em `f52b0de8`. Não tratar uma tentativa vermelha anterior como um APK aprovado.
- Nenhuma alteração em `main` ou nas PRs dependentes #193/#205 é autorizada por esta solicitação.

- Reenvio técnico: o primeiro workflow foi rejeitado por uma expressão YAML não citada contendo o caractere de comentário #. A correção é restrita à branch de smoke. A intenção e o código-base do owner permanecem os mesmos.
