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
