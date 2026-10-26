# Desktop :1 — gerenciamento F2, instalação exclusivamente manual

**Não executar estes passos administrativos por um agente sem autorização do
proprietário.** Esta entrega só prepara fontes, binário candidato e instruções.
Nenhum passo abaixo instala/reconfigura TigerVNC/Openbox, abre portas, altera
SELinux/Polkit, habilita a unidade no boot ou inicia/para serviços na instalação.

O My-CT conecta à sessão VNC já ativa **sem este helper**. O helper é necessário
para Iniciar/Encerrar. Desconectar fecha só o cliente/túnel e preserva os programas.
O encerramento administrativo fecha programas gráficos e pode perder trabalho
não salvo; deve ser testado pelo proprietário depois de salvar seus trabalhos.

## Contrato revisado

Binário fixo `/usr/local/libexec/myct-desktop-control`, arquivo regular root:root,
0755, sem setuid/setgid. Unidade constante compilada: `vncserver@:1.service`.
A única entrada aceita é exatamente **um** argumento:

- `status`: consulta readonly `systemctl --no-pager show --value --property=ActiveState`
  da unidade fixa. Também funciona como usuário normal.
- `start` / `stop`: exigem euid 0; executam `systemctl --no-block start|stop` da
  unidade fixa. Exit 0 significa agendado, não concluído.
- Tudo mais, unidades extras ou opções: recusa 64 antes de executar.

Sem shell, argumentos do usuário passados ao systemctl, busca em PATH ou
ambiente de bus/pager herdado. Apenas `/usr/bin/systemctl`, PATH=/usr/bin e LANG=C.
O app usa `sudo -n -N -u root --` com o caminho fixo: não solicita senha nem
atualiza credenciais em cache (`-N`, sudo 1.9.17p2 disponível no Fedora inspecionado).

A prévia do app verifica arquivo/pais root:root sem escrita de grupo/outros,
sem symlinks, e sem setuid/setgid no helper. Confirma `status` sem e com sudo
não interativo e consulta `sudo -l` para os dois comandos; **não executa** start/stop
nessa prévia. Autorização é uma fotografia: expiração de credenciais, alteração
de regra, falha do systemd ou SELinux ainda podem recusar a operação final. O app
revalida antes de alterar e consulta o estado depois de sucesso ou falha.

## 1. Preparar como usuário, sem privilégios

Na raiz do checkout revisado, conferir ferramentas e código:

```bash
command -v g++ systemctl sudo visudo restorecon stat namei sha256sum
sudo --version
mkdir -p artifacts/desktop-helper-v2
g++ -std=c++20 -O2 -Wall -Wextra -Werror \
  -fstack-protector-strong -D_FORTIFY_SOURCE=3 -fPIE -pie \
  -Wl,-z,relro,-z,now ops/desktop/myct-desktop-control.cpp \
  -o artifacts/desktop-helper-v2/myct-desktop-control
sha256sum artifacts/desktop-helper-v2/myct-desktop-control
artifacts/desktop-helper-v2/myct-desktop-control status
visudo -cf ops/desktop/sudoers.example
if [ -d /usr/local/libexec ]; then
  namei -l /usr/local/libexec
else
  namei -l /usr/local
  printf 'libexec ausente: criação manual será necessária no passo autorizado.\n'
fi
```

A consulta não exige sudo nem encerra a sessão. Conferir `sudoers.example`: o
usuário **sam** é o proprietário SSH existente. Se for outro usuário, criar uma
cópia revisada com o nome exato; não criar contas, grupos, wildcards ou sudo ALL.
A regra contém só três comandos exatos, `(root) NOPASSWD: NOSETENV:`.
Nunca autorizar esta documentação, instalador, shell, editor ou compilador na regra.

## 2. Revisão idempotente, backup e instalação autorizada

Passos a seguir são para o proprietário, no seu Terminal. Senhas administrativas
são digitadas pessoalmente, nunca enviadas a agentes ou colocadas em argumentos.

Primeiro verificar os caminhos com `namei -l`: `/`, `/usr`, `/usr/local` e
`/usr/local/libexec` devem ser diretórios regulares root:root sem escrita para
grupo/outros. Se `libexec` ainda não existir, o dono pode criá-lo com
`sudo install -d -o root -g root -m 0755 /usr/local/libexec`. Se qualquer pai for
symlink ou tiver ownership/permissões diferentes, **parar e revisar**, sem
chmod/chown recursivo ou correção automática de diretórios compartilhados.
Não substituir um destino que seja diretório, symlink ou arquivo desconhecido.

Antes de gravar, comparar. Se conteúdos, owner e modos já forem os esperados,
a instalação já está aplicada: **pular as gravações** e ir para Verificar.
Isso evita sobrescrever binário, duplicar regras ou alterar a unidade em reexecuções.

```bash
if sudo cmp -s artifacts/desktop-helper-v2/myct-desktop-control /usr/local/libexec/myct-desktop-control \
   && sudo cmp -s ops/desktop/sudoers.example /etc/sudoers.d/myct-desktop; then
  printf 'Conteúdos já idênticos: conferir modos e pular gravações.\n'
else
  printf 'Instalação ausente/divergente: revisar e criar backup antes de instalar.\n'
fi
for myct_desktop_file in /usr/local/libexec/myct-desktop-control /etc/sudoers.d/myct-desktop; do
  if sudo test -e "$myct_desktop_file"; then
    sudo stat -c '%U:%G %a %n' "$myct_desktop_file"
  else
    printf 'Ausente: %s\n' "$myct_desktop_file"
  fi
done
# Esperado: root:root 755 no helper; root:root 440 na regra.
```

