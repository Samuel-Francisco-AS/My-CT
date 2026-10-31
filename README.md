# My-CT

**Licença:** o código e a documentação originais do My-CT são disponibilizados
sob a [Apache License 2.0](LICENSE), ressalvadas as licenças próprias dos
componentes de terceiros (incluindo Termux e Sora Editor).
Consulte [NOTICE](NOTICE) e [Licenciamento e terceiros](docs/LICENSING.md)
para direitos, atribuições e condições de redistribuição.

**MYCT-DESKTOP — fase aceita no Moto G06 e integração à main autorizada (27/10/2026):** Desktop-1/F1/F2 + UX1/UX2, `0.14.4-desktop-ux2` / code 57.
Proprietário confirmou renderização e uso real do Fedora, gerenciamento restrito
Iniciar/Encerrar, melhoria visual, rotação com conexão preservada, trackpad e
teclado funcionais e estabilidade geral aceitável. Testes informados pelo Codex:
407 JVM PASS/2 SKIP, 4 helper PASS; build/lint sem erros; instrumentação compilada,
não executada. Limites de aceite e documentação consolidados em
[fechamento Desktop](docs/MYCT-DESKTOP-FECHAMENTO.md) e
[relatório UX2](docs/MYCT-DESKTOP-UX2-RELATORIO.md).
Integração via [PR #64](https://github.com/Samuel-Francisco-AS/My-CT/pull/64);
sem release nem implantação automática no Fedora.

**MYCT-DESKTOP-UX1 — aceite visual no Moto G06:** APK
**0.14.3-desktop-ux1 / code 56** instalado e testado pelo proprietário. Barra
compacta, painéis sob demanda, Iniciar, conexão VNC, imagem real/Firefox,
paisagem e melhor uso da tela cheia confirmados. Rotação recria a Activity,
desconecta a visualização e pede a senha novamente, preservando a sessão Fedora;
continuidade sem persistir credenciais fica para UX2. Gestos avançados, Gboard
com acentos e desempenho prolongado não têm aceite físico integral nesta etapa.
[Relatório e limites do aceite](docs/MYCT-DESKTOP-UX1-RELATORIO.md).

**MYCT-DESKTOP-F2 — aceite funcional informado pelo proprietário:** visualização
VNC e gerenciamento restrito aprovados no escopo testado no Moto G06. Helper
instalado manualmente; Iniciar e Encerrar confirmaram o estado real da unidade.
APK anterior **0.14.2-desktop-fix2 / code 55**. O aceite visual UX1 está descrito acima.
[Relatório histórico F2](docs/MYCT-DESKTOP-F2-RELATORIO.md) ·
[Instalação manual/reversão](ops/desktop/README.md).

**Armazenamento local — candidata code 51:** identidade de hardlinks resistente à
renumeração de device Btrfs, retirada da biblioteca e exclusão permanente por alvos
exatos, com uma confirmação e sem backup Cloud obrigatório.
[APK 0.13.4-media-local-rc1/code 51](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-media-local-v0.13.4-rc1) ·
[Contrato e validação](docs/MYCT-MEDIA-LOCAL-RELATORIO.md).
A PR #60 permanece draft; novo aceite físico Moto pendente. Atualizar a APK não
substitui os helpers pessoais automaticamente.

**MYCT-CLOUD-R1:** candidata code 49 publicada anteriormente, seleção Jellyfin,
Atualizar durante `space` e progresso real de leitura preservados.
[Pre-release anterior](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-cloud-r1-v0.13.2-rc1).
O reparo real do cadastro Media reconciliou 264 registros com recibo `succeeded`;
[fechamento técnico](docs/MYCT-CLOUD-R1-RELATORIO.md). Os arquivos pessoais,
backups e registros excepcionais foram preservados. O reconhecimento corrente
não exige repetir esse reparo só por renumeração do device.
[Diagnóstico](docs/MYCT-CLOUD-R1-POS-TESTE-DIAGNOSTICO.md) ·
[Publicação anterior](docs/MYCT-CLOUD-R1-DEPLOY-RELATORIO.md).

**MYCT-SETUP integrada à `main` (21/10/2026):** [PR #59 MERGED](https://github.com/Samuel-Francisco-AS/My-CT/pull/59),
marco `567d51409b10f2ca01c0f822942efd8c0baaf21e`, reúne documentação,
SETUP-1/2/3/4 e AUDIT-FIX-1 com históricos preservados. APK **code 48** aceito
funcionalmente pelo proprietário no Moto G06; nenhuma nova versão/release.
Fedora pessoal e helpers instalados não foram alterados. Provisionamento completo
em Fedora físico limpo **NÃO VALIDADO**. Issue #53 permanece aberta até confirmação
final do proprietário. [Integração, testes e limites](docs/MYCT-SETUP-INTEGRATION-RELATORIO.md).
Os registros de candidatas abaixo descrevem o estado nas respectivas datas.

**MYCT-SETUP-AUDIT-FIX-1 candidata (21/10/2026):** correções pós-auditoria de
assinatura RPM, publicação/retomada, caminhos/locks, ordem dos componentes,
prévia Rclone e retenção de campos secretos. APK **0.13.1-setup-audit-fix1-rc1 /
code 48**, atualização `dev.myct` com o certificado anterior. PR #58 continua
draft; nenhuma integração ou implantação pessoal. Revisão da Luna e testes
físicos desta candidata pendentes; Fedora físico limpo **NÃO EXECUTADO**.
[Correções, testes e riscos residuais](docs/MYCT-SETUP-AUDIT-FIX-1-RELATORIO.md).

**MYCT-SETUP-4 candidata (20/10/2026):** **Backup / Recuperação** em
Opções → Configurar servidor. Pacote básico sem segredos; banco e catálogos
selecionados exigem `age`. Dump lógico PostgreSQL e restauração limitada à
migração 001, com prévia e aprovação. Catálogos são recuperados como históricos;
receitas Transmission/Prowlarr e ativação de catálogos continuam manuais/pendentes.
APK **0.13.0-setup4-rc1 / code 47**; Moto SETUP-4 e Fedora físico limpo
**NÃO VALIDADOS**. Sem backup real/deploy no Fedora pessoal e sem auditoria global
nesta missão. [Relatório, evidências e limites](docs/MYCT-SETUP-4-RELATORIO.md).

**MYCT-SETUP-3 candidata (20/10/2026):** Rclone/Google Drive opcional em
**Opções → Configurar servidor**: diagnóstico passivo, instalação supervisionada,
OAuth humano pelo Terminal/túnel temporário e verificação online separada.
APK **0.12.0-setup3-rc1 / code 46**. Fedora pessoal preservado; nenhuma consulta
Drive ou transferência real nesta missão. Instalação física limpa **NÃO VALIDADA**;
validação funcional SETUP-3 no Moto informada pelo proprietário; OAuth real nesta candidata não comprovado. [Relatório e roteiro seguro](docs/MYCT-SETUP-3-RELATORIO.md).

**MYCT-SETUP-2 candidata (20/10/2026):** planos opcionais separados para
Transmission e Prowlarr em **Opções → Configurar servidor**. APK
**0.11.0-setup2-rc1 / code 45**, diagnóstico aceito pelo proprietário no Moto G06; instalação física limpa NÃO VALIDADA. Legados do Fedora
pessoal reconhecidos e preservados. Diagnóstico SETUP-1 já validado no Moto;
instalação física limpa da Base continua sem aceite.
[Relatório, evidências e roteiro](docs/MYCT-SETUP-2-RELATORIO.md).


**MYCT-SETUP-1 candidata (20/10/2026):** **Opções → Configurar servidor**, diagnóstico
Fedora por SSH e instalação supervisionada de Core/PostgreSQL. APK
**0.10.0-setup1-rc1 / code 44**; diagnóstico Moto aceito, instalação Fedora físico limpo NÃO VALIDADA.
Nenhuma implantação no servidor pessoal. [Arquitetura, testes e validação](docs/MYCT-SETUP-1-RELATORIO.md).


**MYCT-C1C ACEITA E INTEGRADA À `main` (20/10/2026), [PR #50 MERGED](https://github.com/Samuel-Francisco-AS/My-CT/pull/50), merge `677051c23bdd427a3abcb4fbc79c09f4e1883c4f`:** Nuvem em
**Downloads → Mídia → Armazenamento**, com arquivo/restore,
barra de progresso real (%/bytes/taxa/ETA), polling sem piscadas,
cancelamento guiado de novas cópias, liberação local e trabalhos persistentes
no Fedora, mesmo saindo do aplicativo. APK **0.9.1-c1c-rc2/code 42**,
assinatura preservada, instalado sobre o anterior. Aceite físico: temporada
**S01 de 24,4 GiB arquivada e verificada no Drive**, com 28 hardlinks
reconhecidos; posteriormente **liberada do SSD pelo app**, mantendo backup
remoto. Screenshot de Saúde confirmou **50,5 GiB livres no Fedora** (medida
final, não economia isolada garantida). Upload de arquivo pequeno exibiu
**20,6%, 26,5/128 MiB, 1,7 MiB/s e ETA 1min02s**; continuou ao sair/voltar.
**Pendência documentada:** cancelamento remoto e progresso de restauração
não observados fisicamente, embora com testes automatizados.
[PR #50](https://github.com/Samuel-Francisco-AS/My-CT/pull/50) ·
[APK code 42](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-c1c-v0.9.1-rc2) ·
[Relatório e aceite](docs/MYCT-C1C-RELATORIO.md).

**MYCT-C1B-2 ACEITA e integrada à `main` (20/10/2026), [PR #49 MERGED](https://github.com/Samuel-Francisco-AS/My-CT/pull/49), merge `86df44e0d610a807081c6b7995112c3d45ceced6`:** entrega **unificada** com `space` (inodes/hardlinks e economia estimada), `release` (prévia e confirmação, sem excluir Drive), `restore`, `relink` Jellyfin e `recover [--resume]`. **121 testes PASS no Fedora**, mais ciclo físico com fixture própria de 134 MB: upload/restore, duas liberações consentidas, preservação do link Jellyfin, reconstrução idempotente (`added`/`existing`) e seeding iniciado, `rpc_status:6`. **Sem peers/upload observados**. Variação de espaço livre do Btrfs não confirma economia instantânea; `reclaim_guaranteed_bytes:0` é intencional. A PR #49 é a única integração desta entrega; C1C Android ainda pendente na [issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46). [Relatório e evidências reais](docs/MYCT-C1B-2-RELATORIO.md).

**MYCT-C1B-1 ACEITA e integrada à `main` (19/10/2026), [PR #48](https://github.com/Samuel-Francisco-AS/My-CT/pull/48), merge `fce805ea96bc108a85b466133c6724b4688a329c`:** CLI para arquivar pacote torrent integral, preservar metainfo/manifesto, restaurar sem sobrescrita e verificar peças pelo Transmission. Teste físico real no Fedora: ~615 MB enviados ao Google Drive, restaurados com hashes válidos e recuperados pelo Transmission (`seeding_ready:true`, **pausado**). **Seeding efetivo/peers ainda NÃO testados; sem remoções, liberação de SSD ou alteração de hardlinks.** Próximas C1B-2/C1C na [issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46). [Relatório e evidências](docs/MYCT-C1B-1-RELATORIO.md).

**MYCT-C1A ACEITA e integrada à main em 19/10/2026:** arquivamento/restauração de arquivo comum por Rclone, Fedora/Google Drive real e 29 testes locais aprovados; fixture de 37 bytes voltou idêntica após conferência MD5/SHA-256 e `cmp`. [PR #47](https://github.com/Samuel-Francisco-AS/My-CT/pull/47) (merge `bbf929886a1c66d2b0927b2ca3f8c46fa25cfe7b`) · [Relatório final](docs/MYCT-C1A-CLI-REPORT.md). **Na C1A ainda não havia integração torrent, seeding, remoção de dados ou painel Android**; próximas fases C1B/C1C na [issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46). A sincronização do Fedora e a limpeza de branches continuam manuais.

**MYCT-L1D candidata — 17/10/2026:** IDE Local com **Projetos privados / Pasta compartilhada**,
edição direta em árvore SAF autorizada para abrir e compilar os mesmos arquivos
no Termux. APK **0.7.0-l1d-shared-storage/code 28**, sem compilação embarcada.
Gate Moto G06/Termux **pendente**, sem merge. L1C cancelada (PR #30 fechado).
[Relatório e roteiro físico](docs/MYCT-L1D-RELATORIO.md) · [PR #31 draft](https://github.com/Samuel-Francisco-AS/My-CT/pull/31) · [APK e SHA-256](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-l1d-v0.7.0-rc1).

**MYCT-5D-B2 ACEITA no Moto G06 em 16/10/2026; PR #21 integrado à main (merge `ed593cffe798e01177c249b9acffc700184a455a`):** APK
**0.5.6-antivirus/code 13**, seção Antivírus no relatório de arquivos e
**Opções → Antivírus e assinaturas**, pela CLI B1/SSH existente. Sem alterações
no motor, timer ou serviços Fedora. [Relatório B2](docs/MYCT-5D-B2-REPORT.md) ·
[APK candidato](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-5d-b2-v0.5.6-rc1).
**Aceite expresso:** inspeção/ClamAV CLEAN em PDF pequeno, cancelamento INCOMPLETE, atualização manual em Opções SUCCESS e status atualizados. Falha do MKV de 1,79 GB caracterizada como limite de VM (RLIMIT_AS 2 GiB); não houve análise completa nem mudança de recursos. Diagnóstico sanitizado da CLI incluído, **adaptador ainda pendente de instalação manual autorizada**. PR #21 integrado à `main`; issue #18 encerrada; dívida visual R-09 na issue #9.


**MYCT-5D-B1 ACEITA operacionalmente em 16/10/2026 (PR #20):** ClamAV pontual no Fedora,
CLI `myct-av` + worker C++20, base dedicada atualizada realmente, análise
não destrutiva e unidades de usuário entregues. [Relatório B1](docs/MYCT-5D-B1-REPORT.md)
e [contrato para B2](docs/MYCT-5D-B1-CLI.md). Instalação B1 no espaço de `sam`
realizada sob autorização; **timer de 36h ativado e validado pelo proprietário**, confirmado ativo sem alteração
nesta correção. Limite de entrada atualizado para **2.000.000.000 bytes**, com
os demais limites de recursos preservados. O proprietário instalou o worker atualizado e confirmou uma análise CLI real de `abc`: CLEAN, 10,071 s, ~962 MiB RSS, base daily 28155 atual. [Aceite e medições](docs/MYCT-5D-B1-REPORT.md). **[PR #20](https://github.com/Samuel-Francisco-AS/My-CT/pull/20) integrado à `main` (merge `a5770afa45c3771437118536e8183d14e5dd0ce4`); B2 Android aceita no Moto G06.** Issue #18 permanece aberta.


**MYCT-5C aprovada (16/10/2026, PR #17):** BitTorrent/magnet pelo Transmission 4.1.3 no Fedora e controles no APK **0.5.4/code 11**. O proprietário confirmou no Moto G06 download sem SSH, pausa/retomada, remoção preservando arquivo, SFTP e **nome de pasta pelo título real ao concluir**, usando hook/RPC do Transmission. Seeding preservado; sem migração das pastas antigas. [Relatório de fechamento](docs/MYCT-5C-BITTORRENT-REPORT.md) · [release 0.5.4](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-5c-v0.5.4-rc1).

**MYCT-5D-A ACEITA fisicamente (16/10/2026):** Arquivos → Verificar arquivo com tamanho/tipo por conteúdo/SHA-256, cópia e comparação opcional comprovados no Moto G06; APK 0.5.5/code 12, helper C++20 `myct-inspect` no Fedora durante o aceite. [PR #19](https://github.com/Samuel-Francisco-AS/My-CT/pull/19) integrado à `main`; [relatório de aceite](docs/MYCT-5D-A-REPORT.md). MYCT-5D-B1/ClamAV aceita operacionalmente no Fedora; B2 aceita no Moto G06 conforme relatório acima. Dívidas #9 e automações #4 continuam adiadas.

**My-CT** é uma iniciativa de infraestrutura pessoal: um servidor Linux doméstico, administrado por um aplicativo Android nativo, capaz de executar tarefas, disponibilizar arquivos e realizar downloads sem depender de uma sessão gráfica.

> **Registro histórico em 15/10/2026 — MYCT-4/4B integradas e validadas no Fedora e Moto G06.** Core C++20 iniciado automaticamente pelo systemd e comprovado após reboot; Saúde Android funciona por túnel SSH sem início manual; PostgreSQL local é independente. App Android nativo com **Terminal | Arquivos | Saúde | Cofre** e transferências bidirecionais SFTP. A aba Cofre consulta o GNOME Keyring `login` e permite desbloqueio humano em canal SSH/PTY separado. Proprietário comprovou desbloqueio no Moto e Copilot CLI autenticado por credenciais existentes. [PR #13](https://github.com/Samuel-Francisco-AS/My-CT/pull/13) e [PR #14](https://github.com/Samuel-Francisco-AS/My-CT/pull/14) mesclados. MYCT-5A acrescenta CLI HTTP/HTTPS headless **aceita fisicamente após fechar SSH e Android, com hash correto** ([relatório](docs/MYCT-5A-DOWNLOADS-REPORT.md)); naquela data, torrent e rotinas autônomas ainda não existiam; lag, UX/scroll SFTP e prova em background seguem adiados na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). [Relatório de fechamento MYCT-4](docs/MYCT-4-FECHAMENTO-REPORT.md) · [Guia de retomada](docs/10-RETOMADA-PROJETO.md).


**MYCT-5B integrada à main (PR #16, 16/10/2026):** Android
`0.5.2-downloads` (code 9), quinta aba **Downloads** por SSH existente.
O proprietário comprovou histórico e conclusão HTTP/HTTPS no Moto G06,
foco/IME estável e botões sem piscadas durante polling. **Aceite funcional
parcial por decisão do proprietário:** Ver em Arquivos, cancelamento, retorno
após reconexão, cliques durante polling e progresso lento permanecem sem
prova física e constam na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).
A CLI estável em `~/.local/bin/myct-download` é pré-requisito da aba; instalar
ou atualizar manualmente no Fedora. [Fechamento e limites](docs/MYCT-5B-ANDROID-DOWNLOADS-REPORT.md)
· [APK 0.5.2](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-5b-v0.5.2-rc1).

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
| [Downloads MYCT-5A](docs/MYCT-5A-DOWNLOADS-REPORT.md) | CLI HTTP/HTTPS, testes Fedora, uso e aceite real SSH/Moto |
| [Android MYCT-5B](docs/MYCT-5B-ANDROID-DOWNLOADS-REPORT.md) | Quinta aba Downloads, APK 0.5.2, aceite parcial e validações futuras |
| [Fechamento MYCT-5C](docs/MYCT-5C-BITTORRENT-REPORT.md) | BitTorrent, aceite físico, nomeação e pendências |
| [MYCT-C1 — Arquivamento na nuvem](docs/MYCT-C1-ARQUIVAMENTO-NUVEM.md) | Google Drive, restauração e retomada de seeding por fases; sem implantação |
| [CLI MYCT-C1A — candidata](docs/MYCT-C1A-CLI-REPORT.md) | Arquivos comuns via Rclone, catálogo privado, testes simulados; revisão Luna e aceite Drive real pendentes |
| [Próxima fase MYCT-5D](docs/MYCT-5D-PLANEJAMENTO-VERIFICACAO-ARQUIVOS.md) | Inspeção de arquivos e antivírus sob demanda planejados |
| [Guia de retomada](docs/10-RETOMADA-PROJETO.md) | Estado atual do Core systemd, Cofre Android e continuidade |
| [Core/PostgreSQL MYCT-3B](docs/MYCT-3B-POSTGRES-REPORT.md) | Evidência Fedora: build, banco, isolamento, consumo e recuperação |
| [Fechamento MYCT-3A/3B](docs/MYCT-3AB-FECHAMENTO-REPORT.md) | PRs #10/#11, SHAs, provas e pendências |
| [Dashboard MYCT-3C](docs/MYCT-3C-DASHBOARD-REPORT.md) | Implementação, testes, APK e roteiro físico pendente |
| [Fechamento MYCT-4/4B](docs/MYCT-4-FECHAMENTO-REPORT.md) | Prova de reboot, cofre pelo Moto e Copilot CLI, integrações e dívidas |
| [MYCT-4 Core e cofre](docs/MYCT-4-BOOT-VAULT.md) | Serviço systemd, helper e instalação local |
| [MYCT-4B Android](docs/MYCT-4B-ANDROID-VAULT.md) | Protocolo SSH PTY, testes, diagnósticos e aceite físico |
| [Build e instalação Android](android/README.md) | CLI, transferência manual e uso do terminal |
| [Orientações para agentes](AGENTS.md) | Regras de colaboração para Codex e outros agentes |

## Acompanhamento no GitHub

- [#1 — Discussões abertas e revisão de prioridades](https://github.com/Samuel-Francisco-AS/My-CT/issues/1)
- [#2 — Marco A: inventário Fedora e Core C++/PostgreSQL](https://github.com/Samuel-Francisco-AS/My-CT/issues/2)
- [#3 — Marco B: APK com SSH real e SFTP](https://github.com/Samuel-Francisco-AS/My-CT/issues/3)
- [#4 — Marco C: downloads e primeira automação](https://github.com/Samuel-Francisco-AS/My-CT/issues/4)
- [#18 — MYCT-5D: verificação de arquivos](https://github.com/Samuel-Francisco-AS/My-CT/issues/18)
- [#46 — MYCT-C1: arquivo na nuvem e seeding restaurável](https://github.com/Samuel-Francisco-AS/My-CT/issues/46)
- [#9 — Refinamento Android adiado](https://github.com/Samuel-Francisco-AS/My-CT/issues/9)

Estas issues são pontos de acompanhamento **revisáveis**, não marcos com datas imutáveis.

## Princípios do projeto

1. **Utilidade comprovada antes de complexidade.** A primeira entrega real deve funcionar no Fedora e no aparelho físico.
2. **Sem dependência obrigatória de IA.** A rotina do sistema deve continuar funcionando sem Codex, Narys ou provedores remotos.
3. **Tecnologias a serviço do produto.** C++20, PostgreSQL e Android nativo são escolhas de direção; novas dependências precisam justificar seu custo operacional.
4. **Headless e econômico.** Não manter desktop remoto, emulador Android ou modelos locais pesados como pré-requisito.
5. **Interfaces substituíveis.** O app Android não deve ser necessário para o backend continuar operando.
6. **Planejamento vivo.** Reordenar, reduzir ou discutir escopo é permitido e esperado; alterações materiais devem ser documentadas.

## MYCT-4/4B — Core autostart e cofre Android aceitos

O Core roda como `myct-core.service` em `/usr/local/bin/myct-core`; o proprietário comprovou o Dashboard Saúde funcionando após reboot real do Fedora. O GNOME Keyring `login` é desbloqueado manualmente pela aba Cofre, com senha digitada apenas pelo usuário no Moto G06 e autenticação SSH/PTY privada. O Copilot CLI conseguiu utilizar credenciais existentes depois do desbloqueio. Nenhum código do My-CT depende da Narys.

**Operação:** conectar Android via SSH, abrir Saúde ou Cofre. O Core **não** precisa ser iniciado manualmente. O cofre **não** é desbloqueado automaticamente. O banco PostgreSQL permanece independente.

Os PRs #13 e #14 foram mesclados à `main`. [Relatório final](docs/MYCT-4-FECHAMENTO-REPORT.md) · [Instruções de retomada](docs/10-RETOMADA-PROJETO.md).

## Ponto de partida

A CLI C++20 `myct-download` entrega HTTP/HTTPS via curl e execução transitória no systemd de usuário; build, uso e cancelamento no [relatório MYCT-5A](docs/MYCT-5A-DOWNLOADS-REPORT.md). Testes reais no Fedora passaram; **continuidade após desconexão SSH/fechamento do app e integridade de 262144 bytes foram comprovadas pelo proprietário**. A aba Downloads 0.5.2 está integrada ao Android com aceite físico parcial, conforme [relatório MYCT-5B](docs/MYCT-5B-ANDROID-DOWNLOADS-REPORT.md). BitTorrent/magnet foi integrado e aceito na MYCT-5C, inclusive nomes legíveis confirmados no Moto G06; rotinas automatizadas da MYCT-6 seguem pendentes. O refinamento Android (lag, navegação, scroll SFTP e transferência em segundo plano) é uma trilha separada, adiada na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9); não iniciar por conta própria. Priorizar um fluxo útil por vez e preservar serviços, dados e credenciais existentes.
