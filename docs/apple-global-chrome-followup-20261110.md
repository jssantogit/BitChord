# REA #212 — Chrome global: círculos e indicadores de busca (10/11/2026)

Fonte de verdade: `jssantogit/am-descomp`, branch `fase-3-investigacao-visual`, HEAD `7200e849`. Evidência adicional: capturas do usuário de 10/11/2026.

## Círculos da barra superior

`docs/components/library-toolbar.md` vincula estaticamente a ação Mais Opções ao recurso `profile_foryou` de 40dp e à forma com cantos de 50%. O mesmo recorte avisa que o tamanho final de toque/renderização e o segundo círculo não foram medidos em runtime. A referência justifica manter o papel separado de 40dp para a Biblioteca, **não** transformar todas as superfícies do aplicativo em botões de 40dp.

Antes, o Voltar colocava o material diretamente sobre um IconButton (com tamanho interativo mínimo próprio), enquanto filtros e More usavam um Box externo de 40dp. Os caminhos agora compartilham `FloatingLibraryTopBarCircle`: a superfície visual é única e o IconButton fica dentro. Fora dessas categorias, o Voltar genérico deixou de usar o literal 44dp e assume o token genérico `toolbarDiameter` de 48dp, igual às ações genéricas da barra. Este 48dp é política visual já existente no BitChord, não medida final do Apple Music.

## Barras azuis de Busca

`docs/components/search-result-tabs.md` registra observação runtime do seletor de tipos após confirmação, mas declara geometria/cor exatas desconhecidas. As capturas fornecidas mostram que no BitChord o texto do resultado fica azul, mas a barra não aparece; no seletor pré-pesquisa de duas fontes a barra aparece.

Diagnóstico do código: um Row com horizontalScroll recebe largura não limitada, e o Box indicador usava fillMaxWidth em Column sem largura intrínseca fixa. Isto podia produzir um indicador de largura zero. Cada aba de resultados agora mede a largura intrínseca do próprio rótulo. O mesmo ajuste protege a origem selecionada quando há **três ou mais** fontes; para uma ou duas fontes, os pesos originais foram preservados. Os 3dp e a tonalidade azul são escolhas de calibração do BitChord.

## Regressão e limites

Testes Compose de Robolectric adicionados para: 40dp back/action com superfície compartilhada; back genérico 48dp; indicador de resultado após envio com largura positiva; indicador de origem com três opções e rolagem horizontal. Não alteramos fonte de dados, animação, rotas, reprodução ou a PR #209.

Verificação física ainda necessária: cabeçalhos com mais botões, RTL, fontScale, abas de busca em rolagem, fonte local/remota, insets e comparação de contornos em Android. CI não comprova renderização pixel-perfect. Sem nova importação de fontes/assets proprietários.
