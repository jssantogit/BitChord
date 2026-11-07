# Discogs #211 — smoke Android Galaxy A54 (aprovado em 07/11/2026)

**Objetivo:** validar o código Android real da PR #211 (Discogs) sobre a interface nativa da PR #209, sem fazer merge na `main` nem publicar release. O artefato é `devDebug`. O workflow exige testes unitários Android + teste shared de reprodução antes de gerar o APK; checksum e SHA do commit ficam juntos ao APK.

## Identidades e segurança

- A página de um `RELEASE_GROUP` não é uma edição. O MusicBrainz escolhe uma edição com MBID próprio e comprova o grupo; o Discogs só exibe atributos que passou pelo matcher de Release Official, tracklist completa e vínculo externo tipado.
- Na Biblioteca e em álbuns abertos dentro de um artista, só mostrar Discogs para `ReleaseId` local associado a **exato** MBID de edição MusicBrainz. Não inferir com base no título, artista, capa, faixa, gênero ou ordem de popularidade.
- A seção `Sobre esta edição` e o link `Data provided by Discogs.` devem aparecer juntos apenas onde existe evidência VERIFIED ainda válida. `Ver mais`/ `Ver menos` expandem os formatos e selos comprovados, sem alterar metadados MusicBrainz.
- Referência pública de contrato já validada pela probe anterior: MusicBrainz Release `dc919562-19c7-42c8-961c-7ed5520f00bb` → Discogs Release `249504`; a probe não garante que a edição aparecerá numa biblioteca pessoal.

## Roteiro físico

1. Instalar o APK dev debug no Galaxy A54. Confirmar navegação normal, sem travamentos na Home, Explorar, Pesquisa e Biblioteca.
2. Pesquisar uma edição MusicBrainz de álbum online. Abrir tanto um resultado direto `RELEASE` quanto um `RELEASE_GROUP`, observando a tracklist, a capa própria e o rodapé; a edição escolhida não pode fugir do grupo.
3. Trocar rapidamente entre dois álbuns e voltar à pesquisa; o rodapé e as faixas não devem piscar com dados da edição anterior.
4. Abrir um álbum local/da Biblioteca sem relação de edição MusicBrainz: **não** aparecerá seção Discogs. Se houver uma relação exata e validada, a seção pode aparecer.
5. Abrir um álbum por dentro da tela de artista e verificar o rodapé no mesmo layout nativo usado pela Biblioteca; testar os botões de voltar, +/fixar, avaliação, fila e playlist existentes.
6. Expandir `Ver mais`/`Ver menos`; conferir que o link de atribuição abre a página exata do Discogs sem pedir autenticação e que o aviso de não afiliação está em Configurações → Sobre.
7. Alternar Wi-Fi/dados e modo avião: páginas MusicBrainz e da Biblioteca permanecem funcionais quando o Discogs falhar ou estiver indisponível. Sem nova faixa, áudio ou imagem criados pelo Discogs.
8. Se houver um rodapé cujo TTL expire enquanto a tela está aberta, ele deve desaparecer sem deixar espaço vazio; não é necessário manter o telefone por cinco horas para o smoke rápido, pois há teste automatizado de expiração.
9. Em caso de falha, exportar diagnósticos do BitChord e registrar o título, MBIDs, rota e momento aproximado; evitar logs com identificadores privados de serviços locais.

## Critérios de saída

CI Android e gates relevantes aprovados no **mesmo SHA**; workflow de APK aprovado com checksum; navegação de três rotas sem regressões; evidência Discogs somente quando VERIFIED, com atribuição; sem tela legada restaurada. **APK gerado não equivale a smoke físico aprovado**: este último depende da observação no Galaxy A54.

## Revalidação após correção de idiomas da #209

O primeiro workflow de APK da #211 interrompeu na etapa Android com 15 casos de `LocaleStringsTest`, por seis chaves de limite de cache visual ausentes em traduções. A #209 corrigiu essas chaves nos idiomas oferecidos pelo app. Esta revisão da #211 incorpora o HEAD correspondente da #209 e repete todos os gates antes de publicar o APK. Aprovação de CI/APK não equivale a smoke físico no Galaxy A54.

## Reteste obrigatório — seção “Sobre esta edição” após relato no A54

No primeiro smoke físico de `25a9b6a860`, o usuário não identificou nenhuma diferença nas páginas de álbuns. O diagnóstico de 07/11/2026 confirmou `discogs_edition_evidence_v1 result=no_link` em uma consulta, sem erro HTTP Discogs: a edição MusicBrainz não tinha associação Discogs direta e o rodapé anterior estava programado para desaparecer. O problema era de apresentação/cobertura, não de instalação do APK.

**Mudança que este novo APK precisa demonstrar:** abaixo da lista de faixas de *todos* os álbuns (Release, ReleaseGroup, Biblioteca local e álbum aninhado dentro de artista), aparece **“Sobre esta edição”**. Data, contagem e, quando disponíveis, país, gravadora/número de catálogo, formato e estado vêm de dados reais do MusicBrainz/da Biblioteca. Um `no_link` mostra explicitamente que não existe associação Discogs confirmada *no MusicBrainz*, sem afirmar que o disco inexiste no Discogs. Somente um match Discogs VERIFIED adiciona campos Discogs e link de atribuição. Falhas de rede não devem apagar os fatos MusicBrainz.

**Smoke obrigatório após a instalação:** abrir um álbum online com link Discogs e outro sem link, rolar até depois das faixas, verificar ambas as seções, abrir um álbum local sem MBID, abrir um álbum a partir do artista, testar `Ver mais` quando houver dados adicionais, e exportar diagnóstico se algo não aparecer. No terminal da CI, exigir `LocaleStringsTest`, `MusicBrainzCatalogGraphTest`, `NativeAlbumEditorialFooterTest` e `NativeAlbumEditorialFooterComposeTest` aprovados. APK `devDebug` deve incluir SHA e checksum. Não mesclar a PR por esse smoke; somente validação física do A54 confirma a correção.

### Precisão das mensagens de disponibilidade

O estado `Candidate` também ocorre antes de consultar Discogs se o MusicBrainz não fornece uma tracklist ou crédito completo: **não significa** que uma relação Discogs tenha sido encontrada. `Unavailable` também pode vir de falha MusicBrainz, e `Invalid` pode ser o MBID. As mensagens da seção agora distinguem indisponibilidade/insuficiência/identificador sem atribuir falsamente a causa ao provedor. Testes unitários verificam essas distinções.
