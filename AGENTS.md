# AGENTS.md — colaboração segura no My-CT
Estas orientações destinam-se a Codex e outros agentes que trabalhem neste repositório. **Leia README.md e docs/04-PLANEJAMENTO-INAUGURAL.md antes de propor implementação.**

## Estado atual e retomada

**15/10/2026, após merges #10/#11:** ciclo Android/SSH/SFTP consolidado na main e MYCT-3A/3B concluídas com provas reais no Fedora: Core C++20, endpoint HTTP local e PostgreSQL/libpq com cluster por socket privado. **Próximo recorte proposto: MYCT-3C — Dashboard Android mínimo lendo métrica real pelo túnel SSH já autenticado.** Antes de começar, leia [guia de retomada](docs/10-RETOMADA-PROJETO.md), [fechamento 3A/3B](docs/MYCT-3AB-FECHAMENTO-REPORT.md), [Core](server/README.md) e [refinamento Android adiado](docs/09-TRILHA-REFINAMENTO-ANDROID.md). Core e PostgreSQL foram deixados parados, dados preservados; não reaplicar initdb/migração. Lag, UX/scroll e background SFTP continuam dívidas, não iniciar refinamento ou instalações por conta própria.

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
