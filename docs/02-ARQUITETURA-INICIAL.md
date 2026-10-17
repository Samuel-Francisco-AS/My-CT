# My-CT — arquitetura técnica inicial
**Versão:** 0.1 · **Estado:** direção arquitetural, com bibliotecas candidatas ainda em avaliação

## Visão por camadas
**Android (Kotlin + Jetpack Compose)** → **rede privada (local/Tailscale)** → **Fedora**.

No Fedora coexistem componentes distintos:
1. **OpenSSH (SSH + SFTP):** terminal remoto e transporte de arquivos. Não depende da API C++.
2. **My-CT Core (C++20):** API de saúde, serviços, tarefas, histórico e orquestração de downloads.
3. **PostgreSQL:** configurações, tarefas, auditoria e metadados; não armazena o conteúdo binário dos downloads.
4. **Motores existentes:** HTTP(S) via libcurl ou solução comprovada; BitTorrent por daemon headless com API (Transmission como candidato inicial).
5. **systemd:** supervisão, início automático e, conforme necessidade, timers; sem exigir uma sessão GNOME.

O app contém ao menos quatro áreas: **Dashboard, Terminal, Arquivos e Downloads**. Automações podem ser expostas inicialmente no Dashboard ou em uma seção posterior.

## Comunicação
- **SSH/PTTY:** Android ↔ OpenSSH: sessão interativa real; stream de entrada/saída, escape sequences, resize de terminal e teclas especiais.
- **SFTP:** Android ↔ OpenSSH: listar, navegar e transferir em fluxo; Android utiliza Storage Access Framework para arquivos locais.
- **HTTPS ou HTTP restrito à rede privada:** Android ↔ API C++: JSON versionado, observabilidade, tarefas e downloads. Política TLS/túnel será definida na inspeção de rede; não aceitar tráfego administrativo publicamente.
- **PostgreSQL:** acesso apenas pelo Core e ferramentas administrativas locais autorizadas; jamais acesso direto pelo app.
- **BitTorrent daemon:** API RPC local, com autorização e sem exposição à rede externa.

## Tecnologias e nível de compromisso
| Área | Escolha | Estado |
| --- | --- | --- |
| Linguagem servidor | C++20 | Decidido |
| Banco | PostgreSQL | Decidido |
| Aplicativo | Kotlin + Jetpack Compose | Decidido |
| Terminal/arquivos remotos | OpenSSH SSH/SFTP | Decidido como protocolo |
| Build C++ | CMake | Preferência técnica |
| HTTP do backend | cpp-httplib ou equivalente leve | Candidato |
| Acesso a Postgres | libpqxx (ou libpq em caso justificado) | Candidato |
| Download HTTP | libcurl | Candidato |
| Torrent | Transmission-daemon (qBittorrent-nox como alternativa) | Candidato |
| SSH Android | mwiede/JSch ou solução nativa compatível | A validar com terminal interativo |
| Renderer de terminal | Componente Android dedicado (examinar bibliotecas do Termux e licenças) | A validar |
| Acesso fora de casa | Tailscale | Preferência, não pré-condição LAN |

## Fronteiras importantes
- **Não construir** protocolo SSH, cliente BitTorrent, servidor de banco ou emulador de terminal do zero.
- **Não confundir** uma janela gráfica de terminal com shell real: o processo ocorre no Fedora, com uma PTY válida.
- **Não usar** o PostgreSQL para transportar blobs; guardar caminhos, IDs, timestamps, estados e erros.
- **Não fazer** a API C++ executar shell arbitrário a pedido da rede. A shell interativa é exclusiva do canal SSH autenticado do proprietário. Rotinas do Core usam comandos/operações explicitamente definidos e privilégio mínimo.
- Manter serviços não relacionados independentes: uma falha em downloads não pode derrubar o terminal SSH.

## Dados iniciais (hipóteses, não esquema final)
- **job_definition:** id, tipo, parâmetros validados, estado habilitado, agenda opcional.
- **job_run:** id, job_id, horário, resultado, diagnóstico com retenção limitada.
- **download:** id, origem/tipo, destino, status, progresso, motor, timestamps.
- **service_health:** fotografia ou amostra agregada quando houver valor; evitar coletar amostras a cada segundo no banco.
- **audit_event:** evento, horário, origem, ação, resultado, sem segredos.

Antes de migrar o banco: definir ownership de migrações, retenção, backup/restore e migrações reversíveis quando possível.

## Estrutura de código sugerida (revisável)
- server/ — CMake, biblioteca Core, API, integrações, migrações/testes.
- android/ — app nativo, navegação, conexão SSH/SFTP, API, testes.
- ops/ — units systemd, scripts de instalação/reversão e exemplos de configuração.
- docs/ — decisões, critérios, procedimentos e diário.

**Sem monorepo complicado:** pastas únicas e contratos claros bastam para o primeiro ciclo. Não criar estrutura vazia só por estética.

## Sequência de comunicação a validar
1. App ↔ SSH Fedora (autenticação e host key).
2. App ↔ SFTP Fedora (arquivo real e integração Android).
3. App ↔ API C++ (telemetria real e erros).
4. API ↔ PostgreSQL (consulta/gravação mínima).
5. API ↔ motor de download (RPC local).
6. systemd mantém serviços após reboot e app fechado.

Essa ordem pode mudar se uma dependência prática exigir.


## Atualização de implementação — 15/10/2026

**Android/SSH/SFTP já implementados e utilizados no Moto G06.** Kotlin+Compose, mwiede/JSch 2.28.8, módulos de terminal Termux reutilizados, Session SSH com host Ed25519 pinado e canais Shell/PTY e SFTP, SAF para arquivos locais, FGS specialUse opt-in do terminal e dataSync para transferência. tmux do Fedora é usado manualmente para preservar trabalho remoto. Download/upload de PDF via SFTP foram relatados funcionando; o dataSync de transferência **não teve validação física em segundo plano**.

**Ainda apenas direcionais:** Core C++20, PostgreSQL, API JSON, painel de saúde, tarefas e motores de download no Fedora; as escolhas de bibliotecas/serviços desses componentes ainda não são implementações. Consulte [fechamento](08-FECHAMENTO-CICLO-ANDROID.md), [retomada](10-RETOMADA-PROJETO.md) e [refinamento](09-TRILHA-REFINAMENTO-ANDROID.md). A arquitetura poderá ser simplificada na prática, conforme evidência e custo operacional.


## Revisão arquitetural MYCT-L1 — 17/10/2026 (planejada, posterior ao diagrama inicial)

O diagrama inaugural Android → Fedora descreve o **modo remoto existente**, não o limite definitivo do produto. Nova direção aprovada: o **Android também executará um modo local independente**, com IDE Sora e arquivos locais (privado/SAF), um terminal PTY/shell Android e, após prova técnica, compilação C offline no Moto G06. A mesma interface deve distinguir fontes de arquivo e sessão de execução `Local` versus `Fedora`, preservando os modelos remotos e seus privilégios. Componentes do emulador Termux incorporados podem continuar em uso; o **aplicativo Termux** não será uma dependência de execução ou instalação.

Restrições ainda sem implementação: targetSdk 36 e sandbox Android impedem presumir execução de binários baixados/gerados em diretórios graváveis; investigar WAMR/WASI e alternativa nativa segura com protótipo no aparelho. Sem root, sem servidor Fedora para executar programas locais, sem serviço local universal de comandos remotos. Ver [MYCT-L1](MYCT-L1-AMBIENTE-LOCAL.md). **MYCT-6 automações no Fedora permanece planejada, porém posterior à L1.**
