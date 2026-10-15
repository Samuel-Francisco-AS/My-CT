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

### 2026-10-15 — MYCT-1P diagnóstico de digitação (decisão B)
- **Evidência física relatada pelo proprietário:** MYCT-1R com SSH/PTTY, layout/IME/orientação, fonte, clipboard, captura e tmux funcionando; FGS aparentemente funcional. Persistem digitação e exclusão pesadas; Termux parece mais responsivo, sem comparação controlada fornecida.
- **Diagnóstico:** pipeline completo revisado. Máximos anteriores ~1ms IO/~42ms main não medem escrita, parser, desenho ou E2E. TCP_NODELAY já ativo no caminho JSch. Diferença verificável: entrada por caracteres forçada no My-CT vs propriedade default false no Termux de referência; causalidade não demonstrada.
- **Medições:** sonda desktop manual fora do APK, sem socket/comandos remotos: adaptador ASCII p95 1.864µs (20,000 amostras), pipe de recepção JSch p95 17.178µs (1,000); não representam Moto/IME/rede/render. Fedora na amostra fora do sintoma com CPU 96–99% ociosa e ~6.5GiB disponíveis. ADB sem aparelho.
- **Verificações:** 7 testes JVM PASS/1 handshake opt-in ignorado, offline; lintDebug PASS com análises existentes up-to-date, relatório sem problemas; 21 arquivos upstream/2 patches conferidos; sonda javac e asserções PASS, bash -n e diff --check. Testes 37.65s/RSS ~1.13GiB; sem downloads ou alteração do APK.
- **Arquivos/retomada:** `MYCT-1P-LATENCY-DIAGNOSIS.md`, esta entrada e sonda em `android/diagnostics/`. Sem mudança de comportamento do terminal, autenticação, FGS, tmux ou Fedora. Branch de PR #6, draft e sem merge.
- **Recomendação:** B — investigação física adicional, primeiro confirmar Termux local vs SSH comparável e depois A/B isolado da configuração IME se autorizado. Causa principal não identificada; nenhum ganho físico ou correção declarados. Aguardar decisão do proprietário antes de corrigir.

