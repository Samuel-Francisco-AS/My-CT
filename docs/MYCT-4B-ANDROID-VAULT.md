# MYCT-4B — aba Cofre no Android (implementação candidata)

**Estado (15/10/2026):** implementação publicada na branch `myct-4b-android-vault`, baseada em `myct-4-boot-and-vault` (PR #13). **Build/testes Android e execução física no Moto G06 ainda não foram realizados para esta branch.** Não declarar aceite nem fazer merge até validar. PostgreSQL e Core não precisam ser modificados.

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
