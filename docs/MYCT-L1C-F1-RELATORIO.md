# MYCT-L1C-F1 — investigação do crash 139

17/10/2026. Branch `myct-l1c-offline-c`, PR #30 draft sobre L1B.
**Gate físico L1C-B FAIL na RC1/code 26**, confirmado pelo proprietário:
`teste/teste.c` salvo, stdio/puts/main(void)/return 0; console código 139,
290 ms, sem diagnóstico nem módulo executado; fonte preservada.
Não equivale a erro de sintaxe nem identifica por si só a causa do sinal.

## Critérios e investigação antes de corrigir

- Reproduzir com RC1/instrumentação Android, se houver ambiente; Linux não
  encerra F1. Moto não conectado; nenhum acesso ao dispositivo é pressuposto.
- H1: inicialização/linker/empacotamento. Teste: worker instalado no APK,
  instrumentação e backtrace/logcat Android; se necessário, etapas mínimas stderr.
- H2: RLIMIT_AS de 384 MiB versus reservas virtuais da Bionic. Teste: medir
  VM/RSS/stack e limites antes/depois; não ampliar limite por hipótese.
- H3: WCPL/Bionic/ABI arm64, stack/ponteiros/alocações. Teste: localizar estágio
  e componente do crash; comparar artefato Android e worker glibc Fedora.
- Corrigir a menor causa demonstrada, preservando WCPL/WAMR, fontes, limites
  não implicados, workspace, pacote, certificado e atualização de dados.
- Separar recusa normal do compilador, falha de inicialização, sinal/crash,
  timeout/limite e sucesso. Não classificar status 139 como sintaxe.
- Validar stdio/puts/printf/scanf, erro legítimo, cancelamento, recompile após
  falha, L1A/L1B, build/JVM/nativos/lint. Gate Moto continua pendente até retorno.
- Publicar RC2, `0.6.9-l1c-f1`/code 27; manter PR draft, sem merge/L1D.

## Inventário inicial

Emulator 36.6.11 e platform-tools existentes; adb listou **zero dispositivos**.
Sem `/dev/kvm`, nenhuma AVD/imagem provisionada. SDK Manager 22.0, JBR 21.0.10,
NDK 28.2.13676358/CMake 3.22.1 anteriores preservados. Fedora com ~6,5 GiB
RAM disponível na inspeção e ~140 GiB livres no disco. Autorizada pela missão
uma reprodução Android: provisionar imagem AOSP Android 15/API 35 x86_64,
AVD temporária da tarefa, sem root, mudanças de BIOS/KVM ou serviço Fedora.
Não confundir emulador x86_64 com Moto arm64 nem inferir desempenho do aparelho.

Estado final desta entrega: RC2 diagnóstica para o Moto; causa nativa e gate físico ainda abertos.

## Reprodução Android — primeira tentativa

Imagem AOSP API 35/default/x86_64 revisão 2 instalada no SDK (~1,7 GiB).
AVD própria `MyCT_L1C_F1`, 1536 MiB, TCG sem KVM, sem root, SELinux padrão.
O **emulador host** caiu por SIGSEGV após ~4 minutos, antes de instalar o APK;
`coredumpctl` confirmou o processo qemu-system-x86_64-headless, não o worker.
Não é reprodução do defeito L1C. Pico RSS observado do emulador ~2,6 GiB;
pequeno uso de swap (~34 MiB) no host, sem mudança em serviços/limites Fedora.

Hipótese do ambiente H4: caminho gráfico Vulkan/software na execução sem KVM
instável. Um retry limitado com Vulkan desativado, 1 vCPU e log de kernel;
nenhuma mudança no aplicativo/limite decorrente desse crash do emulador.
RLIMIT_CORE=0 aplicado apenas ao processo de teste na segunda tentativa; o registro systemd correspondente informa Storage: none.
Logs brutos do emulador contêm chave ADB **pública** e identificadores locais;
não entram no pacote público de evidências. Não ler/divulgar material privado.

## Resultado da reprodução e limite da entrega

A segunda tentativa também terminou com **SIGSEGV do qemu host**, após ~130 s
no log de kernel, ao iniciar componentes gráficos/bootanim. ADB chegou a
anunciar `device`, mas a primeira consulta shell encerrou sem resposta e o
processo do emulador morreu. **Nenhum APK foi instalado no emulador; nenhum
método instrumentado executou.** Não foi obtido backtrace do worker Android.
O backtrace disponível é apenas do host qemu; não caracteriza a causa no Moto.
Não haverá tentativas adicionais de configurar virtualização ou alterar host.

**RC2 é diagnóstica. O crash do compilador no Moto NÃO está declarado corrigido.**
H1/H2/H3 seguem abertas, sem causa nativa demonstrada. RLIMIT_AS continua
**384 MiB**, sem ampliação, preaquecer alocador deliberadamente, troca de libc,
toolchain ou runtime. Não há base para mudar limite de memória nesta sessão.
As medições Linux não são medições Bionic/arm64 e não justificam tal mudança.

## Correções demonstráveis e diagnóstico implementado

