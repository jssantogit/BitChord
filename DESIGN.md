---
name: BitChord Android
description: Registro do sistema visual Android implementado na main; valores extraídos do código.
colors:
  dark-primary: "#FFFFFF"
  dark-onPrimary: "#000000"
  dark-background: "#000000"
  dark-onBackground: "#FFFFFF"
  dark-surface: "#0D0D0F"
  dark-onSurface: "#FFFFFF"
  dark-surfaceVariant: "#1C1C1E"
  dark-onSurfaceVariant: "#8E8E93"
  dark-outline: "#2C2C2E"
  light-primary: "#000000"
  light-onPrimary: "#FFFFFF"
  light-background: "#FFFFFF"
  light-onBackground: "#000000"
  light-surface: "#F7F7F9"
  light-onSurface: "#000000"
  light-surfaceVariant: "#F2F2F7"
  light-onSurfaceVariant: "#6E6E73"
  light-outline: "#E5E5EA"
  AccentRed: "#FA2D48"
typography:
  displayLarge:
    fontFamily: SF Pro Display
    fontSize: 34sp
    fontWeight: 800
    letterSpacing: -0.8sp
  headlineLarge:
    fontFamily: SF Pro Display
    fontSize: 30sp
    fontWeight: 800
    letterSpacing: -0.7sp
  headlineMedium:
    fontFamily: SF Pro Display
    fontSize: 22sp
    fontWeight: 700
    letterSpacing: -0.4sp
  titleLarge:
    fontFamily: SF Pro Display
    fontSize: 20sp
    fontWeight: 700
    letterSpacing: -0.3sp
  titleMedium:
    fontFamily: SF Pro Display
    fontSize: 16sp
    fontWeight: 600
    letterSpacing: -0.2sp
  bodyLarge:
    fontFamily: SF Pro Display
    fontSize: 16sp
    fontWeight: 400
  bodyMedium:
    fontFamily: SF Pro Display
    fontSize: 14sp
    fontWeight: 400
  labelMedium:
    fontFamily: SF Pro Display
    fontSize: 12sp
    fontWeight: 600
  labelSmall:
    fontFamily: SF Pro Display
    fontSize: 11sp
    fontWeight: 600
spacing:
  PAGE_GUTTER: 16dp
  BAR_GUTTER: 16dp
  ROW_DIVIDER_INSET: 84dp
  TRACK_NUMBER_WIDTH: 24dp
  NUMBERED_ROW_DIVIDER_INSET: 56dp
  LIBRARY_GRID_SPACING: 12dp
---

# Design System: BitChord Android

## Overview

