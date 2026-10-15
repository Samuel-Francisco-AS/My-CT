# AGENTS.md — colaboração segura no My-CT
Estas orientações destinam-se a Codex e outros agentes que trabalhem neste repositório. **Leia README.md e docs/04-PLANEJAMENTO-INAUGURAL.md antes de propor implementação.**

## Estado atual e retomada

**15/10/2026, após PRs #10/#11/#12:** Core C++20, persistência PostgreSQL local e Dashboard Saúde Android com telemetria real por túnel SSH foram integrados à `main` e comprovados no Fedora/Moto G06. PR #12 (merge `fd8076321cd32300e2c6305954adc1b134e26d77`); hotfix de contraste aprovado no Moto. **Novo ponto operacional: Core ainda iniciado manualmente por `./myct-core`, sem serviço systemd, sem autostart/garantia após logout ou reboot.** Não ativar serviços ou modificar sistema sem autorização explícita. PostgreSQL não é necessário para o Dashboard. Consulte [guia de retomada](docs/10-RETOMADA-PROJETO.md), [relatório 3C](docs/MYCT-3C-DASHBOARD-REPORT.md) e [operação Core](server/README.md). O projeto permanece independente da Narys; eventual acesso ao cofre local demanda discussão específica, não entregar credenciais livremente a agentes. Dívidas de lag, UX/scroll e SFTP background continuam adiadas na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).

## MYCT-4: serviço e cofre (trilha ativa, sem implantação ainda)

`ops/systemd/myct-core.service` propõe serviço Core de sistema como `sam`, bind HTTP continua loopback; não iniciar PostgreSQL apenas para Saúde. `ops/myct-vault` é um comando autônomo de desbloqueio **humano** do GNOME Keyring `login` existente, portado do backend anterior da Narys sem depender da Narys em runtime. Não criar cofre/segredos, não imprimir credenciais, não fornecer senha a Copilot/LLM, não alterar daemon GNOME ou relaxar validações. A interface GNOME interna pode falhar após atualização: interromper e pedir revisão. **O Git/PR não instala nem ativa serviços ou CLI no Fedora automaticamente.** O proprietário autorizou a fase, mas operações sudo e digitação de senha pessoal ocorrem apenas no próprio terminal privado. Roteiro de validação e rollback em [docs/MYCT-4-BOOT-VAULT.md](docs/MYCT-4-BOOT-VAULT.md).

## Mandato
Criar utilidade comprovada em um servidor Fedora headless com recursos limitados e cliente Android nativo. Preservar a intenção do proprietário, a saúde do PC e a possibilidade de continuidade sem agentes pagos.

## Restrições de arquitetura
- Backend principal em **C++20**; persistência em **PostgreSQL**; aplicativo **Kotlin + Jetpack Compose**.
- Terminal = sessão SSH/PTTY real; arquivos = SFTP com Android SAF. Não implementar protocolos de criptografia, SSH ou BitTorrent do zero.
- Downloads independem de GUI. Escolher motor headless por teste e manutenção, não por gosto.
- Serviços independentes: um erro de downloader não derruba SSH, API ou banco.
- Evitar containers/VMs/desktops permanentes e grandes dependências se não forem essenciais.

## Prioridades e mudanças
- Atacar um fluxo vertical real por vez; explicitar critérios de aceite ANTES de codificar.
- Tratar o plano como **revisável**. Mudança substancial requer rationale e atualização de docs/05-DECISOES-E-QUESTOES.md.
- Se uma implementação falhar no aparelho físico, não declará-la concluída por build ou mock.
- Não sacrificar terminal e transferência reais para dashboards decorativos ou framework interno.
- Evitar um sistema de agentes complexo ou dependência da Narys no MVP.

## Segurança e permissões
- Não cometer senhas, API keys, material privado SSH ou .env real.
- Não modificar firewall, serviço de SSH, contas, disco, configurações globais, tarefas administrativas ou rede pública sem autorização específica.
- Não fazer limpeza destrutiva, formatação, force push, reinicialização ou deploy automático sem confirmação.
- Não expor API, PostgreSQL, RPC de torrent ou SSH sem uma arquitetura segura revisada.
- Core não deve oferecer endpoint de execução arbitrária de comandos. Terminal interativo apenas via SSH com autenticação, identidade do host verificada e permissões do usuário.
- Validar destinos de downloads e caminhos de arquivo para evitar SSRF, traversal e escape de diretórios.

## Política de execução
1. Inspecionar e registrar ferramentas/versões antes de instalar dependências.
2. Alterações pequenas, revisáveis e com testes proporcionais.
3. Testes reais no Fedora e, para app, no Moto G06; registrar o que foi simulado.
4. Medir custo de RAM/disco/CPU em alterações relevantes; 8 GB de RAM não são licença para sobrecarregar o servidor.
5. Entrega de cada ciclo: arquivos alterados, comandos de validação, evidências, riscos, pendências e instruções de retomada.
6. Não pressupor que qualquer serviço já está instalado ou que o monitor está disponível.
7. Antes de tentar várias correções sucessivas, registrar hipótese, teste mínimo e resultado.

## Documentos de referência
- docs/01-VISAO-E-ESCOPO.md
- docs/02-ARQUITETURA-INICIAL.md
- docs/03-REQUISITOS-E-ACEITACAO.md
- docs/04-PLANEJAMENTO-INAUGURAL.md
- docs/05-DECISOES-E-QUESTOES.md
- docs/06-RISCOS-E-VALIDACAO.md
- docs/07-DIARIO-DE-BORDO.md

**Definição de pronto:** a funcionalidade funciona de ponta a ponta no ambiente alvo, existe evidência registrada e o proprietário consegue mantê-la ou retomá-la sem depender do agente que a escreveu.
