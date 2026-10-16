# MYCT-5D-B1 — contrato CLI v1

CLI operacional `~/.local/bin/myct-av`, Python 3/stdlib, com worker C++20
`~/.local/bin/myct-av-scan`. O Core e o Android não mudam. Transporte futuro:
exec SSH autenticado já existente, separado do PTY. Sem API ou novo banco.

## Comandos

| Comando | Resposta |
| --- | --- |
| `myct-av status` | `state: AVAILABLE/UNAVAILABLE`, `engine`, `databases`, `available`, `stale`, `last_success` |
| `myct-av scan PATH_UTF8_HEX [--watch-stdin]` | Análise síncrona de um arquivo; estados abaixo, detecções e métricas |
| `myct-av update` | Solicita `myct-av-update.service` com `systemctl --user start --no-block`; `requested` confirma somente o pedido |
| `myct-av update-status` | `running`, `result`, `phase`, últimas tentativas/sucesso, base atual, estado systemd e últimas 4 KiB do log |

Cada chamada retorna **um objeto JSON em uma linha**, `version: 1`, sem conteúdo
do arquivo. Código de saída 0 para consulta, `CLEAN`, `DETECTED` e pedido aceito;
1 para `ERROR`, `UNAVAILABLE`, `INCOMPLETE`, atualização falhada/interrompida ou
pedido recusado. Consumir JSON mesmo com saída 1. Campos de métricas podem estar
ausentes se a execução não chegou ao motor. Não usar somente o exit code para
classificar detecção ou confirmar atualização.

`PATH_UTF8_HEX`: bytes UTF-8 codificados em hexadecimal minúsculo. Máximo 32768
caracteres hex; controles, NUL, traversal, symlinks em qualquer componente,
fora do home real (`getpwuid`), diretórios e arquivos especiais são recusados.
O `$HOME` recebido do cliente não autoriza caminhos. Não há argumento de
opções livres. Na B2, construir somente comando fixo + hex validado `[0-9a-f]`,
sem concatenar caminho original em shell. A validação C++ é a mesma da 5D-A.

Exemplo local, com caminho autorizado existente:

```sh
~/.local/bin/myct-av status
MYCT_AV_PATH_HEX=$(python3 -c 'import sys; print(sys.argv[1].encode().hex())' "$HOME/Downloads/prova.txt")
~/.local/bin/myct-av scan "$MYCT_AV_PATH_HEX"
~/.local/bin/myct-av update
~/.local/bin/myct-av update-status
```

Exemplo resumido de análise (há campos adicionais):

```json
{"version":1,"state":"CLEAN","engine":"ClamAV 1.4.6/28155/Fri Oct 16 03:24:17 2026","stale":false,"detections":[],"duration_ms":10798,"peak_rss_kib":984732,"cpu_user_s":9.80648,"cpu_system_s":0.500235}
```

## Semântica

- `CLEAN`: o motor inspecionou o arquivo e não detectou ameaça com as assinaturas
  informadas. **Não comprova segurança.** `stale` deve ser exibido separadamente.
- `DETECTED`: `detections` contém identificações únicas do ClamAV. Nenhuma ação
  sobre o arquivo; não parar downloads ou torrents.
- `INCOMPLETE`: cancelamento, arquivo mudou, limite atingido, conteúdo
  criptografado/quebrado, aviso do motor, arquivo vazio não inspecionado, ou
  ferramenta ocupada (`message: BUSY...`). Pode conter detecções já encontradas;
  não esconder essas identificações nem apresentar resultado limpo.
- `ERROR`: caminho/acesso inválido ou falha de execução.
- `UNAVAILABLE`: motor/worker ou base dedicada ausente/não carregável.

`engine_output` contém saída limitada do ClamAV para diagnóstico, com o alvo
identificado como `stdin`, sem conteúdo original. `exit_code`/`signal` são do
motor; `duration_ms` inclui carga de assinaturas. `peak_rss_kib` é o high water
RSS do filho por `wait4`, não uma estimativa de consumo de toda a máquina.
`databases`: lista com `name`, `version`, `date` e `timestamp` do cabeçalho
CVD/CLD. Disponibilidade em `status` verifica executáveis/cabeçalhos; só uma
análise confirma que a base carrega integralmente. Base corrompida será recusada.

`--watch-stdin`: manter stdin do exec SSH aberto; EOF/cancelamento encerra
somente esse worker/ClamScan, sem parar o serviço de atualização ou a sessão
SSH principal. SIGINT, SIGTERM e SIGHUP também cancelam. O worker sinaliza seu
filho por PID, não usa `pkill`/`killall`. Morte do adaptador sinaliza o worker;
morte do worker mata o scanner por `PDEATHSIG`. Desconexão TCP depende da detecção
pelo SSH. Leitura bloqueada no kernel pode retardar encerramento. Sem histórico
de scans nem retomada; a B2 deve manter o canal enquanto aguarda o resultado.

## Atualizações

`result`: `NEVER`, `RUNNING`, `SUCCESS`, `ERROR` ou `INTERRUPTED`.
`phase`: `WAITING`, `UPDATING`, `FINISHED` (pode faltar antes da primeira tentativa).
`running: true` sempre significa resultado atual `RUNNING`, mesmo antes do
worker escrever os metadados. `SUCCESS` exige FreshClam sair com 0 e as três
bases estarem disponíveis; base já atual também é sucesso. `service.Result`
e `ExecMainStatus` permitem diagnosticar falha/timeout externo ao worker.
Os logs preservam a causa retornada pelo FreshClam, inclusive rede/CDN/DNS;
não há promessa de classificar todas as falhas de rede em um enum próprio.

`last_attempt`, `last_finished`, `last_success`: epoch em segundos, ou null.
Sucesso anterior é mantido após falha. Atualização abortada antes do término é
reportada como interrompida/falha consultando o estado real da unidade, mesmo
se os metadados anteriores ainda disserem RUNNING. `progress` é texto por etapa,
não uma porcentagem garantida. Sem senha, sudo ou prompts.

`stale: true` se faltar `daily`/sucesso, ou se a data da `daily` ou a última
checagem bem-sucedida tiver mais de **72h**; data futura além de 1h também é
tratada como desatualizada. Esta margem acomoda o intervalo de 36h sem esconder
falha persistente. `main`/`bytecode` têm ciclos de publicação próprios, não são
marcadas antigas só por terem data anterior à `daily`.

Um único flock cobre análise e atualização, mantendo metadados/base consistentes
e evitando duas cargas grandes na RAM. Uma segunda análise retorna INCOMPLETE /
BUSY; atualização espera a análise limitada terminar. Pedidos simultâneos de
`update` convergem para a mesma unidade oneshot. O lock também cobre invocação
direta do worker interno `_update-worker`, que não faz parte do contrato Android.