- Console diferencia código 1 normal de recusa do compilador, falha ao iniciar
  processo, sinal nativo (139 compatível com SIGSEGV), sinais de limite
  (SIGALRM/SIGXCPU/SIGXFSZ), cancelamento e sucesso. Código sozinho não explica
  causa: nenhuma atribuição a stack/malloc/Bionic/limite foi feita.
- Falha de ProcessBuilder tem erro de inicialização próprio. Erros de captura/
  arquivo continuam separados. Não executar módulo após qualquer compilação
  falha, preservar fonte e permitir nova tentativa.
- Retorno normal WASI 125 ou 139 permanece término normal do programa quando
  o runtime confirma seu encerramento; não é confundido com sinal do worker.
- `worker.cpp` escreve a primeira etapa por `write()` antes de limites/stdio;
  depois registra somente etapa fixa e números de VmSize/VmRSS/VmStk e limites
  AS/stack. Nenhum caminho, fonte, token, input, ambiente ou endereço é incluído.
- `prepare.py` verifica os mesmos arquivos upstream por SHA-256 e injeta calls
  de diagnóstico fixas na **entrada real de wcpl_main**, antes/depois de
  init_wcpl, parsing C, linker e codificação WASM. Não substitui o parser,
  reconhece exemplos ou muda código gerado; source original do usuário intacto.
  Fontes upstream arquivadas continuam idênticas. Mudança documentada nos notices.
- WAMR tem marcadores read/init/load/instantiate/execute/finished. Sem handler
  próprio para sinais fatais: não esconder nem substituir o debuggerd Android.
  Os marcadores podem alterar timing/inicialização; sucesso de uma candidata
  diagnóstica isoladamente não estabelece a causa original.
- Mesma workspace/artefatos/UID, targetSdk 36, WCPL 1.04 e WAMR 2.4.4,
  memória WASM 16 MiB, RLIMIT_FSIZE 16 MiB, CPU 30 s, parede 30/60 s e
  fallback alarm 90 s. Não há instalação/deploy no Moto ou serviços Fedora.

As novas etapas são visíveis no console, selecionáveis para cópia. São
**diagnóstico temporário da RC2**, não saída produzida pelo programa C.
A compilação bem-sucedida continua exigindo novo `.wasm` e retorno real.

## Checklist curto para o proprietário — todos PENDENTES na RC2

1. Atualizar sobre code 26 **sem desinstalar/limpar dados**; conferir code 27.
   Modo avião, IDE Local, abrir e salvar o mesmo `teste/teste.c` com puts.
2. Compilar. Se falhar, enviar o status, o tempo e todas as linhas
   `[myct-worker:v2]` até a última etapa visível. Esses números/etapas são a
   informação que falta para localizar H1/H2/H3; não enviar senhas/segredos.
3. Se compilar, confirmar `My-CT compilando offline!`, retorno zero e também
   registrar os valores before-limits/after-limits e a sequência de etapas.
4. Testar scanf/printf com 21 → dobro=42; remover ponto e vírgula e conferir
   diagnóstico normal, sem execução antiga; corrigir/salvar/recompilar.
5. Executar `while(1){}` e Interromper; compilar novamente o puts. Conferir
   fonte preservada e Terminal Local; regressões remotas quando disponível.

Critério físico F1 segue aberto até retorno expresso do proprietário. Não fazer
merge, encerrar #27 nem iniciar L1D. Se a RC2 falhar, usar a última etapa e as
medidas antes de qualquer alteração de limite; coletar backtrace somente com
acesso autorizado ao aparelho, sem pressupor ADB/logcat/tombstone disponível.


## Validação final, limites da evidência e retomada

**Fedora PASS:** build Android e APK de testes, JVM e lint totalmente offline,
BUILD SUCCESSFUL em **2m24s**. **213 testes JVM, 212 PASS, 1 handshake SSH opt-in
ignorado**, zero falhas/erros. SFTP descartável real habilitado sem autenticação
ou alteração de SSH. Lint **0 erros / 2 avisos preexistentes**. Avisos SDK XML/
metaspace anteriores preservados, sem mudança de JVM global.

Três novos testes JVM cobrem classificação dos status, **processo de fixture
realmente morto com SIGSEGV** (não é WCPL/Android) e executável ausente. Fluxos
reais do WCPL/WAMR Fedora cobrem programa com retorno 139 normal sem falso
crash, I/O/EOF, sintaxe, falha e recompile, timeout/cancelamento, trap e saída
limitada. Isso comprova correção da classificação no host, **não a causa nem a
correção do SIGSEGV do compilador no Moto**.

**Nativos Fedora:** 9 testes de compilação/execução real PASS. O programa exato
stdio/puts do gate também compilou e executou, retorno zero, fonte preservada.
Uma observação instrumentada no host: compilar 31,509 ms, executar 10,083 ms;
VM/RSS na saída WCPL 8.740/5.800 KiB, stack mapeada 136 KiB e AS 402.653.184
bytes. Não é estimativa Bionic/Moto. `host-gate-and-stages.json` contém a
sequência real entry/wcpl-main/init/parser/linker/encoder/WAMR. Nenhum dado
Android de VM/RSS foi obtido, portanto nenhuma conclusão sobre RLIMIT_AS.

