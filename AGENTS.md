# AGENTS.md — colaboração segura no My-CT

Este arquivo orienta agentes de programação e colaboradores. Para contexto atualizado, leia [README.md](README.md), [STATUS.md](docs/STATUS.md), [ARCHITECTURE.md](docs/ARCHITECTURE.md), [CONTRIBUTING.md](CONTRIBUTING.md) e [SECURITY.md](SECURITY.md). O histórico técnico anterior permanece no [índice](docs/HISTORY.md), e a versão antiga deste arquivo está preservada [no Git](https://github.com/Samuel-Francisco-AS/My-CT/blob/c3023973800ba5a7dad907ccf6265d405c8f5523/AGENTS.md).

## Mandato

Criar utilidade real para Android + Fedora headless, com **autonomia do usuário**, baixo consumo de recursos e manutenção viável. Não presumir que o código do repositório esteja implantado no Fedora pessoal do mantenedor.

## Arquitetura e escopo

- Cliente Android: Kotlin e Jetpack Compose, terminal SSH/SFTP real, IDE Sora, terminal local PTY/SAF e desktop RFB via túnel.
- Fedora: Core C++20, PostgreSQL via libpq e CLIs Python/C++ independentes, com serviços externos opcionais (Transmission, Prowlarr, Rclone, Jellyfin, ClamAV e TigerVNC).
- Não implementar criptografia, SSH, BitTorrent ou servidor HTTP completo do zero sem necessidade concreta.
- Não criar dependência obrigatória de agentes de IA, serviços comerciais, containers pesados ou ferramentas invasivas.
- Preserve Termux e Sora e suas licenças; veja [LICENSING.md](docs/LICENSING.md).

## Limites de autoridade e segurança

- **Nunca** expor ou registrar senhas, tokens, chaves privadas, backups, dados privados ou material de autenticação.
- **Nunca** modificar serviços instalados, firewall, SELinux, SSH, sudoers, contas, banco, rede, dispositivos ou dados reais sem autorização explícita.
- **Nunca** usar reset, formatação, force-push ou publicação automática de release como solução conveniente.
- Testes destrutivos somente com fixtures sintéticas. Não tocar no acervo pessoal para reproduzir falhas.
- Evite abrir VNC/RPC/PostgreSQL/Core na Internet. SSH com host key verificada e privilégios mínimos.
- O modo Desktop pode encerrar programas em execução; respeite o procedimento administrativo separado em [ops/desktop/README.md](ops/desktop/README.md).
- Não presumir que um achado de segurança autorize nova validação obrigatória, bloqueios de exclusão ou regressões de UX. Exemplo: [issue #65](https://github.com/Samuel-Francisco-AS/My-CT/issues/65), risco conhecido e aceito pelo mantenedor.

## Como trabalhar

1. Confira o estado de `main`, as branches relacionadas, documentação e contrato da funcionalidade antes de editar.
2. Prefira mudança mínima e revisável; apresente alternativas e custos quando houver trade-offs, especialmente de latência, CPU, RAM e autonomia.
3. Para funcionalidades relevantes, registre critérios de aceite, testes, resultado e limitações antes de afirmar sucesso.
4. Diferencie **compilação**, **testes automatizados**, **aceite físico**, **integração Git**, **instalação pessoal** e **release publicada**; um não implica outro.
5. Use branches e PRs para mudanças; sem merge ou deploy implícito fora de autorização específica.
6. Registre comandos de validação proporcionais e informativos; não execute scripts com privilégios ou efeitos desconhecidos.
7. Atualize [STATUS.md](docs/STATUS.md), [índice](docs/README.md) e documentação específica ao mudar funcionalidades. Preserve os relatos históricos como evidência.

## Referência técnica rápida

- Android e testes: [android/README.md](android/README.md).
- Core: [server/README.md](server/README.md).
- Desenvolvimento: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
- Operação: [docs/OPERATIONS.md](docs/OPERATIONS.md).
- SETUP: [docs/MYCT-SETUP-INTEGRATION-RELATORIO.md](docs/MYCT-SETUP-INTEGRATION-RELATORIO.md); Fedora físico limpo ainda sem validação completa.
- Desktop: [docs/MYCT-DESKTOP-FECHAMENTO.md](docs/MYCT-DESKTOP-FECHAMENTO.md).
- Licença original Apache-2.0; materiais externos com licenças próprias. Consulte [LICENSE](LICENSE), [NOTICE](NOTICE) e [docs/LICENSING.md](docs/LICENSING.md).

**Definição de pronto:** entrega implementada e validada no escopo explicitamente descrito, com limitações claras e possibilidade de manutenção pelo usuário sem depender de um agente específico.