Se a instalação for nova ou uma atualização aprovada, registrar o backup de
cada arquivo anterior, inclusive sua ausência. O diretório abaixo é privado e
dedicado a este procedimento; não é um serviço nem catálogo do acervo.

```bash
sudo install -d -o root -g root -m 0700 /var/lib/myct-desktop-backups
myct_desktop_backup=$(sudo mktemp -d /var/lib/myct-desktop-backups/install.XXXXXXXX)
if sudo test -e /usr/local/libexec/myct-desktop-control; then
  sudo cp -a /usr/local/libexec/myct-desktop-control "$myct_desktop_backup/helper.previous"
else
  sudo touch "$myct_desktop_backup/helper.absent"
fi
if sudo test -e /etc/sudoers.d/myct-desktop; then
  sudo cp -a /etc/sudoers.d/myct-desktop "$myct_desktop_backup/rule.previous"
else
  sudo touch "$myct_desktop_backup/rule.absent"
fi
# Anotar o caminho impresso, para reversão posterior.
printf '%s\n' "$myct_desktop_backup"
```

Instalar usando arquivos temporários root-owned nos respectivos diretórios e
rename. A regra só passa a existir no nome final depois da validação. Não editar
nem sobrescrever `/etc/sudoers` global. O arquivo de staging tem ponto no nome e
não é incluído por `@includedir` enquanto está sendo preparado.

```bash
myct_desktop_staged_bin=$(sudo mktemp /usr/local/libexec/.myct-desktop-control.XXXXXXXX)
sudo install -o root -g root -m 0755 artifacts/desktop-helper-v2/myct-desktop-control "$myct_desktop_staged_bin"
sudo mv -T "$myct_desktop_staged_bin" /usr/local/libexec/myct-desktop-control
sudo restorecon -v /usr/local/libexec/myct-desktop-control

myct_desktop_staged_rule=$(sudo mktemp /etc/sudoers.d/.myct-desktop.XXXXXXXX)
sudo install -o root -g root -m 0440 ops/desktop/sudoers.example "$myct_desktop_staged_rule"
sudo visudo -cf "$myct_desktop_staged_rule"
# Só continuar se visudo retornar sucesso.
sudo mv -T "$myct_desktop_staged_rule" /etc/sudoers.d/myct-desktop
sudo restorecon -v /etc/sudoers.d/myct-desktop
sudo visudo -c
```

Usar a cópia da regra com usuário correto, se diferente de sam. Em qualquer
falha, interromper; não continuar a sequência nem usar start/stop para tentar
consertar a instalação. O helper anterior não suportava `status`: atualização
de binário **e** regra é necessária para a prévia F2. Não habilitar unidade no boot.

## 3. Verificar sem encerrar a sessão

```bash
sudo stat -c '%U:%G %a %n' /usr/local/libexec/myct-desktop-control /etc/sudoers.d/myct-desktop
sudo sha256sum /usr/local/libexec/myct-desktop-control /etc/sudoers.d/myct-desktop
/usr/local/libexec/myct-desktop-control status
sudo -n -N -u root -- /usr/local/libexec/myct-desktop-control status
sudo -n -N -u root -l -- /usr/local/libexec/myct-desktop-control start
sudo -n -N -u root -l -- /usr/local/libexec/myct-desktop-control stop
systemctl show vncserver@:1.service --property=ActiveState,SubState,UnitFileState,MainPID
```

Esses comandos não iniciam/encerram nada; `-l` apenas verifica a permissão. No
My-CT: **Atualizar estado** deve mostrar unidade ativa e gerenciamento autorizado.
Ainda é necessário o aceite real: salvar os programas, Encerrar com confirmação,
esperar “unidade desligada confirmada”, Iniciar, esperar unidade ativa e Conectar.
Não interpretar scheduling/exit0 ou testes de fixture como esse aceite.

Se aparecer recusa SELinux, manter Enforcing, registrar AVC e revisar o contexto
padrão/restauração do arquivo. Não gerar política ampla nem desativar SELinux.

## 4. Reverter somente esta instalação

Primeiro conferir que hashes atuais ainda são os desta instalação (os hashes
registrados no passo anterior). Se o proprietário já atualizou qualquer arquivo,
parar: **não sobrescrever alterações posteriores** com backup antigo.

- Se `rule.previous` existir, restaurá-la com owner/modo/metadados originais,
  validar com `visudo -cf` num staging e publicar por rename. Se `rule.absent`
  existir, remover **somente** `/etc/sudoers.d/myct-desktop` desta instalação.
- Se `helper.previous` existir, restaurar esse arquivo regular preservando os
  metadados originais por staging/rename. Se `helper.absent` existir, remover
  **somente** `/usr/local/libexec/myct-desktop-control` desta instalação.
- Restaurar contextos padrão nos arquivos restaurados e executar `sudo visudo -c`.
  Conservar o backup até confirmar o resultado. Não apagar outros arquivos da
  pasta, dados VNC, credenciais ou trabalhos gráficos.

Reversão/revogação não requer parar serviço, reiniciar Fedora/SSH, mudar target,
remover Openbox ou apagar configurações. A visualização via SSH/VNC continua
independente; comandos administrativos ficam indisponíveis após revogar a regra.

## Testes de desenvolvimento

```bash
python3 -m unittest discover -s ops/tests -p test_desktop.py -v
```

Os testes substituem geteuid/execve no binário de fixture e não controlam serviços.
A única execução real permitida nesta entrega foi `status` como usuário normal.
A instalação e a prova real dos botões pelo proprietário permanecem separadas.