Regressão PTY L1B recompilada/executada: PASS de terminal real, cwd/env, pipes,
entrada, resize, Ctrl+C, saída/sessão independente, bloqueios e lifecycle de FD.
L1A/Local/Remoto/Sora/salvamento/SAF/transação SFTP seguem na suíte JVM PASS.
21 arquivos upstream verificados e check reverso dos quatro patches PASS;
arquivos WCPL/WAMR originais continuam com SHA-256 verificado pelo prepare.py.

**Android:** quatro testes L1C instrumentados preparados/compilados, incluindo
agora o programa exato `teste/teste.c` salvo via LocalWorkspace → captureC →
worker instalado, mais I/O, erro/recompile e interrupção. Em falha, assertions
incluem o diagnóstico; Logcat de testes registra apenas etapas/recursos fixos.
Os testes L1B continuam compilados. **ZERO métodos instrumentados executados**;
nenhum PASS Android, arm64 ou Moto. Não contar a queda do emulador como falha do
app nem como correção validada. Os novos marcadores não acrescentam fonte/
caminhos, mas o diagnóstico normal de sintaxe do WCPL continua mostrando
linha/coluna e trecho com erro, como exigido pelo editor.

Depois dos dois crashes host, a AVD criada exclusivamente pela tarefa foi
removida (~600 MiB); não há emulador em execução nem VM permanente. A imagem
SDK (~1,7 GiB) ficou em cache para novos testes autorizados; o primeiro core
host é gerenciado pelo systemd (~564 MiB), sem limpeza global nesta tarefa.
Processos/serviços/configurações Fedora não foram modificados. Comando do
build final: pico RSS 2.291.460 KiB (~2,19 GiB), CPU usuário 678,14 s,
parede 2m24,48s e sem swap reportado pelo comando.

Comandos reproduzíveis, da raiz (ferramentas/cache já provisionados):

```sh
python3 android/offline-c/prepare.py android/app/build/offline-c/vendor
/home/sam/Android/Sdk/cmake/3.22.1/bin/cmake --build android/app/build/offline-c/host -j 2
python3 android/offline-c/tests/test_worker.py android/app/build/offline-c/host/libmyct-c-worker.so
MYCT_TEST_LOCAL_SFTP=1 \
MYCT_TEST_OFFLINE_C="$PWD/android/app/build/offline-c/host/libmyct-c-worker.so" \
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
```

A reprodução Android foi tentada com imagem `system-images;android-35;default;x86_64`
revisão 2 e Emulator 36.6.11, TCG sem KVM. Não exige nem autoriza instalar no
Moto via ADB. Para novo ambiente de testes, recriar AVD descartável com essa
imagem e tentar a instrumentação; o host atual falhou antes de instalar APK.

## APK RC2 e Git

- **0.6.9-l1c-f1 / code 27**, `dev.myct`, targetSdk 36, minSdk 26.
- APK: [`myct-l1c-f1-0.6.9-debug.apk`](../artifacts/myct-l1c-f1-0.6.9-debug.apk),
  **19.171.245 bytes**. Workers arm64/armv7/x86_64 com marcadores conferidos;
  arm64 **829.528 bytes**. Seis bibliotecas nativas anteriores (PTY/AndroidX)
  permanecem byte a byte idênticas à RC1. ELF/packaging continuam PIE instalado,
  sem usuário ELF/JIT/W^X/arquivo executado da workspace.
- SHA-256: `b18c19d50cb592663c6fe9e2a31d2122f7b9e09219d5ab3a0ec2f3c81d1b143c`.
- Assinatura apksigner e ZIP alignment 16 KiB PASS; **mesmo certificado da RC1**:
  `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
  Atualizar sobre code 26 sem desinstalar ou limpar dados. Nenhuma instalação
  no aparelho do proprietário foi feita pelo agente.
- APK de instrumentação `myct-l1c-f1-0.6.9-androidTest.apk` é apenas para testes
  autorizados. Checksums em `artifacts/myct-l1c-f1-SHA256SUMS.txt`.
- Evidências sanitizadas em `artifacts/myct-l1c-f1-validation/`; pacote público
  exclui logs brutos do emulador com identificadores/chave ADB pública.
- [Pré-release RC2 diagnóstica](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-l1c-v0.6.9-rc2)
  e [PR #30 draft](https://github.com/Samuel-Francisco-AS/My-CT/pull/30), mesma
  branch/base empilhada. Sem merge, exclusão de branches ou fechamento de #27.

Arquivos alterados: OfflineC.kt (classificação/inicialização), worker.cpp e
prepare.py (diagnóstico fixo/native resources), testes JVM/instrumentados,
versionCode/Name, notices, READMEs e relatórios/requisitos/decisões. Nenhuma
alteração em Sora, workspace original, JNI PTY, servidor/ops/serviços ou L1D.

**Próximo passo necessário:** retorno do proprietário com status/etapas e
medidas da RC2, ou backtrace Android obtido com autorização. Só então selecionar
uma correção da causa demonstrada. A entrega diagnóstica não encerra F1/LOC-03.
