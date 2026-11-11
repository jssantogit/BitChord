# Discogs #211 — controles reais de classificação (fora do corpus de 30)

Revisão documental: 2026-11-09. Estes são **controles negativos**, não casos de ganho editorial do Discogs e não representam correspondências Discogs verificadas. O token de pesquisa autenticada permanece exclusivamente no aparelho; não consultar a API de busca com credenciais de terceiros nem declarar ausência de um lançamento com base em buscas anônimas.

| Referência pública | Fonte canônica MusicBrainz | Evidência editorial independente | Resultado esperado |
|---|---|---|---|
| ILLIT — SUPER REAL ME (2024) | [Release MBID `68e87262-1bfb-4ede-922b-86a025b2ac96`](https://musicbrainz.org/release/68e87262-1bfb-4ede-922b-86a025b2ac96), **EP**, edição CD sul-coreana `SUPER ME ver.` | [Aviso oficial ILLIT, 26 fev. 2024](https://illit-official.jp/news/112181f399e9): primeiro *mini album*, duas versões `SUPER ME` e `REAL ME` | Não promover uma versão de capa a um novo EP; nenhuma correção de tipo se MB já diz EP |
| ILLIT — BREAK EVEN (2026) | [Discografia MusicBrainz do artista](https://musicbrainz.org/artist/6b128dd7-e998-4edb-a4f5-400fef190445): seção **EP**, `BREAK EVEN` | [Aviso oficial ILLIT, 28 set. 2026](https://illit-official.jp/news/77dd047f709d): quinto miniálbum, edição coreana 26 out. 2026 | Classificação de referência EP; não contar como álbum ausente se ele já está catalogado |
| BTS — Proof (2022) | [Release MBID `e4bd76e4-d844-4955-a327-e1a1171e37c2`](https://musicbrainz.org/release/e4bd76e4-d844-4955-a327-e1a1171e37c2): **Album + Compilation**, edição CD coreana | [Discografia oficial BIGHIT](https://bts.ibighit.com/eng/discography/detail/proof/index.html): álbum antológico em três CDs, lançamento em 10 jun. 2022 | Classificação de referência Album/Compilation; não representar como correção Discogs |

## Verificações ainda não feitas

Nenhum dos três controles possui Discogs Release ID confirmado neste documento; não há resultado de autenticação, vínculo exato, consenso por Master ou observação da UI Android. Eles **não** preenchem as vagas `type`, `unlinked` ou `source_only` do arquivo `catalog-211-discogs-corpus-manifest.json`. Um tipo editorial que já estava correto no MusicBrainz deve ser contado como corroboração, nunca como sucesso de correção.

O próximo avanço empírico exige documentar, caso a caso, o Discogs Release ID, Artist/ReleaseGroup MBID, relação (quando existir), descrição explícita de tipo, rastreabilidade editorial e observação do BitChord, incluindo casos em que a busca retorna ambiguidade ou indisponibilidade.
