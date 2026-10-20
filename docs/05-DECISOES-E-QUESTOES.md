# My-CT — registro de decisões e questões abertas
**Base:** conversas inaugurais até 14/10/2026. **Não confundir preferência técnica com componente testado.**

## Convenções
- **DECIDIDO:** direção explicitamente escolhida pelo proprietário; pode ser revista por nova conversa.
- **PROPOSTO:** recomendação técnica, pendente de prova ou validação.
- **ABERTO:** há alternativas legítimas e ainda não ocorreu decisão.
- **SUBSTITUÍDO:** decisão antiga trocada, mantendo a justificativa histórica.

## Decisões de direção
| ID | Assunto | Estado | Decisão / motivo |
| --- | --- | --- | --- |
| ADR-001 | Produto | DECIDIDO | Servidor pessoal + computador remoto no mesmo projeto |
| ADR-002 | Nome | DECIDIDO | My-CT; manter grafia sem presumir significado da sigla |
| ADR-003 | Servidor | DECIDIDO | C++20 como stack principal, não Node.js como motor do sistema |
| ADR-004 | Banco | DECIDIDO | PostgreSQL em vez de SQLite |
| ADR-005 | App | DECIDIDO | Android nativo com Kotlin/Jetpack Compose |
| ADR-006 | Terminal | DECIDIDO | Shell Fedora real via SSH/PTTY dentro do APK |
| ADR-007 | Arquivos | DECIDIDO | Transferência entre Android ↔ Fedora e explorador acessível no APK |
| ADR-008 | Downloads | DECIDIDO | Download e gerenciamento pelo próprio servidor, inclusive torrent/magnet, sem dependência de µTorrent |
| ADR-009 | Operação | DECIDIDO | Headless, econômico, independente do app e sem dependência de IA |
| ADR-010 | Plano | DECIDIDO | Planejamento inaugural flexível e sujeito a discussão/alteração |
| ADR-011 | Identidade visual MYCT-R2 | DECIDIDO | Preto profundo/sombreado e dourado/âmbar vivo em variações, inspirados na linguagem estética de Deus Ex: Human Revolution, com hierarquia e legibilidade; sem cópia de assets |
| ADR-012 | Navegação Android | DECIDIDO | **Nenhum cabeçalho global permanente**, nem hambúrguer/título/status; cinco seções, status SSH, Opções e encerrar exclusivamente no drawer oculto acessível por gesto da esquerda à direita; exceções somente para controles locais funcionais |
| ADR-013 | Princípio UX | DECIDIDO | **Fluidez**: feedback imediato, preservação de estado/posição, transições suaves e curtas, ausência de saltos, piscadas ou efeitos pesados; acessibilidade e ergonomia acima de ornamento |
| ADR-014 | Recorte MYCT-R2 | DECIDIDO | R2A implementa tema global e drawer/shell, sem alterar lógicas de telas. R2B refaz visual das telas, diálogos e oferece ajuda opcional por ícone `i`, com avisos críticos sempre visíveis quando necessários. R-01/lag permanece dívida suspensa |
| ADR-015 | Ambiente local independente | DECIDIDO | MYCT-L1 deve funcionar offline no Moto G06 sem Fedora, SSH e sem o aplicativo Termux instalado; pode reutilizar código Termux embutido e licenciado |
| ADR-016 | Ordenação das fases | DECIDIDO | Após aceite/merge da MYCT-R4/Fix, executar L1A → L1B → L1C antes da MYCT-6/T5 de automações; nenhuma implementação antecipada |
| ADR-017 | Critério da primeira L1 | DECIDIDO | IDE e arquivos locais, terminal Android/PTY local e prova física de editar, compilar e executar C **offline** no Moto; C++/linguagens adicionais e Linux completo são posteriores |
| ADR-018 | Tecnologia compilação local | ABERTO | Prova curta compara compilação C para WebAssembly/WASI e alternativa nativa Android segura; sem garantia de compatibilidade com Linux, sem burlar restrições targetSdk 36 |

