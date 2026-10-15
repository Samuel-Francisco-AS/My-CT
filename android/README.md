# My-CT Android — SSH e correção MYCT-1R

Kotlin/Compose com terminal SSH/PTTY no Fedora. Login, shell e PTY da MYCT-1
foram comprovados pelo proprietário no Moto G06. As correções MYCT-1R exigem
novo aceite físico; build e testes JVM não o substituem. Evidências e limites:
[relatório corretivo](../docs/MYCT-1R-CORRECTION-REPORT.md).

## Build e instalação

Java 21, SDK Android platform 36 e Build Tools 35/36 já preparados:

```sh
cd android
# Definir JAVA_HOME e ANDROID_HOME para os caminhos locais existentes.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python terminal/upstream/verify.py
```

Wrapper Gradle 8.14.3 `all` com checksum fixado, AGP 8.13.0 e Kotlin 2.2.20.
Sem Android Studio ou emulador. Heap 1536 MiB, 2 workers e Kotlin no mesmo
processo. `--offline` exige caches preparados; máquinas novas precisarão de
revisão de impacto/aprovação antes de downloads volumosos. Não há instalador
de pacotes globais ou deploy automático de servidor.

APK de build: `app/build/outputs/apk/debug/app-debug.apk`.
Entrega MYCT-1R: `../artifacts/myct-1r-debug.apk`, com arquivo `.sha256` ao lado,
fora do Git. Pacote `dev.myct`, versão `0.1.1-ssh-corrective` (versionCode 2),
minSdk 26, target/compileSdk 36. APK debug de desenvolvimento, sem assinatura
release; preserve a keystore debug local para atualizar a instalação anterior.
Outra máquina pode gerar assinatura diferente. Nunca commitar keystore.

Sem ADB: transferir por USB/MTP ou cliente SFTP autenticado existente, conferir
SHA256 e abrir pelo gerenciador de arquivos Android. Permitir temporariamente
instalação por esse gerenciador e revogar ao terminar. Não abrir servidor HTTP
público. Com ADB autorizado, na raiz: `adb install -r artifacts/myct-1r-debug.apk`.
Não é necessário apagar dados/pins ao atualizar com a mesma assinatura.

## Conexão e identidade

Use rede privada e usuário Linux sem privilégios. Primeiro pareamento exige
fingerprint obtido por um canal já confiável, diretamente no servidor:

