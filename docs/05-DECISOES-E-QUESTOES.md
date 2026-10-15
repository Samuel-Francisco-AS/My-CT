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
