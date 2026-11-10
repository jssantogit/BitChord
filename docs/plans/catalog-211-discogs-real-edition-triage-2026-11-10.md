# Discogs #211 — triagem de edições reais, 10/11/2026

Escopo: PR #211, Android, sem token Discogs; observações de fonte pública e referências MusicBrainz conferidas. **Documento de triagem, não é corpus homologado.** Manter as 30 vagas em `reviewed=false` até conferência completa e independente. Não converter os achados abaixo em contagens de cobertura, falsos positivos definitivos nem `Verified` do aplicativo.

## Evidência reproduzível

- [Scanner de 10 artistas e inspeção de até 8 edições — GitHub Actions #42807755689](https://github.com/jssantogit/BitChord/actions/runs/42807755689): comparação de 496 grupos MusicBrainz, até 12 linhas de índice Discogs/artista, confirmação ID Artist MB→Discogs, consulta Master/Release e resultado não homologado.
- [Filtro de pontuação e scanner de 12 pistas — GitHub Actions #42808323550](https://github.com/jssantogit/BitChord/actions/runs/42808323550): eliminou três pistas de pontuação sem mudar a identidade de edição.
- [Scanner limitado a segunda página quando a primeira não oferece novidades — GitHub Actions #42808758391](https://github.com/jssantogit/BitChord/actions/runs/42808758391), aprovado; ainda não prova ausência no MusicBrainz.
- Em todos os casos, títulos/índice são pistas; um Discogs Master não equivale a um MusicBrainz ReleaseGroup. A decisão de falta real exige comparar release groups, títulos alternativos, relações, edição, tracklist, Barcode/ISRC e créditos.

## Dossiê de observações específicas

| Origem | ID Discogs observado | MusicBrainz já existente / contexto público | Triagem e próximo discriminante |
|---|---|---|---|
| ENHYPEN — **Confessions** | [Release 33375572](https://www.discogs.com/release/33375572), [Master 3777365](https://www.discogs.com/master/3777365) | Créditos Discogs: Flo Rida, ENHYPEN e Paul Russell; não é lançamento exclusivo do grupo | Excluir da estante Discogs-only de artista exclusivo; para participação discográfica, requer modelagem própria e referência cruzada de créditos |
| ENHYPEN — **Dark Moon Special Album 'Memorabilia'** | [Release 31008160](https://www.discogs.com/release/31008160), [Master 3540736](https://www.discogs.com/master/3540736) | [MusicBrainz release MEMORABILIA — d0c0cfe6](https://musicbrainz.org/release/d0c0cfe6-0d31-469e-965a-3a5c18b1eb10): EP, 6 músicas | Mesmo conteúdo aparente sob título diferente; comparar as seis posições, edições e grupo MB antes de declarar equivalência, não contar como recuperação |
| ENHYPEN — **Romance: Untold -Daydream-** | [Master 3741708](https://www.discogs.com/master/3741708) (sem Release hidratado no primeiro orçamento) | [MusicBrainz release 97d384e6](https://musicbrainz.org/release/97d384e6-372e-4ac4-a32c-abcbdc0abb60), versão existente no catálogo | Revisar edição Discogs exata e barcode; já há grupo MusicBrainz conhecido |
| IVE — **Fashion / ファッション** | [Release 37253067](https://www.discogs.com/release/37253067) | [Artista IVE no MusicBrainz](https://musicbrainz.org/artist/b2f2216a-d7a9-4ce0-8b8f-f494d9a8c196) inclui Single **Fashion**; [EP LUCID DREAM](https://musicbrainz.org/release/231ac4a7-4c9c-4017-ad00-5ae0a74716ef) inclui a faixa | Tradução/japonês no nome Discogs; o ReleaseGroup 'Fashion' existe. Confrontar tracklist e relações, não inferir tradução por texto |
| IVE — **Dare Me / デアミー** | [Release 34121731](https://www.discogs.com/release/34121731) | [Discografia do IVE](https://musicbrainz.org/artist/b2f2216a-d7a9-4ce0-8b8f-f494d9a8c196): Single **DARE ME**, 2025; também incluído em [Be Alright](https://musicbrainz.org/release/4ccc9caf-bafb-4937-9ab9-35a0e4f75fce) | Ambiguidade entre single, versão bilíngue e participação; conferir ID exato e edição antes de classificar |
| TXT — **セツナハナビ (Setsuna Hanabi)** | [Release 38302455](https://www.discogs.com/release/38302455), [Master 4331424](https://www.discogs.com/master/4331424) | [MusicBrainz release 6770b265](https://musicbrainz.org/release/6770b265-719a-463b-af5e-50ac5aeac1e5): Single, quatro faixas | Mesmo lançamento conhecido; Disco CD x digital MB podem ser edições diferentes. Não contar como novo grupo |
| TXT — **7TH YEAR: A Moment of Stillness in the Thorns** | [Release 37110012](https://www.discogs.com/release/37110012), [Master 4200420](https://www.discogs.com/master/4200420) | [MusicBrainz release 2df1c4bb](https://musicbrainz.org/release/2df1c4bb-056c-48dc-a562-68722853d454): EP oficial, 6 faixas | Corroboração de tipo, não correção, sem prova de mesma edição Discogs |
| Stray Kids — **The DominATE Experience** | [Release 38613330](https://www.discogs.com/release/38613330) | O formato Discogs observado é **DVD** (vídeo), não áudio-album | Exclusão apropriada da estante de álbum/EP/single de música |
| aespa — **World Tour in Cinemas** | [Release 35180680](https://www.discogs.com/release/35180680) | O formato Discogs observado é **Blu-ray**, filmagem de concerto | Exclusão apropriada da estante de áudio |
| Stray Kids — **HOP** | Índice observado, Release não hidratado nesta rodada | [MusicBrainz release 105a2cf1](https://musicbrainz.org/release/105a2cf1-6297-417f-9e16-5dfffcea7490): EP + Mixtape/Street, 12 faixas | Não reclassificar para Album por formato LP ou número de faixas |

## Pares de edição reproduzíveis

O workflow `Catalog #211 — Discogs corpus candidate scouting` passou em
[42816462143](https://github.com/jssantogit/BitChord/actions/runs/42816462143)
com três controles de edições (19 testes offline somados):
- ENHYPEN MEMORABILIA: [RG 4e219d4e](https://musicbrainz.org/release-group/4e219d4e-c5e0-4ed9-acec-b948c7319b37) classificado EP, 6/6 faixas iguais, países comparáveis conflitantes, barcode não idêntico, sem vínculo direto Release.
- TXT Setsuna Hanabi: [RG cb79234e](https://musicbrainz.org/release-group/cb79234e-c3d3-4973-a3e3-6ec603b3d1f4) Single, 3/4 nomes de faixa idênticos, sem vínculo direto Release.
- LE SSERAFIM EASY CRAZY HOT: [RG 50f5c625](https://musicbrainz.org/release-group/50f5c625-0add-4fa6-86e5-bfe05f1ca661) já Album, vínculo Release direto, barcode igual e 14/15 nomes de faixa idênticos. O título `Easy-Crazy-Hot` difere somente na pontuação; esse caso revelou uma hipótese de falso conflito no matcher nativo.

A lista `docs/plans/catalog-211-discogs-edition-pairs.json` agora inclui
**mais três controles sem julgamento**: [IVE Fashion (RG c8ddddd3)](https://musicbrainz.org/release-group/c8ddddd3-db0d-460e-8cce-ccfad9c1170c),
[IVE DARE ME (RG 7bd8d871)](https://musicbrainz.org/release-group/7bd8d871-8f1e-4dff-99c2-386097d98910)
e TXT `7TH YEAR` em CD, com MBIDs de Release e IDs Discogs vinculados
a registros públicos. A comparação será publicada no próximo artefato do
workflow; não tratar diferenças de títulos, novos controles nem relações de
grupo como prova de edição ou ausência. Todos permanecem `reviewed=false`.

## Estado por coorte e decisão de fechamento

- **A (tipo):** controles e exemplos já classificados foram identificados; nenhum caso revisado com divergência editorial que justifique correção. Exigir pelo menos duas evidências independentes por Master/grupo quando a origem discorda do tipo MusicBrainz, mais fonte editorial oficial.
- **B (sem vínculo):** ainda sem 10 pares Release MBID↔Discogs Release ID verdadeiramente sem relação Release→Discogs e com barcode + track order completos. Não usar comparações de grupo como prova de edição.
- **C (source-only):** os títulos aqui investigados revelaram vários conhecidos, variantes e vídeos; nenhum dos 10 itens foi homologado como efetivamente ausente do MusicBrainz. Candidatos sem correspondência automática devem ser inspecionados um por um, inclusive hierarquia Master, crédito exato, edição, existência de grupos em outras escritas e fontes independentes.
- Requisito de observação futura da UI: Android Galaxy A54, rota do artista, shelf nativa, atribuição e ausência de reprodução para source-only. Não disparar APK/smoke até os gates CI e evidência autorizarem.

Esta triagem é uma **evidência negativa útil sobre o risco de falsa recuperação**: indexar o Discogs por título sem as proteções implementadas geraria estantes duplicadas ou falsas.
