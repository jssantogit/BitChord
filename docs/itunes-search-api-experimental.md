# iTunes Search API — catálogo de metadados (experimental)

**Branch:** `catalog/itunes-search-metadata-20261113` · PR #214 · **base de validação congelada:** `catalog/itunes-validation-base-744a438b` (snapshot exato da PR #209).

A branch #209 recebe mudanças concorrentes com frequência. A primeira CI da PR #214 compilou o cliente iTunes e executou 3.272 testes: os testes do iTunes passaram, mas duas novas regressões do MusicBrainz **introduzidas depois do snapshot** falharam no merge sintético contra a base móvel. Para não atribuir defeitos do MusicBrainz a este adaptador, o alvo da PR #214 foi temporariamente fixado no snapshot `744a438b`. Ao integrar, retargetar para a PR #209 estabilizada e repetir todas as verificações.

## Objetivo e limites

Este adaptador consulta a **iTunes Search API pública** sem developer token.
Não é a Apple Music API, não requer Apple ID nem assinatura. Pesquisa artistas,
álbuns e músicas por storefront e permite lookup por ID, artista ou UPC.

- Identidade preservada: `itunes:<país>:<tipo>:<id>`; nenhum item se converte automaticamente em MusicBrainz MBID, ReleaseGroup/Release ou MediaAsset.
- Metadados somente: títulos, artista, datas, gênero, duração, número de faixa/disco e URL HTTPS do iTunes/Apple Music.
- Não traz capas ou prévias para Coil/Media3. Não fornece música completa e não oferece qualquer comando de reprodução. A Apple restringe o uso de artwork/previews promocionais, mesmo que o fork seja pessoal.
- A API de busca **não especifica tipo Album/EP/Single** de modo equivalente ao MusicBrainz. Não inferir tipo a partir do número de faixas ou do nome.
- Variações de edição com o mesmo título continuam com IDs distintos. ISRC/UPC, quando disponíveis, são indícios de correspondência, não prova automática para consolidar versões.

## Endpoints (somente GET)

| Operação | Endpoint | Parâmetros |
|---|---|---|
| Buscar artistas | `/search` | `term,country,media=music,entity=musicArtist,limit` |
| Buscar álbuns | `/search` | `term,country,media=music,entity=album,limit` |
| Buscar músicas | `/search` | `term,country,media=music,entity=song,limit` |
| Faixas de um álbum | `/lookup` | `id,country,entity=song,limit=200` |
| Lançamentos de artista | `/lookup` | `id,country,entity=album,limit` |
| Verificar UPC | `/lookup` | `upc,country,entity=song,limit=200` |

`ItunesCatalogClient` está em `app/.../data/catalog/metadata`.
A interface `ItunesCatalogTransport` permite fixtures e testes sem HTTP.

## Segurança, confiabilidade e performance

- Timeout da chamada de 15 s, máximo de 512 KiB por corpo JSON e máximo de 200 linhas.
- Limitador singleton de requests: mínimo 3.100 ms entre inícios, por processo, protegendo o teto aproximado de **20 req/min** documentado pela Apple. Sem retry automático para HTTP 429.
- País de duas letras; padrão `br`. Busca com até 30 resultados por chamada; entradas de usuário com controles e IDs inválidos são recusados.
- Cancela corretamente coroutine; erro HTTP, timeout ou JSON malformado falham fechado.
- Filtra vídeos, resultados heterogêneos e URLs fora de `https://itunes.apple.com` e `https://music.apple.com`.
- Em lookup de álbum, ignora faixas de outra coleção; em lookup de artista, ignora coleções de outro artista.
- Caching e paginação de artistas são trabalho posterior; a API Search não expõe offset estável. Não criar paginação sintética.

## Próxima fase de integração visual

Adicionar **iTunes** como fonte explícita de catálogo em uma superfície de comparação, não misturada por título aos resultados canônicos. Selecionar BR/US e permitir inspecionar uma edição (incluindo suas faixas) e abrir sua página original Apple. Nesta fase o cliente está operacional em nível de dados, mas **não há UI ou APK de teste**.

A futura reconciliação opcional com MusicBrainz/Discogs deverá exigir provas verificadas, escopo storefront, e não poderá alterar classificação, artwork ou status de reprodução sem direitos e identidade independentes.

## Referências oficiais

- https://performance-partners.apple.com/search-api
- https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/LookupExamples.html
