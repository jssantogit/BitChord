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

### Correção do gate após mensagem de indisponibilidade

O smoke de `82df4d8287` bloqueou corretamente o APK por uma asserção desatualizada: o teste de estados não verificados ainda exigia prefixo `Discogs ·` inclusive para `Unavailable`, mesmo após o texto deliberadamente tornar a causa independente de provedor. A implementação permaneceu compilando; o teste foi atualizado para validar a nova afirmação sem reduzir a cobertura, e todas as provas devem rodar novamente antes do upload.


## Reteste A54 — apresentação editorial e tipagem Discogs (07/11/2026)

Observação do usuário no APK anterior: a seção agora aparece, inclusive em `BREAK EVEN` (ILLIT) e no álbum BTS pelas rotas de Pesquisa/Biblioteca, mas estava excessivamente técnica, com `XW`, data ISO e repetidas mensagens de ausência de vínculo. O diagnóstico `bitchord-diagnostics-20261107-195615.txt` contém três `result=no_link` e nenhuma evidência de enriquecimento confirmado nesta janela. Isso não prova inexistência dessas edições no Discogs.

**Mudanças deste corte:**

- Rótulos legíveis separados por `:`: `Gravadora`, `Data`, `Estado da publicação`, `País` e, quando comprovado, `Tipo segundo Discogs`. País `XW` vira `Mundial`, `KR` vira `Coreia do Sul`; nomes ingleses de países Discogs são localizados. Datas completas viram `26 de outubro de 2026`; `Digital Media` vira `Mídia digital`. Data precisa substitui ano genérico, sem duplicação.
- `NoLink` continua observado em diagnósticos mas não cria um aviso longo no rodapé. Metadados já comprovados por MusicBrainz permanecem; Discogs não ganha falsa atribuição.
- Configurações → Avançado → `Exibir metadados dos álbuns` (ativado inicialmente) controla somente a visibilidade da seção editorial em todos os contextos, preservando a data/contagem básicas, downloads, biblioteca, ratings, pins e o provedor de dados. Verificar a preferência após fechar/reabrir o aplicativo. Testar pesquisa nas configurações e tradução das chaves nos idiomas Android.
- Apenas quando houver uma edição Discogs VERIFIED, descrições explícitas `Album`, `EP`, `Single`, `Mini-Album`, `Maxi-Single` ou `Mixtape` podem gerar `Tipo segundo Discogs`, com link de atribuição na mesma seção. `LP` isolado não é prova de álbum; classificações contraditórias permanecem desconhecidas. Este fato não substitui a classificação canônica do MusicBrainz.
- Validar álbum online, Biblioteca local e álbum aninhado dentro de artista, com e sem rede; rolar até o rodapé, alternar o switch com a tela aberta, navegar rapidamente e conferir ausência de sobreposição de edição/estado. Não esperar fonte Discogs onde só existe `NoLink`.

**Próxima investigação, não incluída neste APK:** ampliar descoberta quando falta relação MusicBrainz→Discogs, pois pesquisa na base Discogs exige autenticação. Qualquer modo opcional deve armazenar credencial localmente de modo seguro, não embutir token no APK, limitar requisições e validar os candidatos da edição antes de exibir informações. Nem título coincidente nem número de faixas são critérios suficientes.

**Critério:** workflow de smoke e Android CI em SUCCESS no mesmo SHA; depois teste físico Galaxy A54. Não mesclar a #211 ou a #209.

## Solicitação de smoke final — corpus Discogs 30/30 + proteção repackage (12/11/2026)

**Solicitação explícita do proprietário:** gerar APK `devDebug` de instalação para validar em Galaxy A54 as alterações finais da PR #211, mantendo a branch Draft, sem merge, sem release de produção e sem alterar a PR #209. Corte anterior ao disparo: `1410ee9511c136545f46ce790d8f40a8c696afac`. O APK deve conter o próprio commit desta solicitação, registrado no artefato com SHA-256.

### Checklist físico direcionado

