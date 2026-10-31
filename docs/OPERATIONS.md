# Operação segura no Fedora

Este documento ajuda a diagnosticar uma instalação **existente**. Os comandos de consulta são exemplos; seus serviços, caminhos e permissões podem ser diferentes. **Não faça deploy, migrações, resets nem altere o SSH sem autorização.**

## Atualização de código não é implantação

`git fetch` e `git merge --ff-only` atualizam um **checkout Git**, não `/usr/local/bin/myct-core`, `~/.local/bin/myct-*`, unidades systemd, bibliotecas instaladas, keyring ou bancos. Uma instalação pode estar em versão diferente do repositório. Verifique a versão efetiva do binário antes de decidir substituí-lo.

## Consultas de baixo risco

```bash
# Apenas status; a saída não modifica serviços.
systemctl status myct-core.service --no-pager
systemctl --user status myct-core.service --no-pager

# Logs recentes (pode exigir permissões; remova dados privados antes de compartilhar).
journalctl --user -u myct-core.service -n 30 --no-pager

# Ferramentas locais: verificar disponibilidade, sem executá-las.
command -v myct-torrent || true
command -v myct-media || true

# Recursos da máquina.
free -h
df -h
```

O Core e as CLIs podem usar unidades de sistema **ou** de usuário segundo a fase/instalação. A ausência de uma unidade não justifica criar serviços automaticamente. O serviço de saúde tem histórico em [server/README.md](../server/README.md).

## Componentes e limites

- **SSH/SFTP:** não ignore a verificação da host key; não exponha credenciais e não desligue o host durante uma transferência.
- **PostgreSQL:** cluster dedicado ao projeto, gerenciamento próprio; não reinicializar nem descartar bancos para corrigir falhas de conexão.
- **Downloads/torrents:** Transmission é serviço externo; renomeação de pasta e seeding têm contratos específicos. Exclusões podem impactar arquivos compartilhados e hardlinks.
- **Mídia e Cloud:** mantenha catalogação, hardlinks, backups, recibos e sincronização separados. Revisar prévias antes de exclusões ou operações em Google Drive.
- **Antivírus:** ClamAV é limitado por RAM/virtual memory; resultado incompleto não é arquivo aprovado como limpo.
- **Cofre:** desbloqueio de GNOME Keyring requer intervenção humana, sem persisting senha nem automatizar autenticação.
- **Desktop:** TigerVNC pode funcionar sem helper administrativo; iniciar/encerrar exige helper restrito, instalado manualmente. Desconectar o app **não** encerra programas gráficos. Consulte [guia Desktop](../ops/desktop/README.md).
- **Rede remota:** acesso por rede privada/VPN é distinto de publicar portas de RPC, VNC ou PostgreSQL; não alterar firewall para simplificar testes.

## Quando algo falhar

1. Observe o serviço e colete um **log sanitizado**.
2. Diferencie erro do Android, do SSH, do Core, da CLI, de um serviço externo e do banco.
3. Consulte o [estado vigente](STATUS.md) e o [registro do componente](HISTORY.md).
4. Prefira reproduções em fixtures temporárias e correções reversíveis; não execute comandos destrutivos em dados reais.
5. Para instalar/recuperar a Base, leia [INSTALLATION.md](INSTALLATION.md) e [integração SETUP](MYCT-SETUP-INTEGRATION-RELATORIO.md); implantação em Fedora limpo continua sem validação integral.

## Segurança e recuperação

Relatórios públicos não devem conter tokens, caminhos privados sensíveis, chaves, senhas ou dados de mídia pessoais. Siga [SECURITY.md](../SECURITY.md) para vulnerabilidades e [AGENTS.md](../AGENTS.md) para restrições de operação. Não prometa reversibilidade ou recuperação de dados sem teste de restauração específico.
