# MYCT-S1D — Acesso às fontes e inclusão de arquivos .torrent

**Planejamento acordado:** 20/10/2026.  
**Status:** planejamento registrado; **código não iniciado**.  
**Base confirmada:** `main` commit `382cb7d2faf7ff16472fe109976dfcbb47f246f9`, após PR #50 (C1C/nuvem). Versão Android atual no repositório: `0.9.1-c1c-rc2`, **versionCode 42**, pacote `dev.myct`.  
**Branch:** `myct-s1d-torrent-links` (usar o único checkout local `~/Projetos/My-CT`); **issue:** [#51](https://github.com/Samuel-Francisco-AS/My-CT/issues/51); **PR de implementação:** abrir em draft e manter sem merge até aceite Moto G06.  
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
