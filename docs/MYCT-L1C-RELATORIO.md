# MYCT-L1C — compilação e execução C offline

17/10/2026. Issue #27. Branch `myct-l1c-offline-c`, base L1B `e1deac6`.
PRs #28/#29 abertos antes do início; [PR #30 draft](https://github.com/Samuel-Francisco-AS/My-CT/pull/30) empilhado sobre L1B.
**Candidata implementada e validada no Fedora; gate físico Android/Moto obrigatório PENDENTE.**

## Critérios antes da integração definitiva

- Mesma IDE Sora/workspace L1A e terminal L1B; ação explícita somente em `.c`
  local salvo; pedir salvamento se sujo, recusar conflito/incerteza, nunca executar
  artefato anterior após erro. Compilar os bytes reais do arquivo selecionado.
- APK contém compilador, headers, libc, linker e runtime. Uso sem rede, SSH,
  Termux, root, ADB, download posterior ou redução do targetSdk 36.
- `main(void)`, inteiros/aritmética, if/else, for/while, funções, stdio,
  printf/puts/scanf, diagnóstico real e código de retorno.
- Console identifica compilação, execução WASM interpretada e término; stdin,
  stdout/stderr, cancelamento, limite de tempo/memória e worker fora da UI.
- Fonte preservada; artefatos `.wasm` identificados fora de `projetos`;
  atualização `dev.myct`/assinatura existente, 0.6.9/code 26.
- Build offline, JVM, lint, testes nativos e instrumentação compilada;
  distinguir testes Fedora de execução Android e aceite Moto.
- Publicar candidata e PR draft; sem merge, exclusão de branches, encerramento
  #27, instalação no aparelho ou alteração em serviços Fedora.

## Spike técnico curto

Hipótese A: WCPL 1.04 integra parser/compilador/linker, headers e libc em C,
produz WASI real; WAMR 2.4.4 interpreta os módulos sem JIT/AOT.
Teste mínimo no Fedora: código fornecido ao compilador com função `twice`,
`main(void)`, `for`, `scanf`, `printf` e retorno 7; módulo foi gerado e interpretado,
entrada 21 produziu duas linhas `dobro=42` e código 7. **PASS Fedora, não Moto.**
Uma adaptação da libc coloca stdin/stdout em modo não bufferizado para prompts
interativos nos pipes do console, sem alterar o código do usuário.

Hipótese B: TinyCC/Clang nativos podem gerar código arm64, mas gerar ELF na
workspace não fornece execução permitida para targetSdk 36; TinyCC em memória
exigiria avaliar geração de código executável/W^X. Sem caminho demonstrado
que respeite a missão neste spike, rejeitada para os programas do usuário.
LLVM/Clang mais linker/libc completos também seriam maiores e desnecessários
para o subconjunto inicial. Não foi construída nem alegada prova Android B.

Decisão da candidata: A, usando WCPL nativo distribuído no APK para gerar `.wasm`
e WAMR interpretado em processo separado. O worker nativo é código fixo
instalado pelo PackageManager; nenhum ELF gerado pelo usuário é executado.
Fontes: https://github.com/false-schemers/wcpl,
https://github.com/bytecodealliance/wasm-micro-runtime e
https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission.
Resultados finais, recursos e candidata serão registrados após validação.

## Comparação e decisão final da candidata

| Ponto | A — WCPL + WASI/WAMR | B — C nativo gerado pelo usuário |
| --- | --- | --- |
| Compilador | WCPL 1.04 nativo, compilado pelo NDK nas três ABIs; parser C real, não reconhecedor de exemplos | TinyCC/libtcc tem backend arm64; Clang pode compilar arm64; isso isoladamente não resolve execução Android |
| Headers/libc/linker | WCPL contém headers, objetos de libc e linker em seus próprios fontes, sem sysroot externo | Exigiria headers/crt/libc/linker compatíveis com Android e manutenção da integração |
| Resultado | `.wasm` WASI real, magic/version verificados; fonte consumida a cada ação | ELF em diretório gravável não é executável permitido sob o desenho exigido |
| Execução | WAMR 2.4.4 clássico, sem JIT/AOT/memória executável dinâmica | libtcc -run usa relocações e permissões executáveis; variantes com mapeamentos RW/RX não foram adotadas como contorno |
| Integração | Kotlin ProcessBuilder/argv, worker fixo instalado pelo PackageManager; JNI PTY L1B intacto | Biblioteca/JNI exigiria isolamento de falhas e solução de execução ainda não demonstrada |
| Console | Pipes de stdin/stdout/stderr, scanf real, libc sem buffer nos streams do console, retorno e kill | Possível em princípio, mas insuficiente sem execução permitida demonstrada |
| Offline | Todo compilador, libc, headers, linker e runtime dentro do APK; sem assets baixados | Não demonstrado; não houve instalação de toolchain nativa de usuário |
| Custos | Workers stripped de ~693–825 KiB por ABI; medições Fedora abaixo | Não medidos; não atribuir desempenho/tamanho hipotético |
| Licenças | MIT/Sun no WCPL/libc; Apache-2.0 com exceção LLVM e BSD/CC0 no WAMR/WASI | TinyCC é LGPL; não incorporado, sem obrigação adicional no APK |

