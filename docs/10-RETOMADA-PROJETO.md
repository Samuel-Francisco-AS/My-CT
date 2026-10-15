# My-CT — guia de retomada após MYCT-4/4B

**Atualizado:** 15/10/2026 · **Fonte de verdade:** `main`. PRs #10/#11/#12 (MYCT-3) e #13/#14 (MYCT-4/4B) **mesclados**, com aceite físico Fedora/Moto G06.  
**Relatório de fechamento:** [MYCT-4-FECHAMENTO-REPORT.md](MYCT-4-FECHAMENTO-REPORT.md).

## O que funciona agora

- **Independente da Narys:** Fedora 44 headless + Moto G06 com aplicativo Android Kotlin/Compose, sessão SSH real (identidade do host verificada), navegação/transfers SFTP/SAF bidirecionais.
- **Core C++20 automático:** `myct-core.service` instalado como serviço systemd de sistema, executando `/usr/local/bin/myct-core` como `sam`; GET `127.0.0.1:8765/v1/health` com CPU, RAM, disco e uptime. **Autostart comprovado após `sudo systemctl reboot`**: Dashboard Saúde funcionou sem `./myct-core`. Android acessa endpoint local apenas por túnel SSH, não por API pública.
- **PostgreSQL:** persistência libpq via cluster dedicado/sockets Unix sem TCP validada na MYCT-3B, mas independente do Dashboard e não iniciada pelo serviço Core. Preservar `~/.local/share/myct/postgres`.
- **Android:** quatro abas **Terminal | Arquivos | Saúde | Cofre**. O Cofre mostra locked/unlocked/unavailable do GNOME Keyring `login`, solicita desbloqueio humano via PTY SSH secundário privado (a senha não passa pelo Core). Proprietário comprovou desbloqueio real via Moto e **Copilot CLI autenticando** depois com credenciais existentes. `gh auth status` também confirmou acesso via keyring. `myct-vault status|unlock` funciona independentemente da Narys.
- **Build/testes:** Core 3/3 testes, helper cofre 6/6 após ajuste final (confirmação do proprietário), APK de diagnóstico 48 testes PASS/1 opcional ignorado, lint 0 erros/6 avisos de versões, build assinado compatível com MYCT-3C. Teste real do fluxo de Cofre prevalece sobre simulação.
- **Não entregues:** downloads HTTP(S), BitTorrent/magnet e rotinas autônomas. Não assumir implementados.
- **Dívidas Android adiadas:** lag do terminal, fluidez de interface, scroll SFTP que retorna ao topo e teste de transferência em segundo plano — [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). Não iniciar refinamento sem solicitação.

## Verificação rápida no Fedora

```sh
cd ~/Projetos/My-CT
git switch main
git pull --ff-only origin main
make -C server test
python3 -m unittest discover -s ops/tests -v
systemctl is-enabled myct-core.service
systemctl is-active myct-core.service
curl -fsS http://127.0.0.1:8765/v1/health
~/.local/bin/myct-vault status
```

`status` do cofre depois do reboot pode retornar `locked`: isso é **esperado** e não impede a Saúde. Para desbloquear, o proprietário usa a **aba Cofre** no Android conectado via SSH, ou `~/.local/bin/myct-vault unlock` manualmente em SSH interativo privado. Não pedir senha por chat, logs ou agentes. O GNOME Keyring não é desbloqueado no boot.

**Importante:** o binário em `server/myct-core` do checkout **não** é o binário instalado em `/usr/local/bin/myct-core`. Se for compilada atualização futura, é necessário planejar instalação controlada e restart do systemd; não iniciar `./myct-core` em paralelo com o serviço ativo (porta 8765). O helper instalado em `~/.local/bin/myct-vault` também é cópia separada, que só muda com `install -m 0700`. Instalação original e correção SELinux: [MYCT-4](MYCT-4-BOOT-VAULT.md).

## Banco PostgreSQL preservado

Para consultar registro histórico **somente se necessário**, iniciar o cluster dedicado já existente, sem `initdb`, `createdb` ou reaplicar migrations:

```sh
export MYCT_PGROOT="$HOME/.local/share/myct/postgres"
export MYCT_PGSOCKET="$MYCT_PGROOT/run"
pg_ctl -D "$MYCT_PGROOT/data" -l "$MYCT_PGROOT/postgres.log" \\
  -o "-k '$MYCT_PGSOCKET'" -w start
cd ~/Projetos/My-CT/server
./myct-core --get-event 2
```

Para parar **somente** esse cluster, se ele tiver sido iniciado manualmente: `pg_ctl -D "$MYCT_PGROOT/data" -m fast -w stop`. Os arquivos e operações de banco devem ser preservados e seguir [server/README.md](../server/README.md).

## Limites de operação e segurança

- O helper usa a interface interna **não suportada** do GNOME Keyring com verificação do daemon/hash compatível. Atualizações do Fedora/GNOME podem requerer nova análise. Não apagar cofre, contornar hash ou reinicializar coleção para corrigir compatibilidade.
- O desbloqueio da coleção `login` **não concede acesso seletivo por agente**; processos do mesmo usuário Linux compartilham o domínio de acesso do Secret Service. Senha não é persistida pelo My-CT e não é passada ao serviço Core.
- Evitar alterar SSHD, firewall, SELinux, contas, banco, GNOME Keyring ou Narys sem autorização. API Saúde continua em loopback; acessos remotos via SSH. Não forçar testes destrutivos ou refazer aceitações físicas sem motivo.
- Limite de hardware: Fedora headless, i7-3770, cerca de 8 GiB RAM e sem GPU dedicada. Propor implementações proporcionais ao objetivo de uso pessoal/portfólio, não padrões comerciais sem demanda.
- Interfaces devem permanecer responsivas e suportar retorno ao aparelho; dívidas UX estão documentadas, mas não confundidas com a entrega MYCT-4.

## Documentos e histórico

- [MYCT-4 Fechamento](MYCT-4-FECHAMENTO-REPORT.md) — estado conclusivo e evidências.
- [MYCT-4 Core e helper](MYCT-4-BOOT-VAULT.md) — implementação, correção 203/EXEC e suporte.
- [MYCT-4B Aba Cofre](MYCT-4B-ANDROID-VAULT.md) — protocolo, diagnóstico `logind`, testes e aceite.
- [MYCT-3A/3B Fechamento](MYCT-3AB-FECHAMENTO-REPORT.md), [MYCT-3C Dashboard](MYCT-3C-DASHBOARD-REPORT.md) — implementações anteriores.
- [Trilha refinamento Android](09-TRILHA-REFINAMENTO-ANDROID.md), [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).
- [README](../README.md) e [AGENTS.md](../AGENTS.md).

O trabalho das branches `myct-4-boot-and-vault` e `myct-4b-android-vault` está integrado à `main`. A exclusão das branches Git não remove o serviço Fedora instalado, o APK, o banco ou o cofre.
