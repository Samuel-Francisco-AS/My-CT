# MYCT-4 — operações do Core e cofre GNOME Keyring

**Estado: cofre aprovado no Fedora real; systemd instalado mas em correção após erro `203/EXEC`.** As duas capacidades são independentes. Não mexer nos dados do PostgreSQL nem nos serviços da Narys.

## A. Core automático pelo systemd

Unidade systemd de sistema: `ops/systemd/myct-core.service`. Executa **somente o Core** como `sam` a partir do binário **instalado em `/usr/local/bin/myct-core`** (não diretamente do checkout sob `/home`), sem sudo no processo, com `Restart=on-failure`. Inicia no boot (`multi-user.target`) sem precisar manter SSH, Termux ou My-CT Android abertos. **Não inicia PostgreSQL**; `/v1/health` não depende do banco. API continua bind apenas em `127.0.0.1:8765` por código.

Os caminhos/usuário da unidade são específicos do Fedora de desenvolvimento; conferir antes de instalar. O proprietário autorizou a configuração e já tentou a instalação inicial, mas ela falhou por `Permission denied`/`203/EXEC` após apontar para o binário em `/home`, com SELinux Enforcing. A hipótese mais forte é bloqueio SELinux, ainda sem AVC confirmado. A versão corrigida usa `/usr/local/bin`, aplica `restorecon` e evita o loop em falhas `203/EXEC`. O proprietário executa os comandos privilegiados apenas pelo terminal pessoal.

1. Atualize a branch no Fedora e **termine com Ctrl-C o Core manual** que estiver escutando a porta 8765. Confira `ss -lntp 'sport = :8765'` antes de iniciar um segundo processo.
2. Compile e rode os testes existentes antes de ativar (não instalar nada automaticamente):

```sh
cd ~/Projetos/My-CT
make -C server
make -C server test
```

3. Em terminal SSH direto, se o serviço antigo estiver no loop de falhas, **pare-o primeiro**. Atualize a branch, instale o **binário compilado e a unidade corrigida** fora do `/home`; aplique o contexto SELinux conforme a política do Fedora (sem desativá-la). A senha sudo é digitada somente pelo proprietário:

```sh
sudo systemctl stop myct-core.service
cd ~/Projetos/My-CT
git pull --ff-only origin myct-4-boot-and-vault
sudo install -m 0755 server/myct-core /usr/local/bin/myct-core
sudo restorecon -v /usr/local/bin/myct-core
sudo install -m 0644 ops/systemd/myct-core.service /etc/systemd/system/myct-core.service
sudo restorecon -v /etc/systemd/system/myct-core.service
sudo systemctl daemon-reload
sudo systemctl reset-failed myct-core.service
sudo systemctl restart myct-core.service
systemctl is-enabled myct-core.service
systemctl is-active myct-core.service
curl -fsS http://127.0.0.1:8765/v1/health
ss -lntp 'sport = :8765'
```

A versão instalada não é automaticamente substituída quando se compila dentro do checkout. Para atualizar o Core depois, compilar, parar serviço, instalar binário novo com `install`, `restorecon` e reiniciar o serviço. Uma unidade habilitada que está falhando **não equivale a serviço funcional**. Se persistir `203/EXEC`, consultar `systemctl status`, `journalctl -u myct-core.service` e os AVCs SELinux antes de alterar algo mais.

Depois de desconectar/reconectar o SSH, consultar o endpoint sem iniciar manualmente o Core. Verificar logs com `journalctl -u myct-core.service -n 30 --no-pager`. Para comprovar **reboot** posteriormente, o proprietário decide quando reiniciar o Fedora; não reiniciar remotamente sem coordenação. O serviço deve continuar após logout pela unidade **de sistema**, sem usar `loginctl enable-linger`.

Parar/desabilitar sem apagar dados:

```sh
sudo systemctl disable --now myct-core.service
```

Rollback da instalação (somente se solicitado):

```sh
sudo rm /etc/systemd/system/myct-core.service
sudo systemctl daemon-reload
```

Se houver erro, manter serviço desativado e registrar o resultado; não alterar SSH, firewall, banco, Narys ou outras portas para contornar falhas. Ao reconstruir o binário do Core, reiniciar o serviço somente após confirmar a versão desejada, evitando sobrescrever executável em execução inadvertidamente.

## B. Cofre independente da Narys

**O cofre existente é a coleção `login` do GNOME Keyring (Secret Service)**. Acesso de usuário acontece no bus D-Bus do próprio `sam`. O My-CT **não** cria novo cofre, não migra dados, não lê itens ou exporta valores, não executa comandos GitHub/Copilot em nome de outro usuário. A operação humana de desbloqueio é separada do Core systemd.

`ops/myct-vault` oferece duas operações:

