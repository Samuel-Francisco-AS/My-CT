# MYCT-4 / MYCT-4B — relatório de fechamento

**Data:** 15/10/2026  
**Estado:** concluído e integrado à `main`, com aceite funcional no Fedora 44 headless e Moto G06.  
**Repositório:** `Samuel-Francisco-AS/My-CT`  
**Integrações:** [PR #13](https://github.com/Samuel-Francisco-AS/My-CT/pull/13) (merge `3bda937c232ddbd79fb0b50d1150c07222ba9709`) e [PR #14](https://github.com/Samuel-Francisco-AS/My-CT/pull/14) (merge `f9d2a6b9f254e9c623c8486a4c1c3c0e49121f4c`).

## Resultado entregue e aceito

1. **Core como serviço systemd de sistema.** `myct-core.service` inicia `/usr/local/bin/myct-core` como `sam`, com reinício em falhas, sem sessão SSH permanente. Corrigido erro `203/EXEC Permission denied` da tentativa de executar diretamente sob `/home`; binário instalado em caminho do sistema e contexto SELinux restaurado. Após `sudo systemctl reboot`, proprietário comprovou que o Dashboard Saúde voltou a funcionar **sem executar `./myct-core`**. API continua limitada a `127.0.0.1:8765`, consumida via túnel SSH do aplicativo. PostgreSQL existente não precisa iniciar para o Dashboard e não foi alterado.
2. **Helper independente `myct-vault`.** Comandos `status` e `unlock` no `~/.local/bin/myct-vault` para a coleção `login` **já existente** do GNOME Keyring, sem criar outra coleção nem ler/exportar itens. Desbloqueio humano, não automático; compatibilidade GNOME/daemon pré-existente verificada. Execução independente da Narys. Originalmente validado com SSH interativo privado e `gh auth status` via keyring.
3. **Aba Cofre no Android.** Quarta aba **Terminal | Arquivos | Saúde | Cofre**. Consulta estado locked/unlocked/unavailable sob SSH, sem polling. Botão **Desbloquear** cria canal shell/PT Y independente para o prompt; senha digitada exclusivamente pelo humano no Android após prompt remoto, jamais em argv, URL, variáveis, Core ou terminal principal. Tela protege contra capturas durante o cofre, campo temporário é descartado e bytes mutáveis são zerados dentro dos limites da memória Android/JVM; canal fecha em cancelamento. Após sucesso, consulta novamente estado real.
4. **Correção real de admissão SSH de PTY secundário.** Após o reboot, o app mostrava locked corretamente, mas o botão recebeu `authenticated_ssh_session_required` antes da senha. Diagnóstico seguro no app permitiu localizar o bloqueio; helper passou a aceitar PTY adicional quando `logind` ainda atesta sessão remota `sshd`, mesmo UID, sessão `tty/user` e o kernel comprova que o shell pai usa o mesmo dispositivo de caracteres. Mantidas checagens de propriedade/foreground/ambiente e backend GNOME. Não desativado SSH, logind, SELinux ou firewall.
5. **Validação completa do objetivo.** Proprietário confirmou testes Python passando, **desbloqueio real pela aba Cofre no Moto G06** e, depois, **Copilot CLI autenticando com as credenciais existentes**. O GitHub CLI já havia confirmado acesso pelo keyring em etapa anterior. Nenhuma senha ou token foi transmitido ao assistente.

## Evidências e testes

- Fedora: `make -C server test` = **3/3 PASS** antes da ativação; testes de cofre = 3/3 inicialmente, posteriormente 6/6 (confirmação do proprietário após consertar asserção obsoleta, sem nova transcrição completa); consulta `myct-vault status` real retornou `locked` e backend compatível antes do aceite Android.
- Android: compilação `assembleDebug`, `testDebugUnitTest`, `lintDebug` **aprovada pelo Codex**; no último APK de diagnóstico, **48 testes PASS, 1 opcional ignorado**, lint 0 erros/6 avisos de versões. Certificado compatível com APK MYCT-3C, instalação incremental no Moto. Os resultados de build vieram de execução local Codex, não executados pelo assistente remoto.
- Campo físico: **Dashboard após reboot**, **consulta de locked após reboot**, **unlock pelo botão** e **uso posterior no Copilot CLI** confirmados pelo proprietário. O teste de background SFTP continua fora deste aceite.

## Operação cotidiana após reboot

O Core inicia automaticamente: não usar `./myct-core` em paralelo, evitando conflito na porta 8765. Conectar My-CT Android via SSH autenticado; Saúde funciona sem desbloquear o cofre. Na aba Cofre, consultar estado e, se bloqueado, tocar **Desbloquear** e digitar a senha somente no campo do app. A coleção só precisa ser desbloqueada sob demanda; o serviço systemd não recebe a senha. Estado também consultável por `~/.local/bin/myct-vault status`. O mesmo usuário Linux poderá então usar as credenciais existentes conforme as regras do Secret Service.

Para checagem operacional:
```sh
systemctl is-active myct-core.service
curl -fsS http://127.0.0.1:8765/v1/health
~/.local/bin/myct-vault status
```

O repositório guarda **fonte e documentação**, não a senha, tokens, banco PostgreSQL privado, keystore ou APK gerado. Como o executável está instalado em `/usr/local/bin/myct-core`, recompilar o checkout não o atualiza automaticamente; é necessário instalar a nova versão e reiniciar serviço em atualização futura. O mesmo ocorre com `myct-vault` em `~/.local/bin`.

## Limites e dívidas posteriores (não bloqueiam MYCT-4)

- O mecanismo de `UnlockWithMasterPassword` usa interface interna **não suportada** do GNOME Keyring 50 com hash do daemon fixado; uma atualização poderá exigir revisão de compatibilidade. Não retirar o pin às cegas.
- O cofre desbloqueado **não dá permissões granulares por agente**: processos do mesmo UID podem acessar itens conforme Secret Service. O app não desbloqueia automaticamente ao boot e não exporta segredos.
- **UX Android / desempenho:** lag perceptível no terminal, interação truncada, restauração de posição da lista de arquivos ao voltar de pasta, e validação de transferência em segundo plano ainda pendentes. Manter na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9), como trilha separada; **não iniciar refinamento automaticamente**.
- Downloads HTTP(S), torrent/magnet e rotinas autônomas servidor-side **ainda não foram implementados**. PostgreSQL já validado em fase 3B permanece independente; não iniciá-lo só por causa de Saúde.
- Testes exaustivos de ciclos de cancelamento/IME/background e medições de lag do Android podem integrar refinamento se houver necessidade, não anulam aceite de unlock.

## Registros relacionados e Git

- [MYCT-4 Core e helper](MYCT-4-BOOT-VAULT.md): implementação, problema `203/EXEC` e operação.
- [MYCT-4B Cofre Android](MYCT-4B-ANDROID-VAULT.md): código, protocolo, testes, falha de SSH/logind e aceite físico.
- [MYCT-3C Dashboard](MYCT-3C-DASHBOARD-REPORT.md): base de telemetria Android previamente aceita.
- [Guia de retomada](10-RETOMADA-PROJETO.md): estado atual após integração.
- [Issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9): refinamentos não bloqueantes.

Os PRs #13 e #14 foram efetivamente mesclados, **na ordem** #13 → #14, sem reescrever histórico. Branches locais podem ser excluídas pelo proprietário após `git pull --ff-only origin main`; a instalação Fedora e dados pessoais devem ser preservados. Nenhum comando de exclusão deve tocar o diretório do PostgreSQL, cofre ou APK.
