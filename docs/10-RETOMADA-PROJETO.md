# My-CT — guia de retomada (após integração MYCT-3A/3B)

**Atualizado:** 15/10/2026 · **Fonte de verdade:** `main`.
**Ordem de leitura:** [README](../README.md) → [fechamento MYCT-3A/3B](MYCT-3AB-FECHAMENTO-REPORT.md) → [relatório 3B](MYCT-3B-POSTGRES-REPORT.md) → [operação Core](../server/README.md) → [requisitos](03-REQUISITOS-E-ACEITACAO.md) → [AGENTS](../AGENTS.md). Para Android anterior, consulte [fechamento SSH/SFTP](08-FECHAMENTO-CICLO-ANDROID.md).

## Resumo e estado real

- **My-CT é independente da Narys.** Servidor pessoal Fedora headless, cliente Moto G06 nativo Kotlin/Compose, Core C++20 e PostgreSQL.
- **Android existente:** SSH autenticado com host Ed25519 pinado, terminal PTY/ANSI, SFTP/SAF bidirecional, imagens/PDF abertos no Moto e upload de PDF ao Fedora. Serviços foreground de SSH e dataSync disponíveis no app.
- **Core já integrado à main:** C++20, Makefile, `GET /v1/health` (CPU/RAM/disco/uptime) local em `127.0.0.1:8765`, CLI de gravação/consulta de eventos e libpq direta. PostgreSQL 18.6 exclusivo em socket Unix privado, peer, TCP desativado.
- **Evidência 3A:** Fedora: 2/2 testes PASS, endpoint JSON real, bind só loopback; ~4 MiB RSS observado.
- **Evidência 3B:** Fedora: 3/3 testes locais, 1/1 integração real; evento ID 2 persistiu após reiniciar Core e banco; health respondeu 200 com banco parado; Core ~7,4 MiB RSS, cluster ~23,7 MiB PSS, dados ~42 MiB (amostras).
- **Estado deixado pelo agente:** Core e cluster PostgreSQL **parados**, dados preservados. Nenhum systemd My-CT, API LAN, automação, download server-side ou dashboard Android foi implantado.
- **Dívidas Android adiadas:** lag, UI truncada, scroll SFTP que volta ao topo, transferências SFTP em background ainda sem prova física; [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).
- **Git:** [PR #10](https://github.com/Samuel-Francisco-AS/My-CT/pull/10) (merge `ed5528d0bb733fee9df206cf836d7e0d74c7faf3`) e [PR #11](https://github.com/Samuel-Francisco-AS/My-CT/pull/11) (merge `9d23535ca19357429660a95cb9afa40166309c7b`) integrados em ordem na `main`. O PR #11 foi redirecionado para `main` após o merge do #10.

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

## Próxima missão recomendada: MYCT-3C — Dashboard Android mínimo

**Objetivo único:** no Moto G06, ler e mostrar pelo menos uma métrica **real** fornecida pelo Core, via sessão SSH autenticada existente. Não iniciar nova área operacional até a prova física.

1. Partir da `origin/main` atualizada, branch própria. Ler `SshConnection.kt`, `TerminalModel.kt`, `MainActivity.kt`, `server/main.cpp` e o guia Android.
2. Reaproveitar a sessão JSch SSH verificada para um **túnel local autenticado Android → Fedora 127.0.0.1:8765**; não expor API em LAN, nem guardar credenciais, nem criar login próprio. Se precisar de alteração de forwarding no Fedora, apresentar e obter autorização antes.
3. Implementar uma tela Compose pequena: CPU, RAM e disco (ou um subconjunto inicial); indicador de carregamento/erro; atualização **somente sob demanda**, sem polling contínuo.
4. Manter terminal e SFTP atuais operacionais. Não refatorar renderizador, fluxo SAF ou serviços foreground nesta missão. Testar build/lint adequados e validar valores reais lado a lado com `curl` no Fedora no Moto G06.
5. Se conexão/dispositivo impedir a prova, registrar estado parcial; mocks não são gate físico. Não acoplar dashboard ao PostgreSQL: `/v1/health` funciona com banco parado.

**Gate:** APK atualizado instalado no Moto mostra métrica real do Fedora acessada pela sessão SSH, sem porta API externa nem quebra de SSH/SFTP. Registrar consumo, instruções para reproduzir e pending. A [issue #2](https://github.com/Samuel-Francisco-AS/My-CT/issues/2) continua aberta até esse fluxo Android.

## Fases posteriores e limites

- **Depois da 3C:** operação/autostart por systemd **somente com autorização**, downloads HTTP(S) no Fedora e depois torrent/magnet legal, tarefas independentes. Nada disso está implementado.
- **Refinamento da UI Android:** adiado, uma dívida por vez, [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).
- **Hardware:** Fedora 44 headless, i7-3770, 7,6 GiB RAM, SSD 223,6 GiB, Moto G06, sem monitor; preservar recursos. Dados de RAM/disco do inventário original são snapshots.
- **Regras:** sem segredos no Git/log, sem `sudo`, SSH/firewall/Narys/outros clusters alterados sem autorização, sem force push/rebase destrutivo nem merge automático; custo proporcional à utilidade. O servidor não deve depender de agentes/LLMs para operar.

Documento histórico da fase Android: [08](08-FECHAMENTO-CICLO-ANDROID.md); limites de UX: [09](09-TRILHA-REFINAMENTO-ANDROID.md). Mudanças futuras devem preservar a sequência de decisões, mas este guia descreve **o que existe agora**.
