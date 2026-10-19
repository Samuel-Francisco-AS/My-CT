# MYCT-S1/S2 — Busca de torrents: Prowlarr primeiro, Bitmagnet depois

**Acordado em:** 19/10/2026  
**Situação:** S1A implementada e testada na CLI Fedora em 19/10/2026; helper ainda não instalado; S1B/Android pendente
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
critérios foram verificados no próprio Fedora. **Aceite do proprietário pela
CLI via SSH do My-CT e instalação em `~/.local/bin` permanecem pendentes.**
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

Exemplo real convertido, com envelope reduzido aqui para leitura:

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
| `magnet` | URI `magnet:` efetivamente fornecida em `magnetUrl` ou `downloadUrl`, com `xt` BitTorrent v1 sintaticamente válido; `magnet` contém a URI original |
| `torrent_url` | URL HTTP(S) cujo caminho termina em `.torrent`; é uma indicação pela extensão, sem baixar/verificar o conteúdo |
| `indirect_url` | URL HTTP(S) intermediária, proxy ou página; jamais tratada como magnet |
| `none` | Nenhum link reconhecido/aceito |

URLs HTTP não são devolvidas, pois podem carregar API keys/passkeys. Sem
magnet direto, `magnet` é `null`. Hashes não são usados para fabricar magnets.
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

### Limitação real de magnets — não confundir o nome do campo com o protocolo

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

Por isso os 20 resultados entregues no teste têm `link_type: "indirect_url"`
e `magnet: null`. **Pesquisa real está funcional; envio de um magnet real a
Downloads não está comprovado nem habilitado por esta entrega.** Suporte a
magnet direto foi validado com fixtures sintéticas. Resolver o proxy exigirá
uma microetapa explícita, preservando a credencial e avaliando redirects sem
disparar downloads. Não houve tentativa de decifrar ou seguir esses links.

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

**Não executada nesta entrega.** No Fedora, como `sam`, sem sudo:

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

- Instalar manualmente o helper e confirmar a consulta pelo SSH do My-CT.
- Criar a seção Busca usando o contrato v1 por SSH, sem polling e com estados
  ready/results/empty/error, limite visível e cancelamento/descarte de respostas antigas.
- Exibir links indiretos sem ação automática de download. A amostra real não
  fornece magnet direto: resolver essa limitação antes de prometer o gate de
  inclusão real em Downloads, ou encontrar fonte que efetivamente o forneça.
- Testar qualquer inclusão somente por ação explícita via `myct-torrent`
  existente; verificar duplicata, Downloads e regressões no Moto G06.
- Manter PR #44 draft/sem merge e issue #43 aberta. S2/Bitmagnet não iniciado.

**Próxima ação:** instalação manual e aceite CLI pelo proprietário; depois
S1B com a limitação de links acima explícita. Android, Transmission, Jellyfin,
PostgreSQL e Core permaneceram sem alterações nesta implementação.
