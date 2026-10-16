# Core My-CT — saúde e persistência local (MYCT-3A/3B)

C++20/Linux, Makefile e **libpq direta**, sem ORM/framework/CMake. Saúde usa
`/proc/stat`, `/proc/meminfo` e `statvfs`; não abre conexão com o banco.
PostgreSQL é usado somente pelos comandos CLI explícitos. Estado/evidência:
[relatório MYCT-3B](../docs/MYCT-3B-POSTGRES-REPORT.md).

## Dependências Fedora 44

Antes de instalar, conferir `rpm -q libpq libpq-devel postgresql postgresql-server`
e `g++ --version; make --version`. Instalação **somente após autorização**:

```sh
sudo dnf --repo=fedora --repo=updates install libpq-devel postgresql postgresql-server
```

- `libpq-devel`: header e `pg_config` para build; resolve `libpq` (runtime).
- `postgresql`: `psql`, `createdb`, `pg_dump` para operação manual.
- `postgresql-server`: `initdb`, `postgres`, `pg_ctl` para o banco local.
- `g++`, `make`, Python 3 (testes) já existentes; sem `contrib`/`server-devel`.

Fontes: [libpq-devel Fedora](https://packages.fedoraproject.org/pkgs/libpq/libpq-devel/),
[cliente Fedora](https://packages.fedoraproject.org/pkgs/postgresql18/postgresql/),
[servidor Fedora](https://packages.fedoraproject.org/pkgs/postgresql18/postgresql-server/).
Os repositórios oficiais são selecionados para evitar dependência de repositórios terceiros.

## Build e saúde

```sh
cd ~/Projetos/My-CT/server
make
make test
./myct-core --once
./myct-core
```

Em outra sessão: `curl --fail --silent http://127.0.0.1:8765/v1/health`.
Conferir `ss -lntp` (API apenas `127.0.0.1:8765`); Ctrl-C encerra o Core.
Porta alternativa: `./myct-core --port 18765`. Sem boot/systemd.
`make` usa `pg_config`; se necessário indicar `PG_CONFIG=/caminho/pg_config`.
O binário requer libpq instalada mesmo se o servidor PostgreSQL estiver parado.

## Cluster exclusivo, sem sudo nem TCP

Não usar `postgresql-setup`, serviço global ou cluster de outro projeto.
Executar como usuário normal. Estes caminhos são exclusivos do My-CT;
se já contiverem dados, **não inicializar novamente**, usar a recuperação abaixo.

```sh
export MYCT_PGROOT="$HOME/.local/share/myct/postgres"
export MYCT_PGSOCKET="$MYCT_PGROOT/run"
umask 077
mkdir -p "$MYCT_PGROOT"
mkdir -m 700 "$MYCT_PGSOCKET"
initdb -D "$MYCT_PGROOT/data" --auth-local=peer --auth-host=reject
```

Só após sucesso do `initdb`, acrescentar ao config **deste cluster**:

```sh
cat >> "$MYCT_PGROOT/data/postgresql.conf" <<'CONFIG'
listen_addresses = ''
unix_socket_permissions = 0700
shared_buffers = 16MB
max_connections = 10
CONFIG
pg_ctl -D "$MYCT_PGROOT/data" -l "$MYCT_PGROOT/postgres.log" \
  -o "-k '$MYCT_PGSOCKET'" -w start
createdb -h "$MYCT_PGSOCKET" -p 5432 -U "$(id -un)" myct
psql -X -h "$MYCT_PGSOCKET" -p 5432 -U "$(id -un)" -d myct \
  -v ON_ERROR_STOP=1 -f migrations/001_execution_events.sql
```

Não há senha: `peer` exige identidade Unix. Socket privado e `listen_addresses=''`
impedem acesso TCP, inclusive do Android; não alterar firewall/SSH/rede.
O proprietário do cluster é o usuário local; não é um isolamento contra esse
próprio usuário. A migração é transacional e aplicada **uma vez**. Repeti-la
falha sem apagar dados; não há migração automática na inicialização do Core.

## Gravação, consulta e persistência

```sh
./myct-core --record-event manual.validation
# Saída TSV: ID <tab> data UTC <tab> tipo
./myct-core --get-event 1   # substituir pelo ID retornado
make test-db
```

Cada comando abre e fecha um processo/conexão: a consulta posterior já comprova
persistência após encerrar o Core gravador. `test-db` exige banco real e migração;
cria um evento identificado por UUID, não apaga registros e não administra o banco.
Tipos: 1–64 caracteres ASCII `a-z A-Z 0-9 _ . -`; ID bigint positivo.
SQL parametrizado; erros retornam código 1, uso inválido código 2.

`MYCT_PGSOCKET` é obrigatório apenas na CLI de banco e deve ser caminho absoluto.
Banco `myct`, porta de socket 5432 e usuário Unix efetivo são explícitos;
`PGHOST`, `PGHOSTADDR`, `PGDATABASE`, `PGUSER` não redirecionam a conexão.
Timeout de conexão/SQL de 3 s; diagnósticos não imprimem credenciais.
[Contrato libpq](https://www.postgresql.org/docs/current/libpq-connect.html).

Para comprovar isolamento e degradação:

```sh
psql -X -h "$MYCT_PGSOCKET" -U "$(id -un)" -d myct \
  -c 'SHOW listen_addresses' -c 'SHOW unix_socket_directories'
ss -lntp  # nenhuma porta TCP do cluster; API somente loopback
pg_ctl -D "$MYCT_PGROOT/data" -m fast -w stop
./myct-core --record-event unavailable.test  # falha explícita esperada
./myct-core --once                        # saúde continua OK
# Iniciar ./myct-core e consultar /v1/health com banco parado também.
```

## Retomada e recuperação

Reexportar os dois caminhos acima a cada sessão. Para reiniciar **este** cluster:

```sh
pg_ctl -D "$MYCT_PGROOT/data" -l "$MYCT_PGROOT/postgres.log" \
  -o "-k '$MYCT_PGSOCKET'" -w start
./myct-core --get-event 1  # ID já gravado antes da parada
```

Falha de conexão: conferir `pg_ctl -D "$MYCT_PGROOT/data" status`, caminho do
socket e log privado; não reinicializar nem apagar `postmaster.pid`. Erro SQLSTATE
`42P01`: conferir banco/migração; `42501`: conferir proprietário/permissões.
Se `initdb`/startup falhar, registrar hipótese e mensagem antes de alterar config.

Backup manual, fora do Git (com o banco rodando):

```sh
pg_dump -h "$MYCT_PGSOCKET" -U "$(id -un)" -d myct -Fc \
  -f "$MYCT_PGROOT/myct-backup.dump"
```

Para recuperar, criar outro banco vazio **neste cluster**, por exemplo `myct_restore`,
e usar `pg_restore --exit-on-error -h "$MYCT_PGSOCKET" -U "$(id -un)" \
-d myct_restore "$MYCT_PGROOT/myct-backup.dump"`. Conferir registros via `psql`;
a CLI permanece fixa em `myct`. Substituição do banco original exige planejamento
e autorização; não há comando destrutivo automático. Restore ainda não é evidência
aprovada sem execução real.

Este capítulo descreve o recorte histórico MYCT-3A/3B, anterior ao Dashboard e aos downloads. Métrica de CPU
é amostrada por 120 ms; uptime é do Core; espaço usa `f_bavail`.


## Downloads HTTP/HTTPS — MYCT-5A

Executável C++20 separado do Core, usando curl instalado e systemd transitório
**de usuário**, sem banco, API adicional ou instalação de serviço. Não iniciar
segunda instância do Core na 8765: o serviço instalado continua sendo o dono dela.

```sh
make myct-download
./myct-download start 'https://example.com/' exemplo.html
./myct-download list
./myct-download status ID
./myct-download cancel ID
```

`start` retorna UUID; consultar o resultado por ID. Um worker por vez, sem fila.
Destino `~/Downloads/My-CT/ID/nome`, parcial `.part`; total desconhecido = `-1`.
Precisa de curl (Fedora validado: 8.18; pelo menos 8.4 para limite durante corpo
sem tamanho), systemd de usuário ativo e linger previamente habilitado pelo dono.
Nenhum pacote, serviço ou binário ativo é instalado por `make`.
`make test` inclui integrações reais com systemd/curl, loopback e HOME temporário
(~37 s); requer esse ambiente.

[Relatório MYCT-5A](../docs/MYCT-5A-DOWNLOADS-REPORT.md) contém instalação
opcional, limites, recuperação e procedimento **pendente** de prova SSH/Moto.

## Antivírus sob demanda — MYCT-5D-B1

`make myct-av-scan` compila o worker C++20, usando a mesma autorização de caminhos
de `myct-inspect`. Instalação explícita do adaptador/unidades e validação Fedora:
[relatório B1](../docs/MYCT-5D-B1-REPORT.md) e
[contrato CLI/JSON](../docs/MYCT-5D-B1-CLI.md). Não altera o Core instalado.