## Propostas técnicas (não congelar sem prova)
| ID | Tema | Proposta | Como decidir |
| --- | --- | --- | --- |
| ADR-P01 | HTTP Core | cpp-httplib | Prototipar API mínima, validar manutenção/segurança |
| ADR-P02 | Banco C++ | libpqxx | Testar empacotamento e conexão no Fedora |
| ADR-P03 | HTTP downloads | libcurl | Demonstrar download grande, cancelamento e retomada |
| ADR-P04 | BitTorrent | Transmission-daemon em serviço isolado | Testar API, consumo, status e permissões |
| ADR-P05 | SSH Android | mwiede/JSch ou alternativa mantida | Testar PTY/host key/conexão/reconexão no Moto G06 |
| ADR-P06 | Terminal renderer | Biblioteca madura apropriada ao Android | Testar ANSI, resize, teclado e licenças |
| ADR-P07 | Rede externa | Tailscale | Confirmar viabilidade e segurança, sem exposição direta |
| ADR-P08 | Orquestração | systemd + funções C++ específicas | Comparar simplicidade e requisitos reais |

## Questões abertas para conversas futuras
1. Qual nível de autonomia para automações e quais ações merecem confirmação humana?
2. Política de autenticação do aplicativo: reutilizar identidade SSH ou emitir credencial separada para API? Como bloquear dispositivo perdido?
3. Como comunicar com a API de fora da LAN (VPN, túnel, TLS), sem comprometer o uso local?
4. Políticas de paths, quotas, arquivos muito grandes, velocidade, seeding, espaço livre e retenção de downloads?
5. É desejável um gerenciador de arquivos com operações destrutivas desde a V0.1, ou inicialmente só navegar/enviar/baixar?
6. Melhor forma de notificações Android sem bateria/servidor consumindo recursos em excesso?
7. Quando adicionar tarefas automatizadas de busca de vagas, repositórios e backups sem criar um executor genérico perigoso?
8. Backup externo: destino, periodicidade, retenção e restauração testada?
9. Nome para o aplicativo exibido ao usuário, ícone/identidade visual e convenções de UX?
10. Como medir consumo elétrico e decidir entre 24/7 ou janelas de operação?

## Como registrar novas decisões
Acrescentar **ADR-NNN** com data, contexto, opções, escolha, trade-offs e consequências. Uma decisão pode ser revisada explicitamente; não apagar histórico nem reclassificar um protótipo como produto pronto. Manter links para issues/commits quando existirem.

## 2026-10-14 — escolhas experimentais MYCT-1 (revisáveis)

**Estado: PROPOSTO / parcialmente validado, pendente de Moto G06.** Para a prova
SSH, foram fixados mwiede/JSch 2.28.8 (BSD), Bouncy Castle 1.86 (MIT) para
algoritmos modernos no Android e os módulos Apache 2.0 `terminal-emulator` e
`terminal-view` do Termux v0.118.3, commit `5b657c6adf4304e5198951ce815fe0205dcac29c`.
JSch foi preferido ao JSch original sem manutenção; SSHJ permanece alternativa
se o comportamento físico exigir troca. Termux foi preferido a um renderer
ANSI próprio, reutilizando IME, seleção, buffers e teclas. Apenas fontes Java
e recursos dos módulos foram importados; a sessão local/JNI foi substituída
por adaptador de transporte SSH, sem incorporar o aplicativo Termux GPL.

A primeira prova limita autenticação a senha efêmera e host key Ed25519,
exige fingerprint SHA256 obtido por canal confiável e fixa o pin por
endereço/porta. Importação de chave privada/MFA e serviço Android em background
ficam pendentes de necessidade concreta. Não alterar sshd para acomodar essas
limitações. MinSdk 26 é um limite de build, ainda sem prova de compatibilidade
no aparelho. Gradle 8.14.3 `all` reutiliza distribuição já existente; AGP 8.13.0,
Kotlin 2.2.20, Compose UI/Foundation 1.9.3 e Material3 1.4.0 compõem a prova.

A arquitetura e a ordem do planejamento inaugural não foram modificadas.
Critérios, evidências, limites e retomada: [relatório MYCT-1](MYCT-1-ANDROID-SSH-REPORT.md).

## 2026-10-14 — MYCT-1R: continuidade e correções (revisáveis)

**Estado:** implementação corretiva, pendente de aceite físico do novo APK.
A prova física da MYCT-1 confirmou login/shell e PTY; não confirmou continuidade.
O motor passa a escopo de processo compartilhado por Activity e serviço opt-in,
sem credenciais em Intent/armazenamento e sem reconexão ou comandos automáticos.
Foreground service `specialUse` descreve terminal SSH interativo na propriedade
de subtipo. Não foram pedidos CHANGE_NETWORK_STATE/CHANGE_WIFI_STATE sem uso
real para enquadrar connectedDevice, nem usado dataSync/remoteMessaging como
atalho. Escolha justificada documentalmente; compatibilidade real e política
Play Store não são declaradas aprovadas. Notificação exigida por esta UX,
parada explícita, START_NOT_STICKY e ausência de boot/wakelock são limites.

