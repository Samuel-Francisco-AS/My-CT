# My-CT

**My-CT** é uma iniciativa de infraestrutura pessoal: um servidor Linux doméstico, administrado por um aplicativo Android nativo, capaz de executar tarefas, disponibilizar arquivos e realizar downloads sem depender de uma sessão gráfica.

> **Estado em 15/10/2026 — ciclo Android/SSH/SFTP encerrado com dívidas explícitas:** o proprietário comprovou no Moto G06 terminal SSH real, navegação SFTP, download/abertura de imagens e PDF e upload de PDF para o Fedora. MYCT-2R inclui serviços para SSH/transferência e controles de UX; **transferência SFTP em segundo plano ainda não foi validada no aparelho**. Permanecem dívidas de lag, interface rígida e scroll SFTP que volta ao topo ao sair de uma pasta. **Core C++20/PostgreSQL, Dashboard e downloads executados no Fedora ainda não foram implementados.** Detalhes: [fechamento](docs/08-FECHAMENTO-CICLO-ANDROID.md) e [retomada](docs/10-RETOMADA-PROJETO.md).

**Nesta branch, MYCT-3B validada no Fedora:** Core C++20 com saúde real em
`127.0.0.1:8765` e persistência PostgreSQL por socket Unix privado. Build,
gravação/leitura após reinícios e saúde com banco parado passaram. Ver
[relatório MYCT-3B](docs/MYCT-3B-POSTGRES-REPORT.md) e [operação do Core](server/README.md).
PR #11 depende da MYCT-3A/PR #10; sem merge automático. Dashboard e downloads
continuam fora deste ciclo.

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
| [Guia de retomada](docs/10-RETOMADA-PROJETO.md) | Ponto de partida da próxima conversa, próxima fase Core e comandos de build |
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

## Ponto de partida

O inventário Fedora foi registrado em MYCT-0. O ciclo Android/SSH/SFTP foi consolidado em MYCT-1/1R/1P/2/2R, com uso físico positivo e pendências assumidas. **Próxima frente sugerida: Core C++20, PostgreSQL e saúde real do servidor, após discussão do menor recorte útil**. Para iniciar nova conversa, leia [docs/10-RETOMADA-PROJETO.md](docs/10-RETOMADA-PROJETO.md). A [trilha de refinamento](docs/09-TRILHA-REFINAMENTO-ANDROID.md) está adiada, sem bloquear a nova frente.

Os comandos de build e instalação do APK estão em [android/README.md](android/README.md). Preparação e deploy do servidor serão tratados em missões específicas após revisão de dependências e configuração.
