# Issue #178 — Matriz de aceite Android (55 cenários)

**Data de referência:** 2026-10-25. **Status:** protocolo de validação, não benchmark já executado. **Escopo:** exclusivamente Android; nenhuma obrigação Web/Desktop/TV. [Issue #178](https://github.com/jssantogit/BitChord/issues/178) · [PR draft #183](https://github.com/jssantogit/BitChord/pull/183).

## Regras de prova

- Cada linha só pode ser aprovada com execução reproduzível, input, resultado/asserção, commit exato, ambiente, data e artefato de evidência. Código implementado ou teste escrito **não** é por si benchmark real de 55 cenários.
- **API_LIVE** valida somente metadados públicos de MusicBrainz/CAA; não substitui Room, Android UI, Media3, Navidrome privado ou Galaxy A54. **ROOM_CI** é JVM/Robolectric no HEAD. **ANDROID_CI** exige teste executado no CI. **DEVICE** exige instalação e observação no aparelho. **PRIVATE_REMOTE** só por coleta consentida e sem publicar dados privados.
- Falha HTTP 429/503 é registrada e retentativa deve respeitar limite de solicitações. Resultado sem prova vira **INCONCLUSIVO**, jamais PASS presumido.
- Separar três resultados distintos: **identidade conhecida**, **MediaAsset disponível**, **áudio realmente reproduzido**. MusicBrainz não oferece streaming. Nenhum fallback disfarçado para YouTube.
- Registrar benchmark por estrato: executados/aprovados/reprovados/inconclusivos, latência p50/p95 onde aplicável, cobertura de IDs e zero fusões equivocadas. Comparação com YT editorial requer avaliação humana independente; não transformar ausência de mood/playlist YT em sucesso falso.
- Gate de cutover exige matrizes concluídas, inspeção de chamadas YT com opt-out, smoke no Galaxy A54, tratamento explícito de lacunas de Home/Explore/ratings/letras/playlists e aceite do orquestrador. Esta matriz não autoriza merge nem remoção.

## Evidência pública já coletada (parcial)

[Workflow live #40203783906](https://github.com/jssantogit/BitChord/actions/runs/40203783906) registrou **9/9 checks de API**, 14 tentativas HTTP (9 respostas 200; 5 respostas 503 recuperadas). Sete verificações têm correspondência direta nesta matriz: **01, 02, 11, 12, 21, 26, 27** (IDs 21/26/27 correspondem às provas anteriores de ReleaseGroup e edições). Os dois checks adicionais verificam busca por artista e browse de edições, sem provar UI Android. Portanto **não declarar 55/55**, tampouco marcar os sete como aceite integral quando ainda faltam etapas Android. Reexecutar os checks contra o HEAD final quando pertinente.

## Os 55 cenários e oráculos

**Estado inicial de todos os cenários: PENDENTE DE ACEITE INTEGRAL.** Evidência parcial acima não substitui o teste por ambiente.

| ID | Estrato | Cenário | Ambiente mínimo | Critério observável |
|:--:|---|---|---|---|
| 01 | Busca de artistas (MusicBrainz API) | ILLIT por MBID | API_LIVE | MBID conhecido retorna nome correto e não cria MediaAsset |
| 02 | Busca de artistas (MusicBrainz API) | aespa por MBID | API_LIVE | MBID conhecido retorna nome correto, separado de ILLIT |
| 03 | Busca de artistas (MusicBrainz API) | Busca textual ILLIT | API_LIVE | resultado pesquisado contém o MBID esperado |
| 04 | Busca de artistas (MusicBrainz API) | Busca textual aespa | API_LIVE | resultado pesquisado contém o MBID esperado |
| 05 | Busca de artistas (MusicBrainz API) | Busca sem correspondência | API_LIVE | lista vazia distingue ausência de erro HTTP |
| 06 | Artistas ambíguos / identidade | Homônimos com MBIDs distintos | API_LIVE+ROOM_CI | nunca unir artistas somente pelo nome |
| 07 | Artistas ambíguos / identidade | Disambiguation e country | API_LIVE+ROOM_CI | persistir e restaurar campos após cache offline |
| 08 | Artistas ambíguos / identidade | Alias local versus nome MB | API_LIVE+ROOM_CI | busca MB não sobrescreve apresentação local |
| 09 | Artistas ambíguos / identidade | Crédito com joinphrase | API_LIVE+ROOM_CI | preservar espaço e artista participante |
| 10 | Artistas ambíguos / identidade | MBID inválido | API_LIVE+ROOM_CI | rejeitar antes de persistir |
| 11 | Recordings / versões | Magnetic original | API_LIVE+ROOM_CI | MBID original e título conferidos |
| 12 | Recordings / versões | Magnetic sped-up | API_LIVE+ROOM_CI | MBID diferente do original; não promover equivalência |
| 13 | Recordings / versões | Remix vs gravação original | API_LIVE+ROOM_CI | MBIDs e versões distintos sem colapso por título |
| 14 | Recordings / versões | Duração de Recording vs Track | API_LIVE+ROOM_CI | não substituir metadados de Recording pelos de Track |
| 15 | Recordings / versões | Recording sem audio local | API_LIVE+ROOM_CI | conhecida como metadata-only; nenhuma reprodução implícita |
| 16 | Track / créditos / ISRC | Track MBID separado de Recording MBID | ROOM_CI | mesma Recording pode ocorrer em diferentes Tracks |
| 17 | Track / créditos / ISRC | Artista creditado por faixa | ROOM_CI | não herdar indiscriminadamente artista da edição |
| 18 | Track / créditos / ISRC | Colisão Track MBID/Recording divergente | ROOM_CI | recusar persistência contraditória |
| 19 | Track / créditos / ISRC | ISRC com versões ambíguas | ROOM_CI | não fundir versões sem evidência forte |
| 20 | Track / créditos / ISRC | Créditos da Recording offline | ROOM_CI | round-trip de créditos, título e duração |
| 21 | ReleaseGroup e discografia | SUPER REAL ME por MBID | API_LIVE+ROOM_CI | grupo reconhecido por identificador estável |
| 22 | ReleaseGroup e discografia | Artist→ReleaseGroup online | API_LIVE+ROOM_CI | browse encontra grupo associado ao artista |
| 23 | ReleaseGroup e discografia | Artist→ReleaseGroup offline | API_LIVE+ROOM_CI | relações persistem no Room sem rede |
| 24 | ReleaseGroup e discografia | Grupos de artistas homônimos | API_LIVE+ROOM_CI | vínculo sempre por MBID, nunca por texto |
| 25 | ReleaseGroup e discografia | Página posterior de discografia | API_LIVE+ROOM_CI | cursor usa linhas brutas e deduplica |
| 26 | Edições e tracklists | Edição worldwide | API_LIVE+ROOM_CI | Release MBID e ligação ao ReleaseGroup corretos |
| 27 | Edições e tracklists | Edição japonesa | API_LIVE+ROOM_CI | Release MBID independente, com mesma família de grupo |
| 28 | Edições e tracklists | Disco / número da faixa | API_LIVE+ROOM_CI | medium e position preservados no grafo |
| 29 | Edições e tracklists | Edição pesquisada volta offline | API_LIVE+ROOM_CI | busca+persistência+reabertura sem rede |
| 30 | Edições e tracklists | Tracklist aninhada | API_LIVE+ROOM_CI | Track title e Recording title não se confundem |
| 31 | Capas, erro e rate limit | Capa frontal existente | API_LIVE+ROOM_CI | origem CAA correta da edição quando disponível |
| 32 | Capas, erro e rate limit | CAA 404 confirmado | API_LIVE+ROOM_CI | ausência definitiva pode usar fallback seguro do grupo |
| 33 | Capas, erro e rate limit | CAA 503 transitório | API_LIVE+ROOM_CI | sem falsa ausência/fallback incorreto |
| 34 | Capas, erro e rate limit | URI de capa insegura | API_LIVE+ROOM_CI | rejeitar esquema/host indevido sem marcar 404 |
| 35 | Capas, erro e rate limit | MusicBrainz 429/503 | API_LIVE+ROOM_CI | retry limitado, intervalo mínimo e cancelamento |
| 36 | Busca, paginação, consistência | Cursores por 4 entidades | ANDROID_CI | cada tipo avança independentemente |
| 37 | Busca, paginação, consistência | Deduplicação e offset bruto | ANDROID_CI | filtrados não alteram próximo offset |
| 38 | Busca, paginação, consistência | Retry de página falha | ANDROID_CI | não salta/duplica janela |
| 39 | Busca, paginação, consistência | Respostas antigas após nova query | ANDROID_CI | não sobrescrevem resultados atuais |
| 40 | Busca, paginação, consistência | Busca offline parcial | ANDROID_CI | itens persistidos por tipo continuam visíveis |
| 41 | Jornadas Android de descoberta | Home canônica opt-in | ANDROID_CI+DEVICE | coleções locais e identidades persistidas navegáveis |
| 42 | Jornadas Android de descoberta | Explore canônico opt-in | ANDROID_CI+DEVICE | busca pública acessível, sem dados YT ocultos |
| 43 | Jornadas Android de descoberta | Busca→artista→grupo→edição | ANDROID_CI+DEVICE | navegação mantém IDs e back stack |
| 44 | Jornadas Android de descoberta | Empty/loading/error de descoberta | ANDROID_CI+DEVICE | estado e retry corretos sem tela preta |
| 45 | Jornadas Android de descoberta | Alternância reversível YT/canônico | ANDROID_CI+DEVICE | preserva configurações e não emite chamadas YT em opt-out |
| 46 | Segurança offline e provedores | Rescan local preserva fontes remotas | ROOM_CI+DEVICE | sem prune de arestas MusicBrainz/Navidrome |
| 47 | Segurança offline e provedores | Abertura offline após restart | ROOM_CI+DEVICE | identidade e tracklist disponíveis sem internet |
| 48 | Segurança offline e provedores | Conta remota trocada/revogada | ROOM_CI+DEVICE | sem ghost asset nem identidade tomada por outra conta |
| 49 | Segurança offline e provedores | Local MediaStore com versões iguais no título | ROOM_CI+DEVICE | não fundir arquivos/versions diferentes |
| 50 | Segurança offline e provedores | MusicBrainz metadata não audível | ROOM_CI+DEVICE | sem URL fictícia ou fallback YT implícito |
| 51 | Reprodução, ratings e aceite físico | Recording local com asset válido | ANDROID_CI+DEVICE | PLAY_NOW chega ao Media3 e produz áudio |
| 52 | Reprodução, ratings e aceite físico | Recording Navidrome autorizada | ANDROID_CI+DEVICE | ADD_TO_QUEUE utiliza asset e provedor real |
| 53 | Reprodução, ratings e aceite físico | Recording metadata-only | ANDROID_CI+DEVICE | PLAY_NOW e ADD_TO_QUEUE bloqueados |
| 54 | Reprodução, ratings e aceite físico | Avaliações/playlist/letras canônicas | ANDROID_CI+DEVICE | preservação de proveniência e fallback não-YouTube validado ou lacuna documentada |
| 55 | Reprodução, ratings e aceite físico | Galaxy A54 ponta a ponta | ANDROID_CI+DEVICE | APK HEAD exato, percurso, reinício e modo avião documentados |

## Formato mínimo de evidência por caso

```json
{"case_id":"01","head_sha":"<sha>","environment":"API_LIVE|ROOM_CI|ANDROID_CI|DEVICE","observed_at_utc":"<ISO-8601>","result":"PASS|FAIL|INCONCLUSIVE","evidence_url":"<run/artifact/private note>","notes":"<redacted>"}
```

Validador humano/revisor: não aceitar PASS com SHA ausente, resultado autodeclarado sem observação, endpoints ambíguos, logs com credenciais ou dados privados, ou execução em build antigo. Corpus real de comparação editorial deve manter referência YT como baseline **explícito**, sem funcionar como fallback do aplicativo.
