# Tri-source editorial integration — PR nova (2026-11-13)

## Evidência de partida, não pressupostos

| Fonte | PR | Head congelado nesta análise | Estado |
|---|---|---|---|
| MusicBrainz | #209 | `d21e1259f08a` | identidade terminada e CI verde; base desta PR |
| Discogs complementar | #211 | `19c6b078c010` | 205 commits atrás de #209; conflito ao mesclar |
| iTunes | #214 | `81b337b6b396` | seis arquivos novos sem sobreposição com #209 |
| Interface | #212 | `4f6219347294` | trabalho visual em PR separada; divergiu de #209 |
| Discogs independente | #215 | `02662f0986b2` | backlog preservado, fora do caminho crítico |

A PR #211 tem 145 arquivos no diff relativo à sua base antiga, com 31 caminhos
também alterados em #209. Sua ponte atual parte de um MusicBrainz **Release**
com MBID verificado, e não serve automaticamente para uma edição Apple sem
vínculo MusicBrainz. A implementação #211 também possui projeções de shelves que
substituem o `releaseKind` de linhas MusicBrainz; NÃO transportar essa lógica
para a vitrine Apple-first.

A PR #212 tem 281 arquivos alterados sobre uma base antiga #209 (45 commits de
identidade posteriores no MB). Não fazer merge de UI nesta PR. As interfaces
novas devem usar os contratos tipados nativos que #212 pode consumir.

## Decisão arquitetural

| Campo/questão | Autoridade ou regra |
|---|---|
| Existência comercial / visibilidade / título / ordem / data / tracklist | Apple no storefront escolhido |
| Capa | Apple na edição exata, com proveniência preservada |
| Artistas, gravações, Release/ReleaseGroup e relações de identidade | MusicBrainz, sem converter texto/UPC/ISRC em MBID por conta própria |
| Classificação Álbum/EP/Single | etiqueta Apple explícita; se ausente, evidências de MB/Discogs *vinculadas e verificadas* |
| Edições físicas, gravadoras, país, formatos | Discogs via Release específico verificado, não Master genérico |
| Rankings | Apple RSS/Charts, independente do serviço de busca |
| Reprodução, biblioteca, avaliações, playlist e downloads | subsistemas existentes; nunca tornar a linha de catálogo reproduzível por inferência |

Invariantes obrigatórios:

1. **Apple cria e ordena a lista.** MusicBrainz/Discogs não inserem,
   removem ou renomeiam produtos Apple.
2. **Identidades tipadas.** `itunes:br:album:<id>`, MB ReleaseGroup, MB Release,
   MB Recording, Discogs Master e Discogs Release não são intercambiáveis.
   Dois produtos Apple de mesmo nome não se fundem apenas pelo nome.
3. **Vínculos com proveniência.** Um link de MB ReleaseGroup não confirma por si
   só uma variante/edição Discogs; UPC isolado e ISRC isolado geram candidatos.
   Match ambíguo/conflitante permanece explicitamente inconclusivo.
4. **Classificação não é identidade.** O tipo MusicBrainz pode preencher
   categoria na vitrine Apple depois do crosswalk exato; não pode substituir o
   lançamento Apple. A edição Discogs pode corroborar/corrigir formato quando
   seu vínculo é comprovado; divergências fortes são exibidas como conflito.
5. **Sem perda de dados.** Histórico, ratings por MBID, biblioteca local,
   Navidrome, áudio e pins preservam suas chaves; conteúdo MB-only continua
   consultável em fluxo separado, não vira um lançamento Apple fictício.
6. **Região e falhas honestas.** Brasil como storefront padrão, com região
   explícita em todo cache/identidade. Ausência por geobloqueio, paginação
   incompleta ou falha HTTP não prova inexistência. Falha Apple não promove
   silenciosamente MusicBrainz a fonte editorial.
7. **Fonte Apple incompleta.** Search API não informa Album/EP/Single
   conclusivamente e lookup de artistas tem limite; não declarar catálogo
   exaustivo antes de estabelecer descoberta paginada ou prova de completude.
   Não classificar a partir de contagem de faixas.
8. **Sem mudanças prematuras no visual.** Reusar modelos e componentes nativos
   e o estilo da #212, evitando reintroduzir Material 3 ou o tema legado.

## Fases e gates

**Fase A — transposição segura da Apple (#214).** Copiar os seis arquivos
inalterados para a nova branch baseada no HEAD final da #209, executar CI;
abrir PR draft. Nada de cutover ou merge.

**Fase B — extrair Discogs sem branch velha.** Portar componentes de
`metadata/discogs`, evidência/atribuição e testes da #211 por dependências
reais. Aplicar adaptação *mínima* e explícita nas enumerações de proveniência
(`DISCOGS`, campos de edição) do HEAD final #209. Não substituir por inteiro
MainActivity, MainViewModel, Settings ou os arquivos nativos da #209. Testar
novamente os cases da #211.

**Fase C — reconciliação tri-source.** Projetar `AppleProductId` e
`SourceLink` tipados, cache de links e classificador de exibição
`Album/EP/Single/Unknown/Conflict`. Fonte Apple monta o conjunto;
MusicBrainz oferece identidade; Discogs complementa edição e tipo.
Primeiro TDD offline: mesmo título, variante, múltiplos créditos, UPC
compartilhado, MusicBrainz ausente, Master vs Release, conflito de fontes,
BR vs US e cancelamento/limites de requisição.

**Fase D — conectar catálogo Apple-first aos adaptadores de apresentação.**
Trocar a *origem dos dados*, não reconstruir o visual. Definir navegação
Apple-scoped sem `catalog:RELEASE_GROUP:<MBID>` falso. Artistas grandes
precisam de conclusão paginada; ratings e biblioteca mantêm identidade
própria. Integração com #212 somente depois de reconciliar o HEAD atual.

**Fase E — rankings e aceitação.** Testar RSS/Charts separadamente,
sem popularidade inventada; CI Android e módulos compartilhados necessários ao Android, testes de
retorno sem rede, internacionalização e smoke físico consolidado apenas ao
fim, conforme orientação do proprietário. Nenhum APK de smoke antecipado.

## Critério de encerramento

Em amostra congelada (incluindo HOT, EASY, How Sweet, variantes do mesmo nome,
editions de Discogs, artistas extensos), nenhum produto Apple ganha MBID
errado, nenhuma edição é fundida só por título, categorias fortes não são
adivinhadas e não se perde reprodução, biblioteca, avaliação ou navegação.
Toda CI aplicável fica verde no HEAD integrado exato. Até lá, PR permanece
draft e não é mesclada.

## Diretriz de execução — Android exclusivamente (2026-11-13)

O proprietário confirmou que **somente Android** é alvo de trabalho, manutenção e validação. Desktop, TV e Wear não são critérios de aceite desta integração. Os workflows dessas plataformas podem ser disparados automaticamente, mas não exigem esforço, correção nem CI verde para esta entrega. Garantir CI Android (incluindo testes dos módulos compartilhados usados pelo app) e um único smoke físico final após o desenvolvimento, sem APK antecipado.

A PR #216 recebeu `AppleMusicBrainzEditionMatcher`, `AppleTriSourceEditorialReconciler` e `AppleEditorialCatalogRepository` antes do cutover; nenhum deles deve ser descrito como fonte ativa das telas até que os viewmodels e a navegação Android sejam realmente migrados.
