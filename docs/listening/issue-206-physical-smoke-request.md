# Autorização explícita de APK físico — PR #206 · Biblioteca unificada

Data: 2026-11-03 (America/Bahia).
Owner: solicitou "Ok, dispare apk" nesta conversa após confirmar CI Android verde e auditoria de reutilização de componentes nativos.

## Origem exata do código Android
- PR: https://github.com/jssantogit/BitChord/pull/206 (draft, base #205 / #193).
- HEAD de **código**: `7f9badc663be21db9d143a27dbf44fb350117f89`.
- Android CI no mesmo SHA: https://github.com/jssantogit/BitChord/actions/runs/41602846291 — COMPLETED / SUCCESS (Android unit tests + shared Automix).
- Branch exclusivamente de smoke: `smoke/206-native-visual-20261103`. Desde esse HEAD, somente este documento e a regra de acionamento isolada em `.github/workflows/physical-smoke-apk.yml` podem diferir. Sem merge em main/#193/#205/#206. O ZIP de artifact não equivale a aprovação em dispositivo.

## Plano de teste físico — Galaxy A54
1. Biblioteca unificada permanece uma única navegação: Músicas, Álbuns e Artistas no catálogo MediaStore + Navidrome. Conferir fonte, filtros, tipografia, glass, dimensões/alinhamentos e botão voltar. Verificar 594 faixas e 29h20m locais, quando a biblioteca do dispositivo mantém esse conteúdo.
2. Listas e grades de Músicas/Álbuns/Artistas usam os **mesmos** cards/linhas nativos; checar overflow ⋮ inclusive de música em grade, capa correta e nenhuma tela de Biblioteca paralela.
3. Detalhes AtHeart, aespa, ablume: hero com foto e degradê, controles circulares, Faixas em colunas, Álbuns separado de Singles/EPs, sem filtros indevidos no cabeçalho e sem regressão de lançamentos ou colaborações.
4. Menu contextual: tocar a seguir, fila, Adicionar à playlist, seleção e avaliação. Sem `Song.videoId` fictício, source fallback silencioso ou ações vazias.
5. Seletor de playlist normal: usa chrome nativo do wizard, pesquisa, capa compartilhada, seleção de playlist, Nova playlist e confirmação, evitando inclusão manual em playlists inteligentes. Testar música, álbum e artista expandindo gravações distintas reais e preservando ordem.
6. Seleção múltipla: indicadores por tipo, contagem por gravações reais, fila/seguir/playlist/avaliação em lote, painel acima do miniplayer/teclado, abrir/fechar, scroll e troca de filtros/fonte sem perda silenciosa do rascunho.
7. Avaliação de artista/álbum usando editor nativo de 0,5 em 0,5 estrelas, permanência da nota após reabrir e nenhuma modificação indevida nas avaliações das faixas.
8. Adições recentes → Ver todos: pesquisa e filtros no mesmo toolbar/menu fosco da Biblioteca, ordenação por timestamps locais reais, lista/grade, cached results sem reindexar toda navegação. Não supor datas de inclusão Navidrome.
9. Regressões: Logs internos, contagem/duração, alinhamento do ⋮, ausência de tela Material3 legada.

## Gate de publicação
Usar `Android Physical Smoke APK`: `:app:testDevDebugUnitTest`, `:app:assembleDevDebug`, artifact `bitchord-physical-smoke-dev-debug-${GITHUB_SHA}` com retenção de 3 dias. Ao falhar o guard de #202 ou o guard do HEAD CI #206, **não distribuir o APK**. Após build, confirmar workflow SUCCESS e artifact efetivamente presente antes de indicar que está pronto para instalar.

Este documento é o **gatilho de push estritamente autorizado**, não um novo requisito funcional nem mudança de interface.
