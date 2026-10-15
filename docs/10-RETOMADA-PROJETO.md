# My-CT — retomada após MYCT-3C aceita no Moto G06

**Atualizado:** 15/10/2026 · **Fonte de verdade:** `main`; PRs #10, #11 e #12 mesclados.
Leia [README](../README.md), [relatório MYCT-3C](MYCT-3C-DASHBOARD-REPORT.md), [relatório MYCT-3B](MYCT-3B-POSTGRES-REPORT.md), [operação Core](../server/README.md) e [AGENTS](../AGENTS.md).

## Resumo e estado real

- My-CT é **independente da Narys**. Fedora 44 headless e Moto G06 com app nativo Kotlin/Compose.
- Android: terminal SSH/PT Y com host verificado, arquivos SFTP/SAF, Dashboard Saúde (CPU/RAM/disco/uptime por túnel SSH, sem polling). A MYCT-3C passou nos testes Android (34 PASS, 1 opcional ignorado), build e lint (0 erros). O proprietário enviou captura do Dashboard real no Moto, confirmou o fluxo e os testes físicos. Hotfix de contraste incluído; [PR #12](https://github.com/Samuel-Francisco-AS/My-CT/pull/12) merge `fd8076321cd32300e2c6305954adc1b134e26d77`.
- Core C++20: `GET /v1/health` em `127.0.0.1:8765`, comprovado no Fedora; operações CLI libpq registram e consultam eventos no banco PostgreSQL exclusivo por socket Unix privado, sem TCP. PRs #10/#11 também integrados, [relatório de fechamento](MYCT-3AB-FECHAMENTO-REPORT.md).
- **Core não é serviço permanente:** atualmente `./myct-core` é iniciado manualmente e pode parar com Ctrl-C ou ao encerrar a sessão. **Não há systemd/autostart configurado**. PostgreSQL não é necessário para Saúde; o cluster 3B foi deixado parado ao final do teste, dados preservados. Durante a prova do dashboard o Core foi iniciado manualmente no terminal.
- Dívidas Android separadas: lag no terminal, UX rígida, scroll SFTP que volta ao topo e validação SFTP em background. [Issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9), não iniciar refinamento por conta própria.
- Descarga HTTP/torrent executada no servidor e rotinas autônomas ainda não existem.

## Como verificar no Fedora (sem reinicializar o banco)

```sh
cd ~/Projetos/My-CT
git switch main
git pull --ff-only origin main
cd server
make test
./myct-core --once
```

Para consultar o registro preservado, **somente se necessário** iniciar o cluster dedicado, já inicializado, sem `initdb`, `createdb` nem executar novamente a migração:

```sh
export MYCT_PGROOT="$HOME/.local/share/myct/postgres"
export MYCT_PGSOCKET="$MYCT_PGROOT/run"
pg_ctl -D "$MYCT_PGROOT/data" -l "$MYCT_PGROOT/postgres.log" \
  -o "-k '$MYCT_PGSOCKET'" -w start
./myct-core --get-event 2
```

Para API local em teste manual, executar `./myct-core` e em outra sessão SSH `curl -fsS http://127.0.0.1:8765/v1/health`; sair com Ctrl-C. Para parar **somente** o cluster deste projeto: `pg_ctl -D "$MYCT_PGROOT/data" -m fast -w stop`. Procedimentos completos (instalação, build, backup, recuperação, variáveis) em [server/README.md](../server/README.md). Não apagar, reinicializar nem reaplicar migração no diretório existente.

## Próxima necessidade operacional: systemd para o Core

O proprietário perguntou por que precisa executar `./myct-core`: não há serviço gerenciado nem início automático. **Próximo recorte sugerido**: configurar unidade systemd proporcional (de usuário, se adequadamente persistente, ou de sistema com usuário não privilegiado), inicialização no boot, restart controlado, logs simples, bind somente loopback e procedimento de stop/rollback. **Solicitar autorização explícita antes de modificar serviços/configurações do Fedora**; não iniciar PostgreSQL automaticamente apenas para Dashboard. Validar reboot no Fedora real antes de declarar operação contínua aprovada.

Outro assunto aberto para discussão, distinto do systemd: mecanismo nativo My-CT para desbloquear/usar credenciais do cofre sem depender de Narys. Ainda não há requisitos aprovados ou implementação; distinguir desbloqueio do cofre de autorização de acesso a segredos pelos agentes.

## Fases posteriores e limites

- **Após 3C:** operação/autostart por systemd somente com autorização; downloads HTTP(S) Fedora, depois torrent/magnet legal, tarefas independentes, ainda não implementados.
- **Refinamento da UI Android:** adiado, uma dívida por vez, [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).
- **Hardware:** Fedora 44 headless, i7-3770, 7,6 GiB RAM, SSD 223,6 GiB, Moto G06, sem monitor; preservar recursos. Dados de RAM/disco do inventário original são snapshots.
- **Regras:** sem segredos no Git/log, sem `sudo`, SSH/firewall/Narys/outros clusters alterados sem autorização, sem force push/rebase destrutivo nem merge automático; custo proporcional à utilidade. O servidor não deve depender de agentes/LLMs para operar.

Documento histórico da fase Android: [08](08-FECHAMENTO-CICLO-ANDROID.md); limites de UX: [09](09-TRILHA-REFINAMENTO-ANDROID.md). Mudanças futuras devem preservar a sequência de decisões, mas este guia descreve **o que existe agora**.