- [ ] Instalar e abrir sem crash; Home, Pesquisa, Biblioteca e detalhes de artista/álbum funcionam.
- [ ] Em artista ENHYPEN, abrir *ROMANCE : UNTOLD* e localizar a edição/repackage *ROMANCE : UNTOLD -daydream-* já presente no MusicBrainz: **não** criar uma segunda obra fictícia como `Discogs-only`; o título da edição difere do título do ReleaseGroup. Não assumir que os dois produtos físicos são a mesma prensagem.
- [ ] Conferir ao menos dois artistas adicionais, por exemplo LE SSERAFIM (*EASY* EP vs *EASY (English ver.)* Single) e IVE (*CRUSH* Single vs *ALIVE* EP). Não fundir obras distintas nem sugerir reedições já existentes como inéditas.
- [ ] Checar artist shelves `Álbuns / EPs / Singles`: somente itens de áudio, crédito do artista correto e sem itens novos suspeitos por nome, tradução ou edição.
- [ ] Abrir álbum online via Pesquisa, pelo artista e pela Biblioteca; `Sobre esta edição` preserva gravadora, data, país e estado de publicação do MusicBrainz. Campos Discogs e `Data provided by Discogs.` somente com edição realmente verificada.
- [ ] Configurações → Avançado → `Exibir metadados dos álbuns`: ocultar e restaurar seção em várias rotas, sem esconder faixas, reprodução, avaliações, fixação ou biblioteca.
- [ ] Repetir com internet desligada e depois restabelecer rede; não perder conteúdo MusicBrainz/local, não inventar link Discogs ou edição. Navegar entre dois álbuns rapidamente, sem detalhes de um vazarem no outro.
- [ ] Se houver lista muito grande de edições MusicBrainz (>300) ou timeout, é aceitável **não** mostrar sugestão complementar; não é aceitável exibir falso inédito ou perder discografia principal. Nenhuma recuperação C foi comprovada em corpus, logo ausência de card Discogs-only não implica falha.
- [ ] Se falhar, guardar capturas, artista, título, caminho/rota, tipo incorreto, momento da execução e diagnóstico exportado, sem dados privados.

### Gates do APK

