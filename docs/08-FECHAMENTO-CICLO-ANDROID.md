# My-CT — fechamento do ciclo Android/SSH/SFTP (MYCT-0 a MYCT-2R)

**Data:** 15/10/2026 · **Status:** ciclo funcional concluído por decisão do proprietário, com dívida técnica/UX e validações parciais explicitamente pendentes.  
**Produto:** My-CT, independente da Narys. **Fonte de verdade para novas conversas:** este documento, [retomada](10-RETOMADA-PROJETO.md) e [refinamento](09-TRILHA-REFINAMENTO-ANDROID.md).  
**Integração:** PRs [#6](https://github.com/Samuel-Francisco-AS/My-CT/pull/6), [#7](https://github.com/Samuel-Francisco-AS/My-CT/pull/7) e [#8](https://github.com/Samuel-Francisco-AS/My-CT/pull/8) — implementados em cadeia, integrar em `main` por merges controlados no fechamento. O estado de merge definitivo é registrado ao fim deste documento após integração.

## 1. Resumo e evidência do proprietário

O My-CT agora tem um **APK Android nativo utilizável** no Moto G06 que estabelece sessão SSH autenticada com Fedora, abre um terminal/PTY real, navega no sistema de arquivos via SFTP e transfere arquivos nos dois sentidos via seletor Android (SAF).

**Evidência física relatada ao concluir esta conversa:** após a MYCT-2R, um **PDF do Fedora foi baixado e aberto corretamente no Moto G06**; outro **PDF do Moto G06 foi enviado ao Fedora com sucesso**. Em teste anterior da MYCT-2, duas imagens remotas foram baixadas e abertas corretamente. Navegação por diretórios SFTP funciona. Essas são observações diretas do proprietário, e não testes automatizados do agente. **Não confundir arquivo aberto com hash ponta a ponta comprovado.**

**Outras funções com relato físico positivo:** terminal SSH real no Fedora (incluindo `ls`, `cd`, `pwd` e PTY `/dev/pts/3`), adaptação a teclado/orientação, fonte ajustável, copiar/colar Android, proteção de screenshots com escolha local, `tmux` e permanência SSH no background observada durante uso. O modo `shouldEnforceCharBasedInput=false` **reduziu perceptivelmente** a sensação de lag, mas o atraso ainda incomoda.

**O que NÃO foi comprovado:** permanência da transferência SFTP MYCT-2R com tela bloqueada/segundo plano; cancelamento real em notificações; conflitos e falhas adversas completas; medição quantitativa de lag; operação geral prolongada/reboot; autenticação por chave do usuário; hash PDF remoto contra local. A alegação anterior de queda do SSH/arquivo PDF não abrindo ocorreu com a MYCT-2: o PDF agora funcionou com a MYCT-2R, **sem que a causa histórica da falha tenha sido identificada**.

## 2. Histórico objetivo desta conversa

| Marco | Entrega | Evidência e limite |
| --- | --- | --- |
| MYCT-0 · inventário | Hardware, Fedora, ferramentas, SSH/SDK e bloqueios | Fedora 44/kernel 7.2.8, i7-3770 (4c/8t), 7,6 GiB RAM, SSD 223,6 GiB, 133 GiB livres na coleta; modo headless; C++20 sintático PASS. CMake, PostgreSQL e libs de desenvolvimento ausentes então. |
| MYCT-1 · primeiro APK SSH | Kotlin/Compose, JSch, host key Ed25519 pinada, login por senha, shell/PTY, terminal ANSI/teclas | APK instalado; shell SSH comprovado fisicamente no Moto. Primeiros problemas: IME, clipboard, screenshots e background. |
| MYCT-1R · correção terminal | Insets/resize/fonte/teclas, clipboard manual sem OSC52, opção de captura, FGS SSH opt-in, procedimento `tmux` | Proprietário validou mudanças visuais/clipboard/captura, tmux e background SSH aparentemente funcional. Lag persistiu. |
| MYCT-1P · diagnóstico lag | Inspeção pipeline teclado→IME→JSch→Fedora→render; sonda offline | Resultado **B**: causa não comprovada; `shouldEnforceCharBasedInput` divergia do padrão do Termux. Não houve otimização ampla. |
| MYCT-2 · SFTP/SAF | Aba Arquivos, navegação, upload/download streaming/progresso, cancelamento, IME true/false | Duas imagens foram baixadas e abertas; episódio de PDF não abrindo/SSH caindo; cópia grande cancelou ao background por política então vigente. IME false aliviou lag. |
| MYCT-2R · confiabilidade | Cancelamento isolado por canal, streams sob único worker, confirmação após fechamento/validações, serviço dataSync para cópias, tamanhos decimais/contraste | 25 testes PASS/1 ignorado, lint/build/assinatura PASS; **PDF Fedora→Moto aberto e PDF Moto→Fedora enviado com sucesso no dispositivo.** Serviço dataSync **sem aceite físico**. |

Nos builds MYCT-2R, a prova CLI reportou **25 PASS, 1 ignorado**; não inferir que os testes JVM exercitaram Android/SAF e transferência Wi-Fi real. O teste local SFTP por pipes com OpenSSH e hashes de amostras é distinto dos PDFs testados no Moto. Fonte detalhada: relatórios MYCT-0, 1, 1R, 1P, 2 e 2R em `docs/`.

## 3. Arquitetura implementada (não apenas proposta)

```text
Moto G06 · Android Kotlin/Jetpack Compose
  ├─ Terminal (Termux terminal-view/emulator reutilizados como componentes)
  │     └─ JSch → SSH autenticado/host Ed25519 verificado → OpenSSH Fedora → PTY/bash
  ├─ Arquivos (UI Compose, SFTP/SAF)
  │     └─ ChannelSftp JSch na MESMA Session SSH → OpenSSH/SFTP Fedora
  │           ├─ Android → Fedora: SAF OpenDocument / upload temporário → hardlink no-replace
  │           └─ Fedora → Android: SFTP streaming 64 KiB → SAF CreateDocument
  ├─ Serviço foreground specialUse opt-in: manter conexão SSH com notificação
  ├─ Serviço foreground dataSync: acompanhar transferência SFTP, progresso/cancelar
  └─ Preferências locais: fingerprint pinado, último host/usuário,
        fonte, proteção de captura, IME char-based true/false (sem senha armazenada)

Fedora headless
  ├─ OpenSSH/sshd + subsistema SFTP existente (não alterado neste ciclo)
  └─ tmux 3.7c existente: processo shell persistente manual via socket 'myct'

AINDA NÃO IMPLEMENTADOS: Core C++20, CMake do Core, API de telemetria,
PostgreSQL do My-CT, serviços systemd do Core, downloads HTTP/torrent server-side,
dashboard real e automações independentes.
```

O terminal **não executa o app Termux**: reutiliza suas bibliotecas/componentes de terminal; autenticação, sessões SSH/SFTP e UI pertencem ao My-CT. Terminal de texto trabalha com **grade fixa** (caracteres e escape ANSI), não com reflow de conteúdo responsivo. O layout editorial alternativo deve ser uma camada distinta, se desejado.

**Pontos técnicos:** SSH pinado por fingerprint SHA256 do host Ed25519, usuário com senha efêmera; opção IME true padrão (compatibilidade histórica) / false reversível; screenshot `FLAG_SECURE` configurável, ligado inicialmente; clipboard apenas por ação humana, OSC52 remoto inerte. Upload remoto evita sobrescrita usando `hardlink@openssh.com` e temporários; depende da extensão e do filesystem. SFTP não oferece hash remoto portátil neste fluxo; o download compara bytes/metadados e eventualmente tamanho SAF, sem garantia de hash ponta a ponta. Não há retomada automática após processo morrer.

O serviço Android `dataSync` é específico de transferência; no Android 15+ pode estar sujeito a **6 horas acumuladas em 24 horas em background**; não é prova de continuidade física. O `specialUse` de terminal é distinto e iniciado pelo usuário. `tmux` preserva trabalho remoto depois de perder SSH, mas não reinicia após reboot por padrão.

## 4. Estado de aceite por capacidade

| Capacidade | Situação no encerramento | Base da situação |
| --- | --- | --- |
| APK nativo instalável | **Comprovado fisicamente** | Executado no Moto G06; atualização por APK debug de assinatura igual. |
| SSH real, shell, host verificado, comandos | **Comprovado fisicamente no uso básico** | `ls/cd/pwd`, PTY e conexões autenticadas. Teclas avançadas/TUI não foram todas revalidadas formalmente. |
| IME, adaptação de teclado/orientação, fonte, clipboard, screenshot | **Comprovado fisicamente** | Retorno explícito do proprietário MYCT-1R. |
| Persistência de trabalho com tmux | **Comprovada no uso pelo proprietário** | Prova isolada local do Codex + confirmação física posterior; sem garantia de reboot. |
| SSH em segundo plano | **Relato positivo, duração/robustez não formalmente aceitas** | FGS notificado e utilização relatada; kill/force-stop permanecem limites. |
| Listagem/navegação SFTP | **Comprovada fisicamente** | Navegação em pastas, capturas e relato. |
| Download SFTP Android | **Comprovado fisicamente no fluxo feliz** | 2 imagens e PDF transferidos/abertos. |
| Upload SFTP Fedora | **Comprovado fisicamente no fluxo feliz** | PDF do Moto enviado com sucesso. |
| Cancelamento, conflito e integridade adversa | **Implementados, prova física incompleta** | Testes automatizados; cenários complexos não aprovados no Moto. |
| Transferência SFTP em background MYCT-2R | **Implementada, NÃO testada fisicamente** | FGS dataSync, código/lint/testes; dívida de validação. |
| Digitação sem lag, UI fluida, posição de rolagem | **Dívida de refinamento** | Usuário ainda percebe lag, rigidez visual, scroll volta ao topo ao retornar à pasta pai. |
| Core C++/PostgreSQL, Dashboard, Downloads server-side, Jobs | **Não iniciado** | Planejamento original, sem execução. |

**Aceite do ciclo:** proprietário optou por consolidar a fase Android/SSH/SFTP e avançar, carregando as pendências na [trilha de refinamento](09-TRILHA-REFINAMENTO-ANDROID.md), em vez de exigir solução de todas elas antes do merge. *Aceite do ciclo não equivale a certificar cada cenário físico.*

## 5. Artefatos e reprodução

Último APK da fase: `~/Projetos/My-CT/artifacts/myct-2r-debug.apk` (**fora do Git**, gerado no Fedora onde foi construído), pacote `dev.myct`, versão `0.2.1-sftp-corrective`, versionCode **4**, tamanho **13.522.361 bytes**, SHA256:

```text
c06e4521f55dcfc4596c909d65132257105eb305a4d456ef4b163fcf5ab51e04
```

Build no checkout de `main` após integração, usando JDK 21, Android SDK 36, Build Tools existentes, cache Gradle:

```sh
cd ~/Projetos/My-CT
./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python android/terminal/upstream/verify.py
```

`--offline` presume caches locais previamente preparados. `app-debug.apk` gerado em `android/app/build/outputs/apk/debug/`; assinatura debug pode variar se keystore for regenerada. Não inventar um link de download GitHub do APK: binário não foi commitado. `android/README.md` contém o fluxo de instalação por Termux/ADB opcional, pin do host e limitações SAF/serviços.

O relatório MYCT-0 observou CMake/PostgreSQL e ferramentas de desenvolvimento ausentes naquela data. **Reinspecionar** antes de iniciar Core; não instalar serviços ou expor portas sem autorização. Servidor Fedora e credenciais de outros projetos foram preservados.

## 6. Dívidas explícitas e próxima fase

**Trilha de refinamento futura:** [plano](09-TRILHA-REFINAMENTO-ANDROID.md) e [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). Itens: latência de entrada/backspace persistente (false melhorou); UI/ergonomia rígidas e contraste geral; scroll SFTP perde posição ao voltar de uma pasta; transferências em background MYCT-2R sem teste físico; cancelamentos/conflitos/falhas SAF sem cobertura física suficiente; eventual autenticação por chave protegida.

**Próxima fase escolhida na direção do projeto (não executada neste fechamento):** Core **C++20 + PostgreSQL** + painel de saúde real do servidor, iniciando com a menor API segura e uma gravação/leitura mínima no banco; depois downloads no Fedora, tarefas autônomas e operação por systemd. Consulte [retomada](10-RETOMADA-PROJETO.md), [planejamento](04-PLANEJAMENTO-INAUGURAL.md), [issue #2](https://github.com/Samuel-Francisco-AS/My-CT/issues/2) e [issue #4](https://github.com/Samuel-Francisco-AS/My-CT/issues/4).

## 7. Referências

- `docs/MYCT-0-INVENTORY-REPORT.md`
- `docs/MYCT-1-ANDROID-SSH-REPORT.md`, `docs/MYCT-1R-CORRECTION-REPORT.md`, `docs/MYCT-1P-LATENCY-DIAGNOSIS.md`
- `docs/MYCT-2-SFTP-REPORT.md`, `docs/MYCT-2R-CORRECTION-REPORT.md`
- `docs/03-REQUISITOS-E-ACEITACAO.md`, `docs/05-DECISOES-E-QUESTOES.md`, `android/README.md`
- PRs #6 → #7 → #8, histórico registrado no diário e issue #9.

## 8. Registro de integração na main

*A preencher com SHAs efetivamente retornados pelo GitHub ao integrar #6, #7 e #8; não presumir merge bem-sucedido antes da confirmação.*
