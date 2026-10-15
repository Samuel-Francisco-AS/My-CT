# My-CT Android — prova SSH

App Kotlin/Compose com SSH/PTTY no Fedora, sem API C++ e sem depender de
Android Studio, emulador ou app Termux. **O build não substitui aceite físico.**
Veja o estado e evidências em `../docs/MYCT-1-ANDROID-SSH-REPORT.md`.

## Build CLI

Pré-requisitos: Java 21, Android SDK com platform 36 e Build Tools 35/36,
e permissões/licenças do SDK já aceitas. Não há instalação automática de
pacotes do sistema. Defina o SDK por `ANDROID_HOME` ou `local.properties`
(não versionado):

```sh
cd android
export JAVA_HOME=/caminho/para/jdk-21
export ANDROID_HOME=/caminho/para/Android/Sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

Wrapper: Gradle 8.14.3 `all`, com SHA256 verificado; essa distribuição foi
escolhida para reutilizar o cache existente do Fedora. Em uma máquina nova,
a distribuição e dependências precisarão ser baixadas (obter aprovação antes
de downloads volumosos). Build limitado a 2 workers, heap Gradle 1536 MiB,
compilador Kotlin no mesmo processo e daemon de uso único. Após preparar
caches, adicionar `--offline` para evitar downloads.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Use `sha256sum` antes e
após transferir. APK debug usa identidade de assinatura de desenvolvimento;
não é uma release para distribuição pública. A assinatura local é gerada
pelo tooling Android, nunca deve ser commitada. Instalações futuras de outra
máquina podem exigir reinstalar o APK se a assinatura debug diferir.

## Instalação sem ADB

Copie o APK por um canal existente e autenticado: USB/MTP ou SFTP já disponível
no servidor. Em um cliente SFTP Android existente, conecte ao Fedora com host
key conferida e baixe o APK pelo caminho de build acima. Não crie servidor
HTTP público para essa transferência. Abra o APK pelo gerenciador de arquivos
do telefone e, se solicitado, permita **a esse gerenciador** instalar apps de
origem desconhecida. Revogue a permissão ao terminar. O app precisa apenas da
permissão Internet, concedida no manifest.

Com ADB autorizado, opcionalmente: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Conectar com identidade do host verificada

1. Use LAN privada ou um túnel/VPN existente. Informe endereço, porta SSH e
   usuário Linux sem privilégios.
2. Obtenha o fingerprint por acesso confiável ao servidor, por exemplo uma
   sessão SSH já autenticada/verificada, executando:
   `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256`.
   Cole apenas o valor `SHA256:...`. **Não confie em um scan de rede isolado**.
3. Informe a senha do usuário Fedora e conecte. A prova suporta autenticação
   `password` e host key Ed25519; não implementa importação de chave privada,
   MFA ou keyboard-interactive. Não altere sshd para acomodar o app: registre
   bloqueio caso a política do host exija outro método.
4. Após verificar host e abrir PTY, o app salva apenas o fingerprint, endereço,
   porta e usuário no armazenamento privado. Nunca grava senha. Uma chave
   divergente é recusada antes da autenticação. Para mudança legítima, escolha
   “Refazer pareamento…” e confirme novamente a identidade no servidor.

Senha fica em memória durante a conexão, sem `rememberSaveable` ou logs, é
removida da tela ao conectar/sair do app e buffers mutáveis são zerados. Como
UI/JVM usam Strings imutáveis, não se promete apagamento forense imediato de
memória. Backups Android e capturas de tela/recentes estão desabilitados.

## Terminal e vida útil

A tela usa renderer/IME do Termux conectado a um canal JSch `shell` com PTY
`xterm-256color`. Há teclas Ctrl (para a próxima entrada), Ctrl-C, Esc, Tab e
setas; rolagem e seleção de texto vêm da view upstream. O botão Teclado/tocar
na view abre o IME. Resize da view (incluindo orientação e teclado) envia
`window-change` ao Fedora. OSC remoto não pode escrever/ler clipboard pelo
adaptador; copiar/colar manualmente pela seleção da view é uma ação local.

Encerrar/Cancelar fecha canal e sessão em IO; Reconectar cria nova sessão.
Rotação preserva a sessão via ViewModel. Ao sair para outro app a conexão pode
continuar enquanto o processo existir; não há foreground service nem garantia
de sessão após kill/Doze. Falha de rede/keepalive retorna erro e oferece
reconexão manual, sem loops automáticos ou reenvio de comandos. Fechar a
atividade definitivamente libera a conexão. Não prometa sobrevivência de
programas remotos após encerrar SSH; para isso o proprietário pode usar seu
multiplexador existente, se desejar.

## Validação física pendente / checklist

Registrar modelo, versão Android, rede e resultado real de cada passo:

- Instalar e iniciar; verificar host key; conectar.
- `pwd`, `uname -a`, `tty`, `stty size` (PTY deve ser real).
- Abrir `top` ou `vi` (se htop não estiver instalado), usar setas e Ctrl-C.
- Testar Tab, Esc, Ctrl-D e texto com Unicode via IME do Moto G06.
- Abrir/fechar teclado e girar telefone; comparar `stty size`.
- Encerrar e reconectar; sair/retornar ao app.
- Usar pin incorreto para um endereço ainda não pareado e confirmar recusa.
  Não altere a chave/configuração do servidor para testar. A recusa de chave
  alterada também é exercitada por teste automatizado de repositório de pins.
- Interromper conectividade no telefone e retornar; erro explícito, UI sem
  travamento e reconexão manual. Registrar operações no aparelho separadamente
  dos testes JVM.

Teste opcional de handshake com o OpenSSH local, sem autenticação ou shell:
`MYCT_TEST_LOCAL_SSH=1 ./gradlew :app:testDebugUnitTest --offline --rerun-tasks`.
Usa a chave pública local para comparar pin correto e incorreto. Testes padrão
não tentam acessar SSH e marcam esse teste como ignorado.

Licenças completas: `app/src/main/assets/THIRD_PARTY_LICENSES.txt` e
`terminal/upstream/`. Bibliotecas, commit upstream e mudanças estão no relatório.
