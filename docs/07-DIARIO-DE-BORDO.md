# My-CT — diário de bordo e registro de evidências

## 2026-10-14 — Fundação documental
**Situação verificada:** repositório privado Samuel-Francisco-AS/My-CT encontrado, inicialmente vazio, branch padrão main.

**Escopo conversado e registrado:**
- Servidor pessoal Fedora com Core C++20 e PostgreSQL.
- APK Android nativo Kotlin/Compose.
- Terminal Linux real via SSH/PTTY no app.
- Gestão de arquivos e envio/recebimento via SFTP.
- Downloads HTTP(S) e BitTorrent/magnet executados pelo servidor, sem µTorrent como dependência.
- Dashboard de saúde, base para automações úteis.
- Planejamento aberto, sujeito a revisão; Codex como recurso temporário, nunca dependência de runtime.

**Ato documental:** criação de README, documentos de visão, arquitetura, requisitos, planejamento, decisões, riscos, diário e orientações aos agentes.

**Não realizados nesta etapa:** implementação, build, teste no Moto G06, configuração do Fedora/PostgreSQL, instalação de daemon, VPN, publicação de APK ou deploy de serviços.

**Primeiro passo futuro:** inventário real do Fedora e verificação de toolchain, seguido de prova de terminal/arquivo e Core mínimo conforme planejamento.

## Modelo para entradas seguintes
### AAAA-MM-DD — Título curto
- **Objetivo:**
- **Alterações/commits/issues:**
- **Ambiente e versões:**
- **Comandos e testes executados:**
- **Evidências observadas (dispositivo real ou simulação):**
- **Riscos e pendências:**
- **Decisões alteradas:**
- **Próximo menor passo útil:**

> Não registrar PASS sem execução e evidência. Preferir poucos fatos reproduzíveis a relatórios volumosos.

### 2026-10-14 — MYCT-0 inventário técnico inaugural
- **Objetivo:** inventariar o Fedora, ferramentas Android, rede e viabilidade sem alterar serviços ou instalar dependências.
- **Alterações/commits/issues:** criado `docs/MYCT-0-INVENTORY-REPORT.md`; branch `myct-0-inventory`.
- **Ambiente e versões:** Fedora 44 Workstation, kernel 7.2.8 x86_64, i7-3770 (4c/8t), 7.6 GiB RAM, zram 7.6 GiB, disco 223.6 GiB com 133 GiB disponíveis. G++ 16.2.1; Java 21.0.10; Android SDK 1.8 GiB.
- **Comandos e testes executados:** consultas de sistema, RPM, toolchain, SDK/ADB, systemd, sockets/rede e amostra `vmstat`; teste sintático `g++ -std=c++20 -fsyntax-only` passou. Consulta ADB iniciou daemon local e não encontrou dispositivos. Leitura de config SSH e regras detalhadas de firewall negada sem autorização.
- **Evidências observadas:** SSH server ativo/habilitado; systemd em `multi-user.target`; firewalld ativo; PostgreSQL/CMake/libpq/libpqxx/libcurl-devel ausentes; qBittorrent desktop presente sem daemon headless; Gradle CLI/wrapper não encontrado; Moto G06 não detectado. Nenhum APK ou serviço My-CT testado.
- **Riscos e pendências:** terminal SSH/PTTY Android exige prova física; toolchain de backend e Gradle incompletas; configuração de segurança de SSH/firewall não inspecionada; medir pressão de RAM/disco e política de armazenamento/backup.
- **Decisões alteradas:** nenhuma. Planejamento inaugural não foi reescrito; recomendações permanecem não vinculantes.
- **Próximo menor passo útil:** MYCT-1, prova de build Android CLI e SSH/PTTY real no Moto G06, conforme critérios no relatório.

