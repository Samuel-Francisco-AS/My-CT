# MYCT-R5 — refinamento pontual UI/UX

**Candidata para aceite no Moto G06**, na branch `myct-r5-uiux`, baseada na
`main` `1fb0d91` após fechamento L1. Escopo da [issue #32](https://github.com/Samuel-Francisco-AS/My-CT/issues/32):
somente entrada, prompt local e fechamento de arquivo. **Sem merge antes do
teste físico do proprietário.**

## Mudanças e critérios de aceite

- **Entrada:** seção Início no drawer existente, aberta por padrão numa nova
  Activity sem estado restaurado. Preto/dourado, hierarquia tipográfica,
  cartões com ícones, largura limitada e rolagem para telas/fontes maiores.
  IDE Local seleciona o ambiente local preservando a origem privada/compartilhada;
  Terminal Local abre a sessão existente ou inicia o primeiro shell; Conectar ao
  Fedora abre o fluxo SSH existente (ou o terminal se conectado). Estado SSH
  discreto e botão Menu explícito. Nenhum dashboard, polling ou dependência nova;
  navegação imediata e efeitos Material já existentes.
- **Prompt:** `PS1=My-CT> ` no ambiente entregue ao `/system/bin/sh -i`, antes do
  `execve` em `local_pty.cpp`. O próprio shell mostra cada prompt. Inspecionado
  `LocalTerminalModel.kt`: nenhuma injeção de texto/comando necessária. Diretório,
  transporte PTY, renderizador, teclado e prompt Fedora preservados.
- **IDE:** X de 18 dp com alvo Material de 48 dp e descrição **Fechar arquivo**,
  visível quando há documento, desabilitado durante operação. X e menu chamam a
  mesma `requestClose()` e o diálogo existente, inclusive descarte de buffer
  sujo. Mantidos Salvar, nome com elipse, Local/Remoto e altura da barra. Fecha
  apenas o documento da sessão exibida, sem encerrar Activity ou outros buffers.

Arquivos: `HomeScreen.kt`, `AppSection.kt`, `AppShell.kt`, `MainActivity.kt`,
`IdeScreen.kt`, `FileGlyphs.kt`, `local_pty.cpp`, versão em `app/build.gradle.kts`
e asserções nos testes PTY nativo/instrumentado. Sem alterações em compiladores,
Termux, SAF, persistência, backend, serviços ou configuração Fedora.

## Validação

- Build debug nas três ABIs e APK de instrumentação: **PASS**.
- **223 testes JVM PASS, 1 handshake SSH opt-in ignorado**, zero falhas/erros
  (43 suítes). Fixture SFTP local habilitada; módulo terminal sem testes JVM
  próprios (`NO-SOURCE`).
- Lint debug: **PASS**, zero achados no XML.
- PTY real no host: **PASS** para prompt inicial/após comando/Ctrl+C, cwd,
  pipes/read, resize, exit, sessões independentes e ciclo de descritores.
  A primeira asserção após comando falhou porque Bash adiciona ANSI de bracketed
  paste antes do prompt; hipótese confirmada na saída bruta. A comparação do
  teste passou a ignorar essa sequência, sem alterar o produto/renderizador.
- Proveniência terminal: 21 arquivos e três patches documentados verificados;
  `git diff --check` PASS.
- Build completo: 2 min 37 s, pico RSS do processo medido 2.161 MiB e CPU 321%
  (vários núcleos); APK ~16,9 MiB. Sem medir RAM/bateria de execução Android.
  Avisos de metadados SDK/CMake, metaspace Gradle e duas condições redundantes
  preexistentes da IDE não impediram validação; configurações preservadas.

Instrumentação compilada nunca equivale a PASS físico. `adb devices -l` sem dispositivo; nenhum APK foi
instalado pelo agente, sem teste emulador ou login SSH autenticado. Layout e
interação no Moto continuam pendentes; nenhuma captura estava anexada nesta
conversa nem na issue consultada.

Reprodução na raiz, com ferramentas/caches existentes (nenhuma instalação):

```sh
MYCT_TEST_LOCAL_SFTP=1 ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :terminal:testDebugUnitTest :app:lintDebug \
  --offline --console=plain
g++ -std=c++20 -Wall -Wextra -Werror -pthread -Iandroid/app/src/main/cpp \
  android/app/src/main/cpp/local_pty.cpp android/app/src/test/native/local_pty_test.cpp \
  -lutil -o artifacts/myct-r5-validation/local-pty-test
artifacts/myct-r5-validation/local-pty-test
python3 android/terminal/upstream/verify.py
for patch in android/terminal/upstream/*.patch; do git apply --reverse --check "$patch" || exit; done
git diff --check
```

Ferramentas inspecionadas: OpenJDK 21.0.10, Gradle wrapper 8.14.3, SDK 36,
NDK 28.2.13676358/CMake 3.22.1, GCC 16.2.1, apksigner 0.9/build-tools 36.0.0,
Git 2.55.0 e gh 2.97.0. Logs em `artifacts/myct-r5-validation/` (ignorados pelo Git).

## APK e atualização

[Pré-release](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r5-v0.7.1-rc1)
· [APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r5-v0.7.1-rc1/myct-r5-0.7.1-debug.apk)
· [SHA256SUMS](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r5-v0.7.1-rc1/myct-r5-SHA256SUMS.txt).

- `dev.myct`, **0.7.1-r5-uiux / code 29**, 17.685.509 bytes.
- SHA-256: `a978bd018d4766b07751fdd497d38db97adcee403333c570dd20ea3f9abd600c`.
- `apksigner verify` PASS/v2. Certificado SHA-256:
  `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`,
  idêntico ao APK code 28 publicado na L1D (hash daquele APK conferido contra
  digest da release). `aapt dump badging` confirma pacote/code/nome acima,
  minSdk 26 e targetSdk 36. Assinatura e code permitem atualização sobre 0.7.0;
  preservação dos dados será confirmada no aparelho. **Atualizar sem desinstalar
  ou limpar dados.**

## Roteiro físico e retomada

1. Atualizar sobre code 28. Abrir sem SSH: conferir apresentação, Menu, três
   acessos, rolagem/fontes e ausência de cortes/controles pequenos no Moto G06.
2. IDE Local: conferir projetos privados e pasta compartilhada já autorizada.
   Abrir salvo, tocar X, cancelar e depois confirmar fechamento. Editar sem salvar,
   tocar X: Cancelar preserva texto/undo; Descartar fecha só esse documento.
   Repetir fechamento pelo menu; salvar/reabrir confirma persistência existente.
3. Terminal Local: conferir `My-CT> ` na abertura e após `pwd`, `ls`, `echo teste`,
   `cd` de uma pasta existente e Ctrl+C durante `sleep 20`. Conferir diretório
   real, teclado, resize/rotação e retorno à mesma sessão via Início.
4. Conectar ao Fedora: conferir formulário/pareamento existentes, prompt SSH
   original, alternância Local/Remoto, IDE remota e SFTP. Em Início, conferir
   estado conectado/encerrado e acesso à sessão remota existente.

Build/testes não comprovam fluidez percebida, layout físico, teclado Android,
SAF ou regressão SSH real. Registrar o retorno do proprietário neste relatório
e na issue antes de considerar aceite ou merge. Retomar pela branch existente;
nenhuma instalação Fedora ou migração de dados é necessária nesta entrega.