### 2026-10-15 — MYCT-2 SFTP/SAF e preferência IME (aceite físico pendente)
- **Decisão do proprietário:** suspender investigação MYCT-1P e avançar para arquivos SFTP. Principais funções MYCT-1R relatadas como validadas no Moto, sem converter esse relato em teste MYCT-2.
- **Git:** branch `myct-2-sftp` criada sobre HEAD/origin atualizado de `myct-1-android-ssh` (`cfe3949`); PR novo terá base nessa branch, dependente do PR #6. Main preservada, sem merge/reset/force push.
- **Implementação:** abas Terminal/Arquivos; canais SFTP JSch por operação na Session já verificada/autenticada; home/pastas/pai/refresh, tipos/tamanho/data, limite2.000 itens. Upload/download SAF em blocos64KiB, progresso, cancelamento e abertura do documento. Estado separado, sem fila/replay, cancelar ao ocultar Activity, FGS do terminal preservado.
- **Segurança/limites:** nomes/caminhos/glob tratados como dados; links confirmados para navegação, sem download/destruição. Conflitos pedem outro nome, sem sobrescrita. Upload reserva temporário e publica com hardlink OpenSSH sem substituir; sem extensão/filesystem compatível recusa. Limpeza best effort; SAF parcial é explicitamente tratado. Sem novos pacotes, permissões amplas, comandos shell ou mudanças do Fedora.
- **IME:** Entrada por caracteres persistida, default true, switch reversível consulta preferência real; restartInput ao retornar à view/fechar Opções, sem reconectar SSH. Nenhuma promessa de corrigir lag ou novo diagnóstico.
- **Verificações:** build/testes/lint offline PASS; 18 testes PASS/1 handshake opt-in ignorado (11 novos), lint sem problemas;21 arquivos upstream/2 patches conferidos. Prova SFTP real por pipes com OpenSSH existente:8MiB por direção, SHA-256 de3 arquivos igual, hardlink e conflito preservado. Não é JSch autenticado nem Android/SAF. Assinatura válida/igual ao MYCT-1R.
- **Recursos:** build final68.93s, RSS máximo~1.63GiB, CPU435%; prova SFTP0.27s/RSS~17.8MiB. Sem downloads de dependências, consumo Moto não medido.
- **Artefato:** `artifacts/myct-2-debug.apk`,13,496,124 bytes, versão0.2-sftp/code3; SHA256 `4d2cd343d9685c8795f29e215e270cd2c1de63808ea6f6828bf3ef633778c86a`; binário fora do Git.
- **Próximo gate:** checklist curto em android/README; navegação, enviar/baixar/abrir, conflito/cancelamento, abas, rede/provedor e preferência IME no Moto. ADB sem aparelho; validação física MYCT-2 pendente, sem PASS inventado. Relatório `MYCT-2-SFTP-REPORT.md` detalha retomada e publicação.
- **Fechamento/publicação:** implementação `7bdef5123e95230a5702d654353f596f5c1e7d77` enviada; [PR #7](https://github.com/Samuel-Francisco-AS/My-CT/pull/7) open/draft com base `myct-1-android-ssh`, dependente do PR #6, sem merge. Proprietário respondeu explicitamente para registrar validação física pendente. Fechamento posterior é documental, sem novo APK.

### 2026-10-15 — MYCT-2R correção SFTP (novo gate físico pendente)

- **Base/relato:** branch `myct-2r-sftp-stability` sobre MYCT-2 atualizada
  `b3054b6`. Comentário físico PR #7 confirma navegação/duas imagens abertas,
  PDF não abrindo após queda relatada, vídeo cancelado em background e melhora
  subjetiva IME false. Causa TCP/corrupção do PDF não comprovada.
- **Correções:** cancel aborta só canal idempotentemente, streams fechados pelo
  worker; corrida connect/cancel protegida. COMPLETE após streams/canal; SIZE
  SAF opcional indisponível vira aviso, divergência permanece erro/limpeza.
  MIME/visualizador têm mensagem distinta; bytes exatos preservados.
- **Background:** serviço dataSync separado, notificação/progresso/Cancelar,
  start por usuário com UI visível, START_NOT_STICKY/onTimeout; processo/estado
  não dependem da aba. Sem notificação/serviço, manter visível ou cancelará.
  onDestroy de Activity recriada não desconecta por engano; ownership do serviço
  protege cópia. Sem garantia contra TCP/kill/Doze, nem retry/credenciais em disco;
  só booleano de interrupção. specialUse interativo preservado, sem wakelocks.
- **UX/IME:** unidades decimais KB/MB/GB e contraste Material na área Arquivos.
  Default IME true/persistência/toggle/restartInput preservados. MYCT-1P suspensa.
- **Testes:** build/JVM/lint offline PASS (26 descobertos,25 PASS/1 ignorado),
  lint sem problemas. PDF sintético, truncamento, escrita/flush falhos,
  cancel concorrente,32MiB sem arquivo em disco; canais reais JSch sobre Session
  simulada preservam shell e não chamam Session.disconnect. Não é auth/TCP real.
  Prova OpenSSH por pipes8MiB/direção PASS; Termux21 arquivos/2 patches intactos.
- **Consumo:** build72,81s/RSS~1,67GiB/CPU451%,swap0; disco132GiB livres;
  prova local0,15s/RSS18396KiB. Nenhuma nova dependência/instalação/serviço Fedora.
- **APK:** `artifacts/myct-2r-debug.apk`,13522361 bytes, versão0.2.1/code4;
  SHA256 `c06e4521f55dcfc4596c909d65132257105eb305a4d456ef4b163fcf5ab51e04`.
  Certificado debug igual à MYCT-2, assinatura válida, binário fora do Git.
- **Commit código/APK:** `fc87cda61d98b40137953db26daff7859507ae97`.
  Fechamento documental posterior; SHA final no PR/entrega. Publicação em draft
  com base `myct-2-sftp`, dependente #7→#6; main preservada e nenhum merge.
- **Retomada:** ADB sem aparelho; gate curto PDF/erro isolado/background/retorno/
  cancelamento/MB-GB e contraste em android/README e relatório MYCT-2R. Não
  declarar background/PDF aprovados no Moto por JVM; não repetir provas físicas
  já aprovadas sem motivo. Upload/conflito físico anteriores seguem pendentes.


### 2026-10-15 — aceite funcional físico e encerramento da fase Android/SSH/SFTP
- **Evidência diretamente relatada pelo proprietário no Moto G06:** após instalar a MYCT-2R, PDF Fedora→Android baixou e **abriu corretamente**, e PDF Android→Fedora foi **enviado com sucesso**. Imagens e navegação SFTP já haviam funcionado. Não há confronto de hashes reais do PDF e a causa da falha do PDF na versão anterior permanece não identificada.
- **Não executado pelo proprietário:** transferência de arquivo grande da MYCT-2R com Android em segundo plano/tela bloqueada; teste fica como dívida, assim como cancelamento/conflitos no cenário físico. Background de SSH anteriormente relatado positivo, sem promessa de sobrevivência a kill.
- **Dívidas declaradas:** digitação/backspace ainda com lag perceptível (switch IME=false reduz); interface truncada/rígida; **lista SFTP volta ao início ao retornar do subdiretório para o pai**. O proprietário deseja deixar refinamento para depois e continuar o My-CT, não interromper o projeto com testes intermináveis.
- **Decisão:** encerrar ciclo Android/SSH/SFTP com aceite funcional parcial por requisito; integrar PRs empilhados #6 → #7 → #8 à main, registrar fechamento do trabalho na documentação e limpar branches após confirmação. Sem reescrever ou falsear resultados dos relatórios anteriores.
- **Artefatos documentais:** `docs/08-FECHAMENTO-CICLO-ANDROID.md`, `docs/09-TRILHA-REFINAMENTO-ANDROID.md`, `docs/10-RETOMADA-PROJETO.md`; [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). Próxima conversa pode retomar a partir da main sem depender desta conversa.
- **Próxima frente recomendada:** Core C++20/PostgreSQL + saúde real no servidor; pesquisar dependências atuais, obter aprovação para instalar/configurar serviços, entregar um fluxo vertical mínimo. O refinamento permanece backlog, sem datas e sem implementação neste fechamento.
- **SHAs dos merges:** registrar na seção de integração do fechamento após retorno efetivo do GitHub, sem inventar resultados.