- `myct-vault status` — consulta somente existência/Locked e compatibilidade de backend (JSON); não imprime valores secretos.
- `myct-vault unlock` — comando **manual**, lê senha do cofre em **SSH interativo direto**, sem eco e sem permitir pipes, tmux, shell -c ou invocação de agente comum. Não guarda senha em argv, ambiente, arquivo, IPC My-CT ou logs. Não é endpoint HTTP nem ação da IA.

O módulo é uma **adaptação independente**, incorporada no My-CT, do adaptador `narys-core/ops/credential_manager.py` previamente validado com GNOME Keyring 50. Isso **não cria dependência de execução da Narys**. Conserva validação de sessão logind SSH, dono do serviço D-Bus, hash fixo do `gnome-keyring-daemon` auditado, entrada de senha via buffer nativo bloqueado em memória, sessão libsecret cifrada, recusa sem criar coleções. Usa interface GNOME interna **não suportada**: atualizações do daemon podem exigir nova auditoria de compatibilidade. Se houver recusa, **não desabilitar o pin ou substituir o daemon** sem revisão.

### Instalação do comando no usuário

Não requer root nem habilita serviços novos. Use a branch atualizada:

```sh
cd ~/Projetos/My-CT
python3 -m unittest discover -s ops/tests -v
mkdir -p "$HOME/.local/bin"
install -m 0700 ops/myct-vault "$HOME/.local/bin/myct-vault"
"$HOME/.local/bin/myct-vault" status
```

Se indicar `locked` e `unlock_backend_compatible: true`, executar **somente em terminal SSH direto e privado, fora de tmux e fora de shell comandado por Codex/Copilot**:

```sh
"$HOME/.local/bin/myct-vault" unlock
"$HOME/.local/bin/myct-vault" status
gh auth status
```

A senha deve ser digitada **somente no prompt sem eco do terminal**, jamais enviada à IA. `gh auth status` pode confirmar credencial já salva sem revelá-la; o comando não é parte do desbloqueador. O Copilot CLI deve ser testado **depois**, pelo próprio usuário. Desbloquear `login` **não garante** que Copilot usa a mesma coleção nem que está devidamente autenticado.

Se `status` retornar unavailable ou backend incompatible, parar e registrar apenas o **código de erro sanitizado**, sem fornecer `login.keyring`, segredos, tokens, senha ou registros internos. Não tentar `gnome-keyring-daemon --unlock`, `--replace`, inicializar coleção ou trocar senha automaticamente: isso pode criar/substituir estado existente. Sem GUI, o daemon Secret Service precisa já estar disponível no user manager; o helper não o ativa para evitar tocar Narys ou dados existentes.

### Limites de segurança relevantes

Esta ferramenta não cria isolamento de segurança entre **processos com o mesmo UID**: depois de desbloquear o `login`, aplicativos daquele usuário podem acessar itens conforme regras do Secret Service. O comando impede fornecer a senha diretamente a agentes e rejeita execução automatizada comum; não é prova criptográfica de presença humana contra um processo malicioso do mesmo usuário com acesso real ao SSH/TTY.

O desbloqueio não acontece no boot automaticamente: precisa de ação humana após reinício. **O Core systemd não chama o cofre** e o My-CT Android não solicita essa senha pelo Dashboard. Esta entrega não implementa concessões granulares de itens nem novo gerenciador de credenciais para agentes — caso necessárias, devem ser uma etapa distinta.

## Evidência exigida e fechamento

- Core inicia via systemd como `sam`, acessível pelo Dashboard sem `./myct-core`; permanece depois de desconectar SSH; bind somente `127.0.0.1`. Prova de reboot se/quando executada.
- Testes offline do `myct-vault` e `status` no Fedora; `unlock` humano pessoal **somente pelo proprietário**, sem registro de senha. Estado consultado pós-unlock. `gh auth status` e tentativa Copilot para aferir necessidade real.
- Registrar o que foi comprovado, não alegar instalação pelo merge/PR. Banco existente e credenciais não são alterados.


## Testes reais recebidos do Fedora — 15/10/2026

- `make -C server test`: **3/3 PASS**; `python3 -m unittest discover -s ops/tests -v`: **3/3 PASS**.
- `myct-vault status` pré-unlock: `service_available=true, state=locked, unlock_backend_compatible=true`.
- `myct-vault unlock` executado pelo proprietário em SSH privado: desbloqueio confirmado; status posterior `state=unlocked`.
- `gh auth status`: autenticado via `keyring`; saída mascarou token. Ainda não há prova específica do **Copilot CLI**.
- Unidade systemd original instalada/habilitada, mas `systemctl is-active` foi `activating`, `curl` recusou conexão; journal repetiu `status=203/EXEC` e `Permission denied` ao tentar executar `/home/sam/Projetos/My-CT/server/myct-core`. `ls -l` mostrou executável com bit +x, SELinux Enforcing. Houve aproximadamente 70 reinícios. **Corrigido no Git**, substituindo ExecStart por `/usr/local/bin/myct-core` e `RestartPreventExitStatus=203`; ainda precisa de prova no Fedora. Não declarar boot/autostart aprovado antes da prova.
