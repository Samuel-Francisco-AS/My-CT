# MYCT-R5-F2 — navegação e transições

**Candidata para teste; aceite visual Moto G06 pendente.** Branch
`myct-r5-f2-navigation`, criada da `origin/main` atualizada `2dec947` em
18/10/2026, em worktree separado `/home/sam/Projetos/My-CT-r5-f2`.
O checkout `fix/torrent-dns-trackers` e o PR #34 permanecem intactos.
Não realizar merge antes do retorno físico do proprietário.

## Causa e implementação

A seção selecionada era alterada diretamente e o conteúdo condicional era
substituído no mesmo frame, sem saída/entrada. O drawer começava a fechar em
paralelo. Início, atalhos da IDE/Downloads e retorno do diálogo de buffer sujo
alteravam a seção por caminhos separados. Isso explica a troca visual abrupta;
**não constitui diagnóstico de bloqueio da thread principal no Moto**.

- `SectionNavigation` separa destino solicitado de seção exibida. Todos os
  caminhos passam por `navigateTo`; repetição do mesmo destino não reinicia a
  transição. A seleção do drawer e o ripple Material respondem ao toque.
- O estado do drawer é elevado ao chamador. O conteúdo aguarda `currentValue`
  e `targetValue` fechados e `isAnimationRunning == false`. Não há `delay`,
  espera fixa ou duração de drawer duplicada no código.
- `Animatable`: fade-out linear **80 ms**, troca no zero, fade-in
  `FastOutSlowInEasing` **150 ms**; referência total **230 ms** na escala 1×,
  além do fechamento real do drawer e do trabalho necessário de composição.
  Sem deslocamento ou animação de tamanho, para preservar o viewport nativo.
- Só uma seção fica composta. Não há `Crossfade`, `AnimatedContent`, bitmap
  de captura ou segunda instância deliberada de TerminalView/Sora. A opacidade
  é lida em `graphicsLayer`, sem recomposição/layout por frame da animação;
  o fundo continua sendo a Surface do tema, sem inserir uma tela vazia.
- Mudança de destino cancela o `LaunchedEffect` anterior e continua da
  opacidade atual. O commit lê a solicitação mais recente. Retornar à seção
  ainda exibida restaura sua opacidade. Reabrir o drawer suspende a troca.
- `Animatable` usa `MotionDurationScale` do contexto Compose/Android, incluindo
  escala zero para animações removidas; não se sobrescreve a preferência do
  sistema. O teste JVM injeta escala zero e um relógio de frames controlado.
- `SaveableStateHolder` permanece estável no host; a exceção existente de
  Arquivos/SFTP e suas âncoras permanece. A restauração da Activity escolhe o
  último destino solicitado, totalmente visível, sem restaurar animação parcial.
- Cofre: `FLAG_SECURE` já é aplicado no primeiro frame da Activity e no pedido
  de entrada; uma trava conserva a proteção durante saída, entrada, interrupções
  e troca rápida para outras seções. Só é liberada após a chegada completa a
  uma seção não protegida, se a preferência global permitir. Os diálogos
  existentes continuam herdando a proteção da janela.

Sessões/PTY/tmux, buffers `Content` e viewport do Sora, modelos de tela,
transferências/Downloads, serviços e regras de insets mantêm seus proprietários
e mecanismos anteriores. A navegação não encerra nem recria sessões. A limpeza
de modificadores e a política existente do drawer de ocultar IME ao sair para
uma seção não Terminal agora são aplicadas também aos atalhos. Não há mudança
de backend, PostgreSQL, serviços Fedora ou dados de usuário.

