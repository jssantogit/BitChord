# Especificação de tela — Biblioteca e pastas

> Etapa: **Opções de uma categoria** · Status: **direção visual aprovada; implementação pendente** · Plataforma: **Android/Compose**.  
> Mockup de referência discutido em 28/10/2026; não confundir pixels de render com métricas medidas.  
> [Índice](../README.md) · [Tensões da tela](tensions.md) · [Contrato transversal](../RULES_ENGINE_CONTRACT.md) · [Design do app](../../../../DESIGN.md)

## Objetivo

Exibir opções concretas de uma categoria, com textos de ajuda que distinguem fonte, diretório e presença na biblioteca.

## Correspondência com o mockup

Cabeçalho com ícone de pasta laranja, pesquisa na categoria e grupos ORIGEM DAS MÚSICAS / BIBLIOTECA / ORGANIZAÇÃO, em cards com divisores.

## Anatomia da tela

### Cabeçalho

Voltar à tela 05; ícone de categoria e título 'Biblioteca e pastas'; subtítulo explica regras de origem.

### Pesquisa local

Campo específico para filtrar itens da categoria; a pesquisa global da tela 05 permanece disponível após voltar.

### Origem das músicas

Pasta de origem, Fonte do catálogo, Caminho do arquivo: opções com resumos diferentes e ID próprio.

### Biblioteca

Presença na biblioteca, Disponível offline, Em uma playlist; oferecer somente se há evidência confiável por gravação.

### Organização

Data de adição pode aparecer como categoria cruzada/atalho, mas seu ID canônico fica em Datas e lançamentos; evitar regras duplicadas.

## Interações e navegação

- Selecionar Pasta de origem abre 07; outras linhas abrem editor tipado específico ainda a projetar.
- A pesquisa reduz linhas sem reordenar semanticamente, respeita títulos/sinônimos.
- Desabilitar regra incompatível por fonte, com explicação acessível e recuperação quando possível.

## Estados e tratamento de erros

- Fonte local indisponível: Pasta de origem com motivo/ação de configurar, não navegação quebrada.
- Nenhuma regra compatível: estado vazio informado.
- Permissão revogada no retorno: editor 07 solicita reautorização.
- Texto longo: não cortar descrições críticas.

## Critérios de aceite (implementação futura)

1. Cada linha tem alvo de toque e rótulo acessível distinto.
2. Buscar 'pasta', 'origem', 'diretório' encontra Pasta de origem.
3. Não duplicar 'Fonte do catálogo' como pasta do dispositivo.
4. Abrir/back restaura posição dentro da lista.

## Restrições transversais

- Toda ação de navegação preserva o rascunho até descarte explicitamente confirmado.
- Coluna rolável e CTA com safe area e IME; tamanho de fonte ampliado não pode ocultar ação importante; alvos de toque preferencialmente `>=48dp`.
- `MaterialTheme` e tokens de [DESIGN.md](../../../../DESIGN.md); usar vermelho dos mockups como proposta de estado/CTA local, não redefinir a paleta global.
- Registrar estados de carregamento, falha e ausência com texto acessível, nunca dados artificiais.
- Não supor infraestrutura de regras/fonte/playlist implementada só por constar da tela.

## Pendências e decisões

Ver [tensions.md](tensions.md).