### 2026-10-14 — MYCT-1 APK e terminal SSH (validação parcial)
- **Objetivo:** entregar a primeira prova Android Kotlin/Compose de terminal SSH real, sem backend ou alterações de serviços do Fedora.
- **Alterações/commits/issues:** branch `myct-1-android-ssh` baseada em MYCT-0; novo projeto `android/`, Wrapper, testes, módulos de terminal/licenças, relatório MYCT-1 e instruções de build/instalação. README e escolhas experimentais em docs/05 atualizados; planejamento inaugural preservado.
- **Ambiente e versões:** JDK 21.0.10; distribuição Gradle 8.14.3 all descoberta no cache; AGP 8.13.0, Kotlin 2.2.20, Compose 1.9.3/Material3 1.4.0, mwiede/JSch 2.28.8, BC 1.86; Termux terminal v0.118.3 fixado por commit. MinSdk 26, target 36.
- **Comandos e testes executados:** Wrapper gerado offline e hash oficial conferido; build debug, cinco testes JVM (pins, ANSI/UTF-8/resize, handshake real localhost), lint final sem problemas, clean build offline e assinatura APK v2 verificada. Após ajuste final do caminho de falha de escrita SSH, build/testes/lint offline novamente aprovados.
- **Evidências observadas:** APK em `artifacts/myct-1-debug.apk` (~13.3 MB). Teste SSH real aceitou fingerprint autoritativo e rejeitou pin incorreto, sem login/shell. Primeiro build 194.38s; clean offline 41.51s. Pico Java amostrado ~2.36 GiB; aumento de artefatos de cache ~47.7 MB após aprovação de até 200 MB. Não houve download de SDK/Gradle ou emulador.
- **Dispositivo físico:** ADB sem dispositivos; proprietário informou que a validação física ficará pendente. `pwd`, `uname -a`, programas interativos, IME/teclas/resize e reconexão no Moto G06 NÃO foram validados. Resultado parcialmente validado, sem aprovação da missão no aparelho.
- **Riscos e pendências:** autenticação somente password/host Ed25519; testar política real do servidor sem alterá-la, UI/IME, rede e lifecycle Android. Não há foreground service. APK debug com assinatura local; transferência manual por USB/SFTP descrita em android/README.md.
- **Decisões alteradas:** nenhuma direção arquitetural; bibliotecas e recorte são escolhas experimentais revisáveis em docs/05. SSH/firewall/serviços existentes preservados.
- **Publicação:** implementação em `19289c9`, branch enviada por SSH; [PR #6](https://github.com/Samuel-Francisco-AS/My-CT/pull/6) criado em draft pelo conector GitHub (gh local sem sessão válida). Nenhum merge.
- **Próximo menor passo útil:** instalar APK no Moto G06, conferir host key e executar checklist físico antes de ampliar para SFTP/Core.

### 2026-10-14 — MYCT-1R: prova física e abertura da correção
- **Evidência relatada pelo proprietário:** no Moto G06, APK conectou autenticado por SSH ao Fedora; `ls`, `cd`, `pwd` funcionam; `tty` → `/dev/pts/3` e `stty size` → `17 51` **com teclado aberto**.
- **Problemas observados:** ajuste visual/IME e latência leve, cópia para clipboard inoperante, screenshot preta, sessão terminada quando o Android encerra app em segundo plano e senha exigida ao reconectar.
- **Confirmações por revisão de código:** `FLAG_SECURE` global e callback de Copy vazio; sem foreground service; não foi demonstrada a causa do lag/resize.
- **Decisão:** correção incremental MYCT-1R na branch de PR #6, sem merge; planejar dimensões/IME, clipboard e controle de screenshot, continuidade via serviço Android apropriado e persistência de trabalho remoto via tmux ou equivalente. Sequência negociável. Ver [plano MYCT-1R](MYCT-1R-CORRECTIVE-PLAN.md).
- **Estado:** planejamento apenas; nenhum teste corretivo, build ou implantação realizado nesta entrada.

### 2026-10-14 — MYCT-1R correção e continuidade (aceite físico pendente)
- **Base física preservada:** proprietário confirmou no Moto G06 login SSH, ls/cd/pwd, PTY /dev/pts/3 e stty size 17×51 com IME aberta. Relatou layout/lag, Copy sem efeito, screenshot preta e perda rápida em background. Nenhum PASS físico novo foi inventado.
- **Correções:** insets explícitos e área medida da AndroidView, barra única/fonte ajustável, resize deduplicado com pixels preservados, clipboard manual separado de OSC 52 e paste confirmado, captura protegida por padrão com opt-out local.
- **Continuidade Android:** motor em escopo de processo; FGS specialUse opt-in após autenticar, notificação/Desconectar e sem senha em Intent. UI reanexa à sessão viva; START_NOT_STICKY, sem boot/wakelock/reauth ou comandos automáticos. Validação real de background e bateria pendente.
- **Fedora:** tmux 3.7c existente. Comando manual em namespace myct, sem executar automaticamente. Prova isolada com PTY local: tarefa progrediu de 2 para 4 ticks após fechar cliente; reattach ao mesmo pane/processo. Não é prova de SSH autenticado; teste no Moto pendente. Servidor de teste próprio encerrado sem tocar outras sessões.
- **Verificações:** build/testes/lint offline; 8 testes PASS (pins, ANSI/resize, clipboard/OSC e handshake local sem login), lint sem problemas; 21 arquivos upstream conferidos, 2 patches documentados; assinatura APK v2 e manifest FGS verificados. Último build 60.71s, RSS máximo ~1.65GiB. Sem downloads/pacotes globais ou alteração de SSH/firewall/serviços existentes.
- **Artefato:** artifacts/myct-1r-debug.apk, 13,383,461 bytes; SHA256 4c9d303ab1086efab7a4237cc07be3fc4907905367c0b1cc4a91dc9e5dc8044c. Versão 0.1.1-ssh-corrective, code 2. Binário fora do Git.
- **Documentação/retomada:** relatório MYCT-1R, relatório MYCT-1 e plano corretivo com histórico, android/README e decisões atualizados. Validar IME/TUI, clipboard real, captura, FGS 5/15min/parada/rede e recuperação tmux via SSH antes de aprovar; manter PR #6 draft e sem merge.
- **Publicação MYCT-1R:** commit de implementação `4e9e1a2532f8f9dba39bba1eed40271ae8ca06a4` enviado à branch de trabalho; PR #6 atualizado e confirmado em draft, sem merge. Fechamento documental posterior preserva o APK e os testes registrados.
