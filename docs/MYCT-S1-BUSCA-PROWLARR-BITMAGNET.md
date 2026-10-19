# MYCT-S1/S2 — Busca de torrents: Prowlarr primeiro, Bitmagnet depois

**Acordado em:** 19/10/2026  
**Situação:** S1A/S1B aprovadas no Fedora/Moto G06 e incorporadas à `main` pela PR #44 (`265b4e2`); MYCT-S1C implementada em candidata (filtros, paginação e diversidade), aguardando aceite físico; MYCT-S2/Bitmagnet opcional.
**Issue:** [#43](https://github.com/Samuel-Francisco-AS/My-CT/issues/43)  
**Base inspecionada:** `main`, após consolidação da PR #42 (commit `99aaa24538`).

## Por que fazer

Hoje o usuário pesquisa magnets em sites externos e os transfere manualmente para **Downloads → Torrents** do My-CT. Quer pesquisar diretamente no Moto G06 por uma nova seção do aplicativo, com o Fedora 44 headless atuando como intermediário. A pesquisa deverá ser **sob demanda e dirigida por palavras**, e não um crawler DHT aleatório como requisito inicial.

Prowlarr agrega pesquisas em indexadores configurados: evita visitar cada interface, mas **ainda depende da disponibilidade desses indexadores**. Bitmagnet acrescentaria posteriormente um catálogo local descoberto por DHT, que pode ser consumido via Torznab no próprio Prowlarr.

## Desenho mínimo

```text
Moto G06 — My-CT (Kotlin/Compose)
  ├── Busca (NOVA): pesquisar e selecionar resultado
  │      |
  │      v
  │   SSH autenticado existente
  │      |
  │      v
  │   ~/.local/bin/myct-search (helper Fedora)
  │      |
  │      v
  │   HTTP 127.0.0.1:9696 / API Prowlarr
  │      ├── Indexadores configurados (S1)
  │      └── Bitmagnet Generic Torznab (S2, opcional)
  │
  └── Downloads → Torrents (EXISTENTE)
         |
         v
      ~/.local/bin/myct-torrent → Transmission RPC loopback :19091
         |
         v
      ~/Downloads/My-CT/Torrents → Media/Jellyfin (existente)
```

A separação **Busca = descobrir**, **Downloads = executar/gerenciar** é intencional. Não criar outro cliente torrent nem mudar o diretório/ciclo de nomeação. Não tocar automaticamente no Jellyfin.

O Prowlarr utiliza API HTTP de busca (GET ou POST `/api/v1/search`). É responsabilidade do helper esconder credenciais, converter as respostas para um contrato pequeno e reportar falhas sanitizadas. A lista de campos reais será confirmada com a versão instalada antes de congelar o contrato. A API key de Prowlarr é **somente Fedora**, em configuração privada do usuário; não deve aparecer em argumento de processo, APK, resposta JSON, logs ou Git.

## MYCT-S1A — Fedora: pesquisa útil primeiro

1. Inspecionar Fedora, serviços atuais, recursos disponíveis, instalação .NET/Prowlarr e ocupação da porta 9696. **Não instalar ainda sem executar essas verificações no host.**
2. Escolher método enxuto de execução do Prowlarr, preferencialmente um binário Linux x64 e serviço systemd para o usuário; sem desktop, VM ou stack de contêineres obrigatória.
3. Manter HTTP limitado a loopback. Validar API pelo próprio Fedora via SSH; não liberar porta no firewall, não criar redirecionamento público.
4. Configurar inicialmente 1 ou 2 indexadores compatíveis, sem presumir que 1337x ou outro site está funcionando. Verificar busca textual manual real.
5. Criar helper `ops/myct-search` com ações mínimas `status` e `search <consulta>`, saída JSON padronizada (lista de resultados com título, tamanho, indexador, seeders opcionais, magnet se existir, estado de erro). Limitar consulta e quantidade de resultados para respostas legíveis no aparelho.
6. Teste mínimo: uma busca real, indexador sem resultados, servidor desconectado, resultado malformado e resposta sem magnet. Medir CPU/RAM no Fedora e impacto durante reprodução pelo Jellyfin quando possível.

**Gate S1A:** o proprietário consegue pesquisar via CLI a partir do SSH do My-CT, com indexadores de verdade; não exige Android novo.

## MYCT-S1B — Android: aba Busca

1. Acrescentar `AppSection.Search` / rótulo **Busca** ao drawer e um ícone no estilo atual. Preservar o motor de animações R5, Cofre e navegação.
2. `SearchModel` utiliza conexão SSH existente, job cancelável e geração para descartar respostas antigas. Não adicionar acesso HTTP direto pelo Android.
3. `SearchScreen`: campo de pesquisa, ação Pesquisar, indicador de carregamento, resultados (título, tamanho, origem, seeders quando conhecidos) e erros/estado vazio. Busca sempre iniciada pelo usuário; sem polling de catálogos.
4. Cada resultado com **magnet válido** terá botão explícito **Adicionar aos downloads**. Usar o adaptador `myct-torrent` já existente; avisar sobre envio aceito, duplicata e estado a conferir na aba Downloads. Não disparar múltiplas vezes por retentativa automática.
5. Se a fonte fornecer somente arquivo `.torrent`, página ou link indireto, exibir resultado sem ação de envio automático nesta primeira versão. Investigar suporte adicional em microetapa, sem contornar o caminho supervisionado pelo My-CT.
6. Preservar package/assinatura, dados instalados, scroll de telas existentes, arquivos/hardlinks e estado de Downloads.

**Gate S1B:** no Moto G06, pesquisar em indexador real e adicionar **um magnet público e permitido** ao Transmission via comando existente. Verificar o item em Downloads/Torrents, inclusive duplicata. Build ou mock isolados não equivalem a esse aceite.

## MYCT-S2 — Bitmagnet, apenas após decisão baseada em S1

- Confirmar utilidade do Prowlarr, armazenamento livre, consumo do Fedora e demanda por catálogo DHT.
- Instalar Bitmagnet com **banco PostgreSQL próprio**, sem migração nem acesso ao schema Core My-CT.
- Configurar sua descoberta DHT como **opt-in** e limitar períodos/recursos; não supor que a pesquisa DHT atende a consultas guiadas em tempo real.
- Usar endpoint `/torznab` via indexador **Generic Torznab** do Prowlarr. A aba Busca permanece a mesma; idealmente apenas a fonte dos resultados muda.
- Medir crescimento do banco e efeitos em disco/rede. Caso não compense, adiar/descartar S2 sem bloquear S1.

## Aceite, limites e evidências

- Fazer primeiro um **fluxo vertical útil**; não instalar Sonarr, Radarr, Flaresolverr, automação RSS, rotinas periódicas ou painel extra sem necessidade comprovada.
- O Prowlarr reúne fontes externas, mas não elimina sua dependência; erros de indexador devem ser comunicados, sem prometer cobertura universal.
- Jamais expor API/token do Prowlarr, Transmission RPC ou PostgreSQL à internet ou persistir secrets no Android.
- Nenhuma mudança em serviço Fedora, firewall, SELinux, host, RPM, dados ou download real nesta fase **documental**. Implantação ocorre em etapa explícita no próprio host.
- Registrar comandos testados, resultados, consumo e pendências. Manter PR de implementação em draft até teste real no Fedora e Moto G06.
- As correções do magnet da PR #42 e operações de Mídia/Downloads continuam sendo a base, nunca reintroduzir código antigo da PR #34.

## Referências técnicas

- [API v1 Prowlarr](https://prowlarr.com/docs/api/)
- [Instalação Prowlarr](https://prowlarr.com/)
- [Configurar indexadores (Servarr Wiki)](https://github.com/Servarr/Wiki/blob/master/prowlarr/indexers.md)
- [Bitmagnet: integração Prowlarr via Torznab](https://bitmagnet.io/guides/servarr-integration.html)
- [Bitmagnet: requisitos e riscos](https://bitmagnet.io/faq.html)

## MYCT-S1A — implementação e relatório de validação (19/10/2026)

### Escopo e aceite desta entrega

Implementado `ops/myct-search`, Python 3 + biblioteca padrão, com somente
`status` e `search "consulta"`. Critérios definidos antes da implementação:
status autenticado, pesquisa Ubuntu no Knaben, JSON limitado com metadados,
distinção entre magnet e link indireto, vazio e erros sanitizados. Esses
critérios foram verificados no próprio Fedora. **Após a entrega original
`371c925`, o proprietário confirmou instalação em `~/.local/bin` e aprovação
da pesquisa real.** A revisão S1A-R está registrada ao final deste relatório.
Nenhuma alteração Android nesta etapa.

Ponto de partida confirmado: `/home/sam/Projetos/My-CT`, branch existente
`myct-s1-search-plan`, commit `0939da24656f2ba520301c67ce8461381a8023fe`, árvore
limpa e sincronizada com `origin` (`0/0` após fetch). PR #44 permanece draft,
sem merge; a issue #43 acompanha também a futura fase Android.

Ambiente observado: Fedora 44, kernel `7.2.8-200.fc44.x86_64`, Python
`3.14.7`, Git `2.55.0`. Prowlarr `2.6.5.5623`, serviço de usuário
`myct-prowlarr.service` ativo, escuta/configuração em `127.0.0.1:9696` e
Knaben habilitado (id 1). Instalação/configuração existentes foram apenas
consultadas; não houve reinstalação, reconfiguração ou reinício.

### Credencial e chamadas

O helper lê somente o campo `ApiKey` do arquivo existente
`~/.local/share/myct/prowlarr/config.xml`, sem duplicar a credencial. Exige
arquivo regular, do usuário atual, sem permissões de grupo/outros e sem
symlink no arquivo; o arquivo real foi observado com modo `0600`.
A chave segue apenas no header HTTP `X-Api-Key`, nunca em argumentos,
query string do helper, saída, relatórios ou Git. Erros usam mensagens fixas,
sem reproduzir corpo HTTP ou exceções. Títulos/nomes também têm redação da chave.

- `status`: GET `/api/v1/system/status` e `/api/v1/indexer`.
- `search`: consulta os indexadores habilitados com protocolo `torrent`,
  depois GET `/api/v1/search` com `query`, `type=search`, `indexerIds` e `limit=20`.
- HTTP fixo em loopback, sem proxy de ambiente, redirects, retentativas ou POST.
  Não segue `downloadUrl`, `magnetUrl` HTTP ou páginas de resultados.
- Consulta de 1–200 caracteres imprimíveis; timeout de socket de 7 s para
  metadados e 25 s para pesquisa. Esses valores não são um prazo global da operação.
- Resposta HTTP limitada a 2 MiB, saída a 256 KiB e **20 resultados**.
  O limite local é necessário: a API real devolveu 84 mesmo com `limit=20`.
  A ordem fornecida pelo Prowlarr é preservada; sem paginação nesta etapa.

### Contrato JSON v1

Uma linha JSON em stdout, inclusive em falhas. `schema_version: 1` e `ok`
estão presentes em todas as respostas. Exit code 0 em sucesso/vazio, 2 em
uso/configuração inválidos e 3 em falha da API/indexadores.

`status` retorna `state: "ready"`, `version`, `indexers: [{id, name}]` e
`result_limit: 20`. Ready significa API autenticada e pelo menos um indexador
torrent habilitado; não garante que cada fonte esteja saudável naquele momento.

Exemplo real da S1A original, antes da revisão S1A-R, com envelope reduzido:

```json
{
  "schema_version": 1,
  "ok": true,
  "state": "results",
  "count": 20,
  "received_count": 84,
  "discarded_count": 0,
  "truncated": true,
  "result_limit": 20,
  "results": [
    {
      "title": "[amd64] Ubuntu*Pack 24.04.1 LikeWin (июль 2026)",
      "size_bytes": 4720506880,
      "source": "Knaben",
      "indexer_id": 1,
      "seeders": 7,
      "leechers": 0,
      "link_type": "indirect_url",
      "magnet": null
    }
  ]
}
```

`size_bytes`, `seeders`, `leechers` e `indexer_id` são inteiros não negativos
ou `null` quando ausentes/inválidos; zero conhecido é preservado.
`source` pode ser `null`. Título limitado a 512 caracteres e origem a 128.
Entradas sem título ou fora do protocolo torrent são descartadas e contadas;
lista não vazia sem nenhuma entrada válida gera `invalid_response`.
`received_count` conta a lista desta resposta da API, sem prometer total global.

`link_type` é um dos seguintes, em ordem de preferência:

| Valor | Significado |
| --- | --- |
| `magnet` | URI direta com `xt` BitTorrent v1 válido (preservada), ou magnet mínimo gerado de `infoHash` v1 válido na S1A-R |
| `torrent_url` | URL HTTP(S) cujo caminho termina em `.torrent`; é uma indicação pela extensão, sem baixar/verificar o conteúdo |
| `indirect_url` | URL HTTP(S) intermediária, proxy ou página; jamais tratada como magnet |
| `none` | Nenhum link reconhecido/aceito |

URLs HTTP não são devolvidas, pois podem carregar API keys/passkeys. Na S1A-R,
o magnet direto tem prioridade; sem ele, `infoHash` válido gera um magnet mínimo
sem trackers. Sem ambos, `magnet` é `null` e a classificação HTTP/none é preservada.
O campo adicional `magnet_source` é `"direct"`, `"infohash"` ou `null`.
Os campos anteriores e `schema_version: 1` são preservados para o futuro Android.
Essa checagem sintática não substitui a validação do helper `myct-torrent`
antes de qualquer futura inclusão explícita. Magnets somente v2 não estão
habilitados neste contrato, acompanhando o fluxo v1 existente.

Pesquisa com lista vazia retorna `state: "empty"`, `results: []`, contagens
zero e `truncated: false`, com exit 0. Uma resposta vazia do Prowlarr não prova
saúde dos indexadores: a própria API pode ocultar determinadas falhas como
lista vazia. Conferir a interface local se o vazio parecer inesperado.

Falhas retornam `ok: false`, `state: "error"` e
`error: {"code": "timeout", "message": "Prowlarr excedeu o tempo de espera."}`,
por exemplo. Códigos: `usage`, `config`, `authentication`, `connection`,
`timeout`, `upstream`, `invalid_response`, `response_limit`, `no_indexers`.

### Diagnóstico inicial de magnets — não confundir o nome do campo com o protocolo

A resposta real inicial de Ubuntu continha 84 itens. Foram observados
`title`, `size`, `indexer`, `indexerId`, `seeders`, `leechers`, `protocol`,
`magnetUrl`, `downloadUrl`, `infoUrl` e `infoHash`, entre outros campos.
**80 `magnetUrl` eram HTTP e quatro estavam vazios.** `downloadUrl` tinha
40 URLs HTTP e 44 vazios. Os 84 `infoUrl` eram páginas HTTPS.

Os URLs locais apontavam para `/<indexerId>/download`, com parâmetros
`apikey`, `link` e `file`; seus valores não foram registrados. O campo `link`
é opaco/protegido. O comportamento foi confirmado no código oficial da versão
instalada: [SearchController v2.6.5.5623](https://github.com/Prowlarr/Prowlarr/blob/v2.6.5.5623/src/Prowlarr.Api.V1/Search/SearchController.cs)
converte também `MagnetUrl` em proxy, e
[DownloadMappingService v2.6.5.5623](https://github.com/Prowlarr/Prowlarr/blob/v2.6.5.5623/src/NzbDrone.Core/Download/DownloadMappingService.cs)
protege o destino antes de compor o URL autenticado.

Por isso os 20 resultados da **S1A original** tinham `link_type: "indirect_url"`
e `magnet: null`. A S1A-R supera essa limitação quando há `infoHash` válido,
sem resolver o proxy. **Envio de um magnet real a Downloads continua sem teste
nesta etapa.** Não houve tentativa de decifrar ou seguir os links HTTP.

### Testes e consumo observado

```bash
cd /home/sam/Projetos/My-CT
python3 -m unittest discover -s ops/tests -p test_search.py -v
./ops/myct-search status
./ops/myct-search search "Ubuntu"
./ops/myct-search search "myct-s1a-no-results-7e9138c2d496"
git diff --check
```

- **12 testes automatizados aprovados**: campos reais/zeros/nulos, limite e
  truncamento, seleção de indexador torrent, magnet direto versus proxy e
  `.torrent`, entrada malformada, vazio, configuração privada, validação de
  consulta, header de autenticação e ausência de key/URLs na saída.
- 401/403, conexão recusada, timeout, HTTP 500/redirect, JSON inválido e
  resposta excessiva foram simulados por mocks. Nenhum serviço foi parado ou
  reconfigurado para provocar falhas.
- **Reais:** `status` ready; Ubuntu retornou 84 itens, convertidos para 20,
  sem descartes, saída de **4.743 bytes**; consulta única sem correspondência
  retornou empty com **162 bytes**. Exit 0 nas três execuções.
- Autenticação negativa também real: GET de status com chave sintética
  inválida retornou o código `authentication`, sem expor corpo/credencial.
  Um verificador em memória confirmou ausência da chave real em stdout/stderr
  das três execuções medidas; resposta bruta não foi gravada no repositório.

Medições pontuais via `/usr/bin/time`, sem dependência instalada:

| Operação | Tempo de parede | CPU usuário + sistema | Pico RSS do helper |
| --- | --- | --- | --- |
| status | 0,06 s | 0,05 + 0,00 s | 18.720 KiB |
| Ubuntu | 1,80 s | 0,07 + 0,00 s | 19.852 KiB |
| Sem correspondência | 0,61 s | 0,05 + 0,01 s | 18.652 KiB |

No intervalo das três operações, o cgroup preexistente do Prowlarr passou de
184.877.056 para 186.441.728 bytes (~176,3 → 177,8 MiB) em `MemoryCurrent`;
`CPUUsageNSec` passou de 18.203.417.000 para 18.540.682.000 (~0,337 s).
São duas amostras, não pico ou atribuição exclusiva às pesquisas. Serviço
continuou ativo. Helper com **8.402 bytes** de código, sem cache de pesquisas,
banco, daemon ou dependências novas. Não foi medido impacto durante reprodução
Jellyfin nem feita prova no Moto; não há afirmação de ausência de impacto sob carga.

### Instalação manual do helper e retomada

O proprietário instalou, atualizou e aprovou a S1A-R. O agente não reinstalou
helpers na etapa Android. Para uma futura atualização no Fedora, como `sam`, sem sudo:

```bash
cd /home/sam/Projetos/My-CT
install -Dm755 ops/myct-search "$HOME/.local/bin/myct-search"
"$HOME/.local/bin/myct-search" status
"$HOME/.local/bin/myct-search" search "Ubuntu"
```

Não copiar a API key para argumentos ou para o Android. O helper instalado
continua lendo o XML privado existente. Se houver `config`, conferir localmente
o caminho/proprietário/permissões sem imprimir seu conteúdo. Se houver
`connection`/`timeout`, consultar `systemctl --user status myct-prowlarr.service`
e a interface local; não reinstalar/reconfigurar automaticamente.

Alterações Git não atualizam `~/.local/bin/myct-search`. Reexecutar o `install`
apenas quando o proprietário decidir instalar/atualizar o helper. Não são
necessários novo serviço, restart, porta pública ou alteração do Transmission.

### Pendências S1B

- Instalar a candidata S1B e validar a seção Busca pelo SSH existente no Moto G06.
- Usar `magnet` para inclusão explícita, tanto `direct` como `infohash`.
  Resultados sem magnet continuam sem ação automática de download.
- Testar qualquer inclusão somente por ação explícita via `myct-torrent`
  existente; verificar duplicata, Downloads e regressões no Moto G06.
- Manter PR #44 draft/sem merge e issue #43 aberta. S2/Bitmagnet não iniciado.

**Próxima ação:** gate físico S1B descrito ao final. Nas etapas S1A/S1A-R,
Android, Transmission, Jellyfin, PostgreSQL e Core permaneceram sem alterações.

## MYCT-S1A-R — fallback por infoHash (19/10/2026)

Microcorreção solicitada após instalação/aceite da S1A original pelo proprietário.
Base `371c925`, na mesma branch `myct-s1-search-plan`, limpa e sincronizada
com `origin` antes das alterações. O diagnóstico adicional do proprietário
identificou 80 hashes v1 válidos nos 84 itens Knaben, permitindo gerar magnets
sem tocar nos proxies autenticados.

Alteração restrita à conversão de resultados em `ops/myct-search`, com testes
e documentação: magnet direto válido permanece intacto e prioritário. Como
fallback, `infoHash` deve ser uma string completa com **40 caracteres hexadecimais**
ou **32 caracteres Base32 (`A–Z`, `a–z`, `2–7`)**. Rejeita prefixos, espaços,
controles, padding, tamanhos incorretos e caracteres fora desses alfabetos.
Também suprime qualquer candidato que contenha a API key local.

O resultado gerado é somente `magnet:?xt=urn:btih:<infoHash>`, preservando a
grafia do hash. Sem nome, trackers, URLs HTTP ou parâmetros extras. Exemplo
**sintético** dos campos de link:

```json
{
  "link_type": "magnet",
  "magnet": "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "magnet_source": "infohash"
}
```

`magnet_source: "direct"` identifica URI direta; sem magnet é `null`.
A extensão é aditiva ao JSON v1, mantendo envelope, nomes/tipos anteriores,
estados, erros, limite de 20 e ordem dos resultados. O futuro Android pode
usar `magnet` como antes e, opcionalmente, mostrar a origem. Não houve alteração
Android ou dependências novas.

Validação executada:

- `python3 -m unittest discover -s ops/tests -p test_search.py -v`:
  **15 testes aprovados**, incluindo hashes hex/Base32 em ambas as caixas,
  inválidos/ausentes, prioridade direta, ausência de trackers, preservação das
  classificações sem hash e não exposição de credencial/URLs na saída JSON.
- **Uma pesquisa real Ubuntu pelo Knaben**, executando `main` com observação
  em memória da resposta da API: 84 recebidos, nenhum descartado; **80 magnets
  gerados e 4 sem magnet** no conjunto completo. Não houve magnet direto.
- Na saída limitada aos primeiros 20: **17 magnets `infohash` e 3 `null`**,
  `truncated: true`, JSON de **6.189 bytes**, exit 0, em **1,490 s** nesta
  medição pontual do processo de validação. Sem nova medição de pico RSS;
  as medições da S1A original acima permanecem históricas.
- Verificação em memória confirmou ausência da chave real e de URLs HTTP
  nos resultados convertidos. Todos os 80 magnets gerados foram aceitos por
  `myct-torrent.magnet`, apenas a função de validação existente: **sem RPC,
  inclusão no Transmission ou download**.
- `git diff --check` aprovado. Nenhum serviço reiniciado, configuração ativa
  alterada ou dado Fedora manipulado pelo helper; atualização instalada segue manual.

Limite do aceite: validade sintática do hash e compatibilidade com o validador
não comprovam disponibilidade de peers ou conteúdo. Sem trackers acrescentados,
a descoberta dependerá dos recursos já existentes do cliente BitTorrent.
Inclusão real e prova no Moto G06 pertencem à S1B, por ação explícita.

## MYCT-S1B — seção Busca Android (19/10/2026)

### Estado, escopo e aceite

O proprietário confirmou a S1A-R instalada/atualizada e aprovada no Fedora:
84 itens Ubuntu, 20 retornados, 17 magnets por infohash e 3 sem magnet nessa
listagem, sem downloads. A implementação Android partiu de `a9b4725`, na branch
existente `myct-s1-search-plan`, com árvore limpa e `0/0` após fetch.

Critérios antes de codificar: pesquisa somente por ação explícita via SSH exec,
estados previsíveis, limite visível, resultados sem magnet preservados, cancelamento
e descarte de respostas antigas, inclusão pelo fluxo TorrentAction.Add existente
e bloqueio de repetição enquanto ocupado. **A candidata não equivale ao aceite
físico:** pesquisa pelo Android, inclusão pública/autorizada, duplicata, fluidez,
teclado e regressões no Moto G06 ainda exigem o roteiro abaixo.

### Implementação

- `AppSection.Search`/Busca no drawer com lupa vetorial de traço coerente.
  Usa o host único, SaveableStateHolder e transições R5 existentes. As regras
  de proteção de capturas, incluindo Cofre, permanecem no mesmo fluxo.
- `SearchScreen.kt`: formulário compacto em LazyColumn, campo de linha única,
  IME Pesquisar e botão; teclado recolhido ao pesquisar. Formulário e resultados
  compartilham a rolagem para não ocupar permanentemente a tela do Moto.
  Cards mostram título, tamanho legível, indexador, seeders/leechers conhecidos.
  Truncamento informa o limite de 20; vazio, erro, desconexão e carregamento
  têm mensagens. Sem magnet, o botão permanece desabilitado e explica o motivo.
- `Search.kt`: comandos `myct-search status` e `search 'consulta'` com quoting
  de argumento único, validação de 1–200 caracteres imprimíveis e parser JSON v1
  limitado a 20 itens/256 KiB. `magnet_source` é preservado. Só magnets v1
  utilizáveis habilitam a ação; HTTP/arquivos torrent não viram comandos.
- `SshConnection.search` reutiliza `openDownloadExec`/`executeSshResult`, com
  canal exec independente do PTY, timeout de 45 s e fechamento em cancelamento,
  erro ou conclusão. Sem HTTP Android para Prowlarr, nova sessão de autenticação,
  transporte/persistência de API key ou impressão de conteúdo remoto em logs.
  Erros JSON são mapeados por código; mensagem arbitrária/stderr não vão à UI.
- `SearchModel`: rede em Dispatchers.IO, geração por consulta, cancelamento ao
  editar, abandonar a tela, parar a Activity ou desconectar. Reconexão limpa os
  resultados e aguarda novo toque; abrir a tela não pesquisa nem verifica serviço
  automaticamente. Verificar serviço também é ação manual. Sem polling.
- Inclusão chama `TorrentsModel.add` → `TorrentAction.Add` → `myct-torrent`.
  Enquanto esse modelo está ocupado, nenhum resultado pode disparar outro Add.
  Aceite/duplicata/falha e progresso aparecem junto ao resultado escolhido,
  com acesso a Downloads. Aceite não é apresentado como confirmação de download
  ativo. Uma inclusão já enviada pode terminar pelo fluxo existente mesmo após
  sair de Busca; não há retentativa automática nem cancelamento prometido no servidor.

Arquivos de implementação: `Search.kt`, `SearchModel.kt`, `SearchScreen.kt`,
`AppSection.kt`, `AppShell.kt`, `MainActivity.kt`, `SshConnection.kt`,
`TerminalModel.kt` e `app/build.gradle.kts`; testes novos `SearchTest.kt` e
`SearchModelTest.kt`. Nenhum helper, motor ou serviço Fedora alterado na S1B.

### Validação automatizada e candidata

```bash
cd /home/sam/Projetos/My-CT/android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

Java 21, SDK 36, Gradle 8.14.3, dependências já existentes/caches offline.
Testes verificam parser, vazio/nulos/zero, origens de magnet, mensagens sem
segredos, JSON malformado/excessivo, consulta malformada, quoting com tentativas
de shell injection, timeout/fechamento de canal, cancelamento e resposta tardia,
reconexão sem replay, ausência de Add na pesquisa e prioridade da ação explícita
com aceite/duplicata/falha simulados no TorrentsModel real. **Nenhum torrent real
foi adicionado durante a implementação.** Sem aparelho conectado ao ADB para
instrumentação; testes de UI/fluidez e fluxo SSH do Android não são declarados PASS.

Resultado final: **BUILD SUCCESSFUL**, 268 testes JVM (266 aprovados, 2 opt-in
ignorados: FedoraHandshakeTest e IdeFedoraSftpTest), **lint sem erros/avisos (2 sugestões Hint de estado numérico)**.
São 10 testes novos de Busca; a suíte existente também passou. Verificação
`python3 terminal/upstream/verify.py`: 21 arquivos upstream/3 patches documentados
preservados. `git diff --check` aprovado. Logs locais ignorados pelo Git em
`artifacts/myct-s1b-build-final.log` e `artifacts/myct-s1b-build-time.txt`.

Build final offline: 167,26 s, CPU usuário/sistema 784,75/10,59 s em vários
núcleos, pico RSS medido pelo `/usr/bin/time` de 2.465.464 KiB (~2,35 GiB).
Gradle avisou que o daemon expiraria por metaspace ao final; o build terminou
com sucesso, sem alterar heap/metaspace do projeto. Não foi medido consumo ou
fluidez Android no aparelho. APK de **18.803.354 bytes**; sem novas dependências
ou processos permanentes no Fedora.

SHA-256 do APK:
`529f42f749e2d3e27fa565f74fa7344e2c876613b6719fbcee110b08665d7e2f`.

APK **`dev.myct`, `0.8.0-s1b-search-rc1`, versionCode 39**, acima do code 38
da base, minSdk 26/target 36. Assinatura debug existente, certificado SHA-256
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`,
conferido por apksigner contra o APK R5 local anterior; sem nova keystore.

[Pré-release candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-s1b-v0.8.0-rc1)
· [APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-s1b-v0.8.0-rc1/myct-s1b-0.8.0-debug.apk)
· [SHA256SUMS](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-s1b-v0.8.0-rc1/myct-s1b-SHA256SUMS.txt).
PR #44 permanece draft/sem merge; issue #43 aberta para o gate S1B.

### Atualização e roteiro físico supervisionado

Baixar APK e checksum da pré-release autenticado no GitHub. Conferir SHA-256,
transferir ao Moto por USB/MTP ou SFTP existente e abrir como **atualização**.
Não desinstalar, limpar dados ou substituir a keystore. Mesmos pacote/certificado
e code maior preservam os dados instalados; projetos, pins e sessão devem ser
conferidos no aparelho. Não instalar sobre uma eventual versão com code maior.
Não é necessário atualizar helpers/serviços para esta candidata: a S1A-R já foi
instalada e aceita pelo proprietário.

1. Conectar ao Fedora pelo Terminal e abrir drawer → Busca. Verificar espaço,
   teclado, rolagem e transições; sem pesquisar automaticamente. Tocar Verificar
   serviço e confirmar disponibilidade.
2. Pesquisar Ubuntu explicitamente: conferir metadados e aviso do limite de 20.
   Localizar resultado sem magnet e confirmar botão indisponível. Contagens podem
   variar com a fonte; nenhuma ação nessa etapa deve adicionar torrents.
3. Editar consulta durante pesquisa, cancelar ou sair/voltar à tela. Confirmar
   que resposta antiga não reaparece. Desconectar/reconectar SSH: resultados
   anteriores somem e nenhuma consulta/inclusão é repetida automaticamente.
4. **Somente sob supervisão/autorização do proprietário**, escolher um torrent
   público e permitido, conferir o título e tocar Adicionar aos downloads uma
   vez. Conferir bloqueio enquanto envia e mensagem de aceite ou falha. Não
   presumir que está baixando apenas pela mensagem de aceite.
5. Abrir Downloads → Torrents e conferir o estado real do item. Após concluir
   a operação de inclusão, repetir manualmente o mesmo magnet e conferir aviso
   de duplicata sem nova entrada. Não excluir dados para preparar este teste.
6. Conferir regressões rápidas: Terminal, IDE, SFTP, navegação Downloads/HTTP/Mídia,
   Saúde e proteção do Cofre. Registrar o resultado físico antes de aceite/merge;
   não repetir automaticamente testes de serviços já aprovados.

## Fechamento MYCT-S1 — aceite físico do proprietário (19/10/2026)

**Aceite concedido pelo proprietário no Moto G06 para a candidata `0.8.0-s1b-search-rc1` (versionCode 39).** Evidência comunicada diretamente na conversa, após a publicação do commit `aa20d3a`:

- APK instalado como atualização e seção **Busca** aberta no Moto G06; capturas mostraram serviço Prowlarr 2.6.5.5623 disponível, pesquisa `ubuntu` com 20 resultados, metadados legíveis, aviso de limite e ações desabilitadas nos resultados sem magnet.
- O proprietário tocou **Adicionar aos downloads** em um resultado da Busca, abriu **Downloads → Torrents** e confirmou que a operação havia começado e o torrent **estava baixando no Transmission**. Isso valida o fluxo real Android → SSH → `myct-search`/infohash magnet → `TorrentsModel`/`myct-torrent` → Transmission, além do aceite de inclusão.
- O proprietário declarou explicitamente a etapa aprovada. Não houve intervenção remota da assistente nos arquivos baixados, nem solicitação de parada do download.

**Pendências complementares não bloqueantes:** não foram comunicadas verificações físicas específicas de cancelamento/reconexão, duplicata de inclusão e regressão completa nas telas adjacentes; seus cenários passaram nos testes automatizados onde aplicável, mas não devem ser registrados como teste físico PASS. Registrar eventual problema em issue futura sem retroativamente atribuir comprovação. Disponibilidade de peers não é universal: foi confirmada a transferência **do torrent selecionado**, não de todos os resultados do indexador.

A MYCT-S1 está aceita para integração na `main`. MYCT-S2 (Bitmagnet/DHT) continua **opcional e não iniciada**, com issue #43 como referência de avaliação.

## MYCT-S1C — Busca multi-indexador: filtro, paginação e diversidade (planejamento 19/10/2026)

**Situação:** planejamento autorizado pelo proprietário; sem implementação, instalação, APK, alterações em serviço ou merge nesta etapa. **Base:** `main` após PR #44, commit `265b4e22897f7c2f9cdb1c70d6df4daf9eb5da68`. Desenvolver na branch `myct-s1c-search-filters-pagination` **na única pasta local** `/home/sam/Projetos/My-CT`, preservando a versão 0.8.0 aceita. Manter PR de desenvolvimento em draft até teste físico.

### Motivação comprovada

O proprietário cadastrou **12 indexadores torrent habilitados** no Prowlarr 2.6.5.5623. Diagnóstico real pelo Fedora com busca `Ubuntu`: **281 resultados recebidos**, mas somente os **20 primeiros** apresentados pelo `myct-search`/Android, todos provenientes do Knaben. Distribuição na resposta Prowlarr naquela consulta:

| Indexador | Resultados |
| --- | ---: |
| The Pirate Bay | 100 |
| Knaben | 84 |
| Torrent Downloads | 50 |
| LimeTorrents | 40 |
| BigFANGroup | 3 |
| Tokyo Toshokan | 3 |
| Nyaa.si | 1 |
| ACG.RIP, Anime Tosho, Bangumi Moe, BlueRoms, nekoBT | 0 |
| **Total** | **281** |

Os **5 com zero** não são declarados quebrados: uma consulta Ubuntu pode não corresponder ao conteúdo especializado dessas fontes. Os **20 Knaben** indicam que a seleção atual usa a ordem agregada do Prowlarr e corta a lista, não que os outros indexadores deixaram de ser consultados. A quantidade de resultados pode variar a cada pesquisa.

### Objetivos funcionais (escopo mínimo)

1. **Filtro por indexador:** controle no Android com **Todos** (padrão) e os indexadores torrent **habilitados** retornados pelo `myct-search status` (ID + nome). Consultar explicitamente apenas o indexador escolhido quando houver filtro; nunca confiar em índice fixo, nome como identificador ou ID informado sem validação. Atualizar a lista pelo status em ação manual/entrada controlada, sem polling permanente. Tratar fonte removida/desabilitada e status vazio.
2. **Paginação sob demanda de 20 em 20:** a primeira consulta mostra até 20; **Carregar mais** acrescenta a página seguinte, mantendo a pesquisa/fonte escolhida. Botão só aparece se há itens adicionais conhecidos; sem duplicação ou repetição das páginas. Limitar memória, quantidade processada, tamanho JSON, chamadas HTTP e tempo de espera. Se houver limite técnico deliberado de segurança (por exemplo, máximo de 500 resultados), mostrá-lo explicitamente — não afirmar que foram carregados todos os existentes.
3. **Diversidade em Todos:** distribuir/intercalar resultados de diferentes indexadores que **efetivamente retornaram itens**, para que os primeiros 20 não sejam monopolizados por uma fonte. Usar ordem determinística, com critério simples e documentado (p.ex. round-robin por indexador, mantendo ordem relativa de cada fonte). **Filtro específico não intercala fontes.** Não prometer participação de uma fonte que retornou zero.
4. **Experiência Android:** filtro compacto, contador informativo de exibidos/recebidos e indicador de limite/mais páginas, estados loading/empty/error, botão Carregar mais com bloqueio durante execução, retenção de scroll e de resultados já carregados na adição bem-sucedida, descarte de respostas antigas ao trocar texto/fonte ou desconectar. Não repetir automaticamente inclusão ou pesquisa após reentrada/reconexão. Preservar o layout, navegação, teclado e transições R5.
5. **Manter downloads como estão:** cada resultado com magnet v1 válido usa `TorrentsModel.add` já existente e inclusão **somente por toque explícito**. Os sem magnet continuam visíveis, com botão desabilitado. Não mudar `myct-torrent`, Transmission, permissões, caminhos, seeding, Jellyfin ou o pipeline de mídia.

### Contrato e decisões técnicas

- Inspecionar **a API real da versão instalada Prowlarr 2.6.5.5623** antes de determinar a estratégia. **Não presumir** que `/api/v1/search` suporta `offset`, `page` ou paginação nativa. Se não suportar, usar agregação limitada com seleção de página no adaptador, aceitando que novas consultas ao vivo podem reordenar resultados; documentar a limitação de snapshot e o comportamento mais simples consistente. Evitar banco, cache persistente ou novo daemon.
- Manter `myct-search status` e `myct-search search "consulta"` **compatíveis com a versão 0.8.0** (20 primeiros por padrão e envelope JSON v1); adicionar argumentos opcionais bem validados para fonte/página, sem expor API key, passkeys, URLs proxy autenticadas ou outras credenciais. Tornar campos de paginação/filtro **aditivos**, preservando `schema_version`, campos de cada resultado e `magnet_source`.
- A nova aba Android reutiliza `SshConnection.search`, `executeSshResult`, `SearchModel` e `SearchScreen` atuais; não abre HTTP direto, não faz novo SSH, não interfere no PTY. A lista de indexadores é obtida dinamicamente do helper instalado no Fedora. Respeitar proteção do Cofre.
- Manter saída por operação SSH limitada e rápida; não devolver os 281 (ou mais) itens integralmente à interface numa só resposta. Se paginação exigisse uma consulta repetida ao Prowlarr a cada página, medir custo e deixar explícito. Ordenação manual por seeders/data é melhoria **opcional futura**, não é gate da S1C.
- Todas as pesquisas são **somente leitura**. Sem downloads automáticos ou acesso não autorizado a trackers. Nenhuma configuração do Fedora é alterada durante implementação Codex; atualização do helper e APK após validação são manuais pelo proprietário.

### Aceite e validação

- Testes em Python para status dinâmico, filtro por ID válido/inválido, fonte desabilitada, 0/1/20/21/281+ itens, paginação e ausência de repetição, round-robin determinístico, limites, erro/timeout e preservação de segredos/magnets.
- Testes JVM para seleção da fonte, estado das páginas, anexar resultados, troca de consulta/filtro, cancelamento e resposta tardia, comportamento desconectado, botão sem magnet e inclusão explícita sem repetição. Build/lint e sem regressões nas funcionalidades principais.
- Diagnóstico real **sem download** no Fedora: pesquisa Ubuntu com índice Todos, comparar contagem e fontes; confirmar que a primeira página apresenta variedade quando há várias fontes com resultados, que página seguinte carrega itens adicionais, e que filtro de uma fonte traz apenas aquela origem. Consulta especializada para uma fonte que antes retornou zero **somente se útil**, não exigir que toda fonte responda.
- Gerar APK candidato instalável sobre `dev.myct` code 39 (novo versionCode maior; certificado/pacote preservados) e SHA-256/release. **Não desinstalar nem limpar dados.** Gate físico Moto G06: Busca, filtros, Carregar mais, scroll, resultados sem magnet, estados/cancelamento e verificação breve de Downloads → Torrents. Não executar download real automaticamente; somente se proprietário autorizar o teste.
- Registrar resultados no presente relatório, atualizar PR e manter **draft/sem merge** até o aceite do proprietário. **MYCT-S2/Bitmagnet continua opcional e não iniciada**, sem alterar sua trilha.

**Critério de conclusão:** o usuário pode pesquisar em Todos ou numa fonte específica, navegar pelos resultados por páginas, ver variedade de indexadores em Todos e continuar adicionando manualmente magnets via Transmission, sem perda do comportamento da S1 já aprovado.



## MYCT-S1C — implementação e candidata 0.8.1 (19/10/2026)

**Situação:** implementação/testes Fedora e JVM concluídos; candidata Android
`0.8.1-s1c-rc1`/code **40**. **Aceite físico Moto G06 pendente. PR #45 draft,
sem merge.** O planejamento acima é o registro anterior à implementação.
Árvore inicialmente limpa, fetch realizado e vínculo local criado para a branch
remota existente `myct-s1c-search-filters-pagination`, em `e4b0f13`. Trabalho
somente em `/home/sam/Projetos/My-CT`, sem worktree/clone/outra branch; `main`
preservada. Helper instalado e serviços ativos não foram alterados.

### API observada e solução

Prowlarr local **2.6.5.5623**, 12 indexadores torrent habilitados. O código
oficial de [SearchResource](https://github.com/Prowlarr/Prowlarr/blob/v2.6.5.5623/src/Prowlarr.Api.V1/Search/SearchResource.cs)
e [SearchController](https://github.com/Prowlarr/Prowlarr/blob/v2.6.5.5623/src/Prowlarr.Api.V1/Search/SearchController.cs)
aceita `limit`/`offset` e os encaminha à busca dos indexadores; isso não garante
paginação da lista agregada. Experimento somente GET, com Ubuntu e a configuração
real, confirmou:

| Parâmetros adicionais | Itens | Tempo |
| --- | ---: | ---: |
| nenhum | 281 | 2,109 s |
| `limit=20&offset=0` | 281 | 2,350 s |
| `limit=20&offset=20` | 0 | 0,018 s |

Os dois conjuntos de 281 tiveram a mesma sequência de fonte/título/tamanho
(fingerprint SHA-256 abreviado `6b302f09fc489339`). Portanto o helper não usa
`limit`/`offset` do Prowlarr para navegar: consulta o agregado, normaliza,
intercala e devolve somente a página solicitada. Não usa POST de grab/download.

- **Todos:** round-robin por ID crescente das fontes habilitadas, preservando
  a ordem recebida dentro de cada fonte. Fontes vazias não geram cards.
- **Fonte específica:** valida ID positivo contra a configuração atual e envia
  somente esse ID em `indexerIds`; também descarta itens de outra fonte caso
  o upstream os devolva indevidamente. Fonte removida/desabilitada gera
  `invalid_indexer`; bloqueio temporário futuro em `/api/v1/indexerstatus`
  gera `indexer_unavailable`. Esse estado é uma observação, não uma garantia
  da disponibilidade durante a chamada seguinte; falhas HTTP/timeouts continuam
  sanitizados. Zero resultados por si só não significa fonte quebrada.
- **Deduplicação:** SHA-256 da identidade do conteúdo por BTIH (hex/Base32
  normalizados); sem magnet, título sem distinção de caixa + tamanho. Guarda
  a primeira ocorrência na ordem intercalada, sem reordenar os sobreviventes.
  O fallback por metadados pode unir releases diferentes com título/tamanho
  idênticos e não reconhece equivalência entre um item sem hash e outro com hash.
- **Limites:** 20 itens por resposta, até 500 únicos navegáveis/25 páginas,
  entrada HTTP de até 2 MiB e saída SSH de até 256 KiB. Entrada excessiva falha
  com `response_limit` e pede refinar; acima de 500 únicos, `limited=true` e
  aviso Android. Processamento/memória limitados pela entrada de 2 MiB, sem
  banco, arquivo de resultados, cache permanente, dependência ou daemon novo.
- **Consultas ao vivo:** cada página repete a consulta completa. A ordem relativa
  de cada fonte, itens e contagens podem mudar. Sem snapshot não há garantia
  de percorrer exatamente o conjunto original: mudanças podem pular itens ou
  repetir candidatos. Helper deduplica cada agregado; Android oculta IDs já
  exibidos, avança a página mesmo se todos forem repetidos e não busca outra
  automaticamente. Refazer Pesquisar inicia um conjunto novo. O total mostrado
  pertence à resposta mais recente; a contagem carregada pertence à navegação.

### Contrato JSON v1 atualizado

Comandos compatíveis e extensões (ordem dos pares opcionais indiferente):

```bash
myct-search status
myct-search search "Ubuntu"
myct-search search "Ubuntu" --page 2
myct-search search "Ubuntu" --indexer 10
myct-search search "Ubuntu" --indexer 10 --page 2
```

Sem opções = **Todos, página 1**. `--page` aceita inteiros 1–25 e `--indexer`
inteiros 1–2147483647 existentes/habilitados; flags duplicadas/desconhecidas,
zeros, negativos e controles são rejeitados antes de credenciais/rede. Consulta
continua com 1–200 caracteres imprimíveis. Exit 0 em sucesso/vazio, 2 em
uso/configuração e 3 nas demais falhas. Erros mantêm envelope v1 e códigos seguros.

Mantidos `schema_version:1`, `ok`, `state`, `results`, `count`, `received_count`,
`discarded_count`, `truncated`, `result_limit` e todos os campos anteriores de
cada item (`title`, `size_bytes`, `source`, `indexer_id`, `seeders`, `leechers`,
`link_type`, `magnet`, `magnet_source`). Campos adicionados:

| Campo | Significado |
| --- | --- |
| status `indexers[].available` | disponibilidade observada; ID/nome continuam dinâmicos |
| status/resultados `total_limit` | 500 |
| resultados `page` | página atual, base 1 |
| resultados `indexer_id` | filtro solicitado; `null` = Todos |
| resultados `has_more` | existe próxima página conhecida dentro do limite |
| resultados `total_count` | únicos navegáveis nesta consulta, até 500 |
| resultados `limited` | há únicos além do teto de 500 |
| resultados `duplicate_count` | ocorrências repetidas removidas do agregado |
| item `id` | identidade estável SHA-256, 64 caracteres hex; não é URL autenticada |

`received_count` conta a resposta bruta, `discarded_count` conta itens inválidos
ou de fontes não selecionadas e `duplicate_count` é separado. `truncated` continua
indicando que a resposta não contém todo o conjunto único; **não** é o sinal
para carregar mais: usar `has_more`. Página além do fim retorna `empty` sem mais.

API key permanece somente no Fedora, no cabeçalho HTTP loopback, com validação
privada do config existente. URLs HTTP não são retornadas: links indiretos
continuam classificados, sem magnet; URLs em metadados são omitidas. Magnets
diretos preservam o BTIH e nome opcional seguro, com `magnet_source=direct`;
parâmetros de tracker/URLs extras são removidos para não propagar passkeys ou
credenciais. Isso os deixa dependentes da descoberta existente do cliente,
como o fallback já aprovado. Fallback continua mínimo
`magnet:?xt=urn:btih:<hash>`/`magnet_source=infohash`; sem hash válido,
`magnet=null` e `magnet_source=null`. Não há resolução de proxy HTTP autenticado.

### Android e arquivos

Seletor compacto **Fonte: Todos** + fontes dinâmicas, atualizado manualmente por
**Atualizar fontes** (também verifica serviço). Fontes temporariamente bloqueadas
aparecem desabilitadas. Trocar fonte ou texto cancela/invalida a geração anterior,
limpa cards/páginas e aguarda Pesquisar. Reconexão limpa catálogo/filtro/resultados
e não repete ações. Não há polling nem busca automática ao entrar na tela.

**Carregar mais** acrescenta cards com chaves estáveis na mesma LazyColumn,
preserva os recebidos e não reposiciona a lista. Bloqueio enquanto ocupado;
erro/cancelamento da página mantém cards, página anterior e possibilidade de
retentativa manual. Fim de resultados remove o botão. Contagem, limite de 500 e
natureza ao vivo ficam explícitos. IME Pesquisar, fechamento do teclado,
transições e estilo preto/dourado continuam usando os componentes existentes.
APK novo com helper anterior ainda permite pesquisa básica; aviso de listagem
antiga pede atualizar o helper, e não há Carregar mais nesse contrato legado.

Inclusão permanece **somente por toque** em
`TorrentsModel.add → TorrentAction.Add → myct-torrent`; sem magnet o botão
continua desabilitado. Nenhuma mudança em Transmission, Jellyfin, PostgreSQL,
SSH, diretórios, seeding ou serviços ativos. Nenhum torrent real foi incluído.

Arquivos alterados:

- `ops/myct-search` e `ops/tests/test_search.py`.
- `android/app/src/main/java/dev/myct/Search.kt`, `SearchModel.kt`, `SearchScreen.kt`.
- `android/app/src/test/java/dev/myct/SearchTest.kt`, `SearchModelTest.kt`.
- `android/app/build.gradle.kts` e este documento.

### Validação

```bash
python3 -m unittest discover -s ops/tests -p test_search.py -v
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

**21 testes Python PASS:** catálogo dinâmico/vazio, IDs removidos/desabilitados e
bloqueados, filtro, 0/1/20/21/281/501/620 itens, todas as páginas sem duplicatas,
round-robin determinístico/ordem interna/fontes vazias, limite de 500, hash
hex/Base32 equivalente, URLs/passkeys/chave privadas, erros HTTP/timeouts e
compatibilidade dos magnets diretos/infohash.

**272 testes JVM, 270 PASS e 2 opt-in ignorados** (handshake/SFTP Fedora),
zero falhas. Busca tem 14 testes, cobrindo catálogo/filtro, contrato e quoting,
página inconsistente, anexação sem substituir os 20 iniciais, supressão de
repetições, bloqueio de chamadas concorrentes, retentativa da mesma página após
falha SSH, fim, cancelamento e respostas tardias após troca de consulta/fonte,
saída ou desconexão, reconexão sem replay e inclusão explícita pelo modelo
Transmission existente sem chamadas reais. Build/lint: **BUILD SUCCESSFUL em 2m48s; lint sem erros/avisos (2 sugestões Hint de estado numérico)**.
Avisos Kotlin preexistentes em `IdeScreen.kt:332/344` e aviso de metaspace do
Gradle não motivaram mudanças fora do escopo. `git diff --check` aprovado.

Diagnóstico real com a versão do helper **do repositório**, sem instalar sobre
`~/.local/bin/myct-search`:

| Operação | Recebidos / únicos | Retornados | Tempo | JSON |
| --- | --- | ---: | ---: | ---: |
| status | 12 fontes habilitadas | — | 0,018 s | 762 bytes |
| Ubuntu Todos, p1 | 281 / 228 | 20 | 1,989 s | 7.014 bytes |
| Ubuntu Todos, p2 | 281 / 228 | 20 novos | 2,893 s | 6.991 bytes |
| Ubuntu Pirate Bay, ID 10 | 100 / 100 | 20 só Pirate Bay | 0,225 s | 7.492 bytes |
| ID inexistente 2147483647 | rejeitado antes de search | 0 | 0,017 s | 169 bytes |

Todos p1: Knaben 4, BigFANGroup 3, LimeTorrents 3, Nyaa.si 1, Pirate Bay 3,
Tokyo Toshokan 3 e Torrent Downloads 3. **Sete fontes na primeira página**, sem
inventar as cinco que não retornaram itens. P2: Knaben/LimeTorrents/Pirate Bay/
Torrent Downloads com 5 cada; interseção de IDs p1/p2 **zero**. 53 ocorrências
repetidas removidas, nenhum item inválido descartado, sem limite de 500 atingido.
P1: 8 magnets infohash/12 indisponíveis; p2: 10/10; filtro Pirate Bay: 20/0.
Chave real e URLs HTTP ausentes nas respostas verificadas em memória.
Pico RSS do processo Python que executou essas operações: **26.064 KiB**
(~25,5 MiB), sem processo persistente. Contagens/tempos são observações pontuais,
não garantia de cobertura ou disponibilidade. Página 12 real: 8 itens, `has_more=false`, 1,727 s; os 8 magnets foram
aceitos pela função de validação de `myct-torrent`, sem chamada RPC.

O teste real ocorreu no Fedora local via API loopback; transporte SSH Android
foi validado por testes JVM do canal existente, **não pelo Moto** nesta entrega.
ADB sem dispositivos. Scroll, ergonomia/teclado, transições e inclusão física
continuam sujeitos ao roteiro do proprietário; não foram declarados PASS físico.

### Candidata, atualização e teste físico

`dev.myct`, **0.8.1-s1c-rc1, versionCode 40**, minSdk 26/target 36. Certificado
SHA-256 `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`,
verificado por apksigner contra o APK S1B code 39; mesma keystore existente.
Tamanho APK: 17931625 bytes. SHA-256: `bfe8bb4cf745e5b562743348d4c206b13129beaea0a9be954f047c49fc6e6e2c`.

[Pré-release](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-s1c-v0.8.1-rc1)
· [APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-s1c-v0.8.1-rc1/myct-s1c-0.8.1-debug.apk)
· [SHA256SUMS](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-s1c-v0.8.1-rc1/myct-s1c-SHA256SUMS.txt).

**Atualização manual do helper pelo proprietário**, no Fedora, após conferir
branch `myct-s1c-search-filters-pagination` e árvore limpa. Comandos sem `set -e`
e sem `exit` no shell SSH interativo; se o pull falhar, a instalação não executa:

```bash
cd /home/sam/Projetos/My-CT
git status --short
git branch --show-current
git pull --ff-only && install -m 0755 ops/myct-search "$HOME/.local/bin/myct-search" && "$HOME/.local/bin/myct-search" status
```

Nenhum restart/configuração de serviço é necessário. O Git sozinho não atualiza
o helper instalado. Depois pode conferir `myct-search search "Ubuntu"` e
`myct-search search "Ubuntu" --page 2`, sem downloads. Usar IDs do status atual.
Baixar o APK e SHA256SUMS da release, conferir o hash e instalar como **atualização**
sobre code 39, preservando dados/assinatura. Não desinstalar, limpar dados ou
instalar sobre versão de code maior.

Roteiro Moto G06, gate de aceite antes de merge:

1. Atualizar helper manualmente, instalar APK como atualização e conectar o SSH
   existente. Busca → Atualizar fontes: conferir Todos e as fontes atuais.
2. Pesquisar Ubuntu em Todos: até 20 cards, variedade se houver várias fontes,
   peers/tamanho, contagem e resultados sem magnet com botão desabilitado.
3. Rolar ao fim → Carregar mais: observar novos cards, posição de rolagem,
   contador e ausência de repetição; toque duplo não envia duas páginas. Repetir
   até o fim (na medição, 228 únicos/12 páginas); botão deve desaparecer.
4. Voltar ao formulário, escolher uma fonte: cards antigos devem sumir e nenhuma
   pesquisa deve iniciar. Tocar Pesquisar e confirmar somente essa origem.
   Trocar para Todos e repetir; editar consulta durante carregamento e conferir
   que resposta antiga não se mistura. Conferir teclado e transições.
5. Cancelar Carregar mais: cards preservados e retentativa manual. Testar falha
   de conexão, sair/voltar e reconectar: sem replay automático de pesquisa ou
   inclusão. Falha recuperável da página não deve apagar os cards recebidos;
   desconexão confirmada limpa o estado, conforme o comportamento existente.
6. Abrir Downloads → Torrents e conferir os itens existentes. Inclusão adicional
   só por toque explícito do proprietário em conteúdo permitido, se desejar
   repetir esse fluxo já aceito; não é disparada pelo teste de busca. Não remover
   dados nem alterar seeding. Verificar brevemente Terminal/SFTP e navegação.

Registrar resultado físico antes de retirar draft/fechar #43; **não houve merge**.
MYCT-S2/Bitmagnet permanece opcional e não iniciada.

## MYCT-S1C — Resultado físico no Moto G06 (relato do proprietário, 19/10/2026)

Após atualizar manualmente o helper `myct-search` instalado e instalar a candidata Android, o proprietário confirmou diretamente na conversa o teste da Busca no **Moto G06**:

- **Paginação / Carregar mais:** funcionou na utilização real do app.
- **Filtros por indexador:** funcionaram nas pesquisas realizadas.
- **Diversificação de fontes:** a primeira página e as seguintes apresentaram resultados de fontes variadas; o proprietário avaliou o comportamento como bom.
- **Fedora instalado:** no teste manual anterior à validação Android, `myct-search search "Ubuntu" --page 1/2` mostrou 20 resultados por página, `total_count=228`, `has_more=true`, sete fontes na página 1 e quatro na página 2.

**Aceite físico confirmado para as três funcionalidades centrais da MYCT-S1C.** Não foram relatados testes físicos específicos de descarte de resposta atrasada, cancelamento, perda/reconexão SSH, esgotamento da última página, scroll após falha, preservação de downloads, ou novo envio de magnet. Não registrar esses itens como PASS físico; testes automatizados descritos acima permanecem a evidência nesses casos. Não houve relato de falhas nessas funcionalidades.

**Estado do fechamento:** PR #45 mantida **draft, sem merge** enquanto se aguarda autorização explícita do proprietário para consolidação na `main` e limpeza posterior da branch. MYCT-S2/Bitmagnet segue opcional e fora desta etapa.
