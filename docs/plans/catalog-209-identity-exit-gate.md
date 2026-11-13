# PR #209 — Gate finito para concluir a camada MusicBrainz de identidade

**Decisão de produto (2026-11-13):** Apple/iTunes organiza o catálogo editorial;
MusicBrainz fornece MBIDs e relações de artista, Recording, Track, Release e
ReleaseGroup. Discogs continua complementar. A #209 NÃO tem de aperfeiçoar os
nomes, sleeves, rankings, tipos comerciais e completude da discografia Apple.
Este documento define a conclusão de **#209**, não o cutover ou a conclusão de #214.

**Estado em 13/11/2026: GATE DE IDENTIDADE NA API PÚBLICA APROVADO (20/60/30).**
[Relatório durável com os 20 Artist MBIDs, 60 ReleaseGroup MBIDs e 30 relações](catalog-209-identity-exit-evidence.json) documenta 110 observações públicas PASS, zero contradições de identidade, nos workflows [primeiro lote #43425075506](https://github.com/jssantogit/BitChord/actions/runs/43425075506) e [relações complementares #43426212878](https://github.com/jssantogit/BitChord/actions/runs/43426212878). O primeiro lote tinha nove ERROR e nove INCONCLUSIVE por esgotar o limite HTTP; seus casos de relações foram **substituídos por 30 PASS da verificação complementar**, sem repetir os 80 casos já aprovados. **CI Android/Room no HEAD final e smoke físico seguem gates separados; não declarar o app aprovado somente pela API.**

## Amostra fechada (congelada para esta etapa)

20 artistas identificáveis por **MBID real e individual**, a confirmar na execução,
com variedade de colaborações, idiomas, edições e popularidade:

1. NewJeans
2. LE SSERAFIM
3. IVE
4. ILLIT
5. ENHYPEN
6. aespa
7. BTS
8. TWICE
9. BLACKPINK
10. IU
11. Stray Kids
12. SEVENTEEN
13. ROSÉ
14. Jon Batiste
15. Taylor Swift
16. Billie Eilish
17. Sabrina Carpenter
18. The Weeknd
19. Beyoncé
20. Bad Bunny

Esses nomes são **alvos de amostragem**, não identidades resolvidas nesta
página. Homônimos/aliases precisam ter o MBID correto verificado; nenhuma
busca por texto autoriza escrever relação canônica. Não acrescentar novos
artistas por descoberta incidental: registrar fora do gate no backlog.

### Oráculos mínimos de aceite

| Lote | Amostra fechada | Prova observável | Erro bloqueante |
| --- | ---: | --- | --- |
| A — artistas | 20/20 | MBID do artista correto, alias/homônimo não promovido por nome | associação ou MBID falsos |
| B — lançamentos | 60 casos | 3 lançamentos/edições por artista escolhidos por **IDs reais**; Release→Group e data/tipo somente quando explicitamente estruturados | fusão de grupos, edição com parent errado, tipo inventado |
| C — relações | 30 casos | 10 Artist→Group; 10 Release→Group; 10 Track→Recording/artist-credit; histórico de IDs preservado | atribuição falsa, gravações confundidas, edge destruída |
| D — fronteira Apple | regressões | registro Apple sem MBID continua elegível no catálogo editorial; match só por título/ISRC/UPC não vira MBID verificado; metadata MB não cria MediaAsset | falsa identidade/playback; MB obrigatório para Apple |
| E — estabilidade | Android/Room/unit | build e testes aplicáveis SUCCESS no **mesmo HEAD**; sem crash, loop de atualização, cache/relação destrutiva reproduzíveis | P0/P1 repetível |
| F — aceite físico final | 1 smoke consolidado | executar **somente no fechamento de #209**; documentar navegação e resultado real | crash, navegação incorreta, associação falsa |

**Seleção dos 60 casos:** registrar primeiro um Release Group central por artista,
uma obra/edição independente e uma edição ou lançamento com título/versão
ambíguo. Se um artista não oferecer determinada categoria, selecionar outra
obra real e registrar a substituição; não inventar EP/Single/Album para
preencher cotas. Guardar URL da fonte, MBID, expected result, data, HEAD e
resultado `PASS / FAIL / INCONCLUSIVE`. Casos inconclusivos não contam como
PASS. A bateria pública já disponível de 3 casos da #209 (ILLIT, aespa,
NewJeans) é evidência inicial, **não 60/60 automaticamente**.

## Critérios de aprovação e política de fechamento

- **Identidade:** 0 falsos positivos e 0 fusões indevidas nos 20 artistas,
  60 lançamentos e 30 relações efetivamente validados.
- **Incerteza segura:** links ausentes, `UNCLASSIFIED`, crosswalk
  inconclusivo e obras MusicBrainz-only NÃO são defeito bloqueante quando a
  interface preserva a distinção e não inventa MBID/reprodução.
- **Falhas:** P0 = corrupção, atribuição destrutiva ou reprodução indevida;
  P1 = associação falsa reproduzível, crash/loop de UI, perda grave de
  navegação/identidade. P0/P1 bloqueiam. P2 = capa/nome/ordenamento
  imperfeitos, classificações editoriais fora da responsabilidade MB,
  raridades sem prova ou duplicidades visuais não destrutivas: documentar
  no backlog Apple/Discogs/UX em vez de prolongar a #209.
- **Tempo de trabalho:** no máximo **duas rodadas de auditoria/correção** após
  congelar esta amostra. Ao término, rodar os mesmos casos contra o HEAD
  final e fechar a etapa se não houver P0/P1. Depois disso não ampliar a
  amostra para procurar defeitos raros; apenas P0/P1 novos e reproduzíveis
  podem reabrir a saída.
- **Proveniência:** APIs públicas e testes de source/Room/Android são
  evidências distintas. CI verde não prova aparência; consulta MB não
  prova áudio disponível. Reportar `INCONCLUSIVE` e `SKIPPED` sem mascarar.
- **Sem smoke agora:** não solicitar, disparar ou instalar APK manualmente
  durante as rodadas. O workflow automático já existente de build da PR
  não equivale a aprovação de smoke. O único smoke manual é o F final.
- **Controle de branches:** alterar apenas a PR #209. Não fazer merge nem
  atualizar #211 (Discogs), #212 (REA) ou #214 (Apple) neste gate sem nova
  autorização.

## Artefato de encerramento (a produzir, não assumido)

Um relatório único com os 20 MBIDs verificados, 60 lançamentos, 30 relações,
failures e notas por severidade, links exatos aos resultados públicos/CI,
commit final e resultado do smoke F. Até existir esse relatório com as provas,
a #209 continua em draft. **Não executar uma investigação infinita do corpus
MusicBrainz**: identidades corretas e falha segura são a definição de pronto.
