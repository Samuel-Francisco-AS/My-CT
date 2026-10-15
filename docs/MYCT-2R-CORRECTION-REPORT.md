# MYCT-2R — confiabilidade SFTP, segundo plano e UX

## Resultado e aceite

Correção incremental da MYCT-2 implementada, com validação CLI/JVM/local.
**Aceite físico no Moto G06 pendente.** Não se declara resolvida no aparelho a
queda relatada durante o PDF, nem se declara background comprovado por JVM.

Critérios definidos antes do código: conclusão somente após fechamento e
verificações possíveis, falha/cancelamento restritos ao canal SFTP enquanto o
transporte estiver saudável, continuidade com serviço Android apropriado,
progresso/cancelamento reais ao voltar, unidades decimais e contraste Material.
IME true/false persistida, default true, aplicação sem reconectar preservados;
investigação MYCT-1P segue suspensa.

## Preparação e evidência física preservada

- Lidos AGENTS, README, planejamento inaugural, relatórios MYCT-2/1R e Android
  README. Inspecionados SshConnection, FilesModel, SftpFiles, FilesScreen,
  TerminalModel, SshForegroundService e MainActivity.
- [Comentário físico do PR #7](https://github.com/Samuel-Francisco-AS/My-CT/pull/7#issuecomment-6191938381):
  navegação e duas imagens baixadas/abertas funcionaram; PDF não abriu após
  queda relatada; vídeo cancelou em background; false melhorou subjetivamente
  a digitação. Não se acessaram documentos pessoais para reproduzir o PDF.
- Fetch de `origin/myct-2-sftp`; HEAD local/remoto iguais a
  `b3054b646b9ff1591a7f10e529675e63c1fd668b`. Branch corretiva
  `myct-2r-sftp-stability` criada dessa base. Main preservada em
  `2cebb637a94cc73d1dbf309688070d7550a3537a`; nenhum PR foi mesclado.
- Ferramentas existentes: JBR21.0.10, SDK/Build Tools36.0.0, Gradle8.14.3,
  AGP8.13.0, Kotlin2.2.20, JSch2.28.8. Builds offline; nenhuma instalação,
  dependência nova, emulador ou configuração de serviço Fedora.
- `gh` retornou 401; leitura/publicação de PR usa conector GitHub. Git por SSH
  permanece o transporte de fetch/push autorizado.

## Causas verificáveis e limites do diagnóstico

1. **Background cancelava por política:** `onStop → onBackground → cancel`.
   `onDestroy` ainda desconectava toda Session sem FGS interativo, inclusive
   numa destruição de Activity pelo sistema que não fosse rotação. Agora só
   uma Activity realmente finalizada, sem owner de serviço, pede desconexão;
   o serviço de transferência também protege esse ownership.
2. **Corrida de fechamento:** cancel fechava canal e streams no worker de
   cancelamento, enquanto o worker de cópia podia estar lendo/escrevendo ou
   fechando os mesmos streams. Repetir cancel repetia closes, inclusive em
   recursos já fechados. Streams de protocolo JSch não oferecem aqui contrato
   de close concorrente. Agora cancel é idempotente, aborta só o canal, e
   streams pertencem exclusivamente ao worker da cópia. Corrida entre connect
   do canal e cancel tem fechamento adicional no caminho de exceção.
3. **Conclusão precoce em relação ao canal:** o estado COMPLETE/URI era exposto
   antes de sair de `store.use`. Agora o worker devolve uma ação de conclusão
   aplicada na main somente após fechar streams e canal.
4. **Verificação opcional confundida com falha de cópia:** SIZE desconhecido já
   era permitido, mas exceção na consulta pós-close descartava o download como
   falha genérica. Agora consulta indisponível gera aviso; SIZE divergente
   continua falha de integridade e dispara limpeza do novo documento.
5. **Contraste:** textos avulsos herdavam LocalContentColor sem um Surface
   apropriado na área Arquivos. Essa área recebe onSurface/onSurfaceVariant,
   preservando esquema global. Tamanhos usavam bytes crus.

**Não foi comprovada a causa exata da queda SSH/TCP no PDF do proprietário.**
Não há trace sanitizado nem reprodução no Moto. O caminho SFTP não chama
Session.disconnect: a sessão inteira é fechada pelo owner de terminal em
falha/EOF do shell, desconexão explícita ou teardown de Activity/serviço. A
[implementação JSch Session.run](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/Session.java)
trata exceção de entrega CHANNEL_DATA fechando apenas o canal. A
[close do stream SFTP](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/ChannelSftp.java)
executa protocolo/ACKs, razão para evitar fechamento concorrente. Corrida e
teardown são problemas identificados, não prova da causalidade do episódio.
Uma queda real do transporte TCP afeta PTY e SFTP da mesma Session.

## Fluxo e integridade

Seleção regular vinculada à geração da conexão → metadados selecionados →
CreateDocument SAF, sem abrir destino existente para substituir → canal SFTP
próprio na Session verificada → lstat antes → streaming64KiB com contagem e
SHA-256 local → close do stream remoto/lstat depois → close/flush do destino →
SIZE SAF quando disponível → close do canal → COMPLETE e botão Abrir.

Contagem deve coincidir com tamanho remoto; tamanho/mtime remoto não podem
mudar. SIZE final SAF, quando conhecido, deve coincidir. Não há hash remoto
portátil implementado: a UI informa isso, não executa shell nem baixa novamente.
SHA local não é prova de igualdade criptográfica com o servidor ou re-leitura
integral do provedor. Alteração concorrente de mesmo tamanho/mtime e retenção
adiada por provedores continuam limitações.

Erro de leitura/escrita/flush/close, origem alterada ou divergência não anuncia
sucesso. Mantém tentativa de excluir somente o documento novo criado por SAF;
se não puder, informa PARCIAL e remoção manual. SAF pode mostrar documento
antes de concluir e não garante publicação atômica universal. Processo morto
não executa finally; marcador booleano em preferências avisa no próximo início,
sem URI/caminho/senha armazenados. Acknowledgment do aviso limpa o marcador
quando não há operação ativa. Não existe varredura de documentos pessoais.

Abrir é posterior ao sucesso: MIME do provedor com fallback por extensão se
for genérico/ausente; falha de visualizador/provedor tem mensagem própria e
não prova corrupção. O PDF anterior permanece sem classificação definitiva
entre incompleto, origem, provedor, MIME/visualizador e desconexão.

Upload mantém temporário exclusivo, conferência de tamanho e publicação por
hardlink sem substituir destinos existentes. Cancelamento tardio não remove
arquivo já publicado; queda antes da resposta pode deixar resultado incerto.
Não há retry/reconnect/replay automático ou sobrescrita silenciosa.

## Background Android

`SftpTransferService`, não exportado, `dataSync`, permissão específica
FOREGROUND_SERVICE_DATA_SYNC. Serviço iniciado por download/upload do usuário
com UI visível; o worker aguarda confirmação de startForeground antes de
confiar no background. Pedido em andamento protege a transição de Activity;
se falhar já em background, cancela em vez de copiar sem suporte legítimo.

O owner FilesModel já era do processo e continua assim, independente da aba.
Notificação privada sem nomes/caminhos, progresso em no máximo1Hz, abrir app e
Cancelar. Progresso interno segue contador único/UI100ms. Serviço termina após
operação, sem fila, credenciais em Intent ou reinício sticky. FGS interativo
specialUse e sua opção permanecem separados; não é exigido para esta cópia.
Desconectar explicitamente SSH continua encerrando todos os canais.

Sem permissão de notificações/canal ou se Android recusar serviço: mensagem
orienta manter app visível; ocultar cancela. Botão na área Arquivos solicita
permissão por ação do usuário para a próxima operação. onDestroy/onTimeout do
serviço cancela apenas transferência, e timeout para o serviço imediatamente,
sem esperar provedor bloqueado. Falha ao atualizar notificação também cancela
de forma controlada. Não há wakelock, Wi-Fi lock, boot receiver nem exceção de
bateria. Um provedor preso em IO pode atrasar o worker/limpeza.

[Android documenta dataSync para upload/download](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync).
Foi considerada a alternativa [UIDT API34+](https://developer.android.com/develop/background-work/background-tasks/uidt),
mas exigir agendamento/constraints e caminho adicional min26 não foi escolhido
para uma única operação imediata dependente da autenticação em memória.
DataSync tem [limite Android15+ de6h em24h em background](https://developer.android.com/develop/background-work/services/fgs/timeout),
tratado com onTimeout, sem contornar por specialUse. Não há garantia contra
Doze, perda de rede, kill/force-stop/reboot ou políticas Moto; nada retoma após
isso. Compatibilidade física e consumo Android ainda não medidos.

Este é SFTP **celular ↔ Fedora**. Downloads HTTP/torrent independentes no
servidor permanecem futuros e não foram implementados.

## UX e IME

Um formatador decimal simples em FileSize.kt: bytes abaixo1000; KB/MB/GB com
uma casa, locale pt-BR. Exemplos testados:1500→1,5KB;12500000→12,5MB;
987575524→987,6MB;2500000000→2,5GB. Aplicado à lista, confirmações, progresso,
notificação e resultados; valores Long não mudam e detalhes/resultados mantêm
bytes exatos. Textos na área Arquivos usam cores Material de conteúdo e erro;
sem mudança de identidade global.

TerminalModel, callbacks IME, renderer Termux e JSch não foram substituídos.
Preferência true/false, persistência, default true e restartInput sem reconectar
permanecem. Relato favorável a false preservado, sem nova instrumentação.

## Validações

```sh
/usr/bin/time -v android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python android/terminal/upstream/verify.py
/usr/bin/time -v python android/diagnostics/sftp-subsystem-proof.py
# Build Tools36: apksigner verify --verbose; comparação interna de certificados
# aapt2 dump badging, sha256sum, git diff --check, adb devices -l
```

- R1: compilação passou; teste novo de isolamento falhou na preparação da
  fixture (Session.openChannel lê campo privado, sem conexão simulada marcada).
  Hipótese/teste mínimo registrados antes da correção da fixture; teste dirigido
  repetido passou. Não foi falha de autenticação real ou regressão do app.
- Conjunto: **26 testes descobertos,25 PASS,1 handshake opt-in ignorado**, sem
  falhas/erros. Sem credenciais do proprietário ou novo servidor/porta de teste.
- Novos casos: tamanhos decimais; consulta SIZE indisponível vs divergente;
  cancelamento concorrente8 callers aborta canal uma vez; flush de provedor
  falha; PDF sintético válido em memória com bytes/hash iguais; download
  truncado, escrita recusada e cancelamento não retornam sucesso. Streaming
  aumentado para32MiB sintéticos em blocos64KiB para sink sem arquivo em disco.
- Isolamento: ChannelSftp/ChannelShell reais JSch, Session conectada simulada
  com writes interceptados; cancel/repeated-close encerra SFTP, shell permanece,
  Session.disconnect não é chamado. **Não exercita TCP/auth/servidor real.**
- Prova OpenSSH por pipes existente:8MiB em cada direção, SHA dos3 arquivos
  iguais, hardlink e conflito preservados, temporários próprios removidos.
  0,15s/RSS18396KiB; não é integração JSch autenticada/Android.
- Termux:21 arquivos upstream verificados,2 patches prévios intactos.
- ADB sem aparelho. Sem teste instrumentado Activity/Service/ContentResolver;
  lifecycle/notificação/retorno/provedor real/timeout/rede e contraste físico
  exigem Moto. Lint/manifest e inspeção do fluxo não substituem esses testes.

Build final: **PASS**,72,81s,25 testes PASS/1 ignorado; lint **No issues found**.
Assinatura APK v2 válida, certificado debug idêntico ao APK MYCT-2 comparado
internamente; pacote dev.myct, versionCode4, min26/target36. Manifest contém
apenas o tipo/permissão dataSync específico adicional. `git diff --check` PASS.
O aviso Gradle de pressão de metaspace512MiB reapareceu sem falhar; não se
ampliaram limites globais. A primeira rodada de lint trouxe2 sugestões UseKtx,
corrigidas antes do conjunto final, sem supressões.

## Consumo e APK

- Antes:7819MiB RAM total,6827MiB disponíveis,swap0; disco132GiB livres.
- Build final RSS máximo **1752896KiB (~1,67GiB)**,CPU451%,72,81s,zero swaps
  reportados pelo processo. Heap1536MiB/2 workers preservados; esses parâmetros
  não são um teto global de threads/RSS de toda a máquina.
- Depois:6876MiB disponíveis,swap0,132GiB livres. app/build59MiB,
  terminal/build3,3MiB, artifacts52MiB (inclui APKs anteriores); novo APK
  acrescenta~13,52MB. Fixture grande JVM32MiB não foi gravada no SSD; prova
  local8MiB/direção usa temporários próprios removidos ao terminar.
- Sem novos downloads de dependências. Bateria/RAM/CPU/throughput no Moto não
  medidos. Streaming64KiB permanece; buffers JSch/provedor são adicionais.
- APK: **`artifacts/myct-2r-debug.apk`**, **13522361 bytes (~13,52MB)**.
- SHA256: **`c06e4521f55dcfc4596c909d65132257105eb305a4d456ef4b163fcf5ab51e04`**.
- Checksum ao lado, `artifacts/myct-2r-debug.apk.sha256`. Ambos ignorados pelo
  Git; keystore existente preservada. Instalar manualmente sem desinstalar,
  conforme android/README, ou `adb install -r artifacts/myct-2r-debug.apk` se
  conectar dispositivo autorizado. Nenhum deploy/porta pública foi criado.

## Arquivos e retomada

- SshConnection.kt/SftpFiles.kt: abort do canal, fechamento idempotente,
  proteção da corrida connect/cancel e verificação SAF opcional distinta.
- FilesModel.kt: owner de processo, confirmação do serviço, conclusão após
  closes, recuperação por booleano e mensagens de integridade.
- SftpTransferService.kt/AndroidManifest.xml: serviço dataSync limitado à
  transferência, progresso/cancelamento e limites Android.
- MainActivity.kt: política onStart/onStop/onDestroy conforme ownership;
  FilesScreen.kt/FileSize.kt: unidades, contraste, notificação opt-in e MIME.
- app/build.gradle.kts: versão4/0.2.1-sftp-corrective, dependências intactas.
- SftpTransfersTest.kt, SftpCorrectionTest.kt, SftpIsolationTest.java:
  regressões e fixtures sintéticas, com fronteiras simuladas identificadas.
- android/README, docs/05, diário e este relatório: decisões, limites,
  instalação, evidências e gate físico.

Commit de implementação/APK: **`fc87cda61d98b40137953db26daff7859507ae97`**.
O fechamento documental posterior não altera código/APK. O SHA final da branch
será registrado no PR e na entrega; para recuperá-lo, `git rev-parse
origin/myct-2r-sftp-stability` após fetch. Branch/PR draft sobre `myct-2-sftp`,
dependente do PR #7, que depende do #6; nenhum merge autorizado/executado.

Retomada: instalar APK preservando dados; permitir notificações antes da cópia;
executar o gate abaixo; registrar resultados sanitizados. Se o PDF voltar a
falhar, distinguir se houve ERROR SFTP com terminal utilizável, encerramento
SSH, parada de processo/serviço, falha SAF ou apenas ACTION_VIEW. Não coletar
conteúdo/caminhos privados, não repetir automaticamente nem presumir corrupção.

## Gate físico posterior — curto

1. Baixar PDF não sensível/sintético e abrir.
2. Confirmar que erro isolado SFTP não derruba indevidamente o terminal; não
   usar queda TCP como prova de isolamento entre canais.
3. Transferir arquivo grande sintético com app em segundo plano/tela bloqueada,
   notificação permitida e progresso visível.
4. Voltar e conferir progresso/resultado e tamanho final.
5. Cancelar manualmente (tela/notificação), conferir parcial/limpeza e terminal.
6. Conferir MB/GB e contraste de título, caminho, textos secundários/progresso.

Não se exige repetir navegação/imagens já aprovadas sem sintoma novo.
Upload/conflito físico continuam pendentes da MYCT-2, sem aprovação implícita.
Não ampliar para Core/HTTP/torrent ou investigação de lag antes do retorno.
