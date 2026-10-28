# Smart Playlists #192 — benchmark automatizado de 50.123 gravações

## O que a CI mede

O teste Android/JVM `SmartPlaylistCiBenchmarkTest` é executado pelo
`:app:testDevDebugUnitTest` com Robolectric SDK 35 e **SQLite/Room real
em memória**. Popula 50.123 `RecordingEntity` canônicas em uma transação
de preparação **fora das medições**, lê exatamente todos os IDs pelo
`RoomSmartPlaylistBatchEvidenceReader` e avalia as mesmas regras tipadas
no `SmartPlaylistV2RuleEngine`.

A consulta contém título que inclui “Track”, duração >=180 s, ordenação
por título e resultado de 500 gravações. O teste demonstra:

- 50.123 linhas efetivamente retornadas, sem corte histórico de 5.000.
- 126 leituras em blocos de no máximo 400 RecordingIds (limite SQLite).
- Lista resultante de 500 IDs canônicos, únicos, exatos e determinísticos.
- Projeção restrita aos dois campos usados (TITLE e DURATION_MS).
- Nenhuma consulta por gravação no caminho medido; o número de blocos é
  limitado a 126 e a leitura acontece em lote.

**Métricas:** uma rodada de aquecimento e três rodadas medidas;
`System.nanoTime()` para tempo de leitura Room e avaliação pura do motor,
com mediana e amostras em milissegundos; variação observada do heap JVM
(`totalMemory - freeMemory`) antes/depois de cada rodada, **não** pico RSS,
não alocação total e não medição de bateria. Os tempos e a memória não têm
limites rígidos por dependerem do runner compartilhado da CI.

## Onde os resultados aparecem

No workflow [Android CI](../../.github/workflows/check.yml), a execução
da branch `listening/smart-playlists-v2-192` publica uma seção no
**Job Summary** e o artefato
`smart-playlist-benchmark-<sha>` contendo:

- `metrics.json`: amostras e medianas Room/motor/heap, cardinalidade e lote.
- `summary.md`: interpretação legível e ressalvas.

Se os testes falharem antes de gerar o arquivo, o resumo informa ausência
de métricas; não apresentar números inventados. O status requerido da CI
continua refletindo os testes, não apenas a publicação do artefato.

Para executar isoladamente em um ambiente Android SDK configurado:

```bash
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests 'com.music.bitchord.data.listening.SmartPlaylistCiBenchmarkTest'
```

## O que **não** comprova

Este resultado **não substitui** benchmark físico do Galaxy A54: tempo de
abertura e responsividade Compose/UI thread, heap e RSS do processo Android
com catálogo real persistido, condições térmicas, armazenamento flash,
mudança de contas/fonte, áudio e Media3, nem consumo de energia. Tampouco
prova comportamento de filtros sem metadados canônicos (gênero, compositor,
Favorite Date, data original). Esses gates da issue #192 continuam abertos.

Não exigir APK nem merge para o benchmark da CI. A PR #193 segue draft
até a validação funcional, auditoria Symfonium 15.1.0 e smoke físico.
