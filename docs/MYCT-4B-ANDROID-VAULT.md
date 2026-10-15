# MYCT-4B — aba Cofre no Android (implementação candidata)

**Estado (15/10/2026):** implementação publicada na branch `myct-4b-android-vault`, baseada em `myct-4-boot-and-vault` (PR #13). **Build/testes/lint Android executados na revisão abaixo; execução física no Moto G06 ainda pendente.** Não declarar aceite físico nem fazer merge até validar. PostgreSQL e Core não precisam ser modificados.

## Experiência desejada

Após conectar o My-CT pelo SSH autenticado, a quarta aba **Cofre** exibe o estado da coleção GNOME Keyring `login`: Bloqueado, Desbloqueado, Indisponível ou Incompatível. Não lista nomes de credenciais ou valores. O usuário toca **Desbloquear**, espera o prompt seguro e digita a senha no campo temporário da tela; ao confirmar, o aplicativo consulta novamente o estado.

Consulta ao entrar na aba e por **Consultar estado**. Sem polling. Senha não é salva em preferências, arquivos, logs, mensagens de terminal comum ou argumentos de comandos; tampouco é acessível por uma API do Core ou agente. O texto digitado reside transitoriamente em memória do processo Android, que não oferece zeroização forense de `String`/teclado. O campo é apagado imediatamente após confirmação e ao sair da tela; buffer mutável codificado é zerado. A aba mantém `FLAG_SECURE` mesmo se o usuário tiver permitido capturas no restante do app.

## Comunicação

1. `SshConnection.vaultStatus()` abre um canal `exec` temporário na sessão JSch existente e chama somente `$HOME/.local/bin/myct-vault status` (metadados JSON sem itens).
2. `SshConnection.openVaultUnlock()` cria **outro canal SSH shell/PT Y exclusivo e temporário**, com o mesmo host verificado e a mesma sessão autenticada. Não injeta comandos no terminal principal.
3. O canal shell executa um comando fixo `myct-vault unlock` e aguarda o prompt literal emitido depois que o helper remoto desativa o eco do TTY. O campo de senha **só é habilitado depois desse prompt**.
4. A senha digitada pelo humano é enviada apenas à entrada do PTY temporário pelo transporte SSH, seguida de uma quebra de linha. Não passa pela linha de comando, argumento remoto, variável de ambiente, SFTP, API HTTP nem Core. O resultado do helper é interpretado por marcador de término; saída textual do PTY não é exibida nem logada.
5. O canal é fechado ao terminar ou cancelar, e quando SSH é desconectado/trocado. Depois de um resultado, o app consulta `status` novamente; não presume sucesso só porque escreveu a senha.

**Importante:** o helper remoto preserva sua exigência de sessão SSH com terminal interativo e shell de usuário. Se o `ChannelShell` criado pelo JSch não satisfizer os critérios logind/TTY, **a operação deve falhar**, sem relaxar esses critérios ou recorrer a pipe/password argv. Isso é um gate físico específico desta fase.

O usuário já validou `myct-vault status/unlock` em SSH direto pelo Termux/Fedora e `gh auth status` via keyring. Isso **não é a mesma coisa** que validar o novo desbloqueio pela UI do My-CT.

## Arquivos

- `android/app/src/main/java/dev/myct/Vault.kt`: contrato de estado, transporte de dados e sessão shell temporária.
- `android/app/src/main/java/dev/myct/VaultModel.kt`: fases, atualização manual, cancelamento e ciclo SSH.
- `android/app/src/main/java/dev/myct/VaultScreen.kt`: quarta aba, senha efêmera e feedback.
- `SshConnection.kt`, `TerminalModel.kt`, `MainActivity.kt`: reutilizam SSH, preservam Terminal/Arquivos/Saúde, ativam `FLAG_SECURE` na aba Cofre.
- `android/app/src/test/java/dev/myct/VaultTest.kt`: parsing sintético de metadados inválidos/validos.
- `android/app/build.gradle.kts`: versão `0.4.0-vault`, `versionCode=6`.

## Instruções ao agente implementador/validador

- Sincronizar branch `myct-4b-android-vault` sem reverter commits da Luna.
- Verificar compilação e lint com Android SDK/Java existentes: `./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain`; corrigir apenas erros reais. Não introduzir dependências novas.
- Acrescentar, se necessário, teste sintético pequeno de protocolo/fechamento de PTY e de reconexão, sem acessar credenciais pessoais.
- Gerar `artifacts/myct-4b-debug.apk`, checksum SHA-256 e validar que a assinatura coincide com o APK 3C instalado, para atualizar sem desinstalar. A assinatura e APK não devem ser versionados.
- Reportar limitações e código final, sem alegar `unlock` físico sem teste no Moto.

## Roteiro físico mínimo no Moto G06

1. Confirmar `myct-vault` instalado em `~/.local/bin/myct-vault` no Fedora. O Core systemd e banco não participam da operação. Preparar o cofre **bloqueado** de forma normal (por exemplo, primeiro teste após reiniciar o Fedora); **não forçar logout, lock, daemon --replace ou recriação de coleção apenas para simular estado**.
2. Instalar o APK sobre o 3C (mesma assinatura). Conectar ao SSH, abrir Cofre, conferir **Bloqueado** ou **Desbloqueado** conforme estado verdadeiro. Estado indisponível deve gerar mensagem clara; a tela de Saúde permanece funcional.
3. Se estiver bloqueado/compatível, tocar Desbloquear e verificar que o campo aparece apenas após o prompt seguro. Digitar a senha correta no aparelho, tocar Confirmar e verificar **Desbloqueado** por consulta real. **Nunca compartilhar senha, screen recording ou logs de PTY com IA**.
4. Alternar Terminal (executar `pwd`), Arquivos (navegar) e Saúde, preservando conexão; desconectar SSH e confirmar que o Cofre mostra ausência de conexão. Reconectar e consultar novamente.
5. Conferir no Fedora com `~/.local/bin/myct-vault status` se desejado. Não testar senha errada no cofre pessoal para satisfazer números de testes; simular erros nos testes JVM.
6. Se o app mostrar erro de admissão TTY/logind, registrar **somente um código seguro de erro**, sem capturar senha/PTY, e interromper. Não enfraquecer o `myct-vault` para forçar o sucesso.

## Pendências e riscos assumidos

- API GNOME interna previamente auditada e pinada a versão específica: alterações do GNOME podem interromper compatibilidade; não substituir cofre.
- Um processo com o mesmo UID Linux pode acessar itens desbloqueados conforme regras da coleção; o desbloqueio não cria permissões por agente.
- Não existe bloqueio remoto do cofre neste recorte. Não implementar auto-unlock, exportação de segredos, listagem ou intermediário para Copilot CLI.
- Validar consumo do APK no Moto apenas se houver problema perceptível. Refinamento antigo de lag/UI/SFTP continua adiado na issue #9.


## Revisão Codex — 15/10/2026

Branch sincronizada de `origin/myct-4b-android-vault` em
`4ef3faedf0f110485c7ca8b1a7029706ccf74b6a`, preservando a implementação da Luna.
Base conferida: `myct-4-boot-and-vault`, merge-base
`e46a0d4e2fb3c92033891e8b9bdc992a559241d6`. PR #14 aberto em draft, sem merge.

### Revisão de segurança e correções identificadas

Revisados `Vault.kt`, `VaultModel.kt`, `VaultScreen.kt`, `SshConnection.kt`,
`TerminalModel.kt`, `MainActivity.kt` e o contrato de `ops/myct-vault`.
A autenticação/pin SSH não foi alterada; Cofre usa a mesma sessão autenticada.
Consulta permanece `exec` somente para `status`; desbloqueio permanece
`ChannelShell` com PTY próprio. Não escreve no `TerminalModel`/PTY principal,
não transporta senha por argv, variável de ambiente, HTTP ou SFTP.

Correções limitadas aos problemas encontrados:

1. **Guarda do prompt:** `submitSecret` agora exige ter observado o prompt exato,
   em vez de apenas uma tentativa iniciada. Um marcador aleatório por tentativa
   delimita a execução, ignorando prompt antigo em banner/eco do comando. O
   marcador de término usa o mesmo identificador. O comando continua fixo e não
   contém senha. Isso evita confusão acidental de saída; o servidor/helper remoto
   autenticado continua sendo parte da confiança, não uma prova criptográfica de
   presença humana independente do SSH.
2. **Shell após término:** o shell temporário sai após emitir o marcador de
   término do helper, inclusive em recusa. Uma senha enviada tarde não deve ser
   interpretada como um comando seguinte nesse shell. Não há nova tentativa
   automática após escrita parcial.
3. **Entrada PTY:** o helper usa modo canônico; a versão candidata aceitava
   controles (incluindo Ctrl-C/EOF/DEL) e até 8192 bytes, sujeitos a sinais,
   edição/truncamento. Agora o Android recusa controles C0/DEL e mais de **4095
   bytes UTF-8** antes de escrever, sem transformar a senha, com mensagem própria
   na tela. Senhas afetadas exigem diagnóstico humano no terminal privado;
   não tentar contornar a recusa pelo app. Caracteres UTF-8 imprimíveis continuam
   aceitos. Referência: [Linux termios](https://man7.org/linux/man-pages/man3/termios.3.html).
4. **Cancelamento:** uma tentativa cancelada fecha um canal que termine de abrir
   tarde; não publica esse canal numa sessão nova. Leitura/conexão em IO é
   interrompível, com limite de 15 s por consulta/preparação/conclusão. A espera
   por digitação humana não tem envio nem repetição automáticos. Cancelar aparece
   também na preparação/conclusão. Cancelar/fechar o PTY **não relocka** o cofre
   nem desfaz um desbloqueio que já ocorreu.
5. **Senha efêmera:** limpar campo ao mudar de fase, desconectar, sair da aba ou
   receber ON_STOP; cancelar tentativa ao ocultar a tela. Codificar/zerar o
   `CharArray` antes de lançar o worker, e zerar bytes mesmo se o escopo já estiver
   cancelado antes de executar. Strings da UI/IME e buffers internos de SSH/JVM
   não permitem garantia de zeroização forense de todas as cópias.
6. **Build:** primeira compilação detectou função pública expondo retorno
   `internal VaultUnlockChannel`. `openVaultUnlock()` passou a ser `internal`;
   nenhuma alteração na autenticação ou nos outros canais.

Nenhuma alteração em `ops/myct-vault`: preservados `require_human_tty`, admissão
logind/SSH, shell direto/foreground, permissões TTY, checagem do daemon/interface,
memória protegida, leitura sem eco e ausência de leitura/exportação de itens.
Também não foram modificados systemd, SSHD, GNOME Keyring, PostgreSQL ou Core.

### Hipóteses, testes mínimos e limites

- Hipótese: linha canônica pode editar/truncar o segredo antes do helper.
  Prova local **somente com PTY e dados sintéticos**, sem executar myct-vault ou
  acessar D-Bus/Keyring: `abc + DEL + def + newline` entregou `abdef + newline`;
  4096 bytes mais newline entregaram apenas 4096 bytes (4095 de payload).
- Protocolo testado por streams sintéticos: senha ausente antes do prompt,
  prompt antigo/marker incorreto, helper já desbloqueado/recusado, resultado
  ausente/incorreto, escrita única e recusa de controles/tamanho sem escrita.
- Ciclo testado com IO sintético bloqueado: desconexão durante leitura,
  canal retornando tarde após cancelamento, reconexão sem resultado antigo,
  consulta posterior ao sucesso e buffers apagados mesmo com escopo cancelado.
- `adb devices -l`: **lista vazia**. Nenhum APK instalado, senha pessoal acessada,
  comando `unlock` real executado ou cofre pessoal alterado nesta revisão.
- `python android/terminal/upstream/verify.py`: **21 arquivos verificados,
  2 patches documentados**. `git diff --check` sem erros.
- Java 21.0.10, SDK/Build Tools 36 existentes; sem instalações ou dependências
  novas. RAM/CPU do APK e legibilidade no Moto ainda não medidas.

O gate do segundo PTY JSch satisfazer logind/TTY do helper continua **pendente**.
Se houver recusa, a interface mostra erro sem enviar senha antes do prompt; não
relaxar o helper para obter sucesso. O estado consultado via GNOME real e a
coexistência das quatro abas também dependem do teste do proprietário.

- Falha proporcional adicional: o teste já existente de Saúde falhou uma vez em
  rebind imediato (`BindException`) após remover o encaminhamento, sem alteração
  do transporte Saúde. Hipótese registrada: liberação do socket pode coincidir
  com término da thread de accept JSch. Prova isolada com o jar existente, sem
  autenticação/rede remota/Keyring: 50 ciclos iniciais sem falha; 200 ciclos com
  leitura de `/proc/net/tcp6` reproduziram 5 atrasos de bind, todos liberados na
  checagem seguinte após 50 ms. Ajustado **somente o teste** `HealthTest.kt` para
  exigir reocupação efetiva em até 1 s, a cada 10 ms, mantendo as verificações de
  bind loopback e remoção do registro. Sem mudança no código de Saúde.


### Resultado final Android e APK para o proprietário

```sh
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
```

**BUILD SUCCESSFUL**, última execução em **35 s**. **48 testes: 47 aprovados,
0 falhas/erros e 1 ignorado** (handshake SSH opcional sem
`MYCT_TEST_LOCAL_SSH=1`). Cofre: 3 testes de parsing existentes e 10 novos de
protocolo/modelo, todos aprovados. Lint: **0 erros e 6 avisos de versões de
Gradle/dependências**; não foram atualizados pacotes. Uma execução anterior
emitiu aviso de metaspace do daemon; build concluído, limites mantidos.
Relatórios locais: `android/app/build/reports/tests/testDebugUnitTest/` e
`android/app/build/reports/lint-results-debug.html`.

APK: `artifacts/myct-4b-debug.apk` (**13,404,141 bytes**),
versão **0.4.0-vault**, code **6**. SHA-256:
`971f3c8b2644c93a83f4bc78fa4aaebacc7d4dea56aecdf6a624a01e91fa6597`.
Checksum ao lado: `artifacts/myct-4b-debug.apk.sha256`.
Artefatos fora do Git, sem keystore versionada.

`apksigner verify --print-certs` validou os APKs MYCT-4B e MYCT-3C e confirmou
certificado SHA-256 idêntico:
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Pode atualizar o 3C sem desinstalar ou limpar pins/dados. Instalação permanece
manual; exemplo somente com ADB autorizado:
`adb install -r artifacts/myct-4b-debug.apk`.

**Retomada:** seguir o roteiro físico acima no Moto. Se a coleção já estiver
desbloqueada, validar a consulta, mas manter o envio de senha pendente até um
estado bloqueado natural; não forçar lock/reboot/logout para fabricar evidência.
Conferir cancelamento antes de digitar, retorno do segundo plano com campo
vazio e senha ausente do histórico do terminal principal. Não capturar a senha
em screenshots/logs nem compartilhá-la com agentes. Falha de TTY/logind é
bloqueio a diagnosticar, não autorização para enfraquecer o helper.


## Prova física pós-reboot e diagnóstico de desbloqueio — 15/10/2026

O proprietário instalou `myct-4b-debug.apk` e executou `sudo systemctl reboot` no Fedora. **Comprovou Core systemd após boot:** a aba Saúde voltou a consultar telemetria sem precisar executar `./myct-core`. **Comprovou a consulta GNOME real pelo Android:** a aba Cofre identificou corretamente a coleção `login` bloqueada após o boot.

**Bloqueio atual:** ao tocar **Desbloquear**, o Android exibiu a mensagem genérica de erro antes de qualquer sucesso. Não houve comprovação de que a senha foi solicitada, escrita ou rejeitada. A hipótese é recusa do terminal SSH secundário pelas regras TTY/logind de `myct-vault`, mas falha de protocolo/abertura do canal também é possível. Não afrouxar o helper, nem pedir senha/log PTY à IA.

**Hotfix de diagnóstico publicado na branch após o APK 1429ece:** `VaultUnlockChannel` extrai exclusivamente o campo `error_code` do JSON de recusa já sanitizado pelo `myct-vault`, e `VaultModel` mostra código seguro na aba. Erros de timeout e de canal/protocolo ganham códigos distintos. Nenhum stdout/stderr bruto, senha ou item do cofre aparece na interface. Um teste sintético verifica a recusa antes do prompt e que nenhum byte de senha é escrito.

**Status:** hotfix apenas em fonte. O APK instalado no Moto é anterior ao diagnóstico. Necessário o Codex sincronizar esta branch, rodar `assembleDebug/testDebugUnitTest/lintDebug` (sem substituir testes existentes), gerar **novo** `artifacts/myct-4b-debug.apk` e checksum, conferir assinatura, publicar PR draft **sem merge**. Reinstalar como atualização, conectar SSH e tocar Desbloquear **uma vez sem digitar senha**; anotar exclusivamente o código mostrado. Se for `authenticated_ssh_session_required`, `interactive_ssh_shell_required` ou outro, corrigir a causa concreta sem desativar verificações do cofre. Se for `ssh_channel_or_protocol_error` ou `ssh_prompt_timeout`, examinar especificamente protocolo/PTY, ainda sem lidar com senha.

Não é necessário reiniciar Fedora novamente para o diagnóstico enquanto o cofre continuar bloqueado.
