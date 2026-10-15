# MYCT-3B — persistência PostgreSQL mínima

Data: 15/10/2026. **Implementação entregue; integração real ainda NÃO aprovada.**
Base atualizada: `myct-3a-core-health` em `afeeebc`; PR #10 preservado.
Branch: `myct-3b-postgres`, [PR #11](https://github.com/Samuel-Francisco-AS/My-CT/pull/11)
em rascunho contra a MYCT-3A, sem merge.

## Implementação e aceite

- `server/postgres.cpp/.hpp`: libpq direta, conexão Unix/peer, banco `myct`,
  usuário Unix efetivo, conexão por operação e SQL parametrizado.
- `server/migrations/001_execution_events.sql`: transação com tabela de eventos,
  bigint identity, timestamptz e tipo restrito a 1–64 caracteres ASCII seguros.
- `main.cpp`: `--record-event TYPE` e `--get-event ID`, saída TSV com UTC.
- Makefile mantido; libpq é a única nova dependência de código.
- Saúde HTTP permanece independente do banco, em loopback; sem endpoints novos.
- Teste existente HTTP agora usa socket indisponível. Um teste CLI verifica
  falhas explícitas/entrada; `make test-db` grava e relê via processos distintos.
- `server/README.md`: pacote mínimo, cluster privado sem TCP/systemd, migração,
  operação, backup e recuperação. Sem alterações SSH/firewall/Narys/Android.

Aceite exigido: build Fedora, gravação/leitura reais após encerramento do Core,
saúde com banco parado, binds locais e testes anteriores sem regressão.

## Evidência e validação desta execução

- Fedora Linux 44, GCC `16.2.1 20260819`, GNU Make `4.4.1`.
- `git fetch origin myct-3a-core-health` + avanço fast-forward: já atualizada.
- `rpm -q libpq libpq-devel postgresql postgresql-server`: todos ausentes.
- `dnf info libpq-devel postgresql postgresql-server`: pacotes disponíveis;
  metadados observaram libpq-devel 18.0 e cliente/servidor 18.6. A consulta
  apresentou erro de assinatura de repositório terceiro; nenhuma chave foi
  importada. Instalação proposta usa somente `fedora`/`updates`.
- `python3 -m py_compile server/tests/test_core.py server/tests/integration_postgres.py`:
  PASS (sintaxe Python apenas, não execução dos testes).
- `git diff --check`: PASS.
- `make -C server`: **BLOQUEADO** por ausência de `pg_config`/`libpq-fe.h`;
  receita agora informa a dependência ausente antes de compilar.
- `make test` e `make test-db`: **não executados**, dependem do novo binário.
  Nenhuma gravação PostgreSQL foi demonstrada nesta execução.
- A evidência MYCT-3A informada pelo proprietário (2/2 PASS, health real,
  bind 127.0.0.1:8765, ~4 MiB RSS e 0% CPU) é anterior; não é validação da 3B.

## Dependências, recursos e gate de retomada

Nenhum pacote instalado, nenhum cluster criado, nenhum serviço modificado.
Instalação solicitada ao proprietário e ainda sem autorização recebida:

```sh
sudo dnf --repo=fedora --repo=updates install libpq-devel postgresql postgresql-server
```

`libpq-devel` resolve libpq runtime; `postgresql` fornece cliente/administração;
`postgresql-server` fornece servidor/initdb/pg_ctl. Sem contrib, ORM ou CMake.

**Gate exato:** autorizar/realizar a instalação, seguir o cluster exclusivo em
[server/README.md](../server/README.md), executar `make && make test`, aplicar
migração e rodar `make test-db`. Registrar o ID/UTC retornado; parar/reiniciar
Core e reler esse ID. Parar banco e consultar `/v1/health`, conferir
`SHOW listen_addresses`, socket privado e `ss -lntp`. Reiniciar somente o cluster
My-CT e consultar novamente. Não usar nem alterar banco de outro projeto.

Consumo 3B **não medido**: sem binário/servidor executáveis. Após o gate, registrar
`ps -o pid,rss,%cpu,comm -p PID_DO_CORE`, processos do cluster (RSS pode duplicar
memória compartilhada), tamanho do binário e `du -sh "$MYCT_PGROOT/data"`.
Config proposta: shared_buffers 16 MB, max_connections 10; isso não constitui
medição. Backup/restore documentados, ainda não testados.

Limites: disponibilidade do servidor PostgreSQL não foi demonstrada; compilação,
testes e consumo precisam da instalação. Não declarar o ciclo aprovado por
inspeção de código ou pela validação anterior da MYCT-3A.
