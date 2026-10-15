# My-CT

**My-CT** é uma iniciativa de infraestrutura pessoal: um servidor Linux doméstico, administrado por um aplicativo Android nativo, capaz de executar tarefas, disponibilizar arquivos e realizar downloads sem depender de uma sessão gráfica.

> **Estado em 15/10/2026 — MYCT-3A/3B/3C integradas e comprovadas no Fedora/Moto G06:** Core C++20 fornece CPU/RAM/disco/uptime em `127.0.0.1:8765`; PostgreSQL local/libpq comprovou persistência. App Android nativo possui **Terminal | Arquivos | Saúde**, com métricas reais via túnel da sessão SSH autenticada. A MYCT-3C recebeu correção de contraste e foi aprovada fisicamente pelo proprietário; [PR #12](https://github.com/Samuel-Francisco-AS/My-CT/pull/12) integrada à main. **Core ainda iniciado manualmente por `./myct-core`**, sem systemd/autostart; banco independente da saúde. Downloads e automações não implementados. Lag, UX/scroll SFTP e prova background continuam adiados na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). [Relatório 3C](docs/MYCT-3C-DASHBOARD-REPORT.md) · [Retomada](docs/10-RETOMADA-PROJETO.md).

## Objetivo

Fazer do computador Fedora uma estação remota e um executor de tarefas úteis, acessível pelo Moto G06, com manutenção possível mesmo depois do término do acesso ao Codex.

A prioridade não é acumular funcionalidades: é obter **fluxos completos funcionando no hardware real**, com segurança, baixo consumo de recursos e operação compreensível.

## Direções já escolhidas

- **Servidor:** C++20 como linguagem central, Linux Fedora em modo headless, supervisão via systemd.
- **Persistência:** PostgreSQL, isolado da rede externa e acessado pelo backend.
- **Android:** aplicativo **nativo** em Kotlin + Jetpack Compose.
- **Terminal:** sessão **SSH real** no Fedora, com shell interativo/PTY, não uma simulação.
- **Arquivos:** navegação remota e transferência **bidirecional via SFTP** com o armazenamento do Android.
- **Downloads:** HTTP/HTTPS e BitTorrent/magnet gerenciados pelo servidor, sem dependência de µTorrent ou interface gráfica.
- **Operação:** painel de saúde/serviços e base para tarefas autônomas.
- **Acesso:** rede privada e princípio do menor privilégio; nenhuma API administrativa exposta diretamente à internet.

As escolhas específicas de bibliotecas, contratos e divisão de entregas são **propostas revisáveis** e precisam de provas de compatibilidade.

## Documentação

| Documento | Finalidade |
| --- | --- |
| [Visão e escopo](docs/01-VISAO-E-ESCOPO.md) | Propósito, perfis de uso, limites e resultados desejados |
| [Arquitetura inicial](docs/02-ARQUITETURA-INICIAL.md) | Componentes, comunicações, responsabilidades e alternativas |
| [Requisitos e aceitação](docs/03-REQUISITOS-E-ACEITACAO.md) | Capacidades e verificações objetivas |
| [Planejamento inaugural](docs/04-PLANEJAMENTO-INAUGURAL.md) | Trilhas, prioridades e três janelas iniciais de execução — flexíveis |
| [Decisões e questões abertas](docs/05-DECISOES-E-QUESTOES.md) | O que foi decidido, o que é hipótese e o que discutir |
| [Riscos e validações](docs/06-RISCOS-E-VALIDACAO.md) | Segurança, desempenho, dependências, recuperação e testes |
| [Diário de bordo](docs/07-DIARIO-DE-BORDO.md) | Registro cronológico de decisões, evidências e progresso |
| [Inventário MYCT-0](docs/MYCT-0-INVENTORY-REPORT.md) | Ambiente observado e bloqueadores |
| [Prova SSH MYCT-1](docs/MYCT-1-ANDROID-SSH-REPORT.md) | APK, build, testes e validação física pendente |
| [Correção MYCT-1R](docs/MYCT-1R-CORRECTION-REPORT.md) | IME, clipboard, foreground service e recuperação via tmux |
| [Arquivos MYCT-2](docs/MYCT-2-SFTP-REPORT.md) | SFTP/SAF, preferência IME, APK e limites de validação |
| [Correção MYCT-2R](docs/MYCT-2R-CORRECTION-REPORT.md) | Isolamento SFTP, conclusão, background dataSync, tamanhos e testes |
| [Fechamento do ciclo Android](docs/08-FECHAMENTO-CICLO-ANDROID.md) | Histórico, evidências físicas, limitações e estado de aceite |
| [Trilha de refinamento](docs/09-TRILHA-REFINAMENTO-ANDROID.md) | Lag, scroll SFTP, UX, validações em background e demais dívidas adiadas |
| [Guia de retomada](docs/10-RETOMADA-PROJETO.md) | Ponto de partida atualizado: MYCT-3C, saúde real no Moto |
| [Core/PostgreSQL MYCT-3B](docs/MYCT-3B-POSTGRES-REPORT.md) | Evidência Fedora: build, banco, isolamento, consumo e recuperação |
| [Fechamento MYCT-3A/3B](docs/MYCT-3AB-FECHAMENTO-REPORT.md) | PRs #10/#11, SHAs, provas e pendências |
| [Dashboard MYCT-3C](docs/MYCT-3C-DASHBOARD-REPORT.md) | Implementação, testes, APK e roteiro físico pendente |
| [Build e instalação Android](android/README.md) | CLI, transferência manual e uso do terminal |
| [Orientações para agentes](AGENTS.md) | Regras de colaboração para Codex e outros agentes |

## Acompanhamento no GitHub

- [#1 — Discussões abertas e revisão de prioridades](https://github.com/Samuel-Francisco-AS/My-CT/issues/1)
- [#2 — Marco A: inventário Fedora e Core C++/PostgreSQL](https://github.com/Samuel-Francisco-AS/My-CT/issues/2)
- [#3 — Marco B: APK com SSH real e SFTP](https://github.com/Samuel-Francisco-AS/My-CT/issues/3)
- [#4 — Marco C: downloads e primeira automação](https://github.com/Samuel-Francisco-AS/My-CT/issues/4)
- [#9 — Refinamento Android adiado](https://github.com/Samuel-Francisco-AS/My-CT/issues/9)

Estas issues são pontos de acompanhamento **revisáveis**, não marcos com datas imutáveis.

## Princípios do projeto

1. **Utilidade comprovada antes de complexidade.** A primeira entrega real deve funcionar no Fedora e no aparelho físico.
2. **Sem dependência obrigatória de IA.** A rotina do sistema deve continuar funcionando sem Codex, Narys ou provedores remotos.
3. **Tecnologias a serviço do produto.** C++20, PostgreSQL e Android nativo são escolhas de direção; novas dependências precisam justificar seu custo operacional.
4. **Headless e econômico.** Não manter desktop remoto, emulador Android ou modelos locais pesados como pré-requisito.
5. **Interfaces substituíveis.** O app Android não deve ser necessário para o backend continuar operando.
6. **Planejamento vivo.** Reordenar, reduzir ou discutir escopo é permitido e esperado; alterações materiais devem ser documentadas.

## MYCT-4 — proposta em branch, não implantada

A branch `myct-4-boot-and-vault` prepara **systemd para iniciar o Core no boot** e um **comando My-CT próprio para consultar/desbloquear o GNOME Keyring login existente pelo SSH**, sem dependência de runtime da Narys. O serviço não precisa de PostgreSQL e o desbloqueio do cofre não é automático nem entregue aos agentes. **Ainda não foi instalado nem testado no Fedora do proprietário.** Comandos, limites e reversão: [MYCT-4 — operação e cofre](docs/MYCT-4-BOOT-VAULT.md).

## Ponto de partida

MYCT-3C foi comprovada no Moto G06 e integrada à `main` após corrigir o contraste. Próxima conversa operacional: **serviço systemd para iniciar e manter o Core automaticamente**, somente após autorização do proprietário; manter API em loopback, acesso Android por SSH e banco independente (não iniciar PostgreSQL desnecessariamente para telemetria). Operação atual em [server/README.md](server/README.md). A questão de **acesso autorizado ao cofre sem depender da Narys** é assunto separado para discussão, ainda não projetado ou implementado. A [trilha de refinamento Android](docs/09-TRILHA-REFINAMENTO-ANDROID.md) continua adiada.
