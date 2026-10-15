# MYCT-3B — persistência PostgreSQL mínima

Data: 15/10/2026. **Aceite técnico comprovado no Fedora real.**
Branch: `myct-3b-postgres`, [PR #11](https://github.com/Samuel-Francisco-AS/My-CT/pull/11)
contra `myct-3a-core-health` (`afeeebc`). PR #10 preservado, sem merge automático.

## Implementação

- `server/postgres.cpp/.hpp`: libpq direta, conexão Unix/peer, banco `myct`,
  usuário Unix efetivo, conexão por operação e SQL parametrizado.
- `server/migrations/001_execution_events.sql`: tabela com bigint identity,
  timestamptz e tipo restrito a 1–64 caracteres ASCII seguros; migração transacional.
- `main.cpp`: `--record-event TYPE` e `--get-event ID`, saída TSV com UTC.
- Makefile mantido; sem CMake, ORM, framework ou dependência libpqxx.
- Saúde HTTP independente do banco, somente loopback; sem endpoints novos.
- Teste HTTP com banco indisponível, teste CLI de falha/entrada e teste real
  `make test-db`, que grava e consulta por processos distintos.
- Operação, backup e recuperação em [server/README.md](../server/README.md).

## Ambiente e dependências

Fedora Linux 44, GCC `16.2.1 20260819`, GNU Make `4.4.1`.
Pacotes instalados pelo proprietário após autorização:

```text
libpq-18.0-4.fc44.x86_64
libpq-devel-18.0-4.fc44.x86_64
postgresql-18.6-1.fc44.x86_64
postgresql-server-18.6-1.fc44.x86_64
```

`libpq-devel` fornece header/pg_config; libpq é runtime; postgresql fornece
cliente/administração e postgresql-server fornece initdb/postgres/pg_ctl.
`pg_config --version` informa `PostgreSQL 18beta1` no pacote disponível;
`SELECT version()` confirmou servidor **18.6**. Build e conexão reais passaram
com essa combinação; nenhuma troca de pacote foi necessária.

Histórico do gate: inicialmente faltavam os pacotes; após autorização a tentativa
`sudo -n dnf` exigiu senha, e o proprietário realizou a instalação em terminal.
O gate foi resolvido antes desta validação, sem pedir ou registrar a senha.

## Evidência real e resultados

| Validação | Resultado |
| --- | --- |
| `make -C server test` | Compilação C++20 sem avisos; **3/3 PASS**, incluindo os dois testes anteriores |
| Migração com `psql -X ... -v ON_ERROR_STOP=1 -f server/migrations/001_execution_events.sql` | BEGIN / CREATE TABLE / COMMIT |
| `MYCT_PGSOCKET=... make -C server test-db` | **1/1 PASS**, evento ID 1 gravado/relido por processos distintos |
| `--record-event myct3b.fedora.validation` e `--get-event 2` | Registro real idêntico, conforme abaixo |
| Encerrar Core, parar/reiniciar banco, reiniciar Core e consultar ID 2 | Mesmo registro retornado |
| Banco parado: gravação CLI | Código 1, erro explícito PostgreSQL unavailable |
| Banco parado: `/v1/health`, no processo existente e após reiniciar Core | HTTP **200**, JSON com `status: ok` |
| `SHOW listen_addresses` | Vazio: nenhum listener TCP do cluster |
| `SHOW unix_socket_directories` | `/home/sam/.local/share/myct/postgres/run` |
| `SHOW unix_socket_permissions` e `stat` do diretório/socket | `0700` / `700` |
| `ss -lntp`, filtrado pelos PIDs criados | Somente Core em `127.0.0.1:8765`; nenhum TCP do PostgreSQL |
| Sintaxe Python e `git diff --check` | PASS |

Registro gravado, relido após saída do gravador e novamente após reiniciar banco/Core:

```text
2    2026-10-15T14:47:10.088294Z    myct3b.fedora.validation
```

A saída original usa tabulações. O teste de integração também define
`PGHOSTADDR=192.0.2.1` e `PGSERVICE=myct_nonexistent_service`: a conexão local
passou, comprovando que esses overrides herdados não redirecionam a CLI.

## Consumo observado

Amostra breve em repouso, após escrita/leitura e uma requisição HTTP:

- Core: **7.556 KiB RSS (~7,4 MiB)**, CPU **0,0%** em `ps`.
- Cluster PostgreSQL: **24.227 KiB PSS agregado (~23,7 MiB)**, somando
  `/proc/PID/smaps_rollup` do postmaster e filhos. CPU observada: **0,0%** por
  processo. RSS do postmaster: 21.768 KiB; RSS dos filhos compartilha páginas
  e não deve ser somado como memória física exclusiva.
- Dados do cluster: **42 MiB**, `du -sh`.
- Binário: **58.656 bytes**.
- Configuração: `shared_buffers=16MB`, `max_connections=10`.

Isso não é benchmark de carga nem garantia de consumo contínuo; atende à prova
mínima deste ciclo. Nenhum polling automático foi introduzido.

## Estado deixado e retomada

**Core e cluster exclusivo parados ao fim da validação; os dois registros foram
preservados.** Sem boot/systemd, deploy, SSH/firewall/rede pública/Narys/Android
alterados. Nenhuma instância PostgreSQL de outro projeto foi usada ou alterada.

```sh
cd ~/Projetos/My-CT/server
export MYCT_PGROOT="$HOME/.local/share/myct/postgres"
export MYCT_PGSOCKET="$MYCT_PGROOT/run"
pg_ctl -D "$MYCT_PGROOT/data" -l "$MYCT_PGROOT/postgres.log" \
  -o "-k '$MYCT_PGSOCKET'" -w start
./myct-core --get-event 2
./myct-core
```

Não repetir `initdb`, `createdb` ou a migração no cluster já existente.
Ctrl-C encerra o Core; `pg_ctl -D "$MYCT_PGROOT/data" -m fast -w stop` encerra
somente este banco. Backup/restore estão documentados, mas **restore não foi
executado**. Não há gate pendente dos critérios MYCT-3B; testes prolongados,
Android, systemd, downloads e automações continuam fora do recorte.
