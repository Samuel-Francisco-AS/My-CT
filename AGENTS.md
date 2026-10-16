# AGENTS.md — colaboração segura no My-CT

**MYCT-5C candidata (16/10/2026):** branch `myct-5c-bittorrent`, APK
`0.5.3-torrents`/code 10, Downloads → HTTP/HTTPS | Torrents. Integração
Transmission 4.1+ por SSH/CLI e RPC loopback preparada; **motor ausente,
instalação/ativação e prova real Fedora/Moto pendentes**. Não declarar aceite
nem ativar serviços sem autorização. Remoção preserva dados; seeding explícito;
`.torrent` SAF complementar não implementado. [Relatório e retomada](docs/MYCT-5C-BITTORRENT-REPORT.md).
Estas orientações destinam-se a Codex e outros agentes que trabalhem neste repositório. **Leia README.md e docs/04-PLANEJAMENTO-INAUGURAL.md antes de propor implementação.**

## Estado atual e retomada

**16/10/2026, após merges dos PRs #10–#16:** Fedora 44 headless, Core C++20 via serviço systemd `myct-core.service` como `sam` (executável implantado em `/usr/local/bin/myct-core`), Dashboard Saúde Android por túnel SSH, terminal interativo e arquivos SFTP/SAF. PostgreSQL dedicado permanece independente da telemetria. O Core **foi comprovado após reboot real** sem partida manual. O app tem **Terminal | Arquivos | Saúde | Cofre | Downloads**. A aba Cofre desbloqueia a coleção `login` do GNOME Keyring, por senha digitada exclusivamente pelo proprietário no app em PTY SSH secundário; proprietário comprovou desbloqueio pelo Moto G06 e autenticação posterior do Copilot CLI. O fluxo não depende da Narys nem concede acesso individual a agentes.

**Fonte de verdade:** [guia de retomada](docs/10-RETOMADA-PROJETO.md), [fechamento MYCT-4/4B](docs/MYCT-4-FECHAMENTO-REPORT.md), [Core e helper](docs/MYCT-4-BOOT-VAULT.md) e [Android Cofre](docs/MYCT-4B-ANDROID-VAULT.md). Não afirmar que o Core ainda é iniciado manualmente, nem declarar o desbloqueio Android pendente. A instalação no Fedora continua separada do Git: editar `server/myct-core` ou `ops/myct-vault` **não** atualiza os executáveis já instalados. Novas alterações exigem operação explícita autorizada.

**Restrições MYCT-4:** não compartilhar senha/cofre/token com agentes, não automatizar desbloqueio após reboot, não criar/reinicializar coleção ou relaxar verificação do GNOME Keyring interno/hash. Preservar SSH, SELinux, systemd, PostgreSQL e Narys sem novas mudanças não autorizadas. Os PRs #13 e #14 foram integrados à `main`; sem repetição automática dos testes físicos já aceitos.

**MYCT-5A integrada e aceita:** HTTP(S) via CLI C++20/curl e unidades transitórias do systemd de usuário; testes Fedora aprovados. O proprietário comprovou continuação após sair do SSH e fechar o app, com arquivo de 262144 bytes e SHA-256 idêntico à fixture. Ver [relatório MYCT-5A](docs/MYCT-5A-DOWNLOADS-REPORT.md). Torrent e rotinas servidor-side continuam não implementadas. Lag Android, UX rígida, retorno ao topo em navegação SFTP e teste de transferência em background são dívidas da [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9), **adiadas** até solicitação específica. Engenharia proporcional à utilidade real, sem superdimensionar controles para produto comercial.

**MYCT-5B integrada com aceite parcial:** PR #16 mesclado em 16/10/2026.
Quinta aba Downloads via SSH/CLI, APK `0.5.2-downloads` (code 9).
O proprietário confirmou histórico/download HTTP real no Moto, foco/IME
preservado e botões sem piscadas. **Não declarar como fisicamente validados**
Ver em Arquivos, cancelamento, reconexão da aba Downloads, toques durante
polling nem progresso lento. São dívida da issue #9 e do relatório
`docs/MYCT-5B-ANDROID-DOWNLOADS-REPORT.md`; não bloquear próxima fase.
Não confundir a instalação manual de `~/.local/bin/myct-download` com
atualização automática pelo Git. O Motor MYCT-5A continua aceito.
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
