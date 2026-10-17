# My-CT — requisitos e critérios verificáveis
**Versão:** 0.1 · **Estado:** backlog inicial, sujeito a negociação por entregas

## Vocabulário de prioridade
- **P0 / essencial:** capacidade central para o primeiro produto útil.
- **P1 / importante:** buscar no ciclo inicial se P0 estiver estável.
- **P2 / evolução:** não bloquear a versão inicial.
- **Aceito:** só quando testado no hardware real e com evidência no diário, não com mocks isolados.

| ID | Prioridade | Requisito | Evidência mínima |
| --- | --- | --- | --- |
| INF-01 | P0 | Fedora headless com serviços autoiniciados | Reinício, reconexão SSH e verificação systemd |
| INF-02 | P0 | Core C++20 compilável/reproduzível | Build limpo e execução de binário no Fedora |
| INF-03 | P0 | PostgreSQL utilizado pelo Core | Escrita/leitura real, serviço local e acesso restrito |
| AND-01 | P0 | APK Kotlin/Compose instalável no Moto G06 | Instalação e abertura em aparelho físico |
| SSH-01 | P0 | Terminal Fedora interativo via SSH | Executar pwd, git status e htop ou equivalente PTY |
| SSH-02 | P0 | Teclas especiais e resize | Ctrl, Tab, setas, interrupção e redimensionamento reais |
| SSH-03 | P0 | Verificação de identidade do host | Chave SSH conhecida, mudança rejeitada até confirmação explícita |
| FILE-01 | P0 | Listagem remota SFTP | Navegar diretórios permitidos do usuário |
| FILE-02 | P0 | Upload Android → Fedora | Enviar arquivo real escolhido via SAF e conferir integridade |
| FILE-03 | P0 | Download Fedora → Android | Receber arquivo real em destino escolhido e conferir integridade |
| FILE-04 | P1 | Progresso/cancelamento/conflitos | Transferência grande cancelável; nomes repetidos tratados |
| DASH-01 | P0 | Saúde real do servidor | CPU, RAM, disco e estado dos serviços em atualização sob demanda |
| DL-01 | P1 | Download HTTP/HTTPS no Fedora | URL válida, status e arquivo íntegro após app desconectar |
| DL-02 | P1 | Download BitTorrent/magnet | Torrent legal de teste, status, pausa/retomada e conclusão |
| DL-03 | P1 | Ações no APK | Adicionar, pausar, retomar, cancelar e localizar destino |
| AUTO-01 | P1 | Rotina autônoma persistente | Job pré-definido executa sem app aberto e gera histórico |
| OPS-01 | P0 | Operação sem dependência de Codex | Guia de build, start/stop, logs, rollback e recuperação |
| SEC-01 | P0 | Superfícies administrativas privadas | Sem binding público de API, SSH com autenticação segura |
| SEC-02 | P0 | Privilégios mínimos e segredos protegidos | Sem root obrigatório para Core; sem senhas no repositório/log |
| UX-01 | P1 | Interface responsiva e estados explícitos | Fluxos de conexão, offline, permissão negada e falha recuperável |

## Jornadas de aceitação que prevalecem sobre testes de unidade

### A. Terminal remoto real
No Moto G06, conectar ao Fedora com host key verificada; abrir shell interativo com PTY; executar comando, abrir htop, usar Ctrl-C/setas, alternar de tela e reconectar. Não contar saída textual falsa como êxito.

### B. Arquivos em ambos os sentidos
Selecionar foto/documento do Android; transferir via SFTP; verificar arquivo no Fedora; baixar outro arquivo para local autorizado pelo Android; abrir/inspecionar o resultado. Testar nome existente e perda de conectividade.

### C. Download independente do app
Adicionar URL HTTPS ou magnet de conteúdo legitimamente distribuído; iniciar; fechar o app; retornar e consultar o estado; conferir o arquivo final no explorador. Distinguir download de cópia SFTP.

### D. Core C++ + banco reais
Inicializar PostgreSQL, executar migração mínima e Core como usuário não privilegiado, obter métrica real e registrar operação no banco. Reiniciar serviço e preservar dados.

### E. Reboot e recuperação
Reiniciar Fedora; serviço necessário volta, SSH continua acessível; verificar logs, consumo de memória e integridade das transferências/tarefas segundo política definida.

## Matriz mínima de testes
- **Unitários:** parsing de entradas, estados de tarefa/download, validação de caminhos e respostas HTTP.
- **Integração:** libpq/Postgres, API↔daemon, SSH/SFTP com Fedora.
- **Android instrumental/físico:** permissões SAF, host key, PTY, teclado, vida útil da sessão, compartilhamento de links.
- **Operação:** serviços systemd, baixa memória, disco quase cheio, desconexão e restart.
- **Segurança:** origem não autorizada, tokens/credenciais ausentes, caminho malicioso, endpoints expostos por engano, exclusão com confirmação.

