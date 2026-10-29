# Issue #192 — autorização de APK para smoke físico

Autorizado pelo usuário em 28/10/2026: **“Dispare o apk”**.

Executar uma única compilação `assembleDevDebug` para validar o estado atual
da PR draft #193 (`listening/smart-playlists-v2-192`) no aparelho Android.

Esta solicitação NÃO autoriza merge, release de produção, alteração da main
nem encerramento da issue. O workflow tem gatilho de push restrito exatamente
a este arquivo e a esta branch para não produzir APKs nos próximos commits
funcionais.

O smoke físico deve inspecionar: playlists V1/V2, seleção de referência,
grupos/NOT, filtros, preview, ordem de álbuns, execução Play/Fila até 500,
troca de conta, integridade de fontes, responsividade e logs.

A versão foi validada preliminarmente por Android CI #40737254340.
O APK terá seu próprio build e resultado de CI no SHA do gatilho.

## Correção de execução

O primeiro gatilho encontrou uma restrição de parsing YAML: dentro de
um scalar sem aspas, ` #192` foi interpretado como comentário.
A expressão do ambiente foi corrigida para `issue 192` (sem `#`),
preservando a autorização original e disparando apenas uma nova execução.

## UI redesign — segundo smoke autorizado

Autorizado explicitamente em 28/10/2026: **“Então vamos pro apk”**.

Novo build físico do **editor de playlists inteligentes redesenhado**, na
mesma branch de trabalho e com a Android CI do HEAD `05cb105210bd0f04045464bf78b6874642da8412`
aprovada ([#40789880437](https://github.com/jssantogit/BitChord/actions/runs/40789880437)).

Smoke no Galaxy A54, em particular:
- identificar a diferença entre “Nova playlist” e “Minhas playlists”;
- avançar Identidade → Regras → Grupo → Categorias → Condição → Revisão,
  inclusive rolando uma página antes de voltar e abrir a próxima;
- validar grupos aninhados, `NÃO`, E/OU e reordenação;
- pesquisar condição, usar numéricos/duração, aplicar origem local e
  referências a playlists salvas;
- conferir a prévia real e os cards de fontes sem seleção de provedor falsa;
- abrir as abas Inteligentes/Filtros/Clássicas, seus menus Mais, confirmação
  de exclusão e criação/revisão;
- testar Play/Fila e observar se mini-player/tab bar não se sobrepõem;
- guardar diagnóstico se houver erro.

Disparo limitado a este arquivo pelo workflow
`.github/workflows/physical-smoke-apk.yml`. Esta edição documental não
altera o comportamento do app. O commit deste gatilho será o SHA do APK,
e o próprio workflow repetirá os testes e fará o `assembleDevDebug`.

Nenhum merge, release ou alteração da `main` autorizado.

## Smoke físico — fidelidade visual BitChord, 29/10/2026

Novo APK **explicitamente autorizado** pelo usuário: **“Dispare apk”**.

Esta rodada verifica a nova linguagem visual Android implementada após as
capturas do Galaxy A54: controles próprios em vez de chips e inputs contornados
Material 3, listas agrupadas para categorias/condições, CTA com AccentRed do
BitChordTheme, escolhas em folha inferior, capa neutra, primeira condição não
pré-selecionada, revisão, retorno/rolagem entre etapas e menus de grupos.

Código-base: `d265f26a07d11663644abe237ae151a2cdb47249`.
CI Android #40798528075: SUCCESS nesse SHA. O commit de disparo acrescenta
**apenas esta autorização documental** e não modifica código nem temas.
O próprio workflow testa novamente e compila `assembleDevDebug`; confirmar
conclusão/upload antes de anunciar APK pronto.

Inspecionar no Galaxy A54:
- identidade inicial sem regra obrigatória implícita; primeira condição;
- layout contra specs/tensions e `DESIGN.md`: tipografia, recuos, altura,
  agrupamento das linhas, botões vermelho/escuro, acesso com teclado;
- E/OU, NÃO, referência salva, catálogo de categorias, campo e valor;
- ordenação, folha inferior, botão de revisão e edição do rascunho;
- prévia real e persistência, fontes sem afirmações falsas, Play/Fila;
- biblioteca de filtros/playlist, exclusão confirmada e logs.

**Proibido:** merge, release pública, alterações da main ou encerramento
automático da issue. O APK é somente **Dev Debug** para teste físico.
