# Discogs — modo independente, primeira fatia controlada (13/11/2026)

## Decisão de produto

O Discogs deve poder operar em **dois contextos distintos**:

1. **Complementar** (estado atual, PR #211): metadados externos e edições corroboradas de entidades MusicBrainz. MusicBrainz continua a identidade canônica; só chamar algo de "ausente no MusicBrainz" após verificação completa, com resultado inconclusivo quando a evidência falta.
2. **Catálogo independente** (esta PR, trabalho iniciado): navegar a base Discogs em seu próprio namespace, sem precisar que um produto exista ou falte no MusicBrainz. O usuário poderá comparar a qualidade dos dois catálogos **sem combinar automaticamente os resultados**.

**Não** é troca de provedor padrão nem autoriza substituição de `ReleaseId`, `RecordingId`, playlists, Ratings, biblioteca ou fila. O modo independente não fabrica MBIDs ou fontes de reprodução.

## Escopo implementado nesta primeira fatia

- `DiscogsStandaloneProjection`: recebe apenas registros tipados `DiscogsRecord` e um **Discogs Artist ID**. Nunca exige títulos, indexação ou completude MusicBrainz; preserva `discogs:release:<id>` para cada edição, inclusive quando duas edições compartilham `master_id`.
- `DiscogsStandaloneArtistCatalog.browse(artistId, page)`: lê o índice público `/artists/{id}/releases` (página 1–3) e hidrata cada Release/Master por endpoints fixos do cliente existente, revalidando `artistIds`. Não faz chamadas ao MusicBrainz, pesquisa sem credencial ou scraping.
- **Limites de evidência**: o índice aceita no máximo 12 itens por página; o limite atual é três páginas e nunca se apresenta como discografia integral. Falhas/parcialidade são expostas por estado, não convertidas em `Empty`; no caso Master, `main_release` é apenas edição de referência, não a totalidade das versões.
- Tipos não comprovados e datas ausentes permanecem `null`; créditos do Discogs são preservados. Registros `Unofficial` não são promovidos a oficiais silenciosamente; mantêm marcador.
- Dados sem arte ou mídia reproduzível. Links e rótulos de atribuição para Discogs permanecem junto dos dados em futura UI. A autenticação ampla por `/database/search` continua opt-in com cofre de token criptografado já implementado na #211; este slice **não a aciona**.
- A feature permanece **desligada por padrão e sem UI**, para preservar o APK funcional até completar a navegação.

## Próximas fatias, separadas

**I. Navegação nativa e escolha de catálogo:** opção `MusicBrainz` / `Discogs` nas configurações de catálogo, além de `Discogs como complementar`; binding de artist/release/search à composição compartilhada. O item Discogs só deve exibir ações permitidas (ver detalhes e fonte); **não** criar play, ratings persistidos, pin ou playlist sem identidade local verificada. Manter estado da escolha isolado de perfis e dados canônicos; nenhum flip global de autoridade.

**II. Descoberta de artistas independente:** entrada por Discogs Artist ID validado; busca geral Discogs somente com token opcional (ou caminho público suportado e autorizado), sem inferir IDs pelo nome. Testar paginação/cancelamento, conflitos de artista e resultados de índice.

**III. Comparação empírica controlada:** mesma lista de artistas e queries nos dois catálogos, lado a lado e sem unir registros por título. Registrar em cada fonte: páginas tentadas/completas, registros tipados obtidos, edições por Master, campos faltantes, duplicações visuais não explicadas, títulos desambiguados, capas realmente disponíveis, classificações justificadas e custo/latência de rede. Separar métricas **observadas** das **não mensuráveis**. Classificação/identidade correta deve ser auditada contra fontes primárias, não deduzida da concordância entre provedores.

## Aceite antes de tornar visível

1. Tests dos limites de identidade, de versões distintas no mesmo Master, ausência de MBID sintético, de nenhum Playback, de request parcial, de auth/rate-limit, de entradas inválidas e cancelamento.
2. Android/JVM focused CI verde na branch exata; testes completos de regressão antes de publicar o primeiro APK específico dessa função.
3. Para mudar o seletor no app, precisa haver uma rota de artista e de edição funcional, com atribuição local ao registro, mesmo sem imagens, e cobertura de estados vazios/indisponíveis. **Não exibir modo quebrado apenas porque a camada de dados existe.**
4. Smoke do Galaxy A54, incluindo a correção de scroll na PR #211 de base, validação visual e retorno à escolha MusicBrainz sem perda de dados.

## Restrições Discogs

A [API de Discogs](https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use) distingue campos catalográficos CC0 de conteúdo restrito (imagens/marketplace/usuários). Não transferir imagens ou dados restritos; exibir `Data provided by Discogs.` com link exato para a fonte e o aviso de não afiliação em local apropriado. Dados exibidos devem respeitar frescor de no máximo seis horas; este slice não persiste resultados e usa o limitador existente. A pesquisa geral pelo endpoint `/database/search` depende de autenticação, sem token embutido. Não presumir que um índice completo foi recebido por um page limit.

## Dependências e isolamento

- Base de desenvolvimento **PR #211** na revisão `345178a7` (inclui correção de scroll); #209 e main sem alteração por esta PR.
- Para usar a nova camada em aplicativo, ligar provider-mode à composição nativa tipada, nunca introduzir novo editor ou biblioteca independente.
- Primeira fatia = backend read-only + testes, **não catálogo independente pronto**.