## Sem falsa conclusão
Cada requisito terá status: **não iniciado → em andamento → validado no hardware → aceito** ou **adiado/descartado com justificativa**. Registro de teste e commit acompanhando o status. Nenhum requisito deste documento está inicialmente marcado como implementado.


## Estado de execução e aceite — 15/10/2026

Este checkpoint complementa a tabela de requisitos inicial, **sem reescrever seus critérios**. Aceite de uma jornada funcional não significa comprovação automática de casos adversos ou de estabilidade prolongada.

| IDs | Estado atual | Evidência e limite |
| --- | --- | --- |
| AND-01 | **Comprovado no Moto** | APK nativo instalado e usado; `myct-2r-debug.apk` versão 0.2.1/code4. |
| SSH-01, SSH-03 | **Fluxo real comprovado** | SSH autenticado com host pinada e PTY, `ls/cd/pwd`. Testes completos de TUI e reconexão adversa não certificados. |
| SSH-02 | **Funcionalidade relatada no aparelho, detalhes pendentes** | IME/resize/orientação, teclas/clipboard ajustados; lag permanece. |
| FILE-01 | **Comprovado no Moto** | Navegação/listagem remota SFTP funcionando. UX de retorno à pasta pai perde posição de scroll (dívida). |
| FILE-02 | **Fluxo real comprovado** | PDF enviado do Moto G06 ao Fedora com sucesso, após MYCT-2R; não equivale a hash bilateral documentado. |
| FILE-03 | **Fluxo real comprovado** | Duas imagens e PDF baixados e abertos no Moto. Primeiro incidente MYCT-2 não teve causa exata identificada. |
| FILE-04 | **Parcial: implementado, teste físico incompleto** | Progresso, cancelamento, conflito sem sobrescrita e tratamento de parcial testados por código/JVM; cenários adversos e background dataSync não certificados no Moto. |
| UX-01 | **Parcial / dívida** | Interface ainda rígida, scroll reinicia ao voltar da pasta e lag perceptível. `shouldEnforceCharBasedInput=false` melhorou subjetivamente. |
| INF-01 | **Parcial** | Fedora headless com SSH funcional; reinicialização automática do Core e do cluster My-CT por systemd não implementada. |
| INF-02 | **Comprovado no Fedora** | MYCT-3A/3B: binário C++20 compilou e foi executado no hardware; 2/2 (3A), 3/3 (3B) testes locais PASS. |
| INF-03 | **Comprovado no Fedora (persistência mínima)** | libpq/CLI, PostgreSQL 18.6 exclusivo com socket Unix privado; registro persistiu após reiniciar Core e banco; 1/1 integração PASS. |
| DASH-01 | **Comprovado no Moto (telemetria)** | CPU/RAM/disco/uptime reais via túnel SSH autenticado no APK MYCT-3C; captura física do proprietário após hotfix de contraste. Supervisão de serviços/autostart segue pendente em INF-01. |
| DL-01/02/03, AUTO-01 | **Não iniciado** | SFTP é transferência Android↔Fedora; downloads HTTP/torrent independentes no servidor não existem. |
| OPS-01, SEC-01, SEC-02 | **Parcial** | Core local/API em loopback, banco Unix/peer e operação manual documentada; sem autostart/restore validado nem Dashboard remoto. |

**Decisão do proprietário:** encerrar este ciclo Android/SSH/SFTP com pendências explícitas na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9) e [trilha de refinamento](09-TRILHA-REFINAMENTO-ANDROID.md), sem forçar rodadas de polimento antes do Core. [Histórico integral](08-FECHAMENTO-CICLO-ANDROID.md).


## Checkpoint MYCT-3A/3B integrado — 15/10/2026

