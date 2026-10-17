# MYCT-L1B — terminal Android local real com PTY

17/10/2026. **Candidata para revisão e aceite físico, não aprovada no Moto G06.**
Versão **0.6.8-l1b-local-terminal / code 25**, `dev.myct`, targetSdk 36/minSdk 26.
Branch `myct-l1b-local-terminal`, empilhada sobre `myct-l1a-ide-local` no commit
`8e2e65e5ce89b0e38c413ecda3eae9afa9ab5f23`. O PR #28 estava OPEN/draft, base
main, no início da missão; a L1A foi preservada, sem copiar ou reaplicar commits.
Nenhum merge, exclusão de branch ou fechamento da issue #27 autorizado/realizado.

## Critérios fixados antes de codificar

O contrato é a missão L1B do proprietário: shell `/system/bin/sh` dentro do
Android, PTY real, offline, workspace compartilhada com a IDE L1A, renderizador
existente, entrada/saída/resize/sinais e sessões independentes Local/Remoto.
Não iniciar comandos por navegação, não desconectar SSH ao selecionar Local,
não matar o shell ao abrir IDE, permitir fechar e iniciar outra sessão. O gate
é A–F abaixo **no Moto G06**, incluindo modo avião e regressão SSH autenticada.
Build, testes de modelo e PTY Linux não substituem esse gate.