**Escolha: A para candidata.** O binário fixo `libmyct-c-worker.so` é um PIE
nativo do **próprio aplicativo**, instalado no diretório de código do pacote,
com nome `.so` apenas para o empacotamento de native code. Não é gerado pelo
código C do usuário. `extractNativeLibs=true` é produzido pelo AGP via
`useLegacyPackaging=true`. O worker roda pelo caminho `nativeLibraryDir`; nunca
por caminho gravável da workspace/cache. O programa C vira apenas dados WASM.
ELF arm64 inspecionado: linker Android, PIE, segmentos RX/RW separados, stack RW,
alinhamento de 16 KiB, dependências somente libc/libm/libdl/liblog do Android.
**Permissão de execução do worker no Android ainda precisa da instrumentação e
do gate Moto; os artefatos NDK não substituem essa prova.**

Fontes primárias consultadas:
[WCPL e subconjunto C](https://github.com/false-schemers/wcpl),
[WAMR](https://github.com/bytecodealliance/wasm-micro-runtime),
[restrição Android a executáveis graváveis](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission),
[documentação TinyCC/libtcc](https://bellard.org/tcc/tcc-doc.html),
[código de execução TinyCC](https://github.com/mirror/tinycc/blob/mob/tccrun.c),
[alinhamento Android/NDK](https://developer.android.com/guide/practices/page-sizes).
A rejeição de B é decisão de escopo/compatibilidade com as restrições desta
missão, não uma alegação de impossibilidade universal de C nativo no Android.

## Implementação e operação

A L1C usa a mesma tela/editor Sora, arquivos privados e estados da L1A. Local
`.c` mostra **Compilar e executar**. Remoto, `.cpp` e buffers de identidade
remota não habilitam compilação local. Alterações sujas abrem pedido de
salvamento; após salvar, tocar novamente na ação. Não há compilação por tecla,
autosave ou compilação silenciosa da versão anterior. Resultado incerto é
recusado. Antes de compilar, releitura real da workspace confere bytes e
metadados do snapshot salvo; conflito obriga conferir/reabrir.

Cada ação cria `filesDir/ide-local/compilacoes/<UUID>/` com `origem.txt`
(caminho relativo e SHA-256), `programa.c` (snapshot exato) e novo `programa.wasm`.
Usa nome de módulo fixo para evitar colisões upstream com `stdio.c`. Diagnósticos
mostram `programa.c:linha:coluna` e caret; o console identifica a origem real,
com linhas idênticas. Artefatos anteriores nunca são selecionados após erro.
Fonte/projeto original não são escritos pela compilação nem expostos ao WASI.
Artefatos ficam retidos na área privada; consomem disco e podem ser removidos
pelo proprietário no Terminal Local. Não há limpeza automática de projetos.

Console compacto em diálogo da IDE: compilando/executando/encerrado, stderr e
stdout no mesmo fluxo, erro real do compilador/runtime, status e tempo total;
entrada por linha com **Enviar ↵**, **Fim da entrada** e **Interromper**. Fechar
console permite voltar ao editor e reabri-lo, sem cancelar por ocultação. Não
adiciona serviço nem promete segundo plano. A execução é explicitamente
**WebAssembly interpretado por WAMR**, sem ligar artificialmente o módulo ao
shell L1B. Limites e licenças acessíveis em IDE → Detalhes.

`OfflineCModel` pertence ao dono de processo já existente. IO em dispatcher
separado; um novo worker por compilação e por execução isola globais/exit do
compilador e falhas do runtime. Java acompanha/recolhe o processo. Cancelamento
mata inclusive loops e stdin bloqueado; leituras/escritas destravam. Timeout:
30 s compilar / 60 s executar, contando entrada. Worker: alarm de 90 s como
fallback, 30 s CPU, RLIMIT_AS 384 MiB, arquivo até 16 MiB, core dumps desativados.
WASM: memória linear até 16 MiB, stack interpretada 128 KiB e software bounds
checks. Console até 65.536 caracteres, com parada em excesso. Falhas fatais ou
limites nativos podem encerrar com status de sinal; o código e diagnóstico
permanecem no console, sem apagar Sora/fonte. Novo trabalho continua disponível.
PDEATHSIG encerra o worker ao morrer o processo Android pai.

Sem diretórios preabertos, ambiente ou endereços de rede autorizados ao WASI.
O compilador fixo tem as permissões normais do UID do app, como o shell L1B;
não é um sandbox para analisar fontes hostis com includes arbitrários. O fluxo
suportado compila uma fonte e headers internos; não copia headers de projetos
ou outras unidades de tradução. Não há execução de build scripts.

## Componentes, subconjunto C e limites

- WCPL **1.04**, commit `458a542ca81fa7a8fd8c8eed38a32dce8ed45135`.
  WAMR **2.4.4**, commit `8c18e3f68b16c4bcaf05996b2636f6ed2b4cf629`.
  Fontes arquivadas no Git, hashes em `android/offline-c/upstream/manifest.json`;
  `prepare.py` verifica antes de extrair. Rebuild offline documentado em
  [android/offline-c/README.md](../android/offline-c/README.md).
- WCPL MIT; libc inclui derivações wasi-libc/MIT e Sun fdlibm com notice próprio.
  WAMR Apache-2.0 WITH LLVM-exception; primitivas WASI BSD-2-Clause/CC0.
  Fontes/notices originais preservados e textos no APK `assets/licenses/offline-c/`.
- Única adaptação WCPL: flags de stdin/stdout da libc embarcada em modo sem
  buffer, para o prompt aparecer antes do scanf sobre pipes. Dois bytes do
  segmento de dados do objeto stdio são alterados e recomprimidos pelo script;
  fonte/objeto preparados também refletem isso. Parser/compiler/linker intactos.
- ABIs: arm64-v8a (Moto), armeabi-v7a, x86_64. minSdk 26 / targetSdk 36.
  NDK 28.2.13676358 e CMake 3.22.1 já provisionados, sem instalação nova.
- Headers internos: stdio, stdlib, string, ctype, errno, assert, limits, float,
  stdarg, stddef, stdint, stdbool, inttypes, math, time, locale e auxiliares/POSIX.
  Ter header POSIX não concede filesystem/rede ao programa WASI.
- Gate C coberto por código real: main(void), inteiros/aritmética, if/else,
  for/while, funções, stdio/printf/puts/scanf, erro de sintaxe e retorno.
  Subconjunto WCPL, **sem promessa de C99 completo/GCC**: arrays frequentemente
  exigem `&a[0]`, ponteiros exigem NULL/comparações explícitas; static local,
  bitfields, goto/switch livres e setjmp/longjmp têm limitações/ausência.
  int/long/ponteiro de 32 bits; long long de 64. C++ posterior.
- Entrada por linha, sem TUI/ANSI/raw mode. WAMR exibe código WASI completo;
  status do processo é seu byte baixo. Fonte até 1 MiB, limites L1A mantidos.
- Sem medição de RAM/CPU/bateria ou latência do Moto nesta sessão. Manter app
  visível durante o gate; morte do app perde console/buffer não salvo, como L1A.

## Validação e recursos — ambiente Fedora, não Android

Inventário observado: Fedora 44, GCC 16.2.1, JBR/OpenJDK 21.0.10, SDK 36,
Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20, NDK 28.2.13676358,
CMake 3.22.1, gh 2.97.0, apksigner dos build-tools 36.0.0. Nenhuma dependência
Maven nova e nenhuma instalação de ferramentas/serviços nesta missão.

A fixture de medição tem 133 bytes e scanf/printf com entrada 21; artefato
19.725 bytes. Uma observação no Fedora, com `/usr/bin/time` e perf_counter:
compilação **27,249 ms**, pico RSS **6.152 KiB**, CPU usuário **0,02 s**;
execução **3,806 ms**, pico RSS **4.552 KiB**, CPU arredondada **0,00 s**.
São custos do worker Linux; não incluem UI/ART, não são benchmark estatístico
nem estimativa para o Moto. `artifacts/myct-l1c-validation/host-metrics.json`.

Testes nativos reais (`test_worker.py`): **9 PASS**, incluindo compilação válida,
aritmética/condicionais/laços/função/stdio/retorno, diagnóstico com caret,
vazio/inválido, prompt antes da entrada, scanf, recompilação, matar loop/scanf,
limite de memória, filesystem sem preopens, trap e integridade da fonte.
O primeiro teste de diagnóstico esperava a palavra inglesa `error`; WCPL
informa `expecting ;` com linha/coluna/caret. Corrigida a expectativa do teste,
sem mascarar ou reescrever o diagnóstico real do compilador.

JVM novo: 7 testes de isolamento Local/Remoto/identidade/extensão/incerteza,
captura/releitura/conflito/integridade, dirty recusado, e três testes de fluxo
com o **worker real Fedora**, cobrindo recompile, erro sem executar antigo,
stdin/EOF, cancelamento/timeout, trap/excesso de saída e nova execução.
Instrumentação Android nova: **3 testes preparados** para worker instalado,
compilação real, scanf/prompt e cancelamento; **NÃO EXECUTADOS** nesta sessão.
A instrumentação L1B permanece disponível. Nenhum aparelho, ADB, emulador ou
instalação Android foi usado; o ambiente não tem system image provisionada.

Reprodução completa sem rede, da raiz:

```sh
# Construir/provar worker host: comandos em android/offline-c/README.md
python3 android/offline-c/tests/test_worker.py android/app/build/offline-c/host/libmyct-c-worker.so
MYCT_TEST_LOCAL_SFTP=1 \
MYCT_TEST_OFFLINE_C="$PWD/android/app/build/offline-c/host/libmyct-c-worker.so" \
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
# Check reverso dos quatro patches upstream; git diff --check.
```

A suíte nativa PTY L1B foi recompilada e executada: PASS real de PTY,
cwd/env/pipes/entrada, resize, Ctrl+C, exit, sessão independente,
read/wait bloqueados, falhas, FD/reap. O verificador confirmou 21 arquivos
upstream/3 arquivos com patches; os quatro patches têm check reverso PASS.
Sora/gramáticas/teclas e transação SFTP estão na suíte JVM de regressão.

## Gate obrigatório Moto G06/Android 15 — todos PENDENTES

Instalar como **atualização** sobre 0.6.8/code 25, sem desinstalar/limpar dados.
Não confundir instalação com aceite. Os resultados abaixo só serão preenchidos
após confirmação expressa do proprietário; nenhum PASS físico é inferido.

| Gate | Procedimento | Resultado |
| --- | --- | --- |
| A — Offline | Modo avião, sem Fedora/SSH ou aplicativo Termux; abrir IDE Local e fonte existente | **PENDENTE** |
| B — Compilar | Criar/salvar o programa abaixo e tocar Compilar e executar; saída e retorno 0 | **PENDENTE** |
| C — Entrada | Salvar segundo programa, enviar 21; prompt antes da entrada, dobro=42 e retorno 0 | **PENDENTE** |
| D — Diagnóstico | Remover ponto e vírgula, salvar e compilar; erro legível, sem executar versão anterior | **PENDENTE** |
| E — Controle | Executar laço infinito, Interromper; UI e nova compilação funcionam | **PENDENTE** |
| F — Preservação/regressão | Terminal Local lista/lê workspace; depois conectar Fedora, IDE e Terminal remotos funcionam | **PENDENTE** |

B:

```c
#include <stdio.h>

int main(void) {
    puts("My-CT compilando offline!");
    return 0;
}
```

C:

```c
#include <stdio.h>

int main(void) {
    int n;
    printf("Digite um numero: ");
    if (scanf("%d", &n) != 1) return 2;
    printf("dobro=%d\n", n * 2);
    return 0;
}
```

E:

```c
int main(void) {
    while (1) { }
    return 0;
}
```

Também conferir pedido de salvamento antes de compilar fonte suja, rotação/
Gboard/console e volta à IDE, preservação de buffers e atualização dos dados.
Se o worker for impedido de iniciar no Android, registrar o erro e manter
LOC-03 não aprovado; não sugerir root, downgrade targetSdk ou ELF em HOME.

## Retomada e pendências

L1B já tem confirmação do proprietário para shell/pwd/ls/echo/cd/workspace/
Ctrl+C/fluidez, sem estender isso aos demais gates. L1A/SFTP, UX e R-01 mantêm
seus aceites/dívidas anteriores. Esta candidata não modifica JNI/PTTY L1B,
SFTP/SSH, Core, PostgreSQL, Cofre, timer/serviços Fedora ou automações.

L1D aprovada depois de L1C e antes do refinamento final: acesso direto a
armazenamento compartilhado autorizado; avaliar APIs/SAF/MANAGE_EXTERNAL_STORAGE
na própria missão, com consentimento explícito. Somente documentação agora.
Sem novas permissões de armazenamento, symlinks ou importações implícitas.

Não integrar PRs, excluir branches ou fechar #27 antes das decisões do
proprietário. Após gate, registrar retorno físico por item; se #28/#29 forem
integrados, atualizar a base do PR L1C sem duplicar commits anteriores.


## Resultado final das verificações e APK candidato

Build Android **offline PASS**, `assembleDebug`, `assembleDebugAndroidTest`,
`testDebugUnitTest`, `lintDebug`: **BUILD SUCCESSFUL em 2m28s**. JVM:
**210 encontrados, 209 PASS, 1 handshake SSH opt-in ignorado**, zero falhas/erros;
SFTP OpenSSH descartável real habilitado por MYCT_TEST_LOCAL_SFTP=1, sem rede,
autenticação ou alteração de serviço. Lint: **0 erros / 2 avisos preexistentes**
(versões Gradle/json). Há avisos de SDK XML/metaspace do ambiente; build concluiu
com os limites Gradle anteriores, sem alterar configuração global de memória.
Testes instrumentados L1C/L1B **compilados, não executados**. Integridade de
dependências/patches e diff check PASS. A fonte original Sora e JNI/PTY não
receberam alterações.

- APK: [`myct-l1c-0.6.9-debug.apk`](../artifacts/myct-l1c-0.6.9-debug.apk),
  **0.6.9-l1c-offline-c / code 26**, `dev.myct`, targetSdk 36.
- Tamanho: **18.750.329 bytes** (17,88 MiB); L1B: 18.251.989 bytes;
  aumento líquido **498.340 bytes** (0,48 MiB). Esse delta inclui compressão
  nativa alterada pelo empacotamento; os três workers somam 2.380.756 bytes
  extraídos, sendo **826.872 bytes no arm64**. Instalação contém somente a ABI
  adequada. Não extrapolar delta APK para RAM/disco instalado no Moto.
- SHA-256: `c1bb509c5a21f2cff5c5f9aeb9b6e2d1b491829372c44705d967f5a94be2a100`.
- Assinatura apksigner PASS, **mesmo certificado da L1B**;
  SHA-256 público do certificado:
  `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
  ZIP alignment 16 KiB PASS; applicationId/code/target/extractNativeLibs e
  bibliotecas das três ABIs inspecionados. As seis bibliotecas nativas já
  existentes (PTY e AndroidX) permaneceram byte a byte idênticas à L1B.
- APK de instrumentação: `artifacts/myct-l1c-0.6.9-androidTest.apk`, apenas
  para teste autorizado; não é a atualização comum do proprietário.
- Checksums: `artifacts/myct-l1c-SHA256SUMS.txt`; logs/metadados/medições em
  `artifacts/myct-l1c-validation/`. **APK não foi instalado por agente.**

Principais arquivos: `android/offline-c/` (fontes fixadas/build/worker/testes),
`OfflineC.kt` (captura/controle/recursos), `OfflineCConsole.kt`, `IdeScreen.kt`,
`IdeSession.kt`, `TerminalModel.kt`, `app/build.gradle.kts`, testes JVM e
instrumentados, notices, README Android/projeto, este relatório, requisitos,
planejamento L1/inaugural, decisões e retorno físico parcial L1B.
Nenhum arquivo servidor/ops, banco ou unidade systemd foi alterado.

Ajuste final apenas de whitespace nos textos de licença para diff check;
reempacotamento offline PASS em 14 s, sem alteração de código ou bibliotecas.
Medição do comando de build completo: pico RSS 2.308.260 KiB (~2,20 GiB),
CPU usuário 723,70 s distribuída entre threads/processos, 2m28,40s de parede,
sem swap reportado. Não corresponde ao consumo do aplicativo no Moto.

Pré-release candidata: [myct-l1c-v0.6.9-rc1](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-l1c-v0.6.9-rc1).
O resultado físico continua pendente, mesmo após publicar a candidata.

PR #30 publicado como **draft**, base `myct-l1b-local-terminal`, sem duplicar
commits L1A/L1B. Pré-release candidata com APK, APK de instrumentação, SHA-256,
relatório e pacote de evidências. Nenhum merge, branch excluída ou issue
encerrada. Fonte de entrega é esta branch/tag; preservar os dados na atualização.
O teste de prompt lida também com leituras parciais de pipe, aguardando os oito
bytes antes de enviar entrada; não presume que uma leitura devolva tudo.
