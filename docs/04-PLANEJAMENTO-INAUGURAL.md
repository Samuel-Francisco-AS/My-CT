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


## Checkpoint 15/10/2026 — encerramento da fase Android inicial

- MYCT-0 inventariou o servidor; MYCT-1/1R/1P estabeleceram terminal SSH, corrigiram IME/clipboard/background, investigaram lag sem comprovar causa; MYCT-2/2R adicionaram SFTP/SAF, canal isolado e serviço dataSync. O proprietário comprovou no Moto downloads e abertura de imagens/PDF, upload de PDF, navegação e funções-chave do terminal. Ver [fechamento](08-FECHAMENTO-CICLO-ANDROID.md).
- **Não estender esta janela para corrigir todo o UX:** lag, scroll SFTP ao retornar ao pai, interface rígida e validação de transferências em segundo plano ficam em [refinamento](09-TRILHA-REFINAMENTO-ANDROID.md)/[issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). São dívidas explícitas, não funcionalidades aprovadas por suposição.
- **Próximo menor fluxo útil a discutir:** iniciar Core C++20 + PostgreSQL e painel de saúde real no Fedora, com integração Android privada mínima e controles proporcionais. CMake/Postgres estavam ausentes no inventário; reinspecionar antes de instalar. Não começar HTTP/torrent/jobs/uma arquitetura genérica ao mesmo tempo. [Guia de retomada](10-RETOMADA-PROJETO.md).
- Esta atualização não cria prazo ou obrigação de executar toda a janela A/C; o plano continua **flexível** e submetido a decisão do proprietário.


## Checkpoint 16/10/2026 — MYCT-R2 visual e ergonomia (posterior às entregas funcionais)

O plano inaugural continua como histórico. O proprietário autorizou a nova trilha de **refinamento visual/UX** com identidade inspirada em Deus Ex: Human Revolution (preto profundo/dourado) e princípio obrigatório de **fluidez**. **R2A** cria tema e drawer lateral por gesto **sem cabeçalho global**; **R2B** remodela as telas e diálogos; toda seção deve seguir a linguagem visual. Plano técnico, telas, limites e aceites estão em [MYCT-R2](MYCT-R2-IDENTIDADE-VISUAL-E-PLANEJAMENTO.md), com acompanhamento em [docs/09](09-TRILHA-REFINAMENTO-ANDROID.md).

O lag intermitente do terminal permanece **dívida suspensa** após teste real da 0.5.9; abertura/fechamento do teclado melhoraram, mas digitação não. Não reabrir testes/implementação de performance sem nova solicitação. Preservar o scroll SFTP melhorado. O PR #22 de R1 ainda é draft/sem merge; a branch `myct-r2a-visual-shell` foi criada a partir de R1 para manter o recorte R2A separado. Esta decisão não autoriza merge ou mudança no Fedora.


## Checkpoint 17/10/2026 — MYCT-L1 antes da MYCT-6

**Repriorização expressamente aprovada pelo proprietário:** após fechamento físico e integração da MYCT-R4/Fix, a **MYCT-L1 — Ambiente Local Independente** será a próxima etapa, **antes de MYCT-6 (automações/T5)**. L1 pretende oferecer IDE/arquivos offline, terminal local e prova de compilação e execução de **C** no Moto G06, sem rede, sem Fedora e **sem exigir Termux instalado**. Componentes open source do emulador Termux já embutidos podem continuar sendo reutilizados, respeitando licenças: o requisito é independência operacional, não rejeição de código reutilizado. Detalhes e gates: [planejamento MYCT-L1](MYCT-L1-AMBIENTE-LOCAL.md).

**Sequência decidida:** R4-Fix pendente de teste físico → aceite/merge R4 se aprovado → L1A (arquivos/IDE local) → L1B (PTY/shell Android local) → L1C (prova de compilação/execução C offline) → reavaliar MYCT-6/T5. Desenvolvimento remoto e serviços Fedora devem permanecer intactos. A extensão posterior para C++, Python, JS, Rust, Git, Make e pacotes Linux é **visão**, não escopo prometido da L1 inicial. Viabilidade de compiladores para Android targetSdk 36 exige prova técnica, não presunção de execução de binários de diretórios graváveis.

A janela C/T5 do plano inaugural permanece válida como visão histórica, **mas sua execução foi adiada** por decisão posterior do proprietário. Não criar branch de implementação L1 nem iniciar MYCT-6 enquanto R4-Fix não for fisicamente aceita e fechada.


## Checkpoint 17/10/2026 — R4 integrada, L1 é a próxima

MYCT-R4/Fix **0.6.6/code 23** recebeu **PASS físico do proprietário** após edição e salvamento de C na IDE Sora, compilação GCC e execução no Fedora (`My-CT IDE funcionando! Pós-r4-fix`), com cabeçalho compacto e teclas auxiliares aprovadas. [PR #26](https://github.com/Samuel-Francisco-AS/My-CT/pull/26) integrado à main via merge commit `266e799a0fe3d5a4dd6d9a270095c3d2f3da6005`; [relatório formal](MYCT-R4-FECHAMENTO.md). A condição que antecedia o planejamento de [MYCT-L1](MYCT-L1-AMBIENTE-LOCAL.md) está satisfeita. **Próximo trabalho: MYCT-L1A em nova conversa**, depois L1B/L1C, ainda **antes de MYCT-6**. Sem branch de implementação L1 criada neste fechamento.
