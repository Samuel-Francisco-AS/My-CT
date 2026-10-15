# My-CT — diário de bordo e registro de evidências

## 2026-10-14 — Fundação documental
**Situação verificada:** repositório privado Samuel-Francisco-AS/My-CT encontrado, inicialmente vazio, branch padrão main.

**Escopo conversado e registrado:**
- Servidor pessoal Fedora com Core C++20 e PostgreSQL.
- APK Android nativo Kotlin/Compose.
- Terminal Linux real via SSH/PTTY no app.
- Gestão de arquivos e envio/recebimento via SFTP.
- Downloads HTTP(S) e BitTorrent/magnet executados pelo servidor, sem µTorrent como dependência.
- Dashboard de saúde, base para automações úteis.
- Planejamento aberto, sujeito a revisão; Codex como recurso temporário, nunca dependência de runtime.

**Ato documental:** criação de README, documentos de visão, arquitetura, requisitos, planejamento, decisões, riscos, diário e orientações aos agentes.

**Não realizados nesta etapa:** implementação, build, teste no Moto G06, configuração do Fedora/PostgreSQL, instalação de daemon, VPN, publicação de APK ou deploy de serviços.

**Primeiro passo futuro:** inventário real do Fedora e verificação de toolchain, seguido de prova de terminal/arquivo e Core mínimo conforme planejamento.

## Modelo para entradas seguintes
### AAAA-MM-DD — Título curto
- **Objetivo:**
- **Alterações/commits/issues:**
- **Ambiente e versões:**
- **Comandos e testes executados:**
- **Evidências observadas (dispositivo real ou simulação):**
- **Riscos e pendências:**
- **Decisões alteradas:**
- **Próximo menor passo útil:**

> Não registrar PASS sem execução e evidência. Preferir poucos fatos reproduzíveis a relatórios volumosos.

### 2026-10-14 — MYCT-0 inventário técnico inaugural
- **Objetivo:** inventariar o Fedora, ferramentas Android, rede e viabilidade sem alterar serviços ou instalar dependências.
- **Alterações/commits/issues:** criado `docs/MYCT-0-INVENTORY-REPORT.md`; branch `myct-0-inventory`.
- **Ambiente e versões:** Fedora 44 Workstation, kernel 7.2.8 x86_64, i7-3770 (4c/8t), 7.6 GiB RAM, zram 7.6 GiB, disco 223.6 GiB com 133 GiB disponíveis. G++ 16.2.1; Java 21.0.10; Android SDK 1.8 GiB.
- **Comandos e testes executados:** consultas de sistema, RPM, toolchain, SDK/ADB, systemd, sockets/rede e amostra `vmstat`; teste sintático `g++ -std=c++20 -fsyntax-only` passou. Consulta ADB iniciou daemon local e não encontrou dispositivos. Leitura de config SSH e regras detalhadas de firewall negada sem autorização.
- **Evidências observadas:** SSH server ativo/habilitado; systemd em `multi-user.target`; firewalld ativo; PostgreSQL/CMake/libpq/libpqxx/libcurl-devel ausentes; qBittorrent desktop presente sem daemon headless; Gradle CLI/wrapper não encontrado; Moto G06 não detectado. Nenhum APK ou serviço My-CT testado.
- **Riscos e pendências:** terminal SSH/PTTY Android exige prova física; toolchain de backend e Gradle incompletas; configuração de segurança de SSH/firewall não inspecionada; medir pressão de RAM/disco e política de armazenamento/backup.
- **Decisões alteradas:** nenhuma. Planejamento inaugural não foi reescrito; recomendações permanecem não vinculantes.
- **Próximo menor passo útil:** MYCT-1, prova de build Android CLI e SSH/PTTY real no Moto G06, conforme critérios no relatório.
