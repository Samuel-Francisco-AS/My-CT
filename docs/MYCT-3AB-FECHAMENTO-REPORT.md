# My-CT — fechamento MYCT-3A e MYCT-3B

**Data:** 15/10/2026  
**Estado:** entregas de Core C++20/saúde e persistência PostgreSQL integradas e validadas no Fedora real. **MYCT-3C (Dashboard Android) ainda não iniciada.**

## Integração confirmada no GitHub

| Ordem | PR | Conteúdo | Merge SHA |
| --- | --- | --- | --- |
| 1 | [#10 — MYCT-3A](https://github.com/Samuel-Francisco-AS/My-CT/pull/10) | Core C++20 mínimo, `GET /v1/health` local, CPU/RAM/disco e testes | `ed5528d0bb733fee9df206cf836d7e0d74c7faf3` |
| 2 | [#11 — MYCT-3B](https://github.com/Samuel-Francisco-AS/My-CT/pull/11) | libpq, PostgreSQL por socket Unix privado, migração e CLI de eventos | `9d23535ca19357429660a95cb9afa40166309c7b` |

O PR #10 estava inicialmente em draft; foi marcado como pronto e mesclado em `main`. O PR #11, originalmente baseado em `myct-3a-core-health`, foi redirecionado para `main` **após** o merge #10 e integrado com merge não-squash. Histórico de código e documentação preservado.

## Evidência real e escopo validado

**MYCT-3A (Fedora):**
- `make test`: 2/2 PASS.
- `./myct-core` respondeu com JSON em `GET /v1/health`: CPU percentual, memória total/disponível, disco total/disponível e uptime do processo.
- `ss -lnt`: bind HTTP somente `127.0.0.1:8765`.
- RSS observado: 4.072 KiB (~4 MiB), CPU 0,0% em amostra pontual.

**MYCT-3B (Fedora):**
- C++20 compilado sem avisos; `make test` 3/3 PASS; `make test-db` 1/1 PASS.
- Banco PostgreSQL 18.6 exclusivo do My-CT, em `~/.local/share/myct/postgres`, peer, socket privado e `listen_addresses=''` — sem listener TCP.
- CLI com `--record-event TYPE` e `--get-event ID`; registro ID 2 foi preservado depois de reiniciar Core **e banco**.
- API retornou HTTP 200 com o banco parado. Banco indisponível só afeta operações da CLI que precisam dele.
- Dependências instaladas no Fedora pelo proprietário: `libpq/libpq-devel` 18.0 e PostgreSQL 18.6.
- RSS Core ~7,4 MiB; PostgreSQL ~23,7 MiB PSS agregado; dados ~42 MiB; CPU 0,0% em amostra pontual.
- Core e cluster foram **deixados parados**, com registros preservados; **não** há serviço systemd My-CT nem inicialização automática.

As medições são snapshots, não benchmarks nem garantia de operação prolongada. O relatório completo e o guia para retomar o banco estão em [MYCT-3B-POSTGRES-REPORT.md](MYCT-3B-POSTGRES-REPORT.md) e [server/README.md](../server/README.md).

## O que segue pendente

1. **MYCT-3C:** exibir saúde real no Moto G06, reaproveitando autenticação SSH e um túnel local para `127.0.0.1:8765` no Fedora. Confirmar primeiro uma métrica real no aparelho. Não abrir a API em `0.0.0.0` nem inserir senha/token no APK.
2. **Operação:** systemd/reboot, disponibilidade prolongada e recuperação automática não estão aprovados. Evitar ligar serviços de forma global sem autorização.
3. **Recursos planejados:** download HTTP(S) e torrent/magnet no Fedora, jobs independentes, dashboard mais completo.
4. **Refinamento Android adiado:** lag de teclado, UX rígida, scroll SFTP e validação da transferência em segundo plano ([issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9)). Não reabrir automaticamente.
5. **Banco:** recuperação por `pg_restore` documentada mas não testada; não repetir `initdb` nem migração no cluster existente.

A [issue #2](https://github.com/Samuel-Francisco-AS/My-CT/issues/2) permanece aberta porque o marco original também inclui o cliente Android lendo saúde real; não declarar esse gate encerrado até a MYCT-3C.

## Retomada e Git

- Ponto de verdade: `main`, este documento, [guia de retomada](10-RETOMADA-PROJETO.md) e [server/README.md](../server/README.md).
- No Fedora, atualizar `main`, evitar alterações em outros projetos e preservar o cluster local sem inicializar novamente. Para a próxima missão, criar branch **a partir da main atualizada**, sem acoplar Narys.
- Branches de desenvolvimento `myct-3a-core-health` e `myct-3b-postgres` podem ser removidas **localmente** depois de atualizar `main`; remover remotas apenas em operação explícita.
- Nenhum merge do código atual instalou serviço ou mudou SSH/firewall/publicação da API. Gate de próxima fase continua **fluxo real Moto ↔ SSH ↔ Core**.