- [PR #10](https://github.com/Samuel-Francisco-AS/My-CT/pull/10) e [PR #11](https://github.com/Samuel-Francisco-AS/My-CT/pull/11) integrados à `main` em sequência após provas reais no Fedora.
- **INF-02 e INF-03:** comprovação funcional do Core C++20 e persistência PostgreSQL com registro preservado após reinícios; compilação e integração passaram. Nenhum serviço My-CT autoiniciável foi implantado.
- **DASH-01 foi posteriormente comprovado no Moto G06 na MYCT-3C**, com API acessada por túnel SSH autenticado e captura física após correção de contraste. O marco Core→banco→API→Android foi alcançado; autostart systemd continua pendente (INF-01).
- Base factual e SHAs: [fechamento MYCT-3A/3B](MYCT-3AB-FECHAMENTO-REPORT.md); recuperação e comandos [server/README.md](../server/README.md).


## Checkpoint MYCT-3C integrado — 15/10/2026

- [PR #12](https://github.com/Samuel-Francisco-AS/My-CT/pull/12) mesclado à `main` (`fd8076321cd32300e2c6305954adc1b134e26d77`). Build, 34 testes Android aprovados, 1 opcional ignorado, lint sem erros; primeira inspeção no Moto comprovou comunicação real, porém revelou contraste ruim. Após hotfix `ec0d16d`, nova captura confirmou legibilidade, e proprietário declarou os testes físicos aprovados.
- **DASH-01 (CPU/RAM/disco/uptime real no Android): comprovado no Moto**, por túnel da conexão SSH verificada; sem polling nem API exposta à rede.
- **INF-01 permanece parcial:** `./myct-core` exige início manual; persistência pós-logout/reboot, gerenciamento e reinício automático ainda **não implementados**. Novo recorte systemd requer autorização.
- Evidências em [MYCT-3C-DASHBOARD-REPORT.md](MYCT-3C-DASHBOARD-REPORT.md). Dívidas de lag, SFTP scroll e background continuam [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).


## E. MYCT-L1 — requisitos aprovados, L1A parcial e L1B candidata (17/10/2026)

Esta fase foi priorizada **entre MYCT-R4 e MYCT-6/T5**. Os itens abaixo exigem prova física; a L1A implementa LOC-01/LOC-04 e os aspectos de armazenamento/targetSdk de LOC-05, **com aceite físico parcial descrito abaixo**:

| ID | Prioridade | Requisito | Evidência mínima para aceite no Moto G06 |
| --- | --- | --- | --- |
| LOC-01 | P1 | IDE Sora + arquivos privados/SAF funcionais sem SSH, Fedora, internet ou aplicativo Termux instalado | Modo avião: criar, editar, salvar e reabrir `teste.c` local; conexão remota anterior não sofreu regressão |
| LOC-02 | P1 | Terminal Android local com PTY real, isolado do SSH | Modo avião: `pwd`, `echo`, `ls`, shell interativo, saída/entrada, resize e Ctrl+C, dentro da sandbox |
| LOC-03 | P1 | Compilar e executar C de console localmente sem Termux | Modo avião: `teste.c` compilado, executado com saída verificável e erros mostrados no próprio Moto; tecnologia escolhida após spike real |
| LOC-04 | P1 | Alternância Local/Remoto sem perda acidental de buffer | App não exige login SSH no modo Local; salvar local não escreve via SFTP; dirty buffer protegido; remoto continua funcionando |
| LOC-05 | P1 | Compatibilidade com regras Android modernas | Sem root ou execução arbitrária de binários em diretório gravável, sem baixar targetSdk; licenças e distribuição de toolchain registradas |

**Fora do gate L1:** Linux/POSIX completo, pacotes arbitrários, Cargo/Rust, Python/JavaScript, C++ completo, ambiente root, IDE LSP/debugger. Futuras expansões opcionais. Detalhes: [MYCT-L1](MYCT-L1-AMBIENTE-LOCAL.md). 

## Checkpoint MYCT-L1A — implementação para revisão

LOC-01 e LOC-04: workspace privada/SAF, Sora offline, estados independentes e
salvamento explícito implementados, com testes de filesystem e isolamento.
LOC-05 aplicável: targetSdk 36 preservado, sem root/permissão ampla/executáveis
novos. LOC-02/03 ficaram fora da missão L1A. **Retorno posterior do proprietário:**
criação de pasta/arquivo, edição no Sora, salvamento/persistência após fechar e
reabrir e renomeação preservando arquivo confirmados na code 24. SAF, isolamento
completo e regressão remota continuam sem aceite físico individual. [Relatório, APK e checklist](MYCT-L1A-RELATORIO.md).


## Checkpoint MYCT-L1B — candidata, LOC-02 pendente no Moto

PTY local real em JNI/C++20, `/system/bin/sh`, workspace da IDE, resize/sinais e
sessão independente do SSH implementados na code 25. LOC-02/04/05 exigem gate
físico A–F (modo avião, Gboard/rotação/clipboard, retorno IDE e isolamento com
SSH real). Testes JVM e PTY no host não aprovam Android. LOC-03 e escolha de
toolchain não iniciados. [Relatório e checklist](MYCT-L1B-RELATORIO.md).


## Checkpoint MYCT-L1C — candidata, gate físico pendente

LOC-03: WCPL embarcado compila C para WASI real e WAMR interpreta no console da
IDE Local, com stdin/erros/retorno/interrupção. LOC-04/05: buffers/SSH preservados,
sem exec de artefatos graváveis, JIT ou redução do targetSdk. Prova de compilação
e execução em host e build NDK não aprovam Android: **todos os testes físicos
L1C estão PENDENTES** até confirmação do proprietário. [Relatório](MYCT-L1C-RELATORIO.md).
L1B teve confirmação física parcial de shell/pwd/ls/echo/cd/workspace/Ctrl+C e
fluidez; os demais gates continuam pendentes.

L1D aprovada para depois da L1C: acesso direto ao armazenamento compartilhado
autorizado; avaliar APIs/SAF/MANAGE_EXTERNAL_STORAGE na própria fase, sem
implementação ou concessão de permissões nesta missão.