Clipboard manual usa entrada distinta de OSC; captura protegida torna-se
preferência local. Dois patches mínimos da view upstream estão registrados
com hashes, sem reimplementar ANSI. tmux 3.7c existente recebe procedimento
manual de criação/attach no namespace myct; o app apenas exibe/copia comando,
não o executa ao conectar. Processo remoto e conexão Android são camadas
separadas. Evidências, limites e retomada no relatório MYCT-1R; plano inaugural
preservado.

### MYCT-2 — recorte SFTP e entrada IME (15/10/2026)

- Por decisão do proprietário, suspender investigação de latência MYCT-1P e
  implementar SFTP nativo + preferência IME reversível, default true.
- Reutilizar JSch 2.28.8/sessão SSH autenticada. Um canal exclusivo por operação
  sequencial, separado da PTY; SAF Open/CreateDocument, sem permissões amplas.
- Sem sobrescrita nesta entrega: conflitos pedem outro nome. Publicar upload
  temporário com hardlink@openssh.com, pois rename JSch pode selecionar POSIX
  com substituição. Requer extensão/filesystem compatíveis; falha explícita
  sem fallback inseguro. Reavaliar portabilidade em missão futura, se necessário.
- Cópias acompanhadas com Activity visível; sair cancela. Não ampliar o FGS de
  terminal para transferências persistentes. Hash remoto/retomada avançada e
  operações destrutivas ficam fora do recorte. Aceite físico MYCT-2 pendente.

### MYCT-2R — transferência iniciada pelo usuário em background (15/10/2026)

- Rodada corretiva autorizada após relato físico no PR #7: duas imagens e
  navegação OK; PDF não abriu após queda relatada; vídeo cancelou ao ocultar.
  Não atribuir corrupção ou causa TCP sem evidência. MYCT-1P segue suspensa.
- Substituir a política de cancelar sempre em onStop por continuidade enquanto
  serviço **dataSync separado** estiver ativo (ou iniciando por ação visível).
  specialUse permanece exclusivamente SSH interativo. Não há senha/URI em
  Intent, jobs genéricos, replay, wakelock ou pedido de exceção de bateria.
- Android documenta dataSync para upload/download. UIDT (API34+) foi considerado,
  mas acrescentaria agendamento/constraints e outro caminho para min26, para
  uma cópia imediata que depende da Session já autenticada em memória. O recorte
  usa dataSync com limite Android15+ e stop imediato em onTimeout; não promete
  transferências indefinidas nem aceite Play Store. Reavaliar se duração real
  exceder limites, sem improvisar uso de specialUse para contorná-los.
- Sem notificação permitida/canal ativo, a cópia permanece visível e cancela ao
  ocultar. Parada do processo é definitiva; marcador booleano indica possível
  parcial ao reabrir, sem guardar caminhos privados nem retomar automaticamente.
- Cancelamento aborta só canal; worker fecha streams. Queda TCP real continua
  afetando Session/PTY/SFTP. SIZE SAF opcional indisponível não é corrupção;
  divergência de contagem/tamanho é falha, não sucesso.
