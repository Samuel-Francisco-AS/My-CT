# MYCT-S1D — Acesso às fontes e inclusão de arquivos .torrent

**Planejamento acordado:** 20/10/2026.
**Status:** candidata implementada; testes automatizados aprovados; **aceite físico e inclusão real pendentes**.
**Base confirmada:** `main` commit `382cb7d2faf7ff16472fe109976dfcbb47f246f9`, após PR #50 (C1C/nuvem). Versão Android atual no repositório: `0.9.1-c1c-rc2`, **versionCode 42**, pacote `dev.myct`.
**Branch:** `myct-s1d-torrent-links` (usar o único checkout local `~/Projetos/My-CT`); **issue:** [#51](https://github.com/Samuel-Francisco-AS/My-CT/issues/51); **PR de implementação:** [#52](https://github.com/Samuel-Francisco-AS/My-CT/pull/52), draft/sem merge até aceite Moto G06.
**Relacionado:** [MYCT-S1/S2](MYCT-S1-BUSCA-PROWLARR-BITMAGNET.md); MYCT-S1A/B/C já concluídas. **MYCT-S2/Bitmagnet** é opcional, não iniciada.

## Por que fazer

A Busca combina indexadores Prowlarr 2.6.5.5623, filtra por fonte e pagina 20 a 20. Atualmente o `ops/myct-search` reconhece `link_type` magnet, `torrent_url`, `indirect_url`, mas apenas envia magnet ou gera fallback quando `infoHash` v1 é válido. Os campos `downloadUrl`, `magnetUrl` e `infoUrl` podem conter URLs autenticadas/proxy: **o helper propositalmente não expõe esses links**. No Android `SearchScreen.kt`, resultados sem magnet têm o botão de inclusão desabilitado. `ops/myct-torrent` só suporta `add MAGNET`, enviando `filename` ao RPC Transmission.

Em teste MYCT-S1C com `Ubuntu`: **281 ocorrências brutas / 228 resultados únicos**; na página 1, **8** tinham magnet derivado do infohash e **12 não tinham magnet**; página 2, **10 com magnet / 10 sem magnet**. Esses 22 cards sem magnet nas duas páginas motivam S1D, mas **não demonstram** quantos realmente possuem .torrent ou página pública acessível.

## Requisitos funcionais mínimos

1. **Abrir página da fonte:** em resultado com página *pública* HTTP(S) identificável e sem credenciais, permitir toque `Abrir página da fonte`, abrindo o navegador externo via Android ACTION_VIEW/Intent apropriado; sem WebView, browser embutido, túnel, proxy público ou login automatizado. Não apresentar opção quando o endereço for só API/proxy autenticado, link de download protegido, URL opaca, localhost/rede privada, esquema inadequado ou URL com dados sensíveis.
2. **Adicionar por .torrent:** quando o resultado selecionado possuir metadados recuperáveis pelo Prowlarr, permitir uma ação **manual e explícita** no card. O Fedora, pelo SSH existente, identifica a fonte/resultado, obtém o arquivo binário pelo caminho confiável do Prowlarr, valida o conteúdo e chama a instância dedicada do Transmission via helper `myct-torrent` existente, com `torrent_add`/`metainfo` (base64 local apenas). Não enviar .torrent, cookies, passkeys, links autenticados nem metainfo para o Android, logs ou stdout.
3. **Estados honestos:** distinguir `magnet disponível`, `arquivo .torrent potencialmente disponível`, `página pública disponível`, `sem opção`. Possibilidade de .torrent **não implica** download garantido. Se Prowlarr retornar HTML, 401/403/404, captcha, redirect inválido, arquivo inválido ou source removida, mostrar erro claro e seguro, sem iniciar download e sem atribuir sucesso.
4. **Continuar preservando:** busca por palavra, `Todos`/fonte, round-robin, paginação de 20 até 500, IDs estáveis, cancelamento e reconexão; magnets diretos/infohash v1 e inclusão via toque permanecem iguais. A integração C1A/C1B/C1C de nuvem, mídia, armazenamento e Transferências permanece intacta.

## Descoberta técnica obrigatória antes de implementar

- Inspecionar em **modo somente leitura** como Prowlarr 2.6.5.5623 fornece, para **um resultado público permitido** sem `infoHash`, os campos `guid`, `downloadUrl`, `infoUrl`, `magnetUrl`, `indexerId`, e se o endpoint proxy de download (quando presente) realmente entrega binário .torrent. **Não presumir endpoint universal de .torrent, nem que `infoUrl` seja navegável, nem que todo `downloadUrl` termine em `.torrent`**.
- Evitar imprimir URL completa ou tokens nos diagnósticos; informar apenas categoria de endereço, host público se não sensível, HTTP status, content-type e tamanho. Preferir testes simulados; prova real somente quando disponível, sem criar torrent no Transmission.
- Os resultados S1C são *deduplicados*: dois indexadores com mesmo título/tamanho podem colapsar, apesar de um possuir arquivo .torrent. Ao adicionar disponibilidade da fonte, ponderar deduplicação de forma simples e previsível para não ocultar o único resultado utilizável; documentar critério.
- Para resolver um card sem expor sua URL de download ao Android, definir **identificador opaco/não secreto de resultado + ID de fonte validado** e resolução **novamente pelo Fedora** na ação manual. Evitar usar índice de página, title isolado ou string HTTP arbitrária passada pelo Android. Como as pesquisas são ao vivo, detectar item ausente/alterado e pedir nova consulta; não permitir que resposta desatualizada importe conteúdo diverso.
- Caso não haja no Prowlarr um caminho recuperável para um indexador, a funcionalidade deve **falhar explicavelmente ou oferecer apenas Abrir página**, sem bypass de autenticação, Cloudflare/CAPTCHA ou scraping genérico.

## Caminho de execução proposto (simples)

`SearchScreen` apresenta ações por resultado. `SearchModel` despacha apenas após toque, bloqueando duplo acionamento e fazendo limpeza de sessão geracional. `SshConnection` usa canal exec existente; nenhum SSH adicional ou API HTTP pública do Android.

Para **Abrir página**, o helper retorna somente URL pública sanitizada e verificada (ou um comando de resolução por ID de resultado sem retornar URLs privadas). Android abre no navegador do Moto por Intent; nunca navegar sem URL adequada. **A página aberta pode exibir anúncios ou conteúdo de terceiros**, pois é externa.

Para **Adicionar .torrent**, o helper consulta Prowlarr loopback com sua chave privada, valida resultado/endpoint permitido e obtém **bytes limitados**. Transmite por processo local/stdin (não argumento de shell, que tem limite de tamanho) ao `myct-torrent`, que valida metainfo bencoded e deriva o hash **BitTorrent v1**, lida com duplicatas usando as rotinas atuais `destination()`, `naming_lock()`, `read_naming()`, `write_naming()`, e envia `torrent_add` com metainfo base64 via RPC local `127.0.0.1:19091`, retendo o comportamento de paused/start do fluxo de magnet. Metadados privados **preservam os trackers necessários** e nunca são convertidos num magnet v1 mínimo que perderia autenticação. Torrent v2 puro/híbrido: testar suporte real e recusar explicitamente caso o helper atual não possa garantir o contrato.

**Restrições essenciais da fronteira de confiança**: autorizar somente operação baseada em resultado e fonte Prowlarr; nunca aceitar URL arbitrária do Android para fazer fetch. Para qualquer acesso externo considerar SSRF (destino/porta/IP privado, DNS e redirects), tamanho/tempo/conteúdo, HTTPS válido e redirecionamentos proibidos ou verificados. Não exibir API key, cookie, passkey, URLs proxy, magnets com tokens nem o próprio metainfo nos logs/JSON Android; não seguir links de HTTP retornados por uma página. Sem cache persistente, novo daemon, dependência pesada ou banco; evitar complexidade desnecessária.

## Contrato e interface

- `myct-search status` e `myct-search search "Ubuntu" [--page N] [--indexer ID]` permanecem retrocompatíveis com o Android 0.9.1 (JSON v1 e 20 cards). Adicionar apenas campos opcionais relacionados a origem e ações; nova operação via CLI de comando explícito e argumentos validados.
- `myct-torrent add MAGNET` e os comandos de listagem/controle/exclusão permanecem compatíveis; se adicionar `add-metainfo-stdin`, aceitar bytes somente localmente, com limite, sem imprimir os metadados; stdin não deve vazar ao PTY.
- Card Android: `Adicionar aos downloads` para magnet (como hoje); `Adicionar arquivo torrent` apenas quando houver caminho plausível de obtenção; `Abrir página da fonte` somente quando URL pública verificável; permitir as duas opções se aplicável. Mostrar loading, feedback sucesso/duplicata/falha, sem ação ao tocar simplesmente no card. Navegação/sair/reconectar nunca causa repetição da inclusão.
- Não mudar UI de outras abas. Não adicionar botões inúteis se resultado não permite ação. Não mostrar `sem magnet` como se fosse ausência de todos os caminhos possíveis.

## Validação e liberação

1. Testes Python locais: URL pública vs API autenticada vs privacidade; `infoUrl` não equivale a arquivo; URL maliciosa, DNS/redirect/rede interna, HTML/HTTP inválido, limite de bytes/tempo, binário .torrent válido/malformado, bencode, hash v1, passkey preservada dentro do arquivo e ausência em logs, retorno de RPC, duplicata, organização sem alteração de outras pastas, id de resultado inválido/stale, sem novos downloads automáticos.
2. Testes JVM: nova resposta JSON v1 aditiva, ações dos cards, seleção, debounce, cancelamento, perda/reconexão, indisponibilidade, abertura somente via navegador externo, integração com Downloads/Torrents. Verificar que nenhuma chamada de inclusão é executada sem toque. Usar mocks, não chamadas reais a sites terceiros nos testes.
3. Build, lint, smoke CLI Fedora read-only por conteúdo público permitido, com evidência de pelo menos um caso sem magnet **quando disponível**; caso nenhum seja resolvível, relatar limitação em vez de alegar sucesso. Teste RPC de inclusão real apenas com **autorização explícita do proprietário** e torrent legal, sem baixar conteúdo sem seu toque.
4. APK candidato **0.9.2-s1d-rc1/code 43 ou posterior**, pacote `dev.myct`, **mesmo certificado** instalado; nunca desinstalar/limpar dados. Publicar SHA-256 e roteiro de atualização manual do helper. Uso SSH com comandos seguros para sessão interativa, sem `set -e` direto no shell.
5. PR de implementação em **draft/sem merge** até testes Moto G06 e autorização de fechamento. Atualizar este documento com resultados reais e dívida residual. Não alterar Transmission/Jellyfin/Prowlarr/SSH/PostgreSQL/Rclone nem seus serviços/configuração durante a implementação sem prévia autorização.

## Fora do escopo

Bitmagnet/DHT S2, automação Sonarr/Radarr/RSS, downloads automáticos, processamento de sites bloqueados, alteração de autenticação de trackers, FlareSolverr, browser headless, serviços de fundo, APIs públicas, nova infraestrutura de armazenamento e reformulação geral do app.

**Critério de conclusão:** para um resultado recuperável sem magnet, o proprietário consegue obter um `.torrent` no Transmission por ação manual; para um resultado que expõe somente página pública, consegue abri-la no navegador sem revelar credenciais. Quando a origem não oferece nenhuma das duas possibilidades, o app explica o motivo e não executa ações ocultas.

## Implementação candidata — 20/10/2026

**Estado:** implementação e testes automatizados entregues na PR [#52](https://github.com/Samuel-Francisco-AS/My-CT/pull/52), **draft/sem merge**. Aceite físico Moto G06 e inclusão real de arquivo no Transmission **pendentes**. Não se declara a S1D aceita. Nenhum helper instalado foi substituído; nenhum serviço/configuração foi alterado ou reiniciado. Nenhum torrent real foi adicionado nesta implementação.

### Investigação no Fedora e decisão

Checkout único `/home/sam/Projetos/My-CT`, árvore inicialmente limpa, fetch de `origin`, branch remota S1D em `9257356e`, ancestralidade da base `382cb7d2faf7ff16472fe109976dfcbb47f246f9` confirmada. PR #52 confirmada com base `main`, head S1D e draft. A branch local anterior C1C foi apenas deixada; `main` local não foi modificada. Leitura integral deste planejamento, README e planejamento inaugural, seguida da inspeção de Busca, modelos, canal SSH exec, RPC Transmission e testes existentes.

Ambiente: Fedora 44, Python 3.14.7, Git 2.55.0, Java 21.0.10, Gradle 8.14.3, Android SDK/build-tools 36. Sem novas dependências, serviços ou banco.

A API instalada respondeu **Prowlarr 2.6.5.5623**, com 12 fontes torrent habilitadas. Foram feitas consultas GET e obtenção limitada de respostas de metadados de distribuições Linux, sem chamar POST de grab/search nem RPC de inclusão. GET do proxy pode registrar histórico/contadores internos do Prowlarr; “somente leitura” aqui significa ausência de alteração de configuração e de transferências no Transmission.

O código oficial da versão instalada explica os campos:

- [`SearchController.MapReleases`](https://github.com/Prowlarr/Prowlarr/blob/v2.6.5.5623/src/Prowlarr.Api.V1/Search/SearchController.cs) converte `downloadUrl` e `magnetUrl` em proxies. O nome `magnetUrl` não garante que o valor recebido seja um magnet direto.
- [`DownloadMappingService`](https://github.com/Prowlarr/Prowlarr/blob/v2.6.5.5623/src/NzbDrone.Core/Download/DownloadMappingService.cs) produz `/{indexerId}/download`, com referência cifrada e autenticação local. Essa referência varia entre pesquisas e não serve como identidade estável.
- [`NewznabController.GetDownload`](https://github.com/Prowlarr/Prowlarr/blob/v2.6.5.5623/src/Prowlarr.Api.V1/Indexers/NewznabController.cs) pode entregar bytes, erro ou redirect, inclusive para magnet. GET não equivale à operação POST que envia ao cliente de download.

Amostras públicas de Linux **sem infoHash**:

| Fonte | Resposta do proxy | Conclusão limitada à amostra |
| --- | --- | --- |
| Torrent Downloads | HTTP 301, destino de esquema magnet, 0 bytes | Não entregou .torrent binário; redirect recusado |
| LimeTorrents | HTTP 301, destino de esquema magnet, 0 bytes | Não entregou .torrent binário; redirect recusado |
| Knaben | HTTP 500, application/rss+xml, 89 bytes | Origem não disponibilizou metadados nessa tentativa |

Nenhum redirect foi seguido. Nenhuma API key, cookie, passkey, URL autenticada ou metainfo aparece nesta evidência. Não foi encontrada, nas amostras legais selecionadas, uma resposta binária adequada sem infohash. **A integração binária tem prova automatizada, ainda não prova real por indexador.** Os resultados acima não demonstram falha universal dessas fontes.

**Escolha:** consumir somente o proxy local retornado por uma nova pesquisa na fonte habilitada. O helper não faz fetch de URL externa nem segue redirects, HTTP ou magnet. Isso evita adicionar cliente HTTP externo, scraping, autenticação ou contorno de CAPTCHA. A disponibilidade de arquivo é uma possibilidade, explicitada no card, nunca uma garantia de obtenção. Os redirects para magnet observados produzem mensagem explicativa; não são convertidos silenciosamente numa inclusão diferente.

### Contrato JSON v1 e resolução

Comandos existentes preservados:

```text
myct-search status
myct-search search "Ubuntu"
myct-search search "Ubuntu" --page 2 --indexer 10
myct-torrent add MAGNET
```

Campos opcionais por resultado:

| Campo | Semântica |
| --- | --- |
| `public_url` | Página HTTP(S) pública verificada, ou null |
| `result_ref` | SHA-256 opaco da identidade da fonte/resultado, ou null |
| `torrent_available` | Há `guid` e proxy `downloadUrl` local reconhecido; obtenção ainda pode falhar |

`result_ref` inclui indexerId, guid, título completo, tamanho, infoHash e infoUrl originais. Não inclui o ciphertext variável do proxy. A nova operação é:

```text
myct-search add-torrent "consulta original" --indexer ID --result REF_HEX_64
```

O Android não fornece URL, GUID original ou metainfo. A CLI valida argumentos, verifica fonte torrent habilitada e disponibilidade, refaz **somente essa fonte**, exige um único resultado com a mesma referência e usa seu `downloadUrl` atual. Resultado ausente, alterado ou ambíguo exige nova pesquisa. A identidade sem infohash é a identidade declarada pelo indexador, não uma prova criptográfica prévia do conteúdo. Quando houver infoHash v1, ele também é conferido pelo helper antes do RPC.

A paginação continua round-robin, 20 por página, até 500, consultada ao vivo. Magnets continuam deduplicados pelo hash v1. Resultados sem magnet com ação de página/arquivo passam a manter identidades distintas por fonte/referência, para não ocultar o único caminho utilizável. Sem ações, o fallback título/tamanho permanece. Assim, o total pode aumentar em relação à S1C mesmo sem novos resultados brutos.

### Fronteiras e limites

Página: apenas `infoUrl`, nunca `downloadUrl`. HTTP(S), porta padrão, sem userinfo/fragmento, parâmetros de consulta limitados a IDs numéricos públicos, sem chave conhecida, caminhos de API/download/auth/trackers ou tokens opacos reconhecíveis. Rotas aceitas: `/view/ID`, `/torrent/ID`, `/torrents/ID`, `/details/ID`, `viewtopic.php`, `viewtorrent.php`, `details.php`, `description.php` com IDs, e `*-torrent-ID.html`. DNS deve resolver exclusivamente para IPs globais. Falha de DNS, endereço privado/reservado ou rota não reconhecida omite a ação. Android faz uma segunda validação sintática, sem DNS na thread de UI, e usa ACTION_VIEW + CATEGORY_BROWSABLE. Não há WebView. Não se promete alcance, ausência de anúncios ou disponibilidade posterior da página no navegador.

Arquivo: conexão fixa `127.0.0.1:9696`, sem proxy de ambiente; origem, porta, path com o ID correto, parâmetros únicos e API key devem coincidir com o proxy recém-retornado. A key vai no header, não na URL reconstruída. **Todos os redirects são recusados**, incluindo destinos públicos, privados e magnet. Limites: **4 MiB**, socket **15 s**, orçamento de leitura **20 s**, pesquisa Prowlarr **25 s**, processo local **25 s**; canal SSH Android **80 s**. Erros HTTP de autenticação, expiração, indisponibilidade, HTML/XML, tamanho excessivo e corpo incompleto têm códigos sanitizados. Não há retries automáticos.

O destino externo cifrado e eventuais redirects **internos** ao fetch do Prowlarr continuam sob responsabilidade do Prowlarr e da configuração de fontes confiáveis já instalada. O adaptador não decifra esses links e não é um controle de egress do serviço. Sua fronteira recusa URLs arbitrárias e redirects recebidos pelo próprio helper. DNS de páginas/trackers é validado no momento da operação; isso não fixa o DNS usado posteriormente por navegador/Transmission.

O `.torrent` passa apenas por `subprocess` local/stdin para:

```text
myct-torrent add-metainfo-stdin [INFOHASH_V1_ESPERADO]
```

Sem shell e sem bytes grandes em argumentos. Entrada stdin tem limite de 4 MiB e 10 s. Validação bencode com profundidade 32, até 100.000 nós, chaves ordenadas/únicas, estrutura v1, nomes/caminhos seguros, até 20.000 arquivos, tamanhos e contagem de peças coerentes. Infohash é SHA-1 do **intervalo original dos bytes de info**, sem recodificação. V2, híbridos, arquivos especiais/padding/symlinks, web seeds e nós DHT embutidos são recusados claramente. Trackers usam a política de endereço existente; tracker inválido, restrito ou sem resolução recusa **o arquivo inteiro**. Não são removidos trackers necessários de torrent privado. Credenciais de trackers permanecem somente nos bytes locais e no daemon.

O helper reutiliza `add_source`, comum a magnet/metainfo: `destination`, lock por hash e registro de nomes atuais, RPC `torrent_add` com **paused=true**, conferência do hash, registro de elegibilidade e `torrent_start` somente se for novo. Duplicata não inicia novamente nem altera o registro. Metainfo usa o parâmetro RPC `metainfo` em base64 local; não se substitui por magnet. Controles posteriores e organização continuam os existentes em Downloads → Torrents. Erros RPC de metainfo não retransmitem texto de terceiros potencialmente sensível.

### Android e cancelamento

Ações aparecem independentemente: magnet válido → **Adicionar aos downloads**; proxy candidato e referência → **Adicionar arquivo torrent**; página validada → **Abrir página da fonte**. Um card pode oferecer mais de uma ação. Sem caminho utilizável, há mensagem explicativa em vez de botão inoperante.

Inclusão usa `TorrentAction.AddFile` e os mesmos estados de `TorrentsModel`: bloqueia repetição durante operação, mostra aceitação/duplicata/erro e oferece acesso aos Torrents. O erro de arquivo não limpa resultados, filtros, páginas nem a lista em rolagem. Navegação, edição, desconexão ou **Interromper espera** cancelam a espera e descartam resposta tardia, sem reenviar. Cancelar a espera SSH **não garante rollback de uma operação já recebida pelo Fedora**: consultar Downloads antes de repetir. A limpeza cancela apenas a inclusão correspondente, não comandos posteriores de outra tela.

### Arquivos alterados

- `ops/myct-search`: URLs públicas, referências, sinalização de arquivo, resolução manual, leitura limitada e passagem local por stdin.
- `ops/myct-torrent`: validação de metainfo v1 e rotina compartilhada de inclusão, preservando gerenciamento e nomes.
- `ops/tests/test_s1d.py`: fixtures HTTP, identidade, metadados, hash, privacidade e RPC simulado.
- `android/app/src/main/java/dev/myct/Search.kt`: campos opcionais, validação de página, comando e respostas de inclusão.
- `SearchModel.kt`, `SearchScreen.kt`: ações, feedback, cancelamento e navegador externo.
- `Torrents.kt`, `TorrentsModel.kt`: nova ação pela mesma comunicação SSH exec, sem mudar Terminal/PTY.
- `android/app/src/test/java/dev/myct/SearchS1dTest.kt` e `TorrentsModelTest.kt`: regressões e cenários novos.
- `android/app/build.gradle.kts`: candidata code 43.
- Este documento: investigação, contratos, evidências e retomada.

### Testes e evidências

Comandos reproduzíveis no checkout:

```bash
python3 -m unittest discover -s ops/tests
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

Resultados finais: **204 testes Python PASS** em 36,419 s e **302 testes JVM PASS**, build/lint Android PASS. Lint: 0 erros, 1 warning `UseKtx` novo pela chamada `Uri.parse` de abertura externa e 2 hints `AutoboxingStateCreation` preexistentes em SearchModel. O warning sugere uma extensão equivalente, sem apontar falha funcional. Execução final Gradle: 3min16s, pico RSS 2.418.164 KiB (processo de build, sem novo serviço permanente), CPU user/system 956,54/12,15 s; suíte Python pico RSS 41.892 KiB, user/system 16,77/2,89 s. Sem swap nas duas medições. Gradle avisou sobre pressão de metaspace ao terminar, mas concluiu exit 0; não houve mudança de limites/configuração. Abrangem URL pública/privada/autenticada, fonte habilitada e referência alterada, deduplicação, ausência de inclusão durante pesquisa, redirects recusados, HTML/XML, HTTP negado/expirado, limites, bencode inválido/v1/hash, trackers privados preservados, duplicata e início controlado com RPC simulado. JVM cobre contrato aditivo, quoting real via shell de fixture, ações explícitas, duplo toque, erro preservando resultados, cancelamento, resposta tardia, navegação e reconexão sem replay. A suíte existente inclui S1C, Torrents e C1/armazenamento; isso não substitui aceite físico.

Smoke real do helper do checkout, sem instalar nem adicionar torrents:

| Comando | Resultado | Tempo | RSS máximo do processo filho |
| --- | --- | --- | --- |
| `status` | ready, Prowlarr 2.6.5.5623 | 0,244 s | 23.060 KiB |
| `search "Ubuntu"` | 282 brutos / 229 únicos, página 1 com 20; 8 magnets, 16 candidatos a arquivo, 14 páginas | 1,981 s | 26.484 KiB |
| `search "Ubuntu" --page 2 --indexer 10` | 100 da fonte / 20 na página, 20 magnets | 0,545 s | máximo acumulado 26.484 KiB |

Uma consulta final, após incluir também a rota pública `description.php`, manteve 282/229/20 e 8 magnets/16 candidatos; passou a **17 páginas públicas**, em 1,477 s. O proxy sem key na query, autenticado somente pelo header, foi comprovado com a mesma falha segura `download_redirect` em Torrent Downloads.

Consulta agregada final, antes da deduplicação:

| Fonte com resultados Ubuntu | Brutos | Sem magnet | Proxies candidatos | Páginas aceitas |
| --- | ---: | ---: | ---: | ---: |
| Knaben | 85 | 4 | 39 | 85 |
| BigFANGroup | 3 | 3 | 3 | 3 |
| LimeTorrents | 40 | 40 | 40 | 24 |
| Nyaa.si | 1 | 0 | 0 | 1 |
| The Pirate Bay | 100 | 0 | 0 | 100 |
| Tokyo Toshokan | 3 | 0 | 3 | 3 |
| Torrent Downloads | 50 | 50 | 50 | 44 |

ACG.RIP, Anime Tosho, Bangumi Moe, BlueRoms e nekoBT não forneceram resultados nessa consulta; não há conclusão de incompatibilidade dessas fontes. “Páginas aceitas” verifica formato/DNS, não o conteúdo HTTP ou disponibilidade no Moto.

Os números são snapshots de busca ao vivo, não cobertura garantida de fontes. As fontes testadas com obtenção de arquivo tiveram apenas as falhas/redirects da tabela anterior. **Suporte efetivo de arquivo:** resultado de qualquer fonte torrent habilitada com GUID estável e proxy Prowlarr no formato aceito, que entregue diretamente binário v1 compatível. **Nenhum indexador tem ainda prova real de ponta a ponta para esse caminho.** Magnet mantém o suporte S1C existente; páginas dependem das rotas/DNS acima.

### Atualização manual segura dos helpers

Fazer depois de revisar a candidata. Não precisa parar nem reconfigurar serviço algum. A publicação desta branch **não instala** helpers em `~/.local/bin`.

No Fedora, confirmar primeiro branch e revisão desejadas, árvore sem mudanças próprias e resultados dos testes. Depois, em shell interativo (sem `set -e` ou `exit`):

```bash
cd /home/sam/Projetos/My-CT
git status --short --branch
git log -1 --oneline
python3 -m unittest discover -s ops/tests -p 'test_search.py'
python3 -m unittest discover -s ops/tests -p 'test_torrent.py'
python3 -m unittest discover -s ops/tests -p 'test_s1d.py'
```

Se os testes passarem, guardar cópias privadas e instalar **os dois** helpers candidatos:

```bash
backup_dir="$HOME/.local/state/myct/s1d-backup-$(date +%Y%m%d-%H%M%S)"
install -d -m0700 "$backup_dir"
if cp -p "$HOME/.local/bin/myct-search" "$HOME/.local/bin/myct-torrent" "$backup_dir/"; then
    install -m0700 ops/myct-torrent "$HOME/.local/bin/myct-torrent" &&
    install -m0700 ops/myct-search "$HOME/.local/bin/myct-search" &&
    "$HOME/.local/bin/myct-search" status
fi
```

O bloco só instala se o backup terminar com sucesso. Para rollback, usar o diretório de backup exibido/guardado nesta sessão e restaurar apenas os dois executáveis com `install -m0700`. Não alterar hook `myct-torrent-done`, serviços, banco, arquivos de downloads ou configuração de Prowlarr/Transmission. APK novo funciona com helper antigo com os campos opcionais ausentes; novas ações dependem da atualização dos helpers. Não instalar CLI 5C antiga sobre este fluxo.

### Roteiro físico Moto G06 — pendente

1. Atualizar o APK sobre code 42, pacote `dev.myct`, sem desinstalar nem limpar dados. Confirmar conexão, perfis e dados preservados. Atualizar helpers manualmente pelo procedimento anterior quando decidido pelo proprietário.
2. Busca → consultar `Ubuntu`, filtrar fonte, carregar página 2, rolar e abrir uma **página da fonte**. Confirmar navegador externo e retorno com resultados/posição preservados. Uma página remota pode estar indisponível ou exigir interação própria; não automatizar login/CAPTCHA.
3. Em resultado sem magnet com proxy candidato, tocar **Adicionar arquivo torrent**. Nas amostras descritas, esperar erro de redirect/indisponibilidade, sem perder a pesquisa nem adicionar torrent. Conferir aviso, filtros e scroll.
4. Quando existir um resultado legal com binário v1 recuperável, o proprietário autoriza e executa o toque de inclusão: observar aceitação, presença em Downloads → Torrents, hash, destino My-CT e estado real. Repetir manualmente uma vez para validar duplicata, sem início extra. Esta inclusão real não foi executada pelo agente.
5. Durante uma tentativa, testar duplo toque, **Interromper espera**, sair/voltar e desconectar/reconectar. Confirmar ausência de replay; antes de repetir, consultar os Torrents porque a inclusão pode ter sido recebida antes do cancelamento.
6. Revalidar um magnet aprovado e a continuidade de controles Torrents; smoke de Terminal/Arquivos, mídia e C1C/nuvem sem ações destrutivas. Não repetir aceites antigos como condição para declarar código compilado, nem marcar esses passos como PASS sem observação no Moto.

### APK candidato e publicação

[Release candidata S1D](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-s1d-v0.9.2-rc1), prerelease. APK `myct-s1d-0.9.2-rc1-debug.apk`, versão **0.9.2-s1d-rc1**, code **43**, pacote **dev.myct**. Instalar como atualização sobre o code 42; não limpar dados.

SHA-256 do APK:

```text
e3b4a506513273a7f1a4ded08aceab1e5e9543435a2d0c9bb08bdd80209b194e
```

`apksigner verify --print-certs` conferiu a assinatura contra o APK code 42 baixado da release C1C. Certificado SHA-256 idêntico:

```text
b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3
```

Retomada: branch `myct-s1d-torrent-links`, PR #52 draft; instalar helpers somente por operação manual revisada, instalar APK por atualização e executar o roteiro físico acima. A ausência de prova binária real sem infohash permanece uma limitação explícita. Não fazer merge nem encerrar o gate como aceito antes da observação do proprietário.