Este documento descreve o Android de `jssantogit/BitChord`, usando como referência canônica a `main` no commit [`d32cb609500f4a9fbc9a10453f13a67fc03a4398`](https://github.com/jssantogit/BitChord/commit/d32cb609500f4a9fbc9a10453f13a67fc03a4398), consultada em **27 de outubro de 2026**. É um inventário da implementação, sem redesign, novos tokens ou decisões de identidade. Links relativos apontam para os arquivos dessa árvore; o commit fixa a evidência caso a `main` evolua.

A interface usa Jetpack Compose e Material 3, com `MaterialTheme` próprio, tipografia SF Pro Display, títulos pesados, conteúdo musical em listas e prateleiras, capas arredondadas e controles flutuantes. Superfícies foscas e Liquid Glass coexistem conforme configuração e versão do Android. Páginas de álbuns/artistas e o player também extraem cores das capas. Essas características são verificáveis em [Theme.kt][theme], [Common.kt][common], [HomeScreen.kt][home], [DetailScreen.kt][detail] e [MainActivity.kt][activity]. Não há uma metáfora de marca formal confirmada; não se cria uma aqui.

### Escopo e leitura da evidência

- **Confirmado:** declaração ou caminho ativo de código identificado nas fontes citadas. Confirmação estática não equivale a teste visual em aparelho.
- **Inferido:** consequência provável da composição, explicitamente identificada como tal; não deve virar contrato sem validação.
- **Lacuna:** documentação, medição ou validação não realizada/não localizada nesta auditoria. Não significa que o recurso seja inexistente em todo o repositório.

O escopo inclui `app/src/main/java/com/music/bitchord/ui/`, o host Android em [MainActivity.kt][activity], recursos Android e a parcela de `sharedUi/src/jvmSharedMain` consumida pelo Android, com seus `actual` em `sharedUi/src/androidMain`. A ligação dos source sets está em [sharedUi/build.gradle.kts](sharedUi/build.gradle.kts); os hosts concretos são [AndroidAppUiHost.kt](app/src/main/java/com/music/bitchord/ui/AndroidAppUiHost.kt) e [AndroidPlayerHost.kt](app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt). Estar em `sharedUi` não torna toda implementação específica de outra plataforma parte deste inventário. Web, Desktop, TV e Wear ficam fora; uma configuração de integração no telefone não documenta a interface do dispositivo externo.

O frontmatter usa os grupos aceitos pelo formato do Impeccable. `dark-*` e `light-*` são **endereços documentais das duas instâncias existentes de `ColorScheme`**, não identificadores novos de produção nem uma terceira paleta. Os nomes após o prefixo preservam os papéis Kotlin. `typography` conserva os nomes dos estilos definidos; `spacing` contém somente constantes compartilhadas confirmadas em [Common.kt][common]. Valores locais e valores calculados são descritos no corpo, sem promovê-los a tokens. Não há escala global própria de `rounded` ou variantes globais de `components` comprovadas para acrescentar ao YAML. Unidades nativas: **dp** para geometria, **sp** para texto; durações em ms, opacidades e fatores sem unidade. Nenhuma conversão para CSS/px deve ser inferida deste arquivo.

### Estado integrado e PRs draft

A auditoria leu exclusivamente o código do commit acima. Na consulta dos PRs abertos, estas propostas funcionais ainda estavam em draft:

| Proposta | Situação nesta documentação |
| --- | --- |
| [#183 — MusicBrainz discovery, release graph, artwork & safe playback](https://github.com/jssantogit/BitChord/pull/183) | Mudanças propostas não foram usadas como evidência do Android integrado. |
| [#182 — avaliações locais e Navidrome com estrelas e proveniência](https://github.com/jssantogit/BitChord/pull/182) | Não atribuir à `main` a apresentação com estrelas dessa proposta. Já existem controles numéricos de avaliação na `main`; ver Components. |
| [#181 — consulta e candidatos Soulseek](https://github.com/jssantogit/BitChord/pull/181) | Não incorporar a apresentação proposta de aquisição ao inventário integrado. |

Também havia drafts de infraestrutura/CI (#148–158, com os números presentes na listagem) e evidência de playback (#145); eles não são fonte visual. Esta listagem registra a separação de estados, não uma auditoria dos diffs dessas propostas. Não foi efetuado merge. O documento Google Stitch não foi usado como fonte de verdade, nem foram geradas aproximações HTML/CSS ou rampas de cores sintéticas.

## Colors

### Esquemas semânticos confirmados

A fonte da paleta do frontmatter é [Theme.kt][theme], em `DarkColors`, `LightColors` e `AccentRed`. `BitChordTheme` seleciona uma das duas instâncias. Os papéis têm o seguinte uso/intenção estrutural confirmado nas chamadas a `MaterialTheme.colorScheme`:

| Papel | Aplicação existente |
| --- | --- |
| `primary` / `onPrimary` | Acento monocromático e conteúdo sobre ele; seleção e ações. O vermelho não é o acento primário global. |
| `background` / `onBackground` | Fundo e texto das páginas comuns. |
| `surface` / `onSurface` | Superfícies, alertas, bases e conteúdo dos controles. |
| `surfaceVariant` / `onSurfaceVariant` | Campos, placeholders, skeletons e texto/ícones secundários. |
| `outline` | Separadores e contornos, às vezes com opacidade local. |
| `AccentRed` | Cor fixa preservada para casos como o badge de posição do Replay; não substitui `primary`. |

Somente os papéis explicitamente sobrescritos estão no frontmatter. `secondary`, `tertiary`, `error`, famílias `surfaceContainer*` e demais papéis não recebem uma definição própria nesse tema: vêm dos defaults da Material 3 resolvida pelo projeto. Não deduzir seus valores a partir do primário, nem tratá-los como uma paleta autoral completa. Há uso de `colorScheme.error`, por exemplo, nos erros de login de [AccountAlerts.kt][alerts]. Não foi encontrado `dynamicLightColorScheme`/`dynamicDarkColorScheme` em `BitChordTheme`; a seleção global não é uma paleta Monet.

O padrão persistido do app é **DARK**, embora a função isolada `BitChordTheme` tenha `isSystemInDarkTheme()` como argumento padrão. O host fornece a escolha `SYSTEM`, `LIGHT` ou `DARK` a partir de [AppSettings.kt][settings-store]. Portanto, não descrever o app como seguindo o sistema por padrão. Ver [MainActivity.kt][activity].

### Cores derivadas da arte

[ArtworkPalette.kt][palette] define um conjunto calculado, não uma coleção de hexadecimais globais: `background`, `wash`, `elevated`, `accent`, `onBackground`, `onBackgroundVariant`, `divider`. `rememberArtworkPalette` aceita cores de origem (`ArtworkKeyColors`) ou decodifica a imagem; na ausência da semente, usa o tema. [DetailScreen.kt][detail] consome essa paleta nas páginas de detalhe; o menu da música também pode ser tingido ([SongActionsSheet.kt][actions]).

O cálculo preserva o matiz da capa via HSL e evita elevar a saturação de tons com saturação abaixo de `0.12`. No modo escuro, a luminosidade HSL do fundo é `0.13`, da superfície elevada `0.22`, do texto secundário `0.80`; o acento limita a luminosidade a `0.62–0.78`. No claro, os correspondentes são `0.91`, `0.83`, `0.34` e `0.30–0.44`. `wash` segue a média da faixa inferior da imagem, limitada a `0.14–0.24` no escuro ou `0.78–0.90` no claro. Esses são **parâmetros do algoritmo**, não medidas de luminância relativa nem garantias universais de contraste. Saturações e demais detalhes permanecem na função `Seed.toPalette` da [fonte][palette].

A troca inicial de paleta usa `tween(260)`; sementes já conhecidas e `reduceAnimation` usam `snap()`. `rememberPlayerTint` produz um tom secundário claro a partir de amostras com chroma de pelo menos `0.08`, com luminosidade HSL `0.88` e saturação máxima `0.40`; pode retornar `null`, preservando o branco do player. Não fixar uma única “cor do player” para todas as músicas ([ArtworkPalette.kt][palette], [PlayerControls.kt][controls]).

### Transparência e barras de sistema

Opacidades são locais ao efeito: o fundo ativo/selecionado de `SongRow` usa o acento a `0.14`; o indicador de swipe usa `primary` a `0.18` ([Common.kt][common]). A borda compartilhada das miniaturas tem `1dp` e preto/branco a `0.15`, escolhido pelo **tema do sistema**, não pelo tema selecionado no app ([SongText.kt][songtext]). Essa divergência está registrada nas observações finais.

O player mantém ícones claros de sistema e calcula um scrim superior a partir da luminância relativa da faixa superior da capa: opacidade entre `0.16` e `0.65`. Fora dele, o host acompanha o tema da superfície. A seleção Android usa `WindowCompat` em [Theme.kt][theme] e o `actual` de [StatusBarIcons.android.kt](sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/theme/StatusBarIcons.android.kt); a política e o cálculo são rastreáveis em [StatusBarIcons.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/StatusBarIcons.kt), [ArtworkPalette.kt][palette] e [MainActivity.kt][activity]. Isso implementa proteção de contraste, sem certificar acessibilidade de toda a tela.

## Typography

### Família e escala

[Theme.kt][theme] monta `SFProDisplay` com os arquivos `regular`, `medium`, `semibold`, `bold` e `heavy` de [res/font](app/src/main/res/font), correspondentes a W400, W500, W600, W700 e W800. `withFamily` aplica essa família a **todos** os estilos Material 3, inclusive os que mantêm as métricas padrão. Não há uma segunda família de corpo ou uma família monoespaçada global declarada nesse tema.

Os nove estilos explicitamente personalizados estão no YAML. Aplicações observadas:

| Estilo | Uso verificável |
| --- | --- |
| `displayLarge` | Grandes títulos de Início, Explorar e Biblioteca. |
| `headlineLarge` / `headlineMedium` | Hierarquia de títulos e cabeçalhos de conteúdo. |
| `titleLarge` / `titleMedium` | Seções, títulos de linhas e cards conforme componente. |
| `bodyLarge` / `bodyMedium` | Texto principal/secundário, descrições e metadados. |
| `labelMedium` / `labelSmall` | Rótulos compactos e badges. |

Fontes de aplicação: [HomeScreen.kt][home], [ExploreScreen.kt][explore], [LibraryScreen.kt][library], [Common.kt][common], [SongText.kt][songtext]. Não existe uma razão modular de escala declarada. Não normalizar os tamanhos por uma progressão inventada.

**Métricas não declaradas:** os `TextStyle` personalizados do tema não especificam `lineHeight`; não publicar uma altura de linha numérica como token desses estilos. Espaçamento entre letras também fica não especificado onde o YAML não o registra. `displayMedium`, `displaySmall`, `headlineSmall`, `titleSmall`, `bodySmall` e `labelLarge` mantêm métricas da Material 3, recebendo SF Pro Display por `withFamily`. Consultar a dependência resolvida antes de reproduzir esses defaults fora de Compose.

### Sobrescritas locais relevantes

- Onboarding: título `40sp`, linha `44sp`, tracking `-1.2sp`; entrada de nome `26sp`/`30sp`, tracking `-0.6sp`. São estilos locais de [OnboardingScreen.kt][onboarding].
- Alertas: título `17sp` W600 e descrição `13sp`/`17sp`; escolhas podem usar `15sp` e subtítulos `12sp`/`15sp` ([AccountAlerts.kt][alerts]).
- Letras: há estilos locais de linhas `34sp`/`41sp` e `30sp`/`38sp`, além de estilos separados para tradução/backing vocals. O tamanho efetivo depende do caminho de apresentação; não substituir a escala do app por esses valores ([PlayerLyrics.kt][lyrics]).
- Títulos de música normalmente truncam em uma linha com `TextOverflow.Ellipsis`; `ExplicitSongTitle` antepõe um badge “E” recortado por `BlendMode.DstOut` ([SongText.kt][songtext]). No player, títulos/créditos podem rolar: marquee a `26dp/s`, intervalo `48dp`, repouso `5000ms` e defasagem de artista `3000ms`, constantes locais de [PlayerControls.kt][controls].

## Layout

### Medidas compartilhadas e limites calculados

As constantes do frontmatter vêm de [Common.kt][common]. `BAR_GUTTER` é alias de `PAGE_GUTTER`; `ROW_DIVIDER_INSET` e `NUMBERED_ROW_DIVIDER_INSET` são expressões derivadas, registradas com o resultado. A coluna de número alinha linhas numeradas; o divisor comum começa após a capa e o espaço que a segue. Não são uma escala abstrata de passos pequenos/médios/grandes.

| Regra existente | Valor/expressão e alcance |
| --- | --- |
| Barras inferiores | `FLOATING_BAR_MAX_WIDTH = 440dp`, centralizadas quando sobra largura. |
| Prateleira compacta | `SHELF_CARD_WIDTH = 150dp`. |
| Card hero | `min(available × 0.70, 320dp)`; largura/altura `HERO_CARD_RATIO = 0.92`. |
| Coluna lateral de músicas | `min(available × 0.88, 400dp)`. |
| Grade “mostrar tudo” da biblioteca | Base de `140dp`, espaçamento `LIBRARY_GRID_SPACING`, de 2 a 5 colunas; largura recalculada para preencher. O mínimo de 2 colunas prevalece em janelas estreitas, logo `140dp` não é garantia absoluta. |

Todos esses cálculos estão em [Common.kt][common]. Largura disponível é a do contêiner, em dp. A prévia `LibraryGridShelf` usa cards de prateleira e limite de cinco itens; a página completa usa `libraryGrid`. Não confundir os dois layouts ([LibraryScreen.kt][library]). Pastas locais/downloads também usam grades adaptativas; ver [LocalFolder.kt](app/src/main/java/com/music/bitchord/ui/screens/LocalFolder.kt).

### Chrome, insets e orientação

`TopBarContentHeight = 52dp`, `TopBarContentGap = 12dp`; `topBarHeight()` soma o inset real de status, e `topBarContentPadding()` acrescenta o intervalo. O host usa chrome superior transparente sobre o gradiente compartilhado. **O bloco de título central rolante está comentado** em `FrostedTopBar`: os comentários históricos sobre seu fade não são prova de um título atualmente visível ([FrostedTopBar.kt][topbar], [MainActivity.kt][activity]).

As barras inferiores respeitam insets de desenho/navegação; existe um piso local de `15dp` para a área gestual em [FloatingBottomBar.kt][bottombar]. O host reserva espaço para o conjunto inferior e usa `FloatingBarsTapGuard` para proteger a área coberta. O player abre como takeover sobre a página, inclusive em telas grandes; não há painel de player permanentemente retirando largura da biblioteca no caminho ativo ([MainActivity.kt][activity]).

No player em retrato, `PLAYER_GUTTER = 30dp` e `PLAYER_MAX_WIDTH = 560dp`. `playerFillsWindow` aceita largura de janela até `620dp` (560 + duas margens). A classificação de tablet para o comportamento de fundo começa em `700dp`; a faixa intermediária não deve ser arredondada para um breakpoint Android genérico. `fullBleedArtworkAvailable` também admite o caso tablet para manter a opção de configuração, mas `heroMode` de imagem estática depende de `playerFillsWindow` ([NowPlayingScreen.kt][nowplaying]).

O modo paisagem exige **largura maior que altura e largura ≥560dp**. Usa duas colunas iguais, largura total máxima de `1100dp` e insets seguros. Capa e seletor de painel ficam à esquerda; à direita alternam controles, letras ou fila. Altura abaixo de `440dp` ativa organização compacta, com margem lateral de `20dp` e intervalos menores. Estado dos painéis é compartilhado com retrato; a preservação visual durante rotação ainda requer teste em dispositivo ([NowPlayingScreen.kt][nowplaying], [LandscapePlayer.kt][landscape]).

## Elevation & Depth

A profundidade combina fundo tonal, capas com sombra, scrims, Haze, desfoque de imagem e vidro com refração. Não há uma escala global própria de elevação configurada em `BitChordTheme`; a Material 3 mantém seus defaults nos componentes que não sobrescrevem valores. Nem todas as superfícies são planas, nem todas usam a mesma sombra ([Theme.kt][theme], [DetailScreen.kt][detail], [LiquidGlass.kt][glass]).

| Efeito/caso | Implementação confirmada, com valores locais |
| --- | --- |
| Haze compartilhado | `optimizedHazeEffect` fixa `HazeInputScale.Fixed(0.33f)`; o chamador ainda pode sobrescrever. Mini player/alertas usam materiais Haze, sem uma cor opaca única equivalente. [OptimizedHaze.kt][haze] |
| Liquid Glass | Disponível em API 31+. Blur `8dp`, vibrancy `1`, opacidade de superfície `0.4`, lens height/amount `0.5 × 48dp`, resolução de captura `0.33`. Valores internos do efeito, não tokens de todos os componentes. [LiquidGlass.kt][glass] |
| Aresta de vidro | `GLASS_EDGE_WIDTH = 0.5dp`, branco a `0.10`; `glassContentColor` escolhe preto/branco pela luminância de `surface`; indicador usa o inverso. [LiquidGlass.kt][glass] |
| Indicador da navegação de vidro | Cor inversa a `0.5`; a barra sobrescreve elevação inline/expandida para `0dp`, pois o backdrop já desenha a sombra. Defaults `6dp`/`12dp` da biblioteca não são os valores efetivos desse chamador. [GlassNavBar.kt][glassnav] |
| Lente da seleção | Crescimento vertical `9dp`, lens height `12dp`, amount `14dp`; sombra de raio `12dp`, deslocamento vertical `2dp`, preto a `0.3`. [GlassSelectionPill.kt](app/src/main/java/com/music/bitchord/ui/components/floatingtabbar/GlassSelectionPill.kt) |
| Menu segurado | Preview pode receber sombra `20dp`; contorno `0.5dp`. Reduzir movimento remove essa sombra no caminho observado. [HeldContextMenu.kt][held] |
| Alertas | Scrim preto a `0.28`, card fosco Haze; reduzir blur troca o preenchimento por `surface`. [UpdateAvailableDialog.kt][update], [AccountAlerts.kt][alerts] |
| Capa paisagem/handle | Sombra da capa chega a `14dp` multiplicada pelo estado; handle usa `2dp`. [LandscapePlayer.kt][landscape] |

Com **reduzir desfoque dinâmico**, a barra Liquid Glass conserva sua estrutura que dobra, mas usa superfícies sólidas e deixa de gravar a página para amostragem. Não confundir redução do material com desligamento do layout ([MainActivity.kt][activity], [LiquidGlass.kt][glass]). Há scrims superior e inferior próprios em [TopFadeBlur.kt](app/src/main/java/com/music/bitchord/ui/components/TopFadeBlur.kt) e [BottomFadeScrim.kt](app/src/main/java/com/music/bitchord/ui/components/BottomFadeScrim.kt), e tratamento de capa em [ArtworkBackdrop.kt](app/src/main/java/com/music/bitchord/ui/components/ArtworkBackdrop.kt). Não substituir esses caminhos por uma única receita de sombra.

## Shapes

O tema não fornece um `Shapes` próprio ao `MaterialTheme`. A linguagem arredondada vem principalmente de `RoundedCornerShape`/`CircleShape` nos componentes; não há evidência de escala global `sm/md/lg`. O quadro registra **valores locais**, não novos tokens:

| Superfície | Forma atual | Fonte |
| --- | --- | --- |
| Capa de `SongRow` / mini player expandido | Cantos `8dp` | [Common.kt][common], [MiniPlayer.kt][mini] |
| Linhas compactas de recentes/detalhe | Há capas com `7dp` | [HomeScreen.kt][home], [DetailScreen.kt][detail] |
| Cards comuns de prateleira / capa de release | `12dp` | [HomeScreen.kt][home], [DetailScreen.kt][detail] |
| Hero de Início / card de categoria de Explorar | `18dp` | [HomeScreen.kt][home], [ExploreScreen.kt][explore] |
| Avatares/artistas e controles circulares | `CircleShape` nos respectivos caminhos | [HomeScreen.kt][home], [DetailScreen.kt][detail] |
| Barra inferior / mini player fosco | `RoundedCornerShape(percent = 50)` | [FloatingBottomBar.kt][bottombar], [MiniPlayer.kt][mini] |
| Busca / `PillTextField` | `11dp` | [SearchField.kt][searchfield], [Common.kt][common] |
| Menu segurado | Preview `20dp`, menu `16dp` | [HeldContextMenu.kt][held] |
| Sheet de ações de música | Cantos superiores `28dp` | [SongActionsSheet.kt][actions] |
| Alertas compartilhados | `ALERT_CORNER = 14dp` | [UpdateAvailableDialog.kt][update] |
| Capa paisagem | `10dp` em repouso | [LandscapePlayer.kt][landscape] |

As formas também mudam nas transições de docking e recolhimento do player: `PlayerDock` informa posição e canto da capa do miniplayer. Não fixar o raio de repouso em todos os frames ([MiniPlayer.kt][mini], [PlayerDock.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerDock.kt), [NowPlayingScreen.kt][nowplaying]).

## Components

### Navegação e barras superiores

O host monta quatro abas, na ordem **Início, Explorar, Biblioteca e Busca**, com ícones `BitChordIcons`. Trocar de aba limpa páginas/painéis transitórios; tocar Busca limpa o texto, pede foco e abre o teclado. Ações superiores variam conforme página e estado de conta/download ([MainActivity.kt][activity], [BitChordIcons.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/icons/BitChordIcons.kt)).

- **Caminho fosco:** `FloatingBottomBar` abaixo de `MiniPlayer` quando há música. Pílula com inset `6dp`, padding vertical de aba `9dp`, intervalo ícone/rótulo `2dp`, ícone `25dp`. Seleção animada segue toque e arrasto; `GlassSpring` tem damping `0.72`, stiffness `320`. `reduceAnimation` troca o movimento do indicador por `snap`. ([FloatingBottomBar.kt][bottombar])
- **Liquid Glass ativo e suportado:** `GlassNavBar` integra now playing e abas em arranjos expandido/inline. A rolagem usa `rememberFloatingTabBarScrollConnection`, cujo limiar padrão é `50dp`; ao recolher, o acessório e as abas mudam de organização. A aba de Busca é tratada como standalone nesse arranjo. Não selecionados usam a cor do conteúdo a `0.65`. ([GlassNavBar.kt][glassnav], [FloatingTabBar.kt][floatingtabs])
- **Topo:** `FrostedTopBar` fornece voltar, acessório de busca, ações e indicador de refresh. O puck acompanha a fração de pull, depois gira durante a requisição. O caminho de pane limitado existe no componente, mas o host usa chrome transparente; o título central comentado não deve ser reproduzido como recurso ativo. ([FrostedTopBar.kt][topbar], [MainActivity.kt][activity])

### Mini player

`MiniPlayer` contém capa `40dp`, cantos `8dp`, padding horizontal `12dp` e vertical `8dp`, título/metadados e transporte. A composição nominal de capa + padding é `56dp`; alvos mínimos de componentes Material, texto ampliado e constraints podem afetar a altura medida. Slots de transporte declarados `40dp`, glifos `32dp`, spinner `22dp`, intervalo `8dp`. Carregamento substitui o glifo de reprodução; controles de sessão bloqueada apresentam tratamento próprio ([MiniPlayer.kt][mini]).

Tocar expande. Swipe horizontal de pelo menos `72dp`, avaliado no fim do gesto, avança à esquerda ou volta à direita, um item por gesto. Arrastar para cima passa o deslocamento à sheet, com resistência visual no próprio mini player e retorno por mola. O host desliga esse pull/docking personalizado ao reduzir animação. `GlassNowPlaying` compartilha os gestos: expandido usa capa/slot `40dp`, glifo `32dp`; inline usa capa/slot `32dp`, glifo `24dp` e canto `6dp`. Seu estado pressionado cresce para `1.04` por mola; essa animação local não consulta `reduceAnimation` no próprio composable ([MiniPlayer.kt][mini], [GlassNavBar.kt][glassnav], [MainActivity.kt][activity]).

### Now Playing, transporte e fila

[NowPlayingScreen.kt][nowplaying] coordena capa, backdrop/mesh, Canvas, título/artistas, favoritos, menu, posição, tempo, transporte, volume, saída de áudio, letras e fila. [PlayerControls.kt][controls] contém os controles compartilhados; [AndroidPlayerHost.kt](app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt) liga as ações ao Android. São controles dependentes do estado de reprodução, do serviço e das configurações, não um mockup estático.

- **Capa:** pode ficar enquadrada ou full bleed. Imagem estática respeita `fullBleedArtwork` e a largura; Spotify Canvas em janela de telefone pode usar o fundo independentemente dessa preferência. Outros clips seguem as condições do caminho hero. Sem uma imagem carregada não se deve presumir um banner estático pronto. ([NowPlayingScreen.kt][nowplaying], [CanvasArtworkPlayer.kt](app/src/main/java/com/music/bitchord/ui/player/CanvasArtworkPlayer.kt))
- **Movimento de capa:** escalas locais de paisagem `1`, `0.88` em pausa e `0.94` durante scrub; pausa prevalece. A curva declarada é `CubicBezierEasing(0.215, 0.61, 0.355, 1)` com duração `500ms`. Não é a curva universal do app. ([NowPlayingScreen.kt][nowplaying], [LandscapePlayer.kt][landscape])
- **Abrir/fechar:** o host usa `PlayerSheetMotion` e `PlayerDock` para acompanhar o dedo e transferir visualmente a capa entre mini player e player. Com reduzir animação, usa o slide/scrim da própria sheet Material 3; não significa ausência de qualquer animação. ([MainActivity.kt][activity], [PlayerSheetMotion.kt](app/src/main/java/com/music/bitchord/ui/player/PlayerSheetMotion.kt))
- **Posição/volume:** `ThinSlider` tem defaults locais de espessura `7dp` em repouso e `12dp` em drag, área vertical `activeHeight + 22dp`, fill branco a `0.92`, trilho a `0.26` e marcador de transição a `0.5`; aceita overrides. Toque e arrasto fazem parte do mesmo loop. Espera/troca de versão usa sheen; Automix pode preencher e pulsar em sincronia. ([ThinSlider.kt][slider])
- **Fila:** abre painel, mostra estados/itens atuais e permite remover/reordenar nos caminhos habilitados; drag tem auto-scroll nas bordas. Há tratamento para controles bloqueados pelo anfitrião de sessão. Não reduzir fila a uma lista estática. ([PlayerQueue.kt][queue], [PlayerDrawer.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerDrawer.kt))
- **Painéis auxiliares:** saída de áudio, membros de sessão, fornecedor e offset de letras têm composables próprios; ver [AudioOutputSheet.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/AudioOutputSheet.kt), [ListenTogetherMembersSheet.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ListenTogetherMembersSheet.kt), [LyricsProviderSheet.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsProviderSheet.kt) e [LyricsOffsetSheet.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsOffsetSheet.kt).

### Letras e gestos de leitura

O painel trata letras sincronizadas, destaque de linha/palavra, linhas auxiliares, tradução/romanização, auto-follow e browsing manual. A linha ativa fica nítida; linhas fora do foco podem escurecer e desfocar. Rolagem manual suspende o seguimento/desfoque de foco no caminho de browsing. Glow depende de `!reduceAnimation`, `!reduceDynamicBlur` e `lyricsBlur`, além das condições de apresentação; não é um brilho sempre ativo ([PlayerLyrics.kt][lyrics], [SyncedLyricsPanel.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/SyncedLyricsPanel.kt)).

Os controles podem recolher após `5000ms` de inatividade no contexto de letras; há temporizador separado para Canvas e animação local de `420ms`. Toques/gestos de retomada são coordenados por [LyricsControlsGesture.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricsControlsGesture.kt) e [NowPlayingScreen.kt][nowplaying]. Foco e relógio têm lógica própria em [LyricFocus.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricFocus.kt) e [LyricClock.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricClock.kt). Compartilhamento Android usa [AndroidLyricsShareSheet.kt](app/src/main/java/com/music/bitchord/ui/player/AndroidLyricsShareSheet.kt) e [LyricsShareCard.kt](app/src/main/java/com/music/bitchord/ui/player/LyricsShareCard.kt); esse card de exportação não redefine a tipografia global.

### Linhas, cards, badges e estados de dados

`SongRow` é reutilizado em busca, biblioteca e detalhes. Capa `52dp`, padding vertical `4dp`, espaço antes do texto `14dp` quando há capa; a variante numerada usa a coluna compartilhada e o gutter. Título explícito, artista, badges de download/qualidade, overflow e indicador atual dependem dos parâmetros. Seleção/música atual anima fundo tingido; toque e long press executam callbacks do chamador (normalmente reprodução e ações; downloads também pode entrar em seleção). Quando `onSwipeToQueue` é fornecido, swipe em qualquer direção enfileira ou toca a seguir conforme a preferência do chamador: limiar posicional `0.5` da largura e verificação adicional de deslocamento físico de pelo menos `0.45` evitam flings curtos; a linha retorna em vez de ser removida ([Common.kt][common], [SourceQualityBadge.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SourceQualityBadge.kt)).

`Shelf`, `ShelfCard`, `HeroCard`, `NewShelfCard`, `SectionHeader` e variantes compactas estão em [HomeScreen.kt][home], apoiadas por [ShelfRow.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/ShelfRow.kt). Cards distinguem tipo por capa quadrada, avatar circular, ícone de serviço/placeholder e metadados. Não há uma API global única `Card` com todas essas variantes. [CoverPlaceholder.kt](app/src/main/java/com/music/bitchord/ui/components/CoverPlaceholder.kt) e [RemoteArtwork.kt](app/src/main/java/com/music/bitchord/ui/components/RemoteArtwork.kt) cobrem casos Android de arte ausente/remota.

`ShimmerBox` usa `surfaceVariant` e brilho baseado em `onSurfaceVariant`, período `1400ms`, formas locais `6dp` para blocos e `4dp` para linhas. Skeletons reaproveitam fórmulas reais de hero/colunas e têm variantes de feed, busca, biblioteca e detalhe. `MessageState` apresenta mensagem e ação opcional; chamadas de tela determinam retry, login ou apenas estado vazio. O wrapper `PullToRefresh` usa Material 3 e delega o indicador ao topo ([Skeletons.kt][skeletons], [Common.kt][common]).

### Menus, sheets, alertas e campos

| Família | Contrato visual e interativo existente | Fonte |
| --- | --- | --- |
| `HeldContextMenu` | Preview ancorado à origem do long press, backdrop/scrim, menu com largura máxima `280dp`, margem `12dp`, intervalo `10dp`; considera insets. Há fade e abertura com caminhos reduzidos. | [HeldContextMenu.kt][held], [LongPressOrigin.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/LongPressOrigin.kt) |
| `SongActionsSheet` / `ActionRow` | Paleta de capa ou neutra; cabeçalho com capa `52dp`, divisores `0.5dp`, ação desabilitada a `0.4`. Variante menu tem mínimo `46dp` e padding `18dp × 11dp`; sheet usa padding `22dp × 15dp`. Conteúdo condicional: fila, próximo, favoritos, download, playlist, compartilhamento e ações de fonte disponíveis. | [SongActionsSheet.kt][actions] |
| `TintedSheet` | Limite local de altura em `85%` da altura da configuração, cantos superiores `28dp`, handle `34dp × 4dp`; não é regra de todas as sheets. | [SongActionsSheet.kt][actions] |
| Ações de browse/playlist/download | Composables separados para ações de entidade, escolha de playlist, reordenação e progresso de downloads; não presumir mesmas opções para toda entidade. | [BrowseActionsSheet.kt](app/src/main/java/com/music/bitchord/ui/components/BrowseActionsSheet.kt), [PlaylistPickerSheet.kt](app/src/main/java/com/music/bitchord/ui/components/PlaylistPickerSheet.kt), [ReorderPlaylistSheet.kt](app/src/main/java/com/music/bitchord/ui/components/ReorderPlaylistSheet.kt), [DownloadManagerSheet.kt](app/src/main/java/com/music/bitchord/ui/components/DownloadManagerSheet.kt) |
| `AlertScaffold`, confirmação/escolha/servidor | Card central `270dp`, canto `14dp`, ações de altura `44dp`, separador `0.5dp`, título/descrição centrados. Toque exterior fecha nos chamadores que fornecem dismiss; card consome o toque. | [AccountAlerts.kt][alerts], [UpdateAvailableDialog.kt][update] |
| `AudioPopupCard` | Variante de largura `320dp`, conteúdo limitado a `420dp` de altura, reaproveita canto do alerta. Não é o tamanho de todo diálogo. | [AudioPopupCard.kt](app/src/main/java/com/music/bitchord/ui/components/AudioPopupCard.kt) |
| `SearchField` | Campo de `46dp`, canto `11dp`, `surfaceVariant`, leading control e limpar, foco e IME. | [SearchField.kt][searchfield] |
| `PillTextField` | Campo de `40dp`, canto `11dp`, padding horizontal `12dp`, texto local `15sp`; opções de senha, teclado e habilitação. Não assumir um contorno de foco customizado universal. | [Common.kt][common] |

`AlertAction` diferencia a ação enfatizada por W600 e a comum por W400, sem botão preenchido; ao pressionar, aplica `onSurface` a `0.09` na linha inteira, sem ripple. Desabilitado usa opacidade `0.4`; destrutivo usa `colorScheme.error`. `SearchField` envia a pesquisa pelo ícone ou IME, limpa o foco no submit e mantém/reabre o teclado ao limpar o texto. `PillTextField` usa cursor `primary` e `PasswordVisualTransformation` quando solicitado. Esses estados são locais às famílias, não variantes globais de botão/campo ([UpdateAvailableDialog.kt][update], [SearchField.kt][searchfield], [Common.kt][common]).

O feedback transitório de fila é `QueueActionNoticeHost`, com fundo local `Color(0xFF282828)`, cantos `5dp` e sombra `4dp`. Falhas de reprodução podem usar `PlaybackRecoveryBanner`, uma `Surface` de até `430dp`, canto `14dp`, elevação tonal `5dp` e sombra `3dp`, com retry/dismiss conforme estado. Esses valores não fazem parte das paletas/elevações globais ([QueueActionNotice.kt](app/src/main/java/com/music/bitchord/ui/components/QueueActionNotice.kt), [PlaybackRecoveryBanner.kt](app/src/main/java/com/music/bitchord/ui/components/PlaybackRecoveryBanner.kt), [MainActivity.kt][activity]).

### Comportamento das telas principais

| Tela | Composição/estados confirmados | Fonte |
| --- | --- | --- |
| Início | Título grande, prateleiras/hero, recentes em lista paginada lateralmente ou cards conforme preferência, entrada de conta quando aplicável; loading inicial com skeleton, erro com retry, paginação com skeleton adicional e pull-to-refresh. | [HomeScreen.kt][home] |
| Explorar | Grade de moods/gêneros, capa duotone com faixa lateral (`0.28` da largura), razão `1.72`, canto `18dp`, gap `12dp`. `moodColumns` calcula de 2 a 6 colunas usando base `220dp`, respeitando o mínimo de duas; erro/retry/loading/pull-to-refresh. | [ExploreScreen.kt][explore] |
| Busca | Campo superior, sugestões/typeahead e recentes, filtros por tipo/origem, resultados de catálogo/biblioteca conforme estado. Skeleton de artistas pode ser circular; vazio e erro têm mensagens próprias. Selecionar filtro altera conteúdo, sem uma “cor de filtro” global nova. | [SearchScreen.kt][search], [MainActivity.kt][activity] |
| Biblioteca | Replay, entradas locais, prateleiras e playlists com fixação/ordem; “mostrar tudo” abre grade; ações para criar/importar playlist mesmo quando ainda não há uma. Sem login mantém entradas locais e mostra chamada de autenticação. | [LibraryScreen.kt][library], [LibraryPhoneParts.kt](app/src/main/java/com/music/bitchord/ui/screens/LibraryPhoneParts.kt) |
| Álbum/playlist/artista | Cores de capa; release enquadrado e artista com imagem de cabeçalho/wash, ações de reprodução e biblioteca, metadados, faixas/seções e pesquisa/ordem conforme entidade. Release limita ações a `420dp`, laterais `50dp`; artista usa play circular `70dp` e laterais `52dp`. São métricas locais distintas. | [DetailScreen.kt][detail] |
| Configurações | Hub hierárquico de grupos com cartões e navegação para destinos por tema; a busca da raiz abre a categoria já filtrada pelo resultado. Dentro das categorias, os switches, seletores, diálogos e condicionais originais são preservados. A mudança de estrutura foi introduzida na issue #187. | [SettingsSheet.kt][settings-ui], [SettingsNavigation.kt][settings-nav], [MainActivity.kt][activity] |
| Onboarding | Duas etapas (nome e conexão), mesh de fundo, transição horizontal/fade, voltar e opção de continuar sem login; valida nome não vazio, respeita IME/insets e limita a coluna a `520dp`. Botão local em pílula `56dp`, campo de nome `64dp`. | [OnboardingScreen.kt][onboarding] |
| Avaliações/coleções canônicas | Já existem controles de notas numéricas e limpar avaliação, listas de avaliações, detalhes de gravação e construtor de playlist inteligente. Não documentar estrelas da draft #182 como se estivessem integradas. | [CanonicalRecordingFeedbackControls.kt](app/src/main/java/com/music/bitchord/ui/screens/CanonicalRecordingFeedbackControls.kt), [CanonicalMyRatingsScreen.kt](app/src/main/java/com/music/bitchord/ui/screens/CanonicalMyRatingsScreen.kt), [CanonicalRediscoveryRecordingDetailsScreen.kt](app/src/main/java/com/music/bitchord/ui/screens/CanonicalRediscoveryRecordingDetailsScreen.kt), [SmartPlaylistBuilderScreen.kt](app/src/main/java/com/music/bitchord/ui/screens/SmartPlaylistBuilderScreen.kt) |
| Replay | Card de acesso, tela, histórias/poster e sheet de compartilhamento têm composição própria; acento vermelho de rank é específico dessa família. | [ReplayCard.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/replay/ReplayCard.kt), [ReplayScreen.kt](app/src/main/java/com/music/bitchord/ui/replay/ReplayScreen.kt), [ReplayPoster.kt](app/src/main/java/com/music/bitchord/ui/replay/ReplayPoster.kt), [ReplayStories.kt](app/src/main/java/com/music/bitchord/ui/replay/ReplayStories.kt) |

Há superfícies adicionais Android de histórico, fontes, conta/scrobbling, equalizador e sessão conjunta, com contratos próprios: [HistoryScreen.kt](app/src/main/java/com/music/bitchord/ui/screens/HistoryScreen.kt), [SourcesScreen.kt](app/src/main/java/com/music/bitchord/ui/screens/SourcesScreen.kt), [AccountAndScrobblingScreen.kt](app/src/main/java/com/music/bitchord/ui/screens/AccountAndScrobblingScreen.kt), [EqualizerScreen.kt](app/src/main/java/com/music/bitchord/ui/screens/EqualizerScreen.kt), [ListenTogetherScreen.kt](app/src/main/java/com/music/bitchord/ui/screens/ListenTogetherScreen.kt). O inventário identifica essas famílias; não afirma uma especificação exaustiva de cada formulário secundário.

### Navegação de Configurações após a issue #187

O índice inicial expõe **Conta, Música, Experiência, Dados e Downloads, Sistema e Sobre**. `SettingsNavigation.groups` centraliza as categorias, mantendo tokens e componentes de `SettingsGroup` sem introduzir nova paleta. Cada destino reutiliza os controles já presentes em `SettingsSheet.kt`; Letras e Fontes recebem destinos próprios sem modificar suas dependências. `MainActivity` preserva a pilha raiz → categoria e volta primeiro à raiz. A pesquisa mantém os aliases históricos e abre cada resultado na categoria correta, com o filtro preservado até o usuário selecionar **Ver tudo**. O hardware controla a presença do destino Wear. O título grande das páginas internas não é duplicado como legenda de uma única seção; as legendas permanecem na busca para diferenciar grupos. Os ícones dos cartões de navegação usam `onSurface` explícito, respeitando o modo escuro. O seletor de ícone do aplicativo fica em **Aparência**; o alinhamento inteligente fica em **Reprodução**; a seleção de versão apenas de áudio fica em **Qualidade de áudio**. "Controles e comportamento" pertence ao grupo **Música** e "Idioma" ao grupo **Experiência**. Para o mapa completo de migração, ver [settings-187-coverage](docs/design/settings-187-coverage.md). A seção descreve uma alteração desta PR, posterior ao commit de referência do inventário original.

### Estados interativos e acessibilidade implementada

Confirmado no código:

- Ícones de ações recebem `contentDescription` localizado; imagens decorativas frequentemente usam `null`. A fila descreve reordenar/remover e o estado atual; controles compostos do player aplicam `semantics`. Isso não comprova ordem de leitura nem cobertura integral de TalkBack. ([PlayerQueue.kt][queue], [PlayerControls.kt][controls], [SearchField.kt][searchfield])
- Controles Material e `clickable`/`combinedClickable` fornecem semântica/interação conforme o componente. Há alvos locais de `36dp`, `40dp`, `44dp` e outros; o tamanho declarado não demonstra a área final expandida pelo Material. Não declarar que todo alvo mede pelo menos `48dp`. ([Common.kt][common], [MiniPlayer.kt][mini], [PlayerControls.kt][controls])
- `reduceAnimation`, `reduceDynamicBlur`, `lyricsBlur` e `liquidGlass` são preferências observadas pelos respectivos caminhos. Blur e animação reduzidos começam desligados; vidro começa desligado e fica indisponível abaixo de API 31 na configuração. Alto desempenho começa desligado. ([AppSettings.kt][settings-store], [SettingsSheet.kt][settings-ui])
- Ativar alto desempenho desliga reduzir movimento/blur; ativar qualquer uma dessas reduções desliga alto desempenho. São preferências vinculadas, não quatro toggles independentes. ([AppSettings.kt][settings-store])
- Feedback tátil Android respeita `Settings.System.HAPTIC_FEEDBACK_ENABLED`, testa capacidade do vibrador e escolhe primitives/waveform; tem ritmos distintos de seleção, expandir, transporte e toggle. Não é apenas uma vibração igual em todo clique. ([Haptics.android.kt](sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/haptics/Haptics.android.kt))
- Strings vêm de recursos Android e Compose Resources, com traduções e seleção de idioma. Ícones auto mirrored existem em ações como voltar, mas isso não certifica todo layout RTL. ([res/values/strings.xml](app/src/main/res/values/strings.xml), [composeResources](sharedUi/src/commonMain/composeResources), [AppLanguageDialog.kt](app/src/main/java/com/music/bitchord/ui/components/AppLanguageDialog.kt), [FrostedTopBar.kt][topbar])

**Lacunas de validação:** esta tarefa não executou TalkBack, Switch Access, fonte ampliada, RTL, medição de contraste completo ou navegação por teclado em Android. Texto em `sp` permite escala do sistema por padrão de Compose (**inferência**); os tamanhos fixos de cards, campos e alertas ainda precisam ser verificados com escala alta. `ThinSlider` implementa gesto/Canvas e não declara `progressSemantics`/`setProgress` no próprio componente: a equivalência acessível de um `Slider` Material não está comprovada. [ThinSlider.kt][slider] é a referência para essa observação, sem correção nesta tarefa.

### Desempenho visual confirmado e limites

O projeto usa listas/grades lazy, chaves de itens em vários caminhos, carregamento Coil, caches de artwork/paleta, `remember`, `derivedStateOf`, `snapshotFlow` e leituras em `graphicsLayer`/draw para limitar trabalho por frame. O mecanismo não é uma garantia medida de FPS ([HomeScreen.kt][home], [LibraryScreen.kt][library], [MiniPlayer.kt][mini], [NowPlayingScreen.kt][nowplaying]).

A paleta é decodificada a `128 × 128`, quantizada fora da thread principal e guardada em cache LRU de `128` sementes. Haze e Liquid Glass usam resolução `0.33`; a gravação de backdrop é condicionada ao uso efetivo do vidro. O sheen do slider só é composto enquanto necessário. Canvas usa caminho Android próprio, com ciclo de vida/renderização em [CanvasArtworkPlayer.kt](app/src/main/java/com/music/bitchord/ui/player/CanvasArtworkPlayer.kt). Fontes: [ArtworkPalette.kt][palette], [OptimizedHaze.kt][haze], [LiquidGlass.kt][glass], [MainActivity.kt][activity], [ThinSlider.kt][slider].

Alto desempenho pede uma taxa suportada pelo display em `preferredRefreshRate`; em API 35+ também ajusta `requestedFrameRate` e `setFrameRatePowerSavingsBalanced`. A resolução da taxa ocorre em [DisplayRefreshRates.kt](app/src/main/java/com/music/bitchord/ui/performance/DisplayRefreshRates.kt) e [MainActivity.kt][activity]. Não significa taxa garantida, nem modo automaticamente mais econômico.

Há sinais/contexto de diagnóstico em [RuntimePerformanceUiContext.kt](sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/performance/RuntimePerformanceUiContext.kt), com sink opcional e caminhos de instrumentação Android. **Lacuna:** não foram capturados frames, GPU timings, consumo, memória ou macrobenchmarks nesta auditoria documental; não atribuir métricas de desempenho ao design.

### Widgets Android (superfície nativa adjacente)

Widgets do launcher usam RemoteViews/XML e renderização própria, não os componentes Compose acima. [dimens.xml](app/src/main/res/values/dimens.xml) declara bandas compacta/larga de `66dp`/`60dp`, padding `8dp`, botões `40dp`/`44dp`, glifos `22dp`/`24dp`; pílula de altura `78dp`, arte `64dp`, botões `38dp`. Raio de fundo é `16dp` antes da API 31 e o valor de sistema depois dela em [values-v31/dimens.xml](app/src/main/res/values-v31/dimens.xml). São recursos específicos de widget, não escala de componentes do aplicativo.

Layouts: [widget_media_compact.xml](app/src/main/res/layout/widget_media_compact.xml), [widget_media_wide.xml](app/src/main/res/layout/widget_media_wide.xml), [widget_media_pill.xml](app/src/main/res/layout/widget_media_pill.xml). Composição da imagem e ações: [MediaWidgetArt.kt](app/src/main/java/com/music/bitchord/widget/MediaWidgetArt.kt), [MediaWidgetPill.kt](app/src/main/java/com/music/bitchord/widget/MediaWidgetPill.kt) e [MediaWidgetActions.kt](app/src/main/java/com/music/bitchord/widget/MediaWidgetActions.kt). A aparência final depende também do launcher; não houve captura visual nessa superfície.

## Do's and Don'ts

### Reproduzir o que está confirmado

- Usar os papéis de `MaterialTheme.colorScheme` e a tipografia de `BitChordTheme`; preservar a separação entre tema, paleta de artwork e valores locais.
- Reutilizar `PAGE_GUTTER`, helpers de largura/grade e componentes existentes quando reproduzir suas superfícies. Consultar o chamador: parâmetros e overrides fazem parte da especificação efetiva.
- Usar dp/sp e os insets reais da janela; manter as condições de API, largura/orientação, login, fonte, carregamento e bloqueio da sessão.
- Conferir a `main` antes de tratar uma proposta de PR como integrada. Atualizar o commit de referência quando houver nova auditoria.

### Não introduzir decisões nesta documentação

- Não transformar raios, opacidades, sombras, curvas ou estilos locais em tokens globais; não preencher papéis Material ausentes com novas cores.
- Não inferir comportamentos a partir apenas do nome do arquivo ou de comentários: o título central de `FrostedTopBar` e os defaults de elevação substituídos pelo chamador são exemplos concretos.
- Não aplicar recomendações estéticas do Impeccable, criar identidade verbal, usar o documento Google Stitch como autoridade ou incluir outras plataformas no sistema Android.
- Não interpretar observações abaixo como correções aprovadas, nem as verificações documentais como validação de runtime.

### Observações, duplicações e documentação ausente

1. **Tema do sistema versus tema do app:** `thumbnailBorder` usa `isSystemInDarkTheme`, enquanto o app pode forçar tema diferente. **Inferência:** a escolha da borda pode divergir da superfície. Não foi corrigido ([SongText.kt][songtext], [Theme.kt][theme]).
2. **Comentário versus caminho ativo:** o cabeçalho de `FrostedTopBar` descreve título que aparece ao rolar, mas o bloco está comentado. A documentação registra o estado ativo, mantendo a discrepância identificada ([FrostedTopBar.kt][topbar]).
3. **Geometria duplicada/variantes:** capas compactas de `7dp`, linhas de `8dp`, cards de `12dp`; `SearchField` e `PillTextField` partilham raio mas têm alturas distintas. Há várias linhas compactas privadas e famílias de alerta/menu. Isso é heterogeneidade confirmada, não prova de defeito; não foi unificado ([HomeScreen.kt][home], [DetailScreen.kt][detail], [Common.kt][common], [SearchField.kt][searchfield]).
4. **Reduzir movimento é parcial:** navegação, paleta e vários trechos de letras consultam a preferência, mas `ShimmerBox`, a espessura/morph de `ThinSlider` e a transição de página do onboarding têm animações próprias sem a mesma consulta local. O host ainda usa movimento Material 3 ao reduzir a transição de docking. Não afirmar “zero animação” ([Skeletons.kt][skeletons], [ThinSlider.kt][slider], [OnboardingScreen.kt][onboarding], [MainActivity.kt][activity]).
5. **Cores XML legadas:** [colors.xml](app/src/main/res/values/colors.xml) contém nomes purple/teal do template. Eles não definem `DarkColors`/`LightColors`; não foram promovidos a paleta do Compose. Ambos os [themes.xml](app/src/main/res/values/themes.xml) e [values-night/themes.xml](app/src/main/res/values-night/themes.xml) definem janela preta, enquanto o conteúdo Compose pode ser claro. Possível diferença transitória na abertura é **inferência**, sem captura para confirmar.
6. **Sem contrato acessível completo demonstrado:** alvos, sliders customizados, truncamento, gestos e foco precisam de auditoria em aparelho. Não há nesta entrega certificação de WCAG/TalkBack, escala máxima suportada ou FPS mínimo.
7. **Defaults externos:** não se extraiu a tabela completa de defaults Material 3 nem se inventou linha/spacing ausente. O tema e as dependências resolvidas devem permanecer juntos para reprodução exata ([Theme.kt][theme], [app/build.gradle.kts](app/build.gradle.kts), [sharedUi/build.gradle.kts](sharedUi/build.gradle.kts)).
8. **Cobertura:** a documentação detalha padrões e telas principais e referencia famílias secundárias; não substitui todos os condicionais de negócio de cada formulário. Aparência de artwork remota, textos de catálogo e conteúdo de conta variam com os dados.

### Fontes analisadas e verificações desta entrega

A inspeção priorizou tema/fontes/XML, host/navegação Android, componentes compartilhados, player/letras/fila, telas principais, configurações e preferências, haptics, efeitos/backdrop e recursos de widgets. Os links distribuídos em cada seção permitem conferir declaração e chamador. Não foram usados screenshots de outra plataforma, intenção de PR draft ou documentação de protótipo como substituto do código integrado.

Verificações documentais: sincronização inicial de `main` com `origin/main`; referência fixa do commit; consulta da lista de PRs abertos/draft; conferência das cores, estilos e espaçamentos do frontmatter contra as declarações Kotlin; validação de YAML, ordem das oito seções canônicas, links locais e diff limitado a `DESIGN.md`; `git diff --check`. Não foram executados build Android, testes de aplicação, emulador ou captura visual: o diff é documental e as afirmações de runtime permanecem limitadas como indicado acima.

[theme]: app/src/main/java/com/music/bitchord/ui/theme/Theme.kt
[activity]: app/src/main/java/com/music/bitchord/MainActivity.kt
[common]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Common.kt
[home]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/HomeScreen.kt
[explore]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/ExploreScreen.kt
[library]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/LibraryScreen.kt
[search]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/screens/SearchScreen.kt
[detail]: app/src/main/java/com/music/bitchord/ui/screens/DetailScreen.kt
[palette]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/theme/ArtworkPalette.kt
[settings-store]: app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt
[settings-ui]: app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt
[songtext]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SongText.kt
[topbar]: app/src/main/java/com/music/bitchord/ui/components/FrostedTopBar.kt
[bottombar]: app/src/main/java/com/music/bitchord/ui/components/FloatingBottomBar.kt
[glass]: app/src/main/java/com/music/bitchord/ui/components/LiquidGlass.kt
[glassnav]: app/src/main/java/com/music/bitchord/ui/components/GlassNavBar.kt
[floatingtabs]: app/src/main/java/com/music/bitchord/ui/components/floatingtabbar/FloatingTabBar.kt
[mini]: app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt
[nowplaying]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt
[landscape]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LandscapePlayer.kt
[controls]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt
[lyrics]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt
[queue]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt
[slider]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ThinSlider.kt
[haze]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/OptimizedHaze.kt
[held]: app/src/main/java/com/music/bitchord/ui/components/HeldContextMenu.kt
[actions]: app/src/main/java/com/music/bitchord/ui/components/SongActionsSheet.kt
[alerts]: app/src/main/java/com/music/bitchord/ui/components/AccountAlerts.kt
[update]: app/src/main/java/com/music/bitchord/ui/components/UpdateAvailableDialog.kt
[searchfield]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/SearchField.kt
[skeletons]: sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/Skeletons.kt
[onboarding]: app/src/main/java/com/music/bitchord/ui/onboarding/OnboardingScreen.kt

[settings-nav]: app/src/main/java/com/music/bitchord/ui/screens/SettingsNavigation.kt