- Referências: [tipos FGS/dataSync](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync),
  [limites](https://developer.android.com/develop/background-work/services/fgs/timeout),
  [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt).
  Evidências e riscos no relatório MYCT-2R; gate físico pendente.


## 2026-10-15 — fechamento do ciclo Android e dívidas (decisão do proprietário)

- **DECIDIDO:** integrar a implementação Android/SSH/SFTP à `main` e tratar a fase inicial como consolidada, com aceite do **fluxo feliz comprovado fisicamente** e limitações documentadas. O merge não reclassifica background SFTP nem cenários adversos como aprovados.
- **DECIDIDO:** manter `shouldEnforceCharBasedInput` configurável true/false e não retomar investigação pesada de lag agora. O modo false reduziu perceptivelmente o atraso para o proprietário, mas não o eliminou; causa permanece aberta. Padrão inicial do app permanece true, sem mudança silenciosa.
- **DECIDIDO:** abrir e adiar trilha de refinamento (lag, UI rígida, scroll SFTP que perde posição ao voltar, validação background/cancelamentos) documentada em [docs/09](09-TRILHA-REFINAMENTO-ANDROID.md) e [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).
- **PROPOSTO, não aprovado automaticamente:** próxima entrega Core C++20 + PostgreSQL + dashboard de saúde real; menor demonstração e segurança/rede a decidir na conversa seguinte. Sem backend ou instalação deste ciclo.
- **Evidência:** após MYCT-2R, proprietário baixou/abriu PDF do Fedora e enviou outro PDF ao Fedora com sucesso; MYCT-2 havia transferido/aberto imagens. Serviço Android dataSync de SFTP ainda **não testado fisicamente**. [Relatório](08-FECHAMENTO-CICLO-ANDROID.md).

## 2026-10-15 — MYCT-3B: persistência mínima autorizada

- **Implementação:** libpq direta substitui a candidata ADR-P02/libpqxx neste
  recorte: uma tabela, duas operações parametrizadas, sem benefício concreto de
  wrapper/ORM. Makefile da MYCT-3A preservado, sem CMake.
- Banco `myct` em cluster exclusivo no diretório do usuário, socket Unix privado,
  peer e TCP desativado; sem tocar instâncias de outros projetos ou serviços.
- Migração SQL explícita/transacional; CLI grava/consulta eventos com ID e UTC.
  HTTP de saúde não consulta banco e não ganha endpoints de escrita.
- Aceite exige compilação e gravação/leitura reais, sobrevivência ao encerramento
  do Core, saúde com banco parado e bind local. Instalação depende de autorização;
  evidência e gate corrente: [relatório MYCT-3B](MYCT-3B-POSTGRES-REPORT.md).


## 2026-10-15 — MYCT-5A: HTTP/HTTPS pequeno e independente

- Recorte autorizado na missão MYCT-5A: CLI pelo Terminal SSH; torrent, Android
  Downloads, automação genérica, agendamento e integração PostgreSQL adiados.
  Não encerra todos os itens da issue #4.
- C++20 em executável separado `myct-download`, reutilizando curl 8.18 instalado
  (libcurl-devel ausente). Não instalar dependências para obter o mesmo transporte.
- Unidade transitória de systemd **de usuário** por transferência, com linger já
  existente e verificado, sem instalar serviço permanente nem tocar no Core.
  Um download ativo, sem fila, com falha explícita para segundo pedido.
- Metadados privados em arquivo bastam para consultar progresso/resultado após
  desconectar; exigir PostgreSQL manteria dependência sem utilidade nesta fase.
  Arquivos dedicados por ID, `.part` até sucesso, publicação sem sobrescrita,
  reserva de 16 MiB e limite pelo espaço disponível. URLs HTTP(S) e destinos
  públicos validados a cada redirect; LAN somente por opção explícita.
- [Relatório MYCT-5A](MYCT-5A-DOWNLOADS-REPORT.md): testes Fedora e uso.
  **Aceite SSH/Moto pendente** por autenticação SSH indisponível ao agente; não
  confundir saída da CLI com prova de encerramento da sessão. Sem merge/deploy.

## 2026-10-15 — MYCT-5B: Android como cliente da CLI existente

- Quinta aba Downloads, sem API/daemon/banco novos. Canais SSH exec curtos na
  sessão autenticada existente; PTY Terminal e PTY privado Cofre preservados.
- Caminho fixo `$HOME/.local/bin/myct-download`, verificado por `test -x` a cada
  operação. Instalação manual pelo proprietário, sem sudo. Android nunca instala.
- Polling de 4 s apenas com aba/Activity visíveis e SSH conectado. Sem fila,
  reenvio de ações ou histórico local exclusivo. Histórico vem de `list`.
- Integração pequena com `FilesModel.load(path)` permite Ver em Arquivos sem
  refatorar o SFTP. Barra de abas ganha rolagem horizontal para telas estreitas.
- Escopo continua HTTP/HTTPS público. Não expor `--allow-private` como nome nem
  opção Android; a CLI mantém validação completa de URL/nome/DNS/destino.
- Desvio pontual justificado por testes: `show()` da CLI relê estado depois de
  detectar unidade inativa. Corrida observada em três testes existentes produzia
  `interrupted` junto de mensagem final. Teste determinístico simula término
  durante `systemctl`; sem alterar motor, serviços instalados ou regras de rede.
- Aceite físico MYCT-5B pendente no Moto; aceite MYCT-5A permanece válido.
  [Entrega e roteiro](MYCT-5B-ANDROID-DOWNLOADS-REPORT.md).

## 2026-10-16 — MYCT-5B encerrada com aceite parcial

- **DECIDIDO pelo proprietário:** integrar o PR #16 à `main` e adiar
  validações físicas restantes, sem bloquear novas funcionalidades.
- **Provas Moto G06:** histórico e download real na aba Downloads,
  edição com foco/IME estável após 0.5.1 e botões sem piscadas na 0.5.2.
- **Não provado no aparelho:** Ver em Arquivos, cancelar, reconectar e
  recuperar histórico, clique durante polling, progresso lento. Testes
  automatizados passam, mas não substituem os testes físicos; issue #9.
- **Integração:** merge `83e053737ff782ecf523bf31776fec9da55889dd`.
  PostgreSQL/Core/Cofre/serviços existentes não foram alterados.
  [Relatório final](MYCT-5B-ANDROID-DOWNLOADS-REPORT.md).

## 16/10/2026 — MYCT-5C: Transmission e adaptador operacional mínimo

Candidata na branch `myct-5c-bittorrent`, sem aceite físico/merge. Usar
Transmission 4.1+ com JSON-RPC 2.0 somente loopback e unidade dedicada de usuário,
sem API no Core nem pacote transmission-cli. Adaptador SSH `myct-torrent` em
Python stdlib já presente: exceção pequena de linguagem para não acrescentar
parser JSON/HTTP C++ ou biblioteca externa a um wrapper operacional. Backend
principal/Core e motor HTTP continuam C++20. Metadados/resume pertencem ao
Transmission. Diretório por hash sob `Downloads/My-CT/Torrents` evita colisões
entre torrents distintos; remoção preserva dados. Seeding do motor é explícito
na UI; não alterar limites silenciosamente. `.torrent` SAF fica complementar.
Pacote/ativação ainda dependem do proprietário, sem prova real do motor.
[Arquitetura, instalação, riscos e gates](MYCT-5C-BITTORRENT-REPORT.md).

## 16/10/2026 — correção MYCT-5C: título real na conclusão, por RPC

Decisão solicitada pelo proprietário no PR #17: nomes legíveis para novos
torrents, sem migrar existentes nem prejudicar seeding. Usar hook nativo de
conclusão e `torrent_set_location(move=true)` com título `torrent_get.name`
confirmado por metadados, sufixo hash8 e registro privado por hash. Adiar o nome
final até conclusão evita worker/timer para aguardar metadados e funciona sem
Android. Nenhum serviço novo; nenhuma renomeação de arquivo interno, mudança
em ratio/banda ou efeito de escrita em list/status. Colisões e RPC ambíguo não
repetem move automaticamente. CLI e hook atualizados na instância dedicada por
RPC, sem restart. APK 0.5.4/code 11 necessário para aceitar o caminho legível.
[Prova real, instalação e limites](MYCT-5C-BITTORRENT-REPORT.md).

## 2026-10-16 — MYCT-5D-A: inspeção pontual pelo SSH existente

CLI C++20 separada, JSON v1, SHA-256 via OpenSSL EVP já disponível e MIME via
`file`. Escopo de inspeção limitado ao home do usuário SSH, mais restrito que a
navegação SFTP, cobrindo os downloads atuais sem autorizar leitura fora do home.
`openat2` recusa symlinks/escape; stdin aberto permite cancelar sem serviço ou
fila. Limite de 30 min; sair do app cancela. Sem API, banco ou deploy. Decisões
com prova automatizada no Fedora, experiência física ainda pendente; detalhes e
limites no [relatório MYCT-5D-A](MYCT-5D-A-REPORT.md).

## 2026-10-16 — MYCT-5D-B1: ClamAV pontual, base do usuário e timer de 36h

Solicitação explícita do proprietário para B1 Fedora, sem APK/B2. ClamScan sem
daemon, worker C++20 com validação compartilhada da 5D-A e O_NOATIME obrigatório,
argv fixo e leitura do inode via stdin. Adaptador operacional em Python stdlib
segue a exceção do myct-torrent para JSON/systemd/locks, sem biblioteca nova;
Core/backend principal continuam C++20. Análise estritamente informativa.
Base ClamAV privada, FreshClam sem root pelo mesmo serviço oneshot para CLI e
timer; OnStartupSec=5min e OnUnitInactiveSec=36h, sem persistir tempo desligado.
Lock único entre análise e atualização limita RAM e estabiliza versão da base;
update aguarda scanner terminar. Freshness separada em 72h, admitindo margem
sobre o intervalo de 36h. Pacotes já instalados pelo proprietário, sem sudo.
Instalação/atualização real de B1 autorizadas; timer entregue desabilitado,
ativação permanente depende de confirmação. Consumo real ~1 GiB e ~11 s por
análise pequena, sem pressão observada. PR sem merge até validação.
[Entrega, provas e limites](MYCT-5D-B1-REPORT.md).

## 2026-10-16 — correção B1: entrada de 2 GB decimais

Pedido do proprietário no mesmo PR #20: até 2000000000 bytes inclusivos, abaixo
de INT_MAX−2 do ClamAV 1.4.6. `maxfilesize` e teto de tamanho PCRE em 2000000000;
`maxscansize` em entrada + 128 MiB, preservando expansão limitada. RLIMIT_FSIZE
do filho precisa ser 2000000000 porque stdin é copiado integralmente pelo motor
e um limite menor gera aviso. Memória 2 GiB, CPU 120 s, parede 180 s, recursão e
entradas preservados; aceitar o pedido não promete inspeção completa. Teste real
80 MiB CLEAN, ~22,33 s e ~1,01 GiB RSS; ZIP excedente INCOMPLETE. Timer já ativado
e validado pelo proprietário, confirmado ativo e sem alteração nesta correção.
[Detalhes e comando para atualizar somente o worker](MYCT-5D-B1-REPORT.md).

## 2026-10-16 — MYCT-5D-B2: relatório Android e assinaturas em Opções

Integrar só o contrato B1 via exec SSH existente. Leitor bruto preserva JSON em
exit 1 sem alterar política de erro dos consumidores anteriores; stdin aberto
somente no scan. Cancelamento por EOF/exec, sem encerrar sessão compartilhada.
SHA-256 e antivírus são ações/relatórios distintos no mesmo diálogo; sem scan
automático. Limite local inclusivo de 2000000000 bytes é preflight; CLI decide.
Assinaturas em uma seção dentro de Opções com retorno ao menu, preservando cinco
abas e evitando diálogo único excessivamente longo. STATUS/UPDATE_STATUS são
consultas; UPDATE só por botão. Pedido aceito não é sucesso: consultar resultado
real, polling 2 s apenas em execução/seção visível, sem replay ao reabrir.
Motor/timer Fedora intactos. APK 0.5.6/code 13 candidato, aceite Moto pendente.
[Implementação, testes e limites](MYCT-5D-B2-REPORT.md).

## 2026-10-16 — MYCT-R1C: workers SSH e resize consolidado

Autorizada refatoração localizada após relato físico de resize excessivo/lento.
Adotar workers dedicados e recepção limitada com entrega assíncrona à main,
inspirados na sessão Termux v0.118.3, sem subprocessos/JNI. Texto ordenado tem
prioridade; resize ocupa um único slot substituível, com estabilização só de
layout. Escrita/resize continuam serializados no mesmo worker. Aceite automático:
ordem de bytes, limites/backpressure, último resize, encerramento e reconexão;
aceite de responsividade ainda exige Moto. Ver MYCT-R1-TERMINAL-FILES.md.
O adaptador para JSch 2.28.8 evita que window-change herde espera de reply do
connect(timeout); usa setPtySize com want_reply=false e restaura timeout em
finally, apenas no canal interativo e no mesmo escritor. Teste de pacote real
sem rede protege esse acoplamento à versão fixada. Não muda autenticação.


## 2026-10-16 — MYCT-R2: identidade preto/dourado e fluidez (ADR-011 a ADR-014)

Decisão explícita do proprietário após revisão das capturas reais do Moto G06. O desenho deve seguir a estética tecnológica preto profundo/dourado do Deus Ex: Human Revolution sem reproduzir obras ou texturas do jogo. **Fluidez** é a palavra-guia de interações, transições e organização visual. O proprietário **rejeitou** a recomendação de manter qualquer barra global mínima; tudo que é global fica no drawer acessível por gesto, sem cabeçalho permanente em nenhuma aba. Caminho atual em Arquivos e barra de teclas do Terminal são conteúdos funcionais locais permitidos.

Toda a aplicação — inclusive seções não exemplificadas em capturas — deve adotar a identidade. Explicações longas não críticas ficam sob ícone `i`, mas alertas relevantes para decisão/segurança continuam visíveis no momento oportuno. **R2A só shell e tema; R2B redesenho de telas, com melhoria de Arquivos, Saúde, Cofre, HTTP/Torrents, Opções e diálogos AV.** R-01/lag SSH suspenso após 0.5.9; não reabrir diagnóstico e não fazer a experiência de digitação condição da R2A. Decisão e aceite detalhados em [MYCT-R2](MYCT-R2-IDENTIDADE-VISUAL-E-PLANEJAMENTO.md).


## 17/10/2026 — MYCT-L1 e reordenação antes da automação (ADR-015 a ADR-018)

O proprietário quer My-CT como estação de programação offline autossuficiente, não dependente do app Termux, do Fedora ou de internet, com fase inicial para programas de console, e expansão futura em direção a ferramentas Linux completas. O mesmo Sora, explorador e terminal gráfico devem atender **Local** e **Remoto** por backends de dados/execução distintos. A rota L1A (IDE/arquivos locais) → L1B (PTY/shell Android) → L1C (compilação e execução C offline) foi **aprovada para ser a fase seguinte ao fechamento da R4**. MYCT-6/T5 automações foi explicitamente adiada, sem cancelamento. Não presumir compilador funcional no target 36; escolher runtime/compilador após protótipo comprovado. Fonte: [MYCT-L1](MYCT-L1-AMBIENTE-LOCAL.md).

## MYCT-R4 — 17/10/2026 — editor remoto pessoal

Autorização expressa: última auditoria SSH + IDE na mesma sessão. Auditoria não
identificou nova causa demonstrável; transporte/emulador permanecem como R3.
IDE usa Sora 0.23.6 LGPL, AndroidView e TextMate local (C/C++, Rust, Python,
Kotlin, Shell e JavaScript), reusando sessão e canais SFTP existentes. Sem LSP,
NDK, Core, banco, execução automática ou serviço adicional. Um buffer em memória,
limites 1 MiB/30 mil linhas, snapshot só em abrir/salvar: fluidez antes de abas.
Save explícito com comparação integral, temporário verificado, modo/uid/gid,
hardlink de backup privado e posix-rename OpenSSH; resultado incerto bloqueia
novos saves até revisão/reabertura. SFTP não oferece CAS, ACL/xattr/hardlinks
existentes não são reproduzidos no novo inode; backup conserva inode anterior.
Evitar outro escritor no intervalo final; sem promessa de sincronização concorrente.
Arquivo deve pertencer ao usuário SSH, ter escrita de proprietário e não ter bits
especiais. Sem essas garantias/extensões, recusar em vez de truncar o original.
Buffers/undo sobrevivem à navegação, recriação da Activity e desconexão dentro do
mesmo processo; nenhuma cópia persistente/autosave silencioso. Back avisa sobre
buffer sujo; encerramento forçado do processo Android exige salvar antes.
Fontes/licenças e reconstrução em `android/IDE-LICENSES.md`; evidências e limites
em `docs/MYCT-R4-TERMINAL-IDE.md`. **Aceite físico R4-Fix concedido em 17/10/2026:** Sora editou/salvou `teste.c`, e GCC executou no Fedora com saída `My-CT IDE funcionando! Pós-r4-fix`; teclas 2×7, cabeçalho e navegação aprovados pelo proprietário. **PR #26 autorizado para merge**, SHA de integração registrado no fechamento da R4. Lag SSH continua R-01 suspensa.


## MYCT-R4 — PASS e merge #26 (17/10/2026)

R4-Fix 0.6.6/code 23 foi aceita fisicamente no Moto G06 após teste de IDE Sora com edição/salvamento e GCC Fedora retornando `My-CT IDE funcionando! Pós-r4-fix`. O proprietário aprovou os três ajustes de ergonomia e autorizou fechamento. [PR #26](https://github.com/Samuel-Francisco-AS/My-CT/pull/26) **MERGED** em `266e799a0fe3d5a4dd6d9a270095c3d2f3da6005`; [relatório formal](MYCT-R4-FECHAMENTO.md). As decisões sobre L1 (ADR-015 a ADR-018) permanecem intactas e o marco de entrada L1 está liberado. MYCT-6 automações segue posterior à L1. A investigação SSH da R4 não encontrou solução comprovada; lag R-01 continua suspenso.


## 17/10/2026 — MYCT-L1B: PTY Android próprio, sem novo serviço

Missão expressa do proprietário autoriza JNI/NDK e continuidade sobre L1A ainda
sem merge (#28 draft), após teste físico parcial da IDE/code 24. Escolha:
`openpty` (API 23, minSdk 26) + fork/setsid/TIOCSCTTY/dup2/execve de
`/system/bin/sh -i`. Ponte própria pequena em biblioteca legítima do APK,
NDK r28c/CMake, sem importar o app/JNI do Termux, pipes como transporte de shell,
root, distribuição ou execução de binário em diretório gravável. Mesmo renderer,
filas por sessão e dono de processo; seletor contextual independente do SSH.
Sem FGS local: sobrevive a navegação/Activity dentro do processo, sem promessa
após morte do app. Cwd/HOME usam a raiz L1A, sem chroot: shell tem o UID e as
permissões do app. [Rationale, APIs, validação e gate Moto pendente](MYCT-L1B-RELATORIO.md).
ADR-018/toolchain C fica para L1C, sem estudo nesta missão. Nenhum merge
realizado, Fedora/FGS SSH intactos, R-01 continua suspensa.

## 17/10/2026 — MYCT-L1D: SAF direto e compilação no Termux

Decisão definitiva do proprietário substitui ADR-018 e planos de compilação
embarcada: L1C cancelada e PR #30 fechado sem merge. A L1D parte de L1B,
sem WCPL/WAMR/worker. O menor fluxo útil é editar no Sora existente e salvar
**a URI do documento original** em `Documents/MyCT` autorizada por
`ACTION_OPEN_DOCUMENT_TREE`; o Termux acessa o arquivo compartilhado e usa
Clang instalado à parte, gerando executável no seu HOME. A IDE escolhe entre
workspace privada e árvore SAF, sem nova aba ou terminal.

Rationale: evita nova infraestrutura de compilação e usa as capacidades Android
concedidas explicitamente por pasta, preservando target 36 e dados existentes.
Grants e trilha persistem; conteúdo completo/metadados são conferidos antes
de salvar e o resultado é relido. SAF não garante substituição atômica ou CAS:
falha desde abrir `wt` deixa resultado incerto e buffer preservado. Renomeação
segue a URI retornada; pasta com documento descendente aberto exige fechá-lo
antes. Sem MANAGE_EXTERNAL_STORAGE, tradução URI→POSIX, root, distribuição,
serviço ou mudança Fedora. Gate real Moto/Termux pendente, sem merge autorizado.
[Implementação, limites e provas](MYCT-L1D-RELATORIO.md).

## 19/10/2026 — MYCT-C1B-1: pacote inteiro, catálogo complementar e nenhuma exclusão

Recorte solicitado pelo proprietário após aceite/merge C1A (#47): entregar
archive/restore de torrent completo e preparar seeding, mantendo liberação do
SSD separada para C1B-2. Escolhido `myct-archive-torrent`, reutilizando funções
C1A/RPC/Media, com catálogo próprio para não mudar a validação/formato v1 aceito
de arquivos comuns. Manifesto + `.torrent` original preservam extras e permitem
reimportar sem a entrada atual do daemon; nada depende apenas de magnet ou da
lista de vídeos Jellyfin. Sem serviço/banco/dependência nova.

Restauração é nova árvore; recuperação aponta por move:false, verifica os dados
e exige transição de verificação observada antes de permitir start explícito.
Não substituir hardlinks nem limpar dados; dados pré-existentes permanecem.
96 testes simulados aprovados; gate real e auditoria Luna pendentes. Detalhes e
limitações em [MYCT-C1B-1](MYCT-C1B-1-RELATORIO.md).


## 19/10/2026 — C1B-1 aceita funcionalmente; C1B-2 segue pendente

Proprietário encerrou e integrou a PR #48 à `main` no merge `fce805ea96bc108a85b466133c6724b4688a329c` após testes físicos no Fedora: `inspect` reconheceu três arquivos e um hardlink Jellyfin; `archive` enviou 614825177 bytes com tamanho/MD5 remoto validados; `restore` reconferiu tamanho, MD5, SHA-256 e metainfo; `recover` observado pelo Transmission terminou em `seeding_ready:true` e `rpc_status:0` (pausado). **Não houve `--resume` ou prova de tráfego/peers**. Transmission foi redirecionado à cópia restaurada; originais/hardlinks intactos. Não excluir nem alterar dados por causa deste fechamento. C1B-2 será prévia do SSD/hardlinks e liberação guiada com autorização específica; C1C continua planejada. [Relatório de aceite](MYCT-C1B-1-RELATORIO.md) · [PR #48](https://github.com/Samuel-Francisco-AS/My-CT/pull/48).
