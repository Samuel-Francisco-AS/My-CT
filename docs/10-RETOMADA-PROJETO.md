# My-CT — ponto de retomada para a próxima conversa/agente

**Atualizado:** 15/10/2026 · **Objetivo:** permitir retomar o repositório remoto sem depender das conversas anteriores.  
**Leia em ordem:** `README.md` → [fechamento do ciclo](08-FECHAMENTO-CICLO-ANDROID.md) → este documento → [requisitos](03-REQUISITOS-E-ACEITACAO.md) → [plano inaugural](04-PLANEJAMENTO-INAUGURAL.md) → `AGENTS.md`. Consulte relatórios históricos apenas conforme necessidade.

## Identidade e foco

- Repositório privado: `Samuel-Francisco-AS/My-CT`; projeto **independente de Narys**, sem acoplamento obrigatório a agentes ou LLMs.
- Objetivo: computador Fedora headless funcionando como servidor pessoal, controlado por **APK Android nativo**; SSH/SFTP já trazem utilidade, mas My-CT pretende incorporar monitoramento real, downloads executados no servidor e tarefas autônomas.
- Decidido: **Core C++20, PostgreSQL, Kotlin/Jetpack Compose**, OpenSSH/SFTP, systemd, baixa RAM/disco, operação privada. CMake, libpqxx/cpp-httplib/libcurl/Transmission são **candidatos ou ferramentas ainda por verificar**, não tecnologia implementada.
- Hardware inventariado: Fedora Workstation 44/kernel 7.2.8, i7-3770 4c/8t, 7,6 GiB RAM, SSD útil 223,6 GiB. Inventário inicial ~133 GiB livres; reavaliar valores ao iniciar novo ciclo. **Sem monitor**, acesso headless (Termux/SSH); alvo Android Moto G06.

## O que existe na main após integração do ciclo

- `android/`: Kotlin+Compose + JSch 2.28.8, SSH host key Ed25519 conferida por fingerprint SHA256, shell/PTY, módulos de terminal derivados do Termux para ANSI e teclado; IME true/false configurável, fonte, clipboard manual, capturas opcionais, serviço SSH opt-in e helper manual `tmux`.
- Arquivos via SFTP na mesma Session JSch, UI própria de pastas, SAF upload/download (stream 64KiB, progresso/cancelamento, sem sobrescrever), isolamento de falhas por canal, serviço Android dataSync e apresentação de tamanhos MB/GB.
- Evidência física do proprietário: SSH real e comandos básicos; layout/clipboard/tmux; navegação SFTP; imagens/PDF baixados e abertos; PDF enviado Android→Fedora. Background SFTP novo **não testado fisicamente**. Lag mesmo com false, interface rígida e scroll perdido ao retornar à pasta pai → [refinamento futuro](09-TRILHA-REFINAMENTO-ANDROID.md), issue #9.
- **Ainda não existem:** código do Core C++/PostgreSQL/My-CT em produção, API/admin/dashboard, download HTTP(S)/torrent via servidor ou agente de automação. Um teste sintático C++20 não é um backend.

## Último APK da fase

`artifacts/myct-2r-debug.apk` no filesystem do Fedora onde foi produzido, **não versionado no Git**, versão 0.2.1-sftp-corrective/code4, ~13,52 MB, SHA256 `c06e4521f55dcfc4596c909d65132257105eb305a4d456ef4b163fcf5ab51e04`. Atualizar por instalação sobre o app existente com a mesma assinatura debug; não presumir que outro clone possua o APK ou a keystore. Android CLI: `./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain` se cache existente. `android/README.md` contém instalação e testes.

## Próxima fase provável: MYCT-3 — Core mínimo + dados reais

**Não executar automaticamente; combinar escopo com proprietário na próxima conversa.** Sugestão proporcional:

