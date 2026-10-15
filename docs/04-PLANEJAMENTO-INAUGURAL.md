# My-CT — planejamento inaugural aberto
**Data de criação:** 14/10/2026 · **Natureza:** mapa de execução revisável, não contrato de calendário

## Intenção do ciclo
Converter o acesso restante ao Codex (aproximadamente **3 dias**, informado pelo proprietário) em uma base útil, testável e sustentável. Não presumir que todas as áreas estarão prontas em três dias. Não consumir o período apenas produzindo arquitetura e testes abstratos.

**Regra de prioridade:** jornadas reais P0 > infraestrutura necessária a elas > novas funcionalidades > polimento e abstrações futuras.

## As três janelas de trabalho (podem trocar de ordem)
### Janela A — Base operacional e primeira prova real
- Inspecionar Fedora, armazenamento, rede, serviço SSH, versões C++/Postgres/Android SDK/Gradle.
- Definir como construir APK pela CLI sem emulador e preservar espaço/RAM.
- Iniciar Core C++20 mínimo com build CMake, API de saúde real e Postgres com migração pequena.
- Estabelecer método privado de acesso à API (LAN primeiro; remoto após validação).
- **Gate A:** Core e banco reais funcionando no Fedora; APK básico instala no Moto G06 e lê dado real, se o ambiente permitir. Registrar qualquer bloqueio.

### Janela B — Ferramentas remotas de maior valor
- Priorizar terminal SSH interativo + SFTP no app.
- Validar biblioteca SSH/renderizador com PTY real e teclado Android antes de construir UI extensa.
- Construir explorador remoto mínimo + integração SAF para upload/download real.
- Criar estados de conexão, erro, cancelamento e verificação de host key.
- **Gate B:** htop/PTY e transferência de arquivo nos dois sentidos em aparelho físico. Se biblioteca não servir, trocar ou reduzir escopo imediatamente.

### Janela C — Trabalho independente e encerramento sustentável
- Integrar ao Core um mecanismo enxuto de trabalhos definidos (sem execução arbitrária por API).
- Escolher e integrar motor de download headless; começar por HTTP(S), acrescentar torrent/magnet se não comprometer estabilidade.
- Ligar telas do APK aos serviços reais, reiniciar servidor, validar logs, testes e recuperação.
- Documentar instalação, versões, comandos, riscos, configurações e próximos passos para continuar **sem Codex**.
- **Gate C:** pelo menos uma tarefa útil sobrevive ao fechamento do app; build repetível e trilha de recuperação clara.

**Reserva de contingência:** reservar tempo significativo do último ciclo para corrigir falhas e consolidar o que já funciona. Não iniciar nova frente se um gate central estiver quebrado.

## Trilhas paralelas, sem exigir trabalho simultâneo
| Trilha | Núcleo | Pode começar quando | Critério de avanço |
| --- | --- | --- | --- |
| T1 — Base Fedora | toolchain, SSH, systemd, rede, Postgres | imediatamente | ambiente inventariado e acessível |
| T2 — Core C++ | API, telemetria, persistência | após T1 mínima | resultado real de API + banco |
| T3 — Android | Compose, SSH/PTTY, SFTP/SAF | após toolchain e rede conhecidos | APK com terminal e arquivos testado |
| T4 — Downloads | HTTP(S), torrent/magnet, destino | após API e limites de disco definidos | arquivo real baixado e gerenciado |
| T5 — Automação | tarefas allowlisted, execução e histórico | após persistência mínima | tarefa executa sem app |
| T6 — Estabilidade | autenticação, logs, recursos, restauro | transversal | comportamento degradado controlado |

## Plano de corte, se faltar tempo
1. Não negociar SSH seguro e operação do Fedora sem GUI.
2. Preferir **terminal + SFTP funcionais** a várias telas ainda não conectadas.
3. Manter C++ + PostgreSQL com integração mínima comprovada, evitando schema e orquestração prematuros.
4. Aceitar HTTP(S) antes de torrent se integração BitTorrent ameaçar o prazo.
5. Adiar automações sofisticadas, notificações push, visual sofisticado, múltiplos perfis, sincronização contínua e IA.
6. Em último caso, publicar uma **V0.1 parcial identificada como parcial**, documentar exatamente o que não funciona e deixar retomada clara.

## Checkpoints para debate e alteração
Ao fim de cada gate:
- **O que funcionou no hardware real?** Comandos, vídeos/screenshot opcionais, logs, commits.
- **O que custou CPU/RAM/disco e tempo?** Medir, não presumir.
- **O que mudou na premissa?** Dependências, acessos, necessidades.
- **Qual é o próximo menor fluxo útil?** Repriorizar.
- **Quais decisões precisam ser reabertas?** Registrar em docs/05.

Mudanças de prioridade ou tecnologia não exigem novo grande planejamento; basta atualizar este documento, requisitos atingidos e diário de bordo, com justificativa e data.

## Disciplina de execução com agentes
- Uma tarefa por vez com entrada, aceite e evidência definidos.
- Codex implementa, compila e demonstra; não declarar concluído sem gate real.
- Não executar scripts destrutivos, reinicializações, abertura de portas externas ou operações privilegiadas sem revisão explícita.
- Evitar ciclos de tentativa custosos e sem avanço; depois de bloqueio reproduzível, registrar, simplificar ou trocar abordagem.
- Entrega final de cada janela: commit(s), comportamento comprovado, relatório curto, comando de retomada e pendências.
