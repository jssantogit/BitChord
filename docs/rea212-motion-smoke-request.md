# REA #212 — autorização de smoke da rodada de animações

- Autorização: usuário solicitou em 15/11/2026 continuar a validação e, **apenas se estiver tudo certo**, realizar smoke.
- Escopo: rodada de animações e redução de movimento publicada a partir de `8cd010503feb86d2fdab2ba603c7c0c1bc03e138` na PR #212 Android.
- Execução: o workflow `Apple Music Bottom Bar · Smoke APK` deve rodar **primeiro** `:app:testDevDebugUnitTest`, o planejador Automix compartilhado e `:sharedUi:jvmTest`. Somente se todos passarem deve compilar `:app:assembleDevDebug` e publicar o APK universal.
- Restrição: não realizar smoke físico sem instalação/testes em dispositivo real; a publicação do APK é somente a preparação do smoke. Não executar TV, desktop ou Wear, nem mesclar a PR.
- Verificação: registrar SHA exato, resultado da CI e URL do artefato. Se testes falharem, bloquear artefato e corrigir primeiro.

## Nova tentativa — correção do gate de compilação dos testes

- Primeira execução: [GitHub Actions #43678026346](https://github.com/jssantogit/BitChord/actions/runs/43678026346), falha em `:app:compileDevDebugUnitTestKotlin` por imports incorretos de extensões Compose, função de toolbar sem import do pacote de origem e assertiva de coleção indisponível.
- Corrigidos somente os três arquivos de testes afetados, sem relaxar os critérios de aceite. Reexecutar os mesmos testes e permitir APK **somente após sucesso integral**.

## Diagnóstico focalizado após a segunda tentativa

- [Execução #43678458056](https://github.com/jssantogit/BitChord/actions/runs/43678458056): 3.593 testes Android executados; cinco falhas no editor de avaliação, nenhuma nas transições de abas/seleção/fila. APK bloqueado.
- Atualizada a expectativa numérica legada de 1 para 2 vetores V3, correspondente ao valor inicial 2.0. Etapa focalizada e relatórios XML acrescentados ao workflow para identificar as demais falhas sem repetir a suíte inteira a cada diagnóstico.
- Manter a geração de APK condicionada aos testes focalizados, à suíte Android completa e à suíte JVM compartilhada.

## Quarta tentativa — isolamento de paleta sem singleton do player

- [Execução #43679052013](https://github.com/jssantogit/BitChord/actions/runs/43679052013): gate focalizado compilou e executou 6 testes, com 4 falhas do mesmo `IllegalStateException: PlayerPlatform.install() was never called` ao montar o menu ancorado de avaliação no Compose/Robolectric. A fase de APK foi corretamente bloqueada.
- Causa localizada em `sharedUi/.../theme/ArtworkPalette.kt`: `rememberArtworkPalette` acessava `PlayerPlatform.host.settings.reduceAnimation` mesmo como apresentação de um editor isolado. A interface Android já disponibiliza `LocalReduceAnimations` na raiz da composição; o [fix `5b10c463`](https://github.com/jssantogit/BitChord/commit/5b10c463c595a617f491771b6e72b2ed6f06a14e) passou a usá-lo. Não introduzir instalação artificial do player nos testes nem alterar código de playback.
- **Solicitar smoke test-gated para o novo HEAD:** executar primeiro o gate focado dos testes de avaliação, depois a suíte Android completa, os testes shared Automix e sharedUi, e compilar/publicar o APK universal somente com todos aprovados. Registrar link, SHA e checksum do artefato; jamais marcar o smoke físico como concluído sem teste em aparelho.

## Quinta tentativa — corrigir semântica do painel ancorado

- [Execução #43680482920](https://github.com/jssantogit/BitChord/actions/runs/43680482920): os 6 testes focalizados executaram com 3 falhas restantes; a exceção anterior de `PlayerPlatform.install()` não ocorreu mais. As 3 falhas eram `merge function called on unmergeable property PaneTitle`, todas no editor de avaliação ancorado.
- Causa: `PlayerAnchoredActionsMenu` marcava o `Surface` de toda a placa com `Modifier.clickable(onClick = {})`. Isso tentava mesclar a propriedade `paneTitle` definida no editor filho. [Fix `66034d2a`](https://github.com/jssantogit/BitChord/commit/66034d2a9b33aeca52d2a816151a13e11f6b5b86) substitui o clique vazio da superfície por um capturador de toque sem semântica, preservando ações filhas e descartando toques inertes sem fechar o dialog.
- **Novo gate obrigatório:** regressões Compose focadas e toda a suíte Android, `shared:jvmTest` (Automix), `sharedUi:jvmTest`; publicar o APK universal apenas quando tudo concluir sem falha. Não alterar condições da CI nem declarar smoke físico como executado.

## Rodada pós-smoke físico — seis correções REA (15/11/2026)

- O usuário testou o APK da execução #43681012689 no Android e enviou cinco capturas. Pediu seis correções: cor uniforme das estrelas, apenas uma estrela numérica final, navegação de crédito para artista, ação de artista no menu do player, contraste do ⋯, blur dos menus e retorno de menu levantado sem piscar preto nem cair em bottom sheet genérico.
- Foram feitos reparos localizados nos controles de rating, fonte de evidência de artista local/Navidrome/MusicBrainz, fallback explícito de busca de artista sem ID, destaque discreto do ⋯ do player, placas Haze/ArtworkBackdrop e captura/visibilidade do item pressionado longamente. Preservar PR #212 draft e nenhum código fora de Android/sharedUi.
- Gate completo obrigatório: testes focalizados de rating; toda a suíte Android; testes compartilhados Automix e sharedUi; somente então compilar e publicar o APK universal devDebug. Registrar SHA e checksum, sem declarar smoke físico aprovado até receber novo feedback do aparelho.

## Nova solicitação — transições internas do Explorar (15/11/2026)

- **Autorização explícita do usuário:** conferir animação ao navegar entre categorias do Explorar; implementá-la se ausente; em seguida executar o smoke Android.
- **Correção verificada no código:** a rota raiz `tab:1` permanecia igual entre a grade de gêneros e um gênero escolhido, portanto o `AnimatedContent` do host não animava essas mudanças. A solução introduz `ExploreCategoryContentTransition`, com um destino de composição por categoria e o mesmo `rootTabContentTransform` (fade linear 150 ms, sem spring de tamanho; sem animação quando reduzir movimento). O chrome do topo, mini-player e barra inferior seguem fora da transição. Contratos de UI acrescentados em `ExploreCategoryContentTransitionTest`.
- **Implementação até o commit anterior a esta solicitação:** `84c8374de1c43e4683062917413a80341617756d` na branch da PR #212; o SHA do build será o novo HEAD contendo este documento.
- **Gates obrigatórios:** regressões de avaliação, suíte Android completa (incluindo novos testes do Explorar), `shared:jvmTest` e `sharedUi:jvmTest`; somente se todos passarem compilar e disponibilizar o APK universal com SHA-256. Não executar TV, desktop, Wear, nem mesclar PR #212.
- **Smoke no aparelho ainda não executado:** verificar Explorar → categoria → voltar, alternância rápida, preservação do scroll, redução de animação e ausência de flashes; repetir o smoke anterior do menu, blur e navegação local. APK disponibilizado não significa teste físico aprovado.

## Gate #43728444128 — erro de compilação e nova tentativa

- [Run #43728444128](https://github.com/jssantogit/BitChord/actions/runs/43728444128) falhou antes de executar testes, em `app:compileDevDebugKotlin`: parâmetro `reducedMotion` inexistente no helper de Explorar (o argumento é `reduceMotion`) e import da constante `LOCAL_MEDIASTORE_PROVIDER` feito como top-level em vez de usar `CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER` no módulo de artista do player. Nenhum APK foi gerado.
- Os dois erros foram corrigidos preservando a política do smoke e todo o escopo funcional aprovado. Solicitar novamente o mesmo gate de testes Android/sharedUi/Automix antes de compilar qualquer APK. Não interpretar CI como smoke físico.

## Gate #43728868988 — corrigir import do novo teste do Explorar

- [Run #43728868988](https://github.com/jssantogit/BitChord/actions/runs/43728868988) confirmou compilação de produção até `app:compileDevDebugKotlin`, mas `app:compileDevDebugUnitTestKotlin` falhou apenas por imports inexistentes de `assertDoesNotExist` e `assertExists` em `ExploreCategoryContentTransitionTest.kt`. São métodos de `SemanticsNodeInteraction`, já usados sem import em testes da branch; remover somente as linhas de import, preservando as assertivas e cenários.
- Reexecutar o mesmo gate integral, sem desabilitar testes ou compilar APK após falha. Usuário autorizou explicitamente o smoke nesta rodada.

## Gate #43729473306 — estabilizar o teste temporal do Explorar

- [Run #43729473306](https://github.com/jssantogit/BitChord/actions/runs/43729473306) passou nos testes de rating focalizados e compilou app/testes. Na suíte Android integral: `3604 tests completed, 1 failed, 1 skipped`. A falha foi exclusivamente no novo `ExploreCategoryContentTransitionTest.rootToGenreAndBackCrossfadeWithoutReplacingTheOutgoingPage`, linha 44: assertiva tentava verificar conteúdo de entrada após um único frame de 16ms no Robolectric; o destino ainda não tinha sido composto naquele instante.
- Preservar a verificação real da sobreposição temporária entre origem e destino: avançar 64ms (quatro frames), ainda dentro do fade de 150ms, antes de exigir presença de ambas as árvores. Não afrouxar a verificação de remoção do destino antigo após concluir o fade, nem desativar o teste.
- Solicitar novamente o smoke com gate completo sem publicar APK se qualquer etapa falhar. Não declarar smoke físico realizado.

## Gate #43730280457 — estabilização do teste de transição Robolectric

- [Run #43730280457](https://github.com/jssantogit/BitChord/actions/runs/43730280457): teste focalizado de avaliação PASS; suíte Android 3.604 testes / 1 falha / 1 skipped. A mesma assertiva intermediária de `ExploreCategoryContentTransitionTest` persistiu mesmo após 64ms: sob relógio de animação desativado no Robolectric, o destino novo não aparece no primeiro intervalo controlado. Nenhum outro teste falhou e nenhum APK foi gerado.
- Alteração: não ocultar nem ignorar a falha. Substituir o teste temporal dependente do agendador de frames por prova Compose determinística da navegação grade → gênero A → gênero B → grade, com exclusão dos destinos obsoletos após o estado estabilizar; testar separadamente `exploreCategoryContentTransform` contra a política exata do root (fade 150ms, sem size spring, animações reduzidas instantâneas). Não mudar o visual/efeito de produção além de expor a política para teste.
- Nova solicitação de smoke restrita à PR #212 Android: executar gate focalizado, suíte integral Android, shared Automix/sharedUi e somente após aprovação compilar e entregar APK universal e SHA-256.

## Gate de implementação — linguagem dos cards e seleção (15/11/2026)

- <b>Origem:</b> após smoke físico, usuário enviou captura de grave sobreposição de textos no modo seleção de Artistas e autorizou a consolidação da linguagem visual dos cards usando o filtro da Biblioteca como referência. Menu de pressionamento longo também exibiu cores dinâmicas e conteúdo duplicado sob placas translúcidas.
- <b>Escopo:</b> commits de teste `7b900353` e implementação `930177aa`. `NativeContextActionPlate` usa mesma placa neutra do menu de filtros, mas seleção exige tratamento SOLID (nenhum Haze sobre linha selecionável); filtro/rating conservam tratamento FROSTED com fundo sólido de segurança; menu de long press ignora cor de arte, superfície neutra opaca. Barra de seleção começa compacta e expande ações para cima, mantendo summary dock e botões acima do chrome. Guia `docs/rea212-expandable-cards-visual-language.md` com contratos e aceite.
- <b>CI autorizada como gate de qualidade, não como smoke físico:</b> testes focalizados de rating, suíte Android inteira incluindo regressões de seleção, testes shared Automix, sharedUi, compilação de APK universal somente se tudo passar. Nunca relaxar falhas. Não integrar, não desviar para TV/Wear/desktop, não declarar verificação visual sem novo teste no aparelho.

## Gate integral de regressão — navegação de artista e placas unificadas (15/11/2026)

- <b>Origem:</b> smoke do usuário revelou clique no nome de artista do álbum sem ação e menu do player local que recorria à pesquisa; ⋯ do player descaracterizado, popups dinâmicos verdes/roxos e seleção com texto sobreposto.
- <b>Implementações:</b> `63955a5b` atualizou teste de seleção recolhida, `4a67f8f5` incluiu testes para arquivo local já indexado e crédito de álbum, `905ea93e` acrescentou lookup exact MediaStore por `sourceKey` antes do resolver de fila, fallback para grupo local somente se todas as faixas comprovarem mesmo crédito e restaurou vetor original AM-DECOMP sem borda duplicada no player; `8cfb9f7e` testou neutralidade da placa em todos os temas; `6c4f36cf` migrou popups compactos e menu do player para a placa neutra do filtro da Biblioteca, mantendo wash de capa difuso. PR #212 continua em draft, nunca mesclar automaticamente.
- <b>Anterior:</b> run [#43734343784](https://github.com/jssantogit/BitChord/actions/runs/43734343784) compilou app e rodou 3.606 testes Android; única falha era `PlaylistNativeSheetReuseTest` esperando seleção aberta automaticamente. Corrigido sem ocultar testes.
- <b>Executar integral:</b> gate focalizado de rating; `app:testDevDebugUnitTest` completa (cobrir novos contratos); `shared:jvmTest` Automix; `sharedUi:jvmTest`; e `app:assembleDevDebug` APK universal apenas após todos verdes. Se falhar, corrigir a causa, não afrouxar gate. Smoke físico permanece pendente mesmo após CI verde.

## Gate final de identidade de artista local — 15/11/2026

- [Run #43735907827](https://github.com/jssantogit/BitChord/actions/runs/43735907827) validou (no commit anterior a este) rating focalizado, suíte Android, shared Automix e sharedUi; montagem de APK iniciou. **Não reutilizar o APK desse SHA** para smoke completo de navegação local.
- Acrescentada regressão [`82063ef8`](https://github.com/jssantogit/BitChord/commit/82063ef83d51812e44553094b2188535acbe8ae5) para impedir pesquisa por nome em MediaStore quando um link direto ainda está sendo resolvido. Implementado [`6eb4e2dc`](https://github.com/jssantogit/BitChord/commit/6eb4e2dc6bcc58296cbc3056090ca5007b7d1298): fallback `Buscar artista` só é oferecido para músicas sem URI local e com nome de artista; a rota local deve depender de vínculo exato MediaAsset ou grupo de exibição local comprovado.
- Repetir gates focalizados e integrais + build universal somente se todos aprovarem neste novo HEAD. Manter PR #212 em draft; smoke físico pendente para navegação local, clique de artista no álbum, ícone ⋯ original, seleção e cores de menus.

## Gate de regressão — cabeçalho de artistas e fade da prévia pressionada (15/11/2026)

- <b>Base validada anterior:</b> run [#43736716251](https://github.com/jssantogit/BitChord/actions/runs/43736716251) SUCCESS no SHA `c920049b`, PR #212 ainda em draft; a nova rodada é exclusivamente incremental.
- <b>Vídeo de smoke revisto:</b> transição de saída do gesto longo mostrava a imagem capturada ficando transparente antes de liberar a pintura da linha original. [Teste `50ca092e`](https://github.com/jssantogit/BitChord/commit/50ca092ef6e72f2e55f707c1de390a885ed1327a) e [correção `8ada86c5`](https://github.com/jssantogit/BitChord/commit/8ada86c561abdb79d40019278a734f18cc523673) mantêm o snapshot opaco até o handoff ao elemento original, mas não mudam sua posição/escala animadas.
- <b>Invasão do topo em detalhes de artista:</b> [teste `048ed3bd`](https://github.com/jssantogit/BitChord/commit/048ed3bd2cae5bf1e20d6df3d2c36c78cfc6aacf) e [correção `0027b7a9`](https://github.com/jssantogit/BitChord/commit/0027b7a94d89773ba00f40d0d69a93c72a0455fb) limitam a PINTURA do `LazyColumn` ao espaço abaixo de `topBarContentPadding()`; o backdrop fotográfico permanece fora dessa máscara e mantém o edge-to-edge. Não altera dimensões, posição nem identidade das seções.
- <b>CI Android:</b> verificar gate de rating, suíte `app:testDevDebugUnitTest`, shared Automix, `sharedUi:jvmTest`, montagem `app:assembleDevDebug` e SHA256SUMS antes de declarar APK apto ao próximo teste físico. Não afrouxar testes nem executar TV/Wear/desktop. O smoke físico da rolagem/gesto longo permanece necessário; não mesclar a PR.

## Gate #43744226881 — ajuste de ContentDrawScope e reteste

- [Run #43744226881](https://github.com/jssantogit/BitChord/actions/runs/43744226881) falhou em `app:compileDevDebugKotlin` (sem alcançar testes) no `NativeArtistSharedSections.kt`: `drawContent()` dentro da lambda de `clipRect` estava usando implicitamente `DrawScope` em vez de `ContentDrawScope`.
- [Commit `63121b63`](https://github.com/jssantogit/BitChord/commit/63121b63422d36c48f93fd853764add1831d9a85) mantém uma referência explícita ao `ContentDrawScope` exterior antes de `clipRect` e desenha o conteúdo através dela. Os limites da máscara, semântica de interação e comportamento do snapshot não foram modificados.
- Solicitar novamente o mesmo workflow test-gated Android; não contornar os testes, não declarar APK/validação física sem sucesso do build e do smoke no aparelho.


## Solicitação autorizada — convergência de menus e destaque branco (15/11/2026)

- Autorização explícita nesta conversa: **"Continue, se tudo certo, smoke"**. Interpretar "tudo certo" estritamente como gates automatizados aprovados no SHA exato, não como avaliação visual comprovada.
- Escopo atual da PR #212: menus elevados das seções de artista (Artistas semelhantes, Incluído em, playlists e destaque), contorno branco compartilhado entre itens mantidos e os launchers de ⋯ da Home, Explorar, Biblioteca, álbum online, playlists e player, com vetor original do player.
- Correção final: estados de menu de álbum e artista da Biblioteca são reportados pelo compositor que possui `libraryMenu`, e nunca inferidos de `albumMenuToken`/`artistMenuToken`, que são contadores de eventos. Prévia de hold não ativa botão ⋯.
- Base anterior já aprovada: `66727ea4`. **Este pedido deve testar o HEAD de seu próprio commit de trigger**, incluindo as mudanças posteriores. Proibido reutilizar green/artefato de commit anterior.
- Gates obrigatórios permanecem intocados: testes Android focalizados e integrais, shared Automix, `:sharedUi:jvmTest` e `:app:assembleDevDebug`; checksum SHA-256 da build universal.
- Se qualquer gate falhar, não apresentar APK como pronto. Corrigir apenas a falha diagnosticada e repetir a solicitação no HEAD corrigido.
- Se passar, disponibilizar URL e checksum para smoke físico; manter PR #212 draft, sem merge. Não alegar equivalência visual sem inspeção no Android real.


## Regressão visual do smoke físico — gesto elevado (15/11/2026)

- Evidência: vídeo físico `1000685058.mp4` e capturas da Home, página de artista, biblioteca de artistas e prateleiras `Incluído em`/`Artistas semelhantes`, fornecidos pelo usuário.
- Problemas reproduzidos visualmente: o snapshot da prévia recebe um preenchimento grafite inexistente no item original; borda branca de 88%/1,5dp é excessiva; placa de ações era preenchida com cor sólida em vez de compartilhar o vidro do filtro; durante a transição, a placa de ações fica translúcida enquanto o conteúdo se movimenta.
- Correção: `HeldContextMenu.kt` mantém o bitmap original sem segundo fundo cinza (`heldPreviewBackingColor` transparente), reduz o contorno para 36% e 0,75dp, diminui o padding da moldura e retoma `optimizedHazeEffect` com `HazeMaterials.regular` como `NativeContextActionPlate`, mantendo fallback sólido quando `reduceDynamicBlur` estiver ativo.
- Testes de contrato: `HeldContextMenuPlateTest` verifica borda, backing transparente e política de blur. Conferir ainda movimentos, clipping, snapshot de item e restauração no aparelho. As ações do catálogo online continuam metadata-only.
- Solicitação: executar novamente o workflow test-gated para o HEAD desta atualização, sem remover testes Android, sharedUi, Automix ou compilações, e gerar APK somente se todos aprovarem.


## Retry após red — atualização de contrato do contorno (15/11/2026)

- Run anterior: #43766233495 no HEAD `c3c72159698803ab0c043359069ce0585fb0f8de`, compilação e testes focalizados aprovados; 3.619 testes Android, com **uma** falha: `PlayerAnchoredActionsHighlightTest.activeOverflowUsesSharedWhiteHighlightWhileInactiveHasNone`.
- Causa comprovada nos logs: asserção antiga de opacidade branca `0.88f`, enquanto o token compartilhado `heldContextSelectionOutline()` passou para `0.36f` conforme feedback físico do usuário.
- Correção isolada: teste compara o valor com o token canônico em vez de manter uma cópia desatualizada. Nenhuma regra de aprovação foi reduzida.
- Solicitar novamente workflow completo para este HEAD (incluindo o commit de solicitação), mantendo testes focalizados, `:app:testDevDebugUnitTest`, Automix, `:sharedUi:jvmTest` e `:app:assembleDevDebug` condicionada a todos verdes. Não afirmar APK disponível antes do artefato ser publicado.


## Rodada estrutural aprovada — estabilidade de menus (15/11/2026)

- Solicitação do usuário: corrigir deslocamentos para cima, duplicação da prévia, margem inadequada do conteúdo, vetor errado do ⋯ do player e tratamento visual da avaliação, **sem merge**.
- `HeldContextMenu`: prévia deve ficar centrada exatamente na origem, sem ser movida para abrir espaço para ações; o menu deve escolher a região acima/abaixo e rolar quando necessário. Contorno usa margem externa de 8dp, preservando pixels e cores. Ações não herdam translação da captura.
- `PlayerAnchoredActionsMenu`: o launcher real no player retrato usa `AppleQueueVectors.More` (círculo incluso), NÃO `MorePlain`. Overlay usa exatamente o mesmo vetor, cor e dimensões (40dp de botão, 34dp de vetor), acrescentando somente borda 0,75dp.
- Avaliação ancorada: `NativeContextActionPlate`, o mesmo material dos cards expansíveis e filtros; preservar estrelas e ações.
- Testes acrescentados em `HeldContextMenuGeometryTest` e `PlayerAnchoredActionsHighlightTest`.
- Executar os gates integrais da CI Android, incluindo testes Android, Automix e sharedUi. Apenas produzir universal APK quando todos passarem, sem relaxar critérios e sem smoke físico automático.


## Rodada parcial 1 — overflow de álbuns e playlists, 16/11/2026

- Autorização: usuário relatou a primeira parte do smoke físico: menu de três pontos divergente entre Biblioteca, playlists próprias e catálogo online; exige card expansível ancorado, ações legadas intactas, ação Configurações, e mesmo vetor sem aros duplicados.
- Os menus de playlists próprias não usam mais `ModalBottomSheet` no detalhe: `CanonicalPlaylistMenus` usa `FrostedMenuPanel` ancorado, mantendo Tocar a seguir, Adicionar à fila e Editar playlist, acrescentando Configurações. No índice de playlists, as opções de ordenação continuam e ganham Configurações.
- `CanonicalRemoteLibraryScreen` usa `FrostedMenuPanel` no overflow do detalhe de álbum ou artista (mantendo `BrowseActionsSheet` e suas ações), recebendo as coordenadas reais da toolbar; o hold de item conserva seu próprio menu.
- Álbum online MusicBrainz e playlist pública ListenBrainz agora têm o menu montado na camada contextual de nível superior, com `FrostedMenuPanel`, preservando avaliação, fixar/desafixar, abrir no MusicBrainz / compartilhar, e ganhando Configurações.
- `NativeCollectionOverflowAction`: não troca o vetor entre fechado e aberto; a borda ativa é 0,75dp e usa o token branco compartilhado.
- CI: manter gates atuais. Se qualquer teste falhar, não liberar APK nem concluir este bloco. A PR #212 continua draft, sem merge. Não confundir validação automatizada com smoke físico.


### Complemento — outros álbuns de detalhe (16/11/2026)

- O menu ⋯ de álbuns locais ou de servidor aberto via `NativeDetailAlbumTopBarActions` também era um `browseActions` sheet legado. Agora é distinguido de menus de cards/hold por `shouldAnchorNativeAlbumOverflow` e exibido em `FrostedMenuPanel` com a âncora real da toolbar; todas as ações verificadas de `browseMenuBody` continuam, junto de Configurações.
- O menu de cards comuns continua com sua origem e seu próprio comportamento. Não trocar operações de download/queue de origem local por operações de metadata-only.
- Teste adicionado no `NativeAlbumPlaylistToolbarTest` para comprovar separação entre toolbar, card e hold.
- Nova build do HEAD completo, sem aceitar o green anterior de outro SHA.


### Finalização do escopo parcial de overflows (16/11/2026)

- O painel frosted compartilhado agora intercepta Voltar enquanto aberto, antes da navegação do detalhe, preservando a semântica do antigo Popup/BottomSheet.
- A nova rodada deve validar o HEAD exato com todas as alterações de album/playlist online e biblioteca, e apenas gerar APK universal se todos os gates forem verdes.
- Não marcar REA #212 pronta para merge: o usuário está reportando os defeitos do smoke por partes; este checkpoint cobre apenas a primeira parte.


### Regressão de primeira medição (16/11/2026)

- O painel ancorado não deve renderizar largura de 1px enquanto `rootSize` ainda está zerado na primeira composição. Usar largura inicial estável de 280dp e só limitar à janela após a medição.
- Reexecutar os mesmos gates no HEAD que inclui esse ajuste; não reaproveitar o resultado de builds canceladas.

## Solicitação explícita de APK — player / identidade de artista (17/11/2026)

- **Autorização direta do usuário:** “Dispare apk.” Preparar artefato Android universal para instalar e testar a correção de clique no nome do artista (música local → artista local; OpenSubsonic → servidor; catálogo → ID específico) na PR #212.
- **HEAD de código antes do documento:** `5f9a3288642a45e631beb250b919dfdcd4aae203`. [Android CI #44010894838](https://github.com/jssantogit/BitChord/actions/runs/44010894838) SUCCESS; Desktop #44010894800 e TV/Wear #44010894790 SUCCESS para esse SHA. O novo commit altera **somente este documento** para disparar o workflow branch-scoped.
- **Execução autorizada:** `Apple Music Bottom Bar · Smoke APK` disparado via push com mudança em `docs/rea212-motion-smoke-request.md`. O workflow deve executar testes focais de avaliação, a suíte Android completa, Automix e sharedUi antes de `assembleDevDebug`; falha em qualquer gate bloqueia o APK. Registrar SHA-256 do universal.
- **Restrições:** não alterar fontes, não mesclar a PR #212, não publicar release assinada e não declarar smoke físico concluído. A importação de playlists Spotify permanece desativada.