1. Reinspecionar CMake, PostgreSQL servidor/cliente/libs e ferramentas C++ no Fedora **sem instalar pacotes sem autorização**; revisão das portas/rede sem mudar firewall.
2. Construir **um único serviço C++20 pequeno**, compilável por CLI e sem privilégios, que exponha **saúde real** (CPU, RAM, disco, status do próprio serviço) com consultas sob demanda e poucos endpoints locais; evitar polling agressivo.
3. Com PostgreSQL isolado/local, demonstrar **gravação e leitura reais mínimas** (por exemplo, registro de execução/health pontual), não um esquema de ERP ou infraestrutura de orquestração genérica. Instalação, migração, bind, autenticação e permissões demandam revisão antes de serem aplicadas.
4. Entregar um caminho protegido de acesso do Android à API, sem abrir porta administrativa pública ou entregar credenciais no APK; LAN e/ou túnel autenticado após decisão consciente do proprietário. **Nenhum SSH shell arbitrário via API.**
5. Se viável, uma tela Android de saúde enxuta exibindo medidas reais. Validar contra Fedora e Moto; não declarar PASS por mocks.
6. Preparar operação/reinício systemd somente quando autorizado e com evidência de baixo consumo, logs sem segredos e procedimento de reversão. Deixar downloads HTTP/torrent e automações para entregas seguintes.

**Gate mínimo recomendado:** servidor compila, API devolve métricas reais do próprio Fedora, DB salva/retorna um registro e Android mostra pelo menos uma métrica real pela rota autenticada, sem exposição pública. Se dependência bloquear, reduzir recorte e registrar precisamente o que não está funcional.

## Próximas frentes depois do Core

- HTTP(S) executado **no Fedora**, independentemente do APK, usando libcurl ou biblioteca proporcional; validar arquivo real, status e espaço livre.
- Torrent/magnet por daemon existente ou headless testado, com RPC local, sem reinventar protocolo e sem abrir porta externa involuntária.
- Tarefas definidas e seguras que sobrevivam à perda do celular, histórico/controle, operacionalização por systemd.
- Retomar refinamento Android **se o proprietário pedir**, uma dívida por vez; não bloquear o Core por acabamento. [Issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).

## Git e limites de trabalho

- No fechamento desta fase, PRs #6 (SSH), #7 (SFTP) e #8 (correção SFTP) são integrados em `main` **em ordem de dependência**; verificar estado remoto antes de abrir branch nova. Depois da limpeza de branches, documentos permanecem em `main`.
- Iniciar futuras branches a partir de `origin/main` atualizada, um objetivo verificável por PR. Nunca rebase destrutivo/force push ou merge automático sem pedido do proprietário.
- Nenhum token/senha/chave privada no Git; não mexer em Narys, outros serviços, SSH/firewall/postgres global ou pastas pessoais sem autorização.
- Padrão de engenharia acordado: **prova funcional primeiro, mínimo de arquitetura**, testes/controles proporcionais, sem transformar portfólio/servidor pessoal em sistema comercial de alto tráfego.
- Histórico e critérios: `docs/MYCT-*.md`, `docs/07-DIARIO-DE-BORDO.md`, [decisões](05-DECISOES-E-QUESTOES.md), [AGENTS.md](../AGENTS.md).


## Integração final verificada (15/10/2026)

Todos os PRs do ciclo foram mesclados em `main`: #6 → `ebe0773`, #7 → `58fcf7a`, #8 → `e031998`. Histórico preservado; [relatório de fechamento com SHAs integrais](08-FECHAMENTO-CICLO-ANDROID.md). Este registro documental posterior foi feito diretamente na `main`. As branches `myct-1-android-ssh`, `myct-2-sftp`, `myct-2r-sftp-stability` podem ser excluídas após sincronização local, sem apagar a implementação já mesclada. O refinamento continua registrado na issue #9 e não está implementado.

## MYCT-3B — persistência PostgreSQL (15/10/2026)

MYCT-3A permanece no PR #10, sem merge automático. A MYCT-3B deriva de
`myct-3a-core-health`, branch `myct-3b-postgres`, com libpq direta, migração
mínima e CLI local de eventos. Evidência e pendências estão no
[relatório MYCT-3B](MYCT-3B-POSTGRES-REPORT.md); instalação/build/retomada em
[server/README.md](../server/README.md). Não avançar Android/systemd/downloads
por inferência nem declarar aceite do banco sem prova real registrada.

**Atualização de aceite MYCT-3B:** pacotes instalados pelo proprietário e prova
real concluída no Fedora 44: build, 3/3 testes locais, 1/1 integração PostgreSQL,
evento ID 2 persistido após reinícios, health HTTP 200 com banco parado e binds
locais conferidos. Core e cluster exclusivo ficaram parados com dados preservados.
Para retomar, usar os comandos do relatório; não repetir initdb/migração.
PR #11 segue contra MYCT-3A, sem merge automático.