```sh
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

Informe apenas `SHA256:...`; não confie em scan de rede isolado. Host Ed25519,
StrictHostKeyChecking e pin por endereço/porta continuam obrigatórios. Chave
alterada é recusada; “Refazer pareamento…” exige confirmação e nova conferência
externa. Autenticação atual é `password`, sem importação de chave privada/MFA.
Não alterar sshd para acomodar limitações do app.

Senha não é salva em preferências, saved state, arquivos ou logs. Campo limpo
após conectar/sair da tela, buffers mutáveis zerados; Strings da UI/JVM não
permitem prometer apagamento forense imediato de memória. Pins/metadados ficam
no sandbox local com backup e transferência de dados Android desabilitados.

## Layout, teclado e medições

Há uma única barra de teclas de 48dp, rolável na horizontal: Ctrl (próxima
entrada), Ctrl-C, Esc, Tab, setas, Colar e Teclado. Toque no terminal para abrir
IME. **Opções → Fonte** ajusta 10–20sp; valor inicial 12sp. A conversão para
pixels acontece uma vez, conforme a densidade de fonte Android.

Insets de sistema/cutout/IME são unidos antes da AndroidView. Área medida e
fonte alimentam o renderer Termux e o resize SSH. **Opções → Medições locais**
mostra área em pixels, linhas×colunas do emulador e tempos máximos de espera IO/
main. Os valores não medem RTT/echo nem comprovam o tamanho remoto; compare com
`stty size` ao abrir/fechar IME e girar o Moto. Diagnóstico não guarda conteúdo,
fingerprint, host ou usuário. Não há log de terminal ou batching de teclado.

## Clipboard e screenshots

Copy na seleção é ação manual Android; Colar (barra/seleção/mouse) lê somente
texto simples e pede confirmação antes de enviá-lo. Quebras de linha podem
executar comandos. Limite de 64 KiB em UTF-8, sem truncamento silencioso ou
leitura de URI. Texto copiado é marcado como sensível para evitar preview do
SO, mas o clipboard ainda pertence ao Android e pode ser acessado conforme a
política do aparelho. IME pode inserir texto por seus próprios gestos.

Callbacks OSC 52 de escrita/leitura permanecem inertes; o servidor não obtém
acesso ao ClipboardManager. Dois patches mínimos da view separam seleção/mouse
dos callbacks remotos; renderer/emulador ANSI não foram reimplementados.

**Proteger capturas** vem ativado por padrão. Em Opções, desativar exige
confirmação; a preferência é local e a flag muda sem reiniciar SSH. Ao liberar,
conteúdo pode aparecer em screenshots e apps recentes. Religue a proteção
quando terminar. Nenhum conteúdo é enviado a logs.

## Manter conexão ativa no Android

Após autenticar, abra **Opções → Manter conexão ativa**. É uma ação explícita
na Activity visível. No Android 13+, autorize notificações; se a permissão ou
canal estiver bloqueado, o modo não é ativado. Uma notificação identifica a
conexão, abre o app e oferece **Desconectar**; conteúdo é privado na tela
bloqueada. A notificação não substitui a preferência de screenshot.

Motor SSH em escopo do processo é compartilhado por Activity e foreground
service. Voltar ou girar a Activity reanexa ao mesmo objeto/sessão autenticada,
sem novo login, socket ou comandos. Enquanto UI está oculta, callbacks de
redraw e clipboard são desanexados; o emulador mantém histórico limitado.

Desativar a opção, usar Encerrar ou Desconectar na notificação fecha SSH e para
o serviço. Queda de rede gera erro e encerra o modo; não há reconexão automática
nem repetição de comandos. Morte do processo, force-stop, reboot, parada pelo
gerenciador Android, Doze/rede e políticas do fabricante continuam limites
reais. Foreground service reduz o risco de encerramento comum, sem garantir
conexão indefinida. Não solicitamos desativação global de bateria, wakelock,
Wi-Fi lock ou boot receiver.

Tipo escolhido: `specialUse`, subtipo explícito de terminal SSH interativo;
permissões `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` e
`POST_NOTIFICATIONS` (além de Internet). Serviço não exportado,
`START_NOT_STICKY`, sem credenciais em Intent. Essa escolha técnica não é
aprovação da Play Store nem validação física da plataforma. Justificativa e
[referências oficiais](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
no relatório corretivo. Não foi usado dataSync ou permissão de mudança de rede
sem função real para satisfazer outro tipo.

## Recuperar trabalho no Fedora com tmux

Conexão Android e programa remoto são camadas distintas. tmux 3.7c foi
observado no Fedora; outro host pode não o ter. Em **Opções → Recuperar trabalho
· tmux**, o app apresenta/copia este comando fixo **sem executá-lo**:

```sh
tmux -L myct -f /dev/null new-session -A -s myct-main
```

No prompt de shell vazio do Fedora, cole e pressione Enter. Namespace/socket
`myct` é separado do tmux padrão do usuário; `-A` cria ou anexa a `myct-main`,
sem sobrescrever nem destacar clientes existentes. `-f /dev/null` evita carregar
configuração pessoal para um servidor tmux novo; sem root. Não cole em programa
interativo que não seja shell.

Dentro do tmux, faça o trabalho. **Ctrl-B, depois d** destaca o cliente mantendo
o trabalho; não use `exit` se quiser preservar o shell. Após queda de SSH,
autentique novamente e use o mesmo comando, por ação manual. Nenhum comando ou
senha é reenviado automaticamente. tmux não sobrevive a reboot do Fedora,
encerramento da sessão/processo ou falta de recursos.

Prova física inofensiva sugerida, somente dentro dessa sessão:

```sh
n=0; while [ "$n" -lt 30 ]; do n=$((n+1)); printf 'tick %s\n' "$n"; sleep 2; done
```

Anote progresso, desconecte SSH no app, conecte novamente e reanexe manualmente.
Confira que houve progresso enquanto o cliente esteve desconectado. A prova
local MYCT-1R já confirmou sobrevivência após fechar PTY local e attach ao mesmo
processo, em servidor tmux isolado de teste; **não foi prova autenticada de
queda SSH no Moto**. Veja evidência no relatório.

## Gate físico corretivo

Login, `ls`, `cd`, `pwd`, `/dev/pts/3` e `17 51` com IME aberta já foram
observados na MYCT-1 pelo proprietário. Foco novo:

- Alternar IME e orientação, comparar área/linhas/colunas locais com `stty size`,
  manter prompt visível; TUI (`top`/vi), Unicode e teclas sem regressão.
- Selecionar/Copy e colar em outro app; Paste confirmado no terminal; OSC 52
  não altera clipboard. Não usar segredos como conteúdo de teste.
- Captura protegida por padrão, opt-out consciente sem reconectar e proteção
  reativada. Screenshot liberada só durante o teste.
- Com modo ativo: sair, bloquear tela e retornar após 5/15 minutos; mesma sessão
  sem senha repetida; notificação e parada deliberada funcionam. Registrar
  versão Android/bateria sem logs sensíveis.
- Queda de rede, parar FGS e force-stop são testes distintos; sem reenvio de
  comandos. Nova autenticação após morte do processo é esperada.
- Programa em tmux progride após desconectar SSH e é recuperado manualmente.

JVM: `MYCT_TEST_LOCAL_SSH=1 ./gradlew :app:testDebugUnitTest --offline` inclui
handshake real local sem autenticação/shell. O adaptador Base64 em src/test é
somente para alimentar OSC no teste JVM, não faz parte do APK. Sem variável,
esse teste SSH é ignorado. Licenças em assets e `terminal/upstream/`.