**Histórico preservado:** R4-Fix/code 23 aceita e integrada (#26); o proprietário
confirmou na L1A/code 24 criação de pasta/arquivo, edição de `teste.c`, salvamento,
persistência após fechar/reabrir e renomeação de pasta preservando arquivo.
SAF, isolamento completo e regressão remota L1A continuam sem aceite físico
individual e não bloqueiam esta candidata. [Relatório L1A](MYCT-L1A-RELATORIO.md).

## Arquitetura implementada

- `LocalTerminalModel`: dono do processo na mesma duração de `MyCtApplication`
  e `TerminalModel`, independente do owner SSH. Estados IDLE/STARTING/RUNNING/
  STOPPING/ENDED/ERROR, status de saída, Fechar e Novo shell. Nenhum processo é
  criado no construtor, seletor, IDE ou importação; apenas no botão de início.
- `AndroidPty`/`NativePty` e biblioteca própria `libmyct-pty.so`: JNI pequena,
  distribuída pelo APK, sem executável instalado na workspace. Nenhum código
  de processo/JNI do app Termux foi importado. C++20 usa a libc Bionic do sistema
  e libc++ estática do NDK, sem framework ou dependência nativa adicional.
- `openpty` aloca master/slave; `fork` cria o filho; `setsid` e `TIOCSCTTY`
  estabelecem sessão/terminal de controle; `dup2` liga stdin/stdout/stderr;
  `chdir` entra no root validado da L1A; `execve` executa `/system/bin/sh -i`.
  O pai recebe falha de setup/exec por pipe CLOEXEC antes de declarar RUNNING.
  Após fork, o filho usa somente chamadas apropriadas ao intervalo antes de exec,
  sem JNI, alocação de strings, logging ou código Kotlin.
- Termios explicita ISIG, ICANON, echo, CR/LF, UTF-8 e VINTR=3/VERASE=127.
  **Ctrl+C envia byte 0x03 ao master**; a disciplina do terminal gera SIGINT no
  grupo em primeiro plano. Não há comando `kill` montado pela interface.
  `TIOCSWINSZ` atualiza linhas/colunas/pixels e permite SIGWINCH do kernel.
- IO usa master nonblocking + `poll` bloqueante e pipe de despertar. Reutiliza
  `TerminalIo`, `TerminalActionQueue` (128 KiB, resize consolidado) e
  `TerminalOutputQueue` (64 KiB/backpressure), **instâncias exclusivas locais**.
  Leitor/escritor dedicados e um waiter bloqueante em Dispatchers.IO, somente
  durante a sessão; não há worker permanente, temporizador ou polling de estado.
- `waitid(WNOWAIT)` observa saída normal/sinal sem liberar o PID prematuramente;
  status = exit code ou 128+sinal. Até 500 ms para drenar a cauda; depois fecha
  transporte. Shutdown acorda leitura/escrita, encerra grupos/descendentes do
  mesmo SID, faz join/reap com `waitpid` e libera descritores. A inspeção de
  `/proc` acontece uma vez ao fechar, restrita pelo SID da sessão criada.
  Processos deliberadamente destacados com nova sessão (`setsid`) ficam fora
  desse contrato; não há supervisor geral dos processos Android.
- Registro JNI de handles mantém referências durante chamadas concorrentes,
  evitando reutilização de FD/ponteiro quando a sessão é fechada. O filho fecha
  os demais FDs antes de exec (laço de `close` até o limite capturado antes do fork),
  inclusive sockets abertos concorrentemente pelo SSH. O ambiente não herda
  variáveis/segredos do processo Java.
- UI: seletor contextual **Local · Moto G06 / Remoto · Fedora** antes do fluxo
  SSH. Local é o padrão de novo processo, disponível offline, com início explícito.
  A IDE abre o Terminal do seu ambiente, sem enviar comandos. Trocar ambiente
  recria somente a view, mantém emulador/scrollback/processo no owner e limpa
  modificadores temporários. Callbacks capturam sessão + época da seleção;
  eventos de uma view antiga não são aceitos ao trocar e voltar ao ambiente.
- `TerminalView`, `TerminalEmulator`, ANSI, scrollback de 2.000 linhas, seleção,
  clipboard manual/limite de 64 KiB, bracketed paste, Gboard/InputConnection,
  grade 2×7, fonte e insets permanecem no caminho existente. Não há renderer
  novo, WebView, interpretação de comandos ou `TextField` terminal.

### APIs e escolha do PTY

A [API pública Bionic `<pty.h>`](https://android.googlesource.com/platform/bionic/+/main/libc/include/pty.h)
oferece `openpty` e `forkpty` desde API 23. O mínimo do app é 26.
A [implementação Bionic de PTY](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/bionic/pty.cpp)
usa `/dev/ptmx`, grant/unlock/slave; portanto `posix_openpt` manual é alternativa
mais extensa, sem vantagem neste recorte. `forkpty` também é compatível, mas
`openpty` + fork explícito permite conferir setup, fechar os FDs do app e
reportar erro anterior ao exec. Pipes comuns são usados apenas para wake/erro;
a entrada/saída do shell passa **pelo PTY**. A disponibilidade e a política
SELinux efetiva no Moto continuam parte do teste Android, não inferência do host.

NDK r28c/28.2.13676358, CMake 3.22.1, AGP 8.13.0; biblioteca com alinhamento
ELF 16 KiB, conforme [orientação oficial Android](https://developer.android.com/guide/practices/page-sizes).
ABIs: **arm64-v8a, armeabi-v7a, x86_64**. As duas ARM cobrem instalação ARM
compatível; x86_64 permite futura execução instrumentada em emulador. Não foi
reduzido targetSdk nem adicionada permissão, root, PRoot, loopback SSH ou FGS.

## Workspace, ambiente e limites reais

Diretório inicial e HOME: `Context.filesDir/ide-local/projetos`.
`PATH=/system/bin:/system/xbin`, `TERM=xterm-256color`, `LANG=C.UTF-8`.
Shell Android e comandos efetivamente fornecidos pelo sistema; nenhuma promessa
de bash, GNU coreutils, Git, Python, compilador ou distribuição Linux.

O shell usa **o mesmo UID do app**, não um sandbox separado da IDE. Pode acessar
outros dados privados do próprio My-CT e caminhos permitidos pelo Android;
`HOME`/cwd não são uma barreira de segurança ou chroot. Não acessa dados privados
de outros UIDs nem root por esta implementação. A IDE continua recusando links e
conflitos; alterações deliberadas por shell podem causar conflito de salvamento,
que deve ser resolvido reabrindo o arquivo. SAF não vira mount ou acesso geral
para o shell. A workspace continua sendo apagada ao desinstalar/limpar dados.

Sessão sobrevive a alternar seções e recriação da Activity **enquanto o processo
Android continuar vivo**. Não há promessa de execução ilimitada em background,
Doze, sobrevivência a force-stop/reboot/morte do processo ou recuperação de
histórico após morte do app. A sessão local não habilita o FGS SSH. O FGS, tmux,
autenticação/host key e todos os canais remotos continuam com os owners existentes.
Não houve acesso SSH autenticado, instalação/deploy ou mudança em serviços Fedora.
L1C, toolchains, runtimes, pacotes, automações e investigação de R-01 não iniciados.

## Testes e validação

Ferramentas preexistentes: JBR 21.0.10, Gradle 8.14.3/AGP 8.13.0/Kotlin 2.2.20,
SDK 36/build-tools 36.0.0 e g++ 16.2.1. Provisionados no SDK do usuário apenas
NDK r28c (2,2 GiB instalado) e CMake 3.22.1 (60 MiB); AndroidX Test runner 1.7.0
foi resolvido para o APK de testes, sem dependência adicional no APK do produto.
A preparação de ferramentas/cache usou rede; a validação final usa `--offline`.


Rodada completa offline **PASS**: 203 testes JVM encontrados, **202 aprovados,
1 handshake SSH opt-in ignorado, zero falhas/erros**. Os seis novos testes do
modelo passaram, preservando os 196 passes L1A anteriores. O teste SFTP
OpenSSH descartável real foi habilitado (`MYCT_TEST_LOCAL_SFTP=1`); nenhum login
SSH autenticado ou serviço Fedora foi modificado. Lint: **0 erros, 2 avisos
preexistentes** (Gradle e org.json). Upstream: **21 arquivos, 3 com alterações
documentadas, 4 patches reverse-check PASS**; `git diff --check` PASS.

O núcleo nativo passou no host, inclusive bloqueio/reap/FD e job em background.
Rodada host com fechamento portátil de FDs: **10,67 s**, 6,64 s CPU usuário +
3,87 s sistema; pico RSS 4.852 KiB, resultado completo nos logs locais de validação.
Esses números cobrem várias criações de PTY, não um shell no Moto.

O teste de cancelamento revelou que retornar de IO para uma coroutine já
cancelada podia pular a atualização STOPPING → ENDED; cleanup de IO **e**
atualização de estado foram colocados sob `NonCancellable`, com teste de
fechar durante a abertura e reiniciar. O teste inicial Ctrl+C do host esperava
executar um sufixo na mesma linha interrompida; foi corrigido para enviar outro
comando ao prompt e conferir status 130. SDK 36 não fornece o runner legado
`android.test` no classpath padrão; os três testes instrumentados usam
AndroidX Test runner 1.7.0, somente no APK de testes. **Sem execução Android
real nesta missão.**

Logs locais ignorados: `artifacts/myct-l1b-validation/`, incluindo build completo,
resumo JVM, lint, PTY real, metadados APK/ABI e consumo do build. O build
completo inicial custou 1m44,48s, pico RSS 2.110.484 KiB (~2,01 GiB), zero swap.
Avisos de schema SDK, depreciações e metaspace não causaram falha; limites
Gradle preservados. **Rodada final de entrega:** 2m05,70s, CPU 556,46 s usuário +
7,51 s sistema, pico RSS 1.843.168 KiB (~1,76 GiB), zero swap, mesmos
202 passes JVM/lint sem erros. O pacote final também inclui os avisos LLVM/libc++/
libc++abi/libunwind/compiler-rt em Opções → Licenças, com fonte fixada na base
LLVM do NDK r28c.

### Reproduzir sem dispositivo

```sh
# Na raiz; SDK/NDK/CMake previamente provisionados no ambiente de desenvolvimento.
mkdir -p artifacts/myct-l1b-validation
ANDROID_HOME=/home/sam/Android/Sdk android/gradlew -p android \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
  :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
for patch in android/terminal/upstream/*.patch; do git apply --reverse --check "$patch" || exit; done
git diff --check
# PTY real no host; somente fixtures privadas e processos pertencentes ao teste.
g++ -std=c++20 -Wall -Wextra -Werror -pthread -Iandroid/app/src/main/cpp \
  android/app/src/main/cpp/local_pty.cpp android/app/src/test/native/local_pty_test.cpp \
  -lutil -o artifacts/myct-l1b-validation/local-pty-test
artifacts/myct-l1b-validation/local-pty-test
```

`LocalTerminalModelTest` testa backend simulado e emulador real: começo explícito,
duplicação, workspace, IO/CTRL+C como bytes, resize, saída/recomeço, fechamento
na inicialização, erros PTY/exec/library/IO, isolamento de entrada/saída/telas/
modificadores, SSH conectado/desconectado simulado, retorno da IDE e callbacks/
resize antigos. Não faz conexão SSH, renderização Android ou sinais de kernel.

O teste C++ usa **o mesmo núcleo nativo**, PTYs e `/bin/sh` reais no Linux, sem
rede/serviço Fedora: stdin/out/err TTY, cwd/HOME/TERM/PATH, cd/ls/cat/pipes/read,
resize por stty, Ctrl+C interrompendo sleep, exit 7/status de sinal, close isolado,
leitura e wait bloqueantes, falhas exec/chdir, entrada inválida de resize,
reinícios, não herdar FD sem CLOEXEC e limpeza de job em background/descritores.
Não executa a Bionic, JNI ou `/system/bin/sh` Android.

`LocalPtyInstrumentedTest` possui três testes Android: carregamento da biblioteca
+ JNI real + shell/TTY/env/workspace/pipes/read/resize/Ctrl+C/exit; PTYs separados/
fechamento/reinícios; falhas chdir e contagem de FDs. **Preparados/compilados;
execução em Android pendente.** Usam diretório exclusivo no cache de teste,
sem alterar projetos pessoais. Não são executados automaticamente no Moto.
Uma execução futura deliberada usa `:app:connectedDebugAndroidTest` no ambiente
de desenvolvimento com alvo autorizado; isso não é dependência operacional
do terminal local nem substitui o gate de interface A–F.

## APK candidato e cadeia Git

- APK: `artifacts/myct-l1b-0.6.8-debug.apk`, **18.251.989 bytes (~17,41 MiB)**;
  delta sobre a L1A: **+1.151.751 bytes (~1,10 MiB / 6,74%)**.
- SHA-256: `832856d00d47f3b8910cb38ffedc0b9824764477ea7fcef02ee921ae45a6abe2`.
- Certificado SHA-256: `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
  `apksigner verify` PASS/v2 e igualdade com a L1A cujo hash original também foi
  conferido. `aapt2`: pacote `dev.myct`, code 25, versão 0.6.8-l1b-local-terminal,
  minSdk 26/targetSdk 36. Keystore existente preservada.
- `libmyct-pty.so`: **424.008 bytes arm64-v8a**, **252.428 bytes armeabi-v7a**,
  **412.560 bytes x86_64**. libc++ estática; DT_NEEDED somente libc/libm/libdl do
  Android. `libandroidx.graphics.path.so` já era dependência do produto.
  Todos os LOAD das seis bibliotecas do APK têm alinhamento 16 KiB; `zipalign
  -c -P 16 -v 4` PASS. Sete exports JNI conferidos nas três bibliotecas próprias;
  isso verifica empacotamento/símbolos, **não prova dlopen/exec real no Android**.
- [Pré-release 0.6.8-rc1](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-l1b-v0.6.8-rc1):
  [APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-l1b-v0.6.8-rc1/myct-l1b-0.6.8-debug.apk),
  [SHA256SUMS](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-l1b-v0.6.8-rc1/myct-l1b-SHA256SUMS.txt)
  e fonte Sora 0.23.6 correspondente, preservando sua entrega LGPL anterior.
- Branch `myct-l1b-local-terminal`, [PR #29](https://github.com/Samuel-Francisco-AS/My-CT/pull/29)
  **OPEN/draft com base `myct-l1a-ide-local`**,
  dependente do [PR #28](https://github.com/Samuel-Francisco-AS/My-CT/pull/28).
  Antes de qualquer integração futura, verificar o estado do #28 e ajustar a
  base se necessário, sem duplicar/reaplicar L1A. Sem merge ou fechamento #27.
- Commits publicados: `9833201` (núcleo JNI/modelo/testes/licenças), `e10a974`
  (integração UI/IDE) e `209ef9d` (relatório/retomada). Tag da pré-release aponta
  para `209ef9dcbb441b21a4187e2648f3cf8e6e54c60b`; atualização documental posterior
  registra links, sem alterar o APK. GitHub confirmou `prerelease=true`, APK
  uploaded com **mesmo digest SHA-256 e 18.251.989 bytes** do arquivo local;
  checksum e fonte Sora uploaded. PR #28 segue OPEN/draft na mesma HEAD L1A.
  `git merge-base --is-ancestor` confirma a cadeia preservada.

## Uso, instalação e checklist físico compacto

Atualizar **sobre a L1A/code 24**, mesmo `dev.myct`/certificado, sem desinstalar,
limpar dados ou trocar keystore. Baixar APK e checksum da pré-release, verificar
SHA-256, abrir pelo gerenciador Android e confirmar atualização. Projetos salvos
e configurações permanecem; salvar/exportar cópias importantes antes é prudente.
O agente não instala o APK. Para uso: Terminal → Local → **Iniciar shell local**;
**Fechar** encerra só essa sessão; **Novo shell** recomeça na raiz dos projetos.
O menu Terminal da IDE seleciona o ambiente correspondente, sem criar processo.

Todos os resultados abaixo estão **PENDENTES** no Moto G06:

| Teste | Roteiro e resultado necessário |
| --- | --- |
| A — Offline | Modo avião; abrir My-CT → Terminal → Local → Iniciar shell local. Prompt sem SSH/servidor/Termux/root/ADB. |
| B — Comandos | `pwd`, `ls`, `echo "My-CT terminal local!"`, `cd ProjetoTeste`, `ls`, `cat teste.c`, `echo teste \| cat`. ProjetoTeste deve existir na IDE (se renomeado na L1A, usar o nome atual). |
| C — Interativo | `read valor; echo "$valor"`, digitar/Backspace/Enter; setas e grade 2×7; `sleep 20`, CTRL depois C; retornar ao prompt. |
| D — Tela | Abrir/fechar Gboard, girar/reabrir, fonte, scrollback, seleção/cópia/colagem. Sem área inutilizável ou texto dirigido à sessão errada. |
| E — IDE | Criar/alterar/salvar arquivo na IDE Local; retornar e `cat` no Terminal Local; voltar e conferir dados/sessão. |
| F — Isolamento | Sair do modo avião; alternar Remoto desconectado/conectar Fedora com host verificado; comando remoto; voltar Local com histórico/cwd intactos; encerrar shell local por `exit` e/ou Fechar; SSH continua funcionando. |

Registrar versão Android/ABI, A–F PASS/FAIL e comportamento observado, sem segredos.
Medir RAM/bateria local oportunamente no Moto; números do host não estimam seu
consumo. Falhas de Gboard, SELinux, sessões, resize ou sinais reabrem a candidata
antes de conceder aceite. O PR permanece draft; issue #27 e L1C continuam abertas.


## Retorno posterior do proprietário na missão L1C — 17/10/2026

Confirmados no Moto G06/code 25: inicialização real de /system/bin/sh,
pwd/ls/echo/cd, acesso aos projetos da IDE, Ctrl+C e melhor fluidez da digitação
local. Este é **aceite físico parcial**; demais testes de interface, regressão,
resize/rotação/clipboard e coexistência remota continuam pendentes. Não repetir
automaticamente os comandos aceitos. L1C prossegue empilhada sobre esta branch.