Referências técnicas: [animações sequenciais com Animatable](https://developer.android.com/develop/ui/compose/animation/choose-api)
e [MotionDurationScale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale).

## Arquivos

- `android/app/src/main/java/dev/myct/AppSection.kt`: estado, transição e proteção.
- `android/app/src/main/java/dev/myct/MainActivity.kt`: navegação central e drawer.
- `android/app/src/main/java/dev/myct/AppShell.kt`: recebe o estado do drawer.
- `android/app/build.gradle.kts`: candidata `0.7.2-r5-f2` / **code 31**.
- `android/app/src/test/java/dev/myct/SectionNavigationTest.kt`: lógica e animação.
- `android/app/src/test/java/dev/myct/ShellProtectionTest.kt`: proteção em trânsito.
- Este relatório.

## Validação automatizada

Rodada completa aprovada: build debug nas três ABIs e APK de instrumentação;
**234 testes JVM PASS, 1 handshake SSH opt-in ignorado**, zero falhas/erros em
45 suítes; **lint PASS, zero achados**. Os nove testes novos usam o Animatable
real com relógio de frames JVM: pedidos repetidos, retarget durante saída,
cancelamento/retorno à seção atual, último pedido antes do commit, drawer
bloqueando troca/reabertura, proteção durante entrada/saída interrompidas,
restauração e escala zero. Há mais uma asserção de proteção em trânsito no
`ShellProtectionTest`. Isso não executa AndroidView, IME ou renderização Android.

**PTY real no host PASS** (cwd/env, pipes/read, resize, Ctrl+C, exit, isolamento,
erros e ciclo de descritores/processos); proveniência de **21 arquivos e três
patches do terminal verificada**. `git diff --check` PASS. Nenhuma dependência
instalada; nenhuma configuração de serviço alterada.

A primeira rodada foi iniciada antes de estabilizar os fontes e encontrou uma
assinatura intermediária entre código compilado e teste; a repetição completa
com fontes estáveis passou. Na revisão posterior, o efeito de `FLAG_SECURE`
voltou a ser vinculado exclusivamente às mudanças de proteção/Activity, evitando
chamadas à janela em recomposições sem alteração. A candidata foi
revalidada pelos mesmos checks após esse ajuste, todos aprovados.

A rodada final da candidata durou **2 min 56 s**, pico RSS de build medido
**2,20 GiB**, CPU **508%** (vários núcleos). São custos do build no Fedora,
não consumo do app no Moto.
Os avisos preexistentes de duas condições redundantes da IDE e de metaspace
Gradle não impediram o build; configurações preservadas. Logs locais, ignorados
pelo Git: `artifacts/myct-r5-f2-validation/`.

Ferramentas: OpenJDK 21.0.10, Gradle wrapper 8.14.3, SDK/build-tools 36,
NDK 28.2.13676358, CMake 3.22.1, GCC 16.2.1, apksigner 0.9, Git 2.55.0 e
gh 2.97.0, já disponíveis antes da execução.

Comandos na raiz do worktree, usando ferramentas/caches existentes:

```sh
MYCT_TEST_LOCAL_SFTP=1 ANDROID_HOME=/home/sam/Android/Sdk \
  /usr/bin/time -v android/gradlew -p android :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:testDebugUnitTest \
  :terminal:testDebugUnitTest :app:lintDebug --offline --console=plain
g++ -std=c++20 -Wall -Wextra -Werror -pthread -Iandroid/app/src/main/cpp \
  android/app/src/main/cpp/local_pty.cpp android/app/src/test/native/local_pty_test.cpp \
  -lutil -o artifacts/myct-r5-f2-validation/local-pty-test
artifacts/myct-r5-f2-validation/local-pty-test
python3 android/terminal/upstream/verify.py
git diff --check
```

`adb devices -l`: nenhum dispositivo conectado. Instrumentação compilada não
equivale a teste Android executado. Nenhum APK foi instalado pelo agente.

## Candidata e atualização

As branches remotas e releases foram consultadas antes da versão: code máximo
encontrado **30**, inclusive no PR #34. Code **31** não apresentou colisão nessa
consulta. ApplicationId `dev.myct` e configuração habitual de assinatura debug
preservados; nenhum certificado foi substituído.

Artefato local: `artifacts/myct-r5-f2-0.7.2-debug.apk`, **18.367.054 bytes**,
ABIs arm64-v8a, armeabi-v7a e x86_64, minSdk 26/targetSdk 36. Pacote/versão/code
conferidos com `aapt`; `apksigner verify --verbose --print-certs` **PASS/v2**.
Certificado SHA-256:
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`,
idêntico ao APK F1/code 30 cujo hash local foi comparado ao digest da release
publicada. Portanto, a candidata permite atualização compatível em assinatura.

SHA-256 do APK:
`e9827ae7fbd00e7c6d73a6f39cb48eedc9a85a69ebb27eba1c42c169f2395c52`.

[Release candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r5-f2-v0.7.2-rc1)
· [APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r5-f2-v0.7.2-rc1/myct-r5-f2-0.7.2-debug.apk)
· [SHA256SUMS](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r5-f2-v0.7.2-rc1/myct-r5-f2-SHA256SUMS.txt).

Atualizar **sobre a instalação existente**, aceitando a atualização de `dev.myct`;
**não desinstalar nem limpar dados/armazenamento**. Alternativa por cabo:
`adb install -r myct-r5-f2-0.7.2-debug.apk`. Fechar/reabrir o processo para atualizar
um APK pode encerrar shells locais e buffers ainda apenas em memória: salvar
documentos antes da instalação. Isso difere de navegar entre seções, que deve
preservar esses recursos. SSH pode exigir reconexão conforme o modo persistente
existente; permissões SAF, preferências e projetos em disco devem permanecer.

## Roteiro Moto G06 — pendente

1. Início → IDE Local → Terminal → Início; repetir com SSH desconectado/conectado.
   Conferir ripple imediato, saída/entrada discretas, ausência de flashes e
   percepção de resposta rápida. Repetir com animações removidas e escala 0,5×/1×.
2. Abrir drawer por botão/gesto, escolher todas as seções, inclusive a atual;
   interromper fechamento com gesto/reabertura e selecionar outro destino.
3. Terminal SSH e Local → Arquivos → Terminal: conferir mesma sessão, saída,
   tmux, seleção Local/Remoto, digitação, teclas extras e Ctrl+C/resize.
4. IDE com documento aberto e alterações não salvas → outra seção → IDE:
   conferir texto/undo, cursor, rolagem e seleção de armazenamento; repetir com
   documento SAF e remoto. Conferir os diálogos existentes, sem descartes.
5. Permitir capturas globalmente; entrar no Cofre, abrir menu/Opções, sair e
   retargetar durante entrada/saída. Tentar screenshot/gravação/Recentes durante
   as transições: nenhum frame do Cofre pode ser exposto. Na seção comum,
   capturas só devem voltar após a chegada completa.
6. Tocar sucessivamente IDE/Terminal/Fedora no Início e vários destinos no
   drawer: o último pedido prevalece; não há destino antigo reaparecendo,
   instância duplicada, exceção ou tela permanentemente transparente.
7. Repetir navegação com IME aberto no Terminal e na IDE. Conferir teclado,
   foco, área útil e retorno ao terminal. Não houve alteração do código de resize.
8. Rolar Início/Downloads/Saúde, navegar e voltar. Em SFTP, visitar diretórios,
   rolar, sair/reentrar e voltar ao pai: conferir as âncoras existentes. Manter
   transferência/download em andamento e verificar sua continuação.

Sem perfil de frames/RAM/bateria no Moto nesta entrega. Criar TerminalView,
preparar Sora e aplicar seu documento ainda custam composição; a animação não
elimina esses custos. Nenhum novo gargalo foi comprovado sem execução no
aparelho. Se houver pausa real, registrar cenário e medições antes de ampliar
animações ou mexer nos modelos. O aceite depende do roteiro físico acima.