Workflow `Discogs 211 Android native smoke APK` tem `:app:testDevDebugUnitTest`, `:shared:jvmTest` de playback e `:app:assembleDevDebug` como pré-condições para publicar o artefato; também anexa SHA-256 e commit exato. Corpus editorial [30/30 aprovado](https://github.com/jssantogit/BitChord/actions/runs/43200460173) e [Android JVM Discogs focused aprovado](https://github.com/jssantogit/BitChord/actions/runs/43199315323) no corte de código anterior à solicitação. Se teste completo falhar, reportar bloqueio em vez de chamar APK de gerado. Nenhum merge automático.

## Correção A54 — HOT duplicado em LE SSERAFIM, novo APK requerido (12/11/2026)

**Relato físico do proprietário:** APK Discogs #211 anterior instalado com navegação, fonte canônica, rodapé, opções avançadas, rotas e modo offline funcionando. Nas capturas comparativas, a página de artista LE SSERAFIM do BitChord mostra duas entradas `HOT` classificadas `Single` e `EP`; Apple Music de referência não exibe o card redundantemente derivado da faixa-título como um segundo minialbum.

**Causa por fonte:** MusicBrainz mantém `HOT` Single `44adb7df-66bd-4ebe-a43a-3ea45a93529d` explicitamente vinculado como *single/EP taken from* ao `HOT` EP principal `50bcea80-692f-4e36-a7a5-825a3233cb13`. Não é bug de título Discogs nem justificativa para alterar o tipo MusicBrainz em Room.

**Correção no código da PR:** projeção somente da discografia tipada do artista remove o card do Single derivado **apenas** quando ambos os MBIDs corretos aparecem no grid, com títulos e artista iguais e tipos Single/EP. Preserva busca canônica, release details, versões `HOT (English ver.)` Single/EP, `HOT (KAZUHA ver.)` Remix e prateleiras independentes (ex.: Para descobrir), sem tocar no MBID, tracklist ou artistas creditados. Paginação parcial não oculta o Single se o EP pai ainda não estiver visível. Testes novos `DiscogsEditorialArtistShelvesTest.kt` cobrem os controles e evitarão regressões.

### Checklist exclusivo deste APK

1. Abrir LE SSERAFIM → Singles e EPs. Quando ambos os grupos HOT forem carregados, deve existir **um único card principal `HOT`**, marcado **EP**, e não outro card `HOT` marcado Single.
2. Confirmar `HOT (English ver.) - Single` e `HOT (English ver.) - EP`, caso ambos estejam no catálogo, como produtos separados, sem suprimir nenhuma das variantes.
3. Confirmar que `HOT (KAZUHA ver.)` e demais versões/remixes continuam com sua própria identidade e classificação.
4. Abrir o EP HOT, voltar, rolar e reabrir o artista: nenhuma duplicação transitória depois da carga dos grupos. Pesquisa direta por `HOT` continua com todas as identidades MusicBrainz, sem exclusão global.
5. Repetir um exemplo anterior (ENHYPEN `ROMANCE : UNTOLD -daydream-`) e verificar que a proteção contra falsos `Discogs-only` continua funcionando.
6. Se algum card estiver ausente ou duplicado, enviar captura, nome da seção e, quando disponível, a URL/MBID que o card abre.

Regerar apenas o APK `devDebug` da PR #211, **não** publicar release, **não** fazer merge e não mudar #209/main. O workflow continua exigindo `:app:testDevDebugUnitTest`, regressão `:shared:jvmTest` e montagem do APK com SHA e checksum. O smoke anterior não valida este patch; somente novo APK e observação A54.

### Repetição obrigatória por correção de compilação do fixture (12/11/2026)

O gate focused [#43215915310](https://github.com/jssantogit/BitChord/actions/runs/43215915310) chegou à compilação de testes e identificou exclusivamente a falha de nulabilidade em `DiscogsEditorialArtistShelvesTest.kt` (linha que usava `row.releaseKind: String?` no subtítulo `ShelfItem: String`). O fixture foi corrigido em [`178d0e01`](https://github.com/jssantogit/BitChord/commit/178d0e01c9ef6625fb72f394d4a5098b4a9837b7), com `row.releaseKind.orEmpty()`, sem mudança no código de produto nem redução de cobertura. Solicitar APK **novo** incluindo o fix e refazer todos os gates no SHA publicado; o run anterior não comprova a versão final.

## A54 — revalidação de duplicatas HOT e BOOMPALA (12/11/2026)

**Relato após o smoke anterior aprovado #43216920985:** capturas físicas do A54 continuam com **HOT** lado a lado como `Single` e `EP`; três `BOOMPALA` lado a lado como `Single · Remix` sem créditos diferenciadores. Os cards têm fundo roxo (BitChord); a referência anterior preta era do Apple Music. O usuário esclareceu que versões legítimas distintas não devem ser ocultadas sem evidência, mas o nome deve indicar as diferenças. Não atribuir erro ao Discogs quando as três ReleaseGroups já existem no MusicBrainz.

**Fonte MB real:**
- `HOT` Single RG `44adb7df-66bd-4ebe-a43a-3ea45a93529d` tem relação `single/EP taken from` ao EP `50bcea80-692f-4e36-a7a5-825a3233cb13`. São identidades diferentes; suprimir o card secundário somente na discografia tipada quando o pai aparece, sem excluir da busca / detalhes / biblioteca e sem aplicar tipo Discogs.
- `BOOMPALA` com SANTOS BRAVOS RG `d5b6c2ec-0f4f-4f8f-83f5-3011df277964`, GURU RANDHAWA `b40e8dd7-05c9-4b71-8439-03a213647e5a` e Ayra Starr `8c524416-07c2-42a6-bae1-2bf85bf612fa` compartilham o título, mas possuem créditos MusicBrainz diferentes. Identificá-los na interface como `BOOMPALA (feat. SANTOS BRAVOS)`, `BOOMPALA (feat. GURU RANDHAWA)` e `BOOMPALA (feat. Ayra Starr)`, somente quando o ArtistCredit tipado confirma; não inventar nome a partir do Discogs ou da tracklist.

**Causa do falso sucesso anterior:** a UI em `DetailScreen.kt` usava `activeShelf: HomeShelf` como snapshot do momento do clique em Ver tudo. O `editorialArtistSections` reagia aos grupos MB e à projeção, mas o grid expandido `ArtistShelfGridPage(shelf = targetShelf)` continuava no snapshot anterior. Além disso, a regra `HOT` exigia simultaneamente a chegada de MusicBrainz rows mesmo quando ambos os cards nativos com MBIDs tipados já estavam presentes; o retorno prematuro deixava a duplicação. A nova projeção lê a estante expandida atual em vez do snapshot e permite a relação MBID exata nas cartas nativas. Os testes antigos verificavam só o helper, não o fluxo de grid expandido.

**Novos gates:** regressões Kotlin `DiscogsEditorialArtistShelvesTest.kt`: grid expandido obsoleto, três créditos de remixes BOOMPALA, coexistência de dois HOT com rows ainda incompletas, nenhuma equivalência sem fonte, sem perda de English Single/EP, KAZUHA/remixes, seleção independente e identidades originais preservadas. Compilar `:app:testDevDebugUnitTest` e `:shared:jvmTest`, montar `:app:assembleDevDebug`, publicar SHA256/commit em ZIP do APK apenas com CI verde.

### Smoke físico novo
- [ ] LE SSERAFIM → Singles e EPs → Ver tudo, **HOT EP principal aparece sem HOT Single genérico redundante** após conclusão dos grupos.
- [ ] `HOT (English ver.)` Single e EP permanecem distintos quando existentes; remixes/versões de integrantes continuam.
- [ ] Nas três cartas de `BOOMPALA`, o colaborador aparece no **título**, distinguindo SANTOS BRAVOS, GURU RANDHAWA e Ayra Starr; nenhum MBID ou card desaparece por coincidência textual.
- [ ] Reabrir Ver tudo durante e após paginação; evitar o retorno de duplicatas por snapshot antigo.
- [ ] Pesquisa direta de HOT e BOOMPALA mantém entidades MusicBrainz separadas; sem alterações de playback, avaliações ou metadados Discogs verificados.
- [ ] Se ficar sem capa, registrar como problema visual separado; não associar capa vazia a prova de identidade Discogs.

**Não fazer merge automático.** Manter PR #211 Draft e #209/main intactos; esse APK é novo e ainda exige validação física posterior.


## 13/11/2026 — reteste de rolagem contínua na discografia expandida

**Relato físico A54:** em artista com muitos álbuns, "Ver tudo" atualiza/recomeça repetidas vezes e a posição volta ao topo durante a hidratação de capas/páginas. O diagnóstico de 13/11 registra 20 pausas recuperadas da UI, mas não contém offsets do LazyGrid; o mecanismo compatível foi localizado no `AnimatedContent(targetState = displayedActiveShelf)`, que mudava sua identidade toda vez que `HomeShelf` mudava.

**Correção de código:** [`345178a7`](https://github.com/jssantogit/BitChord/commit/345178a7f8fe90b3d30d71a9213164d468b53c18) define `contentKey` por `(artistBrowseId, sectionTitle)` e mantém o `rememberLazyGridState()` do mesmo destino através de atualizações de título/crédito/capa/paginação. A regressão `expandedShelfTransitionKeyIsStableAcrossProgressiveCoverAndPaginationUpdates` exige mesma chave com mudanças de cards, e chave distinta entre artistas/seções.

**Este update solicita novo APK smoke** via workflow existente após gate Android completo + shared playback + assemble, no SHA final produzido pelo commit deste documento. Não declarar aceite do problema sem testar no Galaxy A54.

### Roteiro do reteste
- [ ] Artista com muitos álbuns → Álbuns → Ver tudo; deslizar para a metade/fim e aguardar 30–60 segundos enquanto novas capas e grupos chegam; **o offset deve permanecer**.
- [ ] Repetir em Singles e EPs e em outro artista; capas podem ser preenchidas sem crossfade do grid inteiro nem salto ao topo.
- [ ] Voltar e reabrir Ver tudo; a navegação deve abrir na seção correta, sem herdar a rolagem de outro artista.
- [ ] Checar HOT/BOOMPALA e busca conforme os critérios anteriores; nenhuma identidade deve ser removida.

**Dependências:** #211 continua Draft; #215 iniciou **somente** a camada independente de Discogs e não integra este APK. Não fazer merge de #209 ou #215 como efeito colateral.
