# MYCT-1P — diagnóstico de latência na digitação SSH

## 1. Resumo executivo e decisão

**Classificação: B — investigação física adicional. Causa principal ainda não
identificada. Nenhuma correção de comportamento foi aplicada.**

Inspeção e amostragem em 15/10/2026, sobre `181309d`, na branch
`myct-1-android-ssh`, inicialmente limpa e sincronizada com origin após fetch.
PR [#6](https://github.com/Samuel-Francisco-AS/My-CT/pull/6) consultado como
**draft=true, merged=false**. Main preservada.

O proprietário relata que a MYCT-1R funciona no Moto G06: autenticação, shell/
PTY, IME/orientação, fonte, clipboard, captura configurável e recuperação tmux;
foreground service **aparentemente** funcional. Esses são relatos físicos do
proprietário, não testes executados pelo agente nesta missão. A reclamação
restante é entrada/exclusão pesada, com Termux percebido como mais responsivo.
Não há série comparativa controlada entre os dois aplicativos.

Há uma diferença verificável que merece o primeiro teste: My-CT força entrada
por caracteres na configuração da IME; o Termux de referência permite configurar
essa opção e usa padrão false. Também existem alocações adicionais no adaptador
e sincronização IO→main por bloco. Nenhuma dessas diferenças foi associada
causalmente ao atraso no aparelho.

A sonda desktop não encontrou demora de milissegundos habitual nos pequenos
trechos medidos. Isso **não** mede IME, coroutines Android, SSH autenticado,
Wi-Fi, readline, tmux ou Canvas; não comprova fluidez no Moto. A amostra do
Fedora estava folgada, fora do momento do sintoma. Não há base para substituir
JSch/renderer, remover flush, alterar TCP_NODELAY ou implementar eco local.

**Recomendação ao proprietário:** autorizar uma sessão curta de diagnóstico
físico e, se necessário, um APK diagnóstico com comparação controlada da opção
IME. Não autorizar uma otimização ampla com a evidência atual. Se preferir
avançar para outra funcionalidade, registrar o lag como pendência conhecida;
a investigação não demonstrou que a solução seria uma refatoração cara.

## 2. Critérios deste diagnóstico

- Mapear pontos de espera e custo, distinguindo leitura de código de medição.
- Não registrar conteúdo, senha, clipboard, host, usuário ou dados pessoais.
- Só atribuir causa após teste discriminante no ambiente em que ocorre o sintoma.
- Preservar funcionalidades MYCT-1R, autenticação, tmux e serviços existentes.
- Permitir reproduzir a sonda sem dependências novas e sem tráfego remoto.
- Encerrar com diagnóstico e decisão proposta; aguardar autorização para corrigir.

Foram lidos AGENTS, README, planejamento inaugural, relatórios MYCT-1/1R,
plano corretivo e android/README. Não se reescreveu o planejamento.

## 3. Pipeline: tecla até desenho

| Etapa | Código / thread | Trabalho e fronteiras observadas |
| --- | --- | --- |
| 1. Android/IME | `TerminalView.onCreateInputConnection`, main | `BaseInputConnection`; `commitText` passa pelo Editable e percorre codepoints. Composição pode aguardar commit/finish; não medido no Moto |
| 2. Tecla física ou exclusão IME | `onKeyDown`, `deleteSurroundingText`, main | Exclusão cria KeyEvent DEL e repete por leftLength; KeyHandler mapeia DEL para 0x7f sem modificadores. Texto normal passa por inputCodePoint; não são caminhos idênticos |
| 3. Bytes | adaptador `TerminalSession.writeCodePoint/write`, main | Codepoint→char[]→String→UTF-8→cópia de byte[]; modificadores podem prefixar Escape. Tecla mapeada usa String→UTF-8→cópia. Não há eco visual local do caractere |
| 4. Fila | `TerminalModel.Transport.send`, main→IO | Cria Action.Write com timestamp e `trySend` para Channel de 64 ações; não espera espaço. Fila cheia encerra com aviso, não bloqueia silenciosamente. Resize compartilha a fila |
| 5. Escrita SSH | coroutine writer em Dispatchers.IO | Contador existente mede até antes de output.write; write acumula bytes no stream de canal JSch e flush os envia. Rede/criptografia/window control podem bloquear essa etapa; duração não medida |
| 6. Transporte | thread interna JSch, socket TCP | Pacotes SSH via rede; conexão direta usa TCP_NODELAY=true. Não foi medido RTT, jitter, perda ou retransmissão do Moto |
| 7. Fedora | sshd→PTY→shell/readline; tmux quando anexado | Eco/edição e possíveis sequências de redesenho; backspace pode voltar como vários bytes. Configuração/saturação durante o sintoma não inspecionadas |
| 8. Recepção | JSch→pipe interno→reader IO | input.read em buffer reutilizado de 8192 bytes; copia cada bloco recebido. Bytes podem aguardar no pipe enquanto a coroutine está esperando a main |
| 9. Despacho | `withContext(Dispatchers.Main)` por bloco | Reader aguarda append/callback antes de voltar ao IO e fazer próxima leitura. Timestamp começa somente após read/cópia; não conta espera anterior no pipe nem o retorno main→IO |
| 10. Emulação/desenho | `TerminalSession.append`→`SessionCallbacks`→`TerminalView.onScreenUpdated`→`onDraw`, main | Parser ANSI, callback, invalidação, renderer Canvas das linhas visíveis. invalidate agenda desenho posterior e não equivale a quadro apresentado |

Não há timer de batching no adaptador, sleep por tecla ou espera deliberada de
40ms. Há trocas de thread e cópias explícitas. Nada demonstra o custo delas
no Android atual. `onScreenUpdated` pode construir texto da tela para
acessibilidade quando o serviço Android de acessibilidade está habilitado;
o estado no Moto é desconhecido. Não recomendar desativar acessibilidade como
solução automática.

A saída recebida não é armazenada em estado Compose por caractere. O callback
vai diretamente à view; não foi encontrado caminho explícito de recomposição
de toda a tela por byte. Ctrl modifica estado quando consumido; fonte/layout
podem solicitar atualização/resize, mas não são deliberadamente alterados por
cada caractere normal. `updateSize` deduplica grade/células e o renderer não é
recriado por cada bloco recebido. Invalidações podem ser agrupadas pelo Android;
contar callbacks não equivale a contar quadros.

## 4. Hipóteses e alternativas

Confiança na evidência está separada da confiança causal. Todas as correções
abaixo são **propostas, não aplicadas**; o impacto numérico é desconhecido.

| Hipótese / evidência | Confiança causal atual | Correção potencial se confirmada | Complexidade / risco / efeito esperado | Exige Moto? |
| --- | --- | --- | --- | --- |
| H1 IME: My-CT fixa shouldEnforceCharBasedInput=true; Termux v0.118.3 usa propriedade configurável default false. My-CT seleciona visible-password + no-suggestions sem TYPE_CLASS_TEXT, caminho de compatibilidade herdado com ressalva no próprio upstream | Baixa; diferença comprovada, IME/versão/propriedade do Termux instalado desconhecidas | A/B isolado true/false; só adotar configuração alternativa se melhorar sem quebrar composição, acentos, Enter/DEL/paste | Baixa alteração; risco médio de incompatibilidade de teclado/composição. Pode remover atraso anterior à fila se essa for a causa | Sim |
| H1 exclusão: deleteSurroundingText pode gerar várias ações para uma chamada. DEL normal não usa writeCodePoint | Baixa; leftLength, repetições e custo real não medidos | Ajustar apenas o caminho que o perfil mostrar excessivo, preservando semântica Unicode/IME | Baixa/média; risco médio de perder/apagar caracteres errados. Benefício possível na exclusão repetida | Sim |
| H2 main: máximo histórico ~42ms de despacho, parser e render rodam na main; callback por bloco e extração acessível condicional | Baixa a média como candidato, não causa identificada. 42ms não é custo do parser/desenho nem padrão sustentado | Coalescer notificações de tela sem perder bytes, limitar trabalho por quadro, revisar acessibilidade somente se perfil indicar. Não mover emulador concorrente sem contrato de threads | Média; risco médio/alto de cursor/TUI/seleção/resize. Pode reduzir engasgos de quadros se comprovados | Sim |
| H3 eco remoto: caractere e exclusão precisam ir ao Fedora e voltar; RTT/jitter/JSch/window control/readline/tmux não medidos | Baixa; necessidade do trajeto é fato, contribuição desconhecida | Medir mesmo destino/contexto no Termux; localizar demora antes de mudar transporte. Se externa, tratar condição específica com decisão do dono | Diagnóstico baixo/médio; mudança de rede fora do escopo. Benefício depende da etapa dominante | Sim, mesma rede |
| H3 flush/TCP: flush por ação e TCP_NODELAY já ativo no caminho direto usado. Pipe JSch tem write+flush em IO.put | Baixa para culpabilizar JSch; ausência de atraso habitual no pipe desktop não prova Android | Manter envio imediato. Só avaliar batching limitado com evidência de excesso de pacotes/custo; nunca remover flush cegamente | Baixa/média; risco médio/alto de aumentar atraso ou reter teclas. Nenhum ganho demonstrado | Sim |
| H4 adaptador: gera alocações por codepoint, copia saída e espera main por bloco; upstream usa buffers UTF-8 reutilizados e ByteQueue/Handler | Baixa; diferenças comprovadas, pressão de GC/agendamento não medida | Reduzir alocações ou agrupar entrega apenas no ponto medido, mantendo propriedade dos buffers e ordenação | Baixa para UTF-8, média para recepção; risco de corromper bytes/concorrência. Ganho provável só se GC/dispatch forem dominantes | Sim |
| H4 renderer Termux inalterado e parser na main também no upstream; app atual é debug, Termux instalado e sua configuração/build não foram identificados | Baixa para culpar renderer/Compose/build debug | Medir frames e build comparável antes de trocar componentes ou habilitar otimizações de build | Perfil médio; troca de renderer/SSH alta e sem justificativa. Nenhum ganho quantificado | Sim |
| H5 saturação Fedora: amostra atual CPU 96–99% ociosa e sem swap IO, memória disponível ~6.5GiB | Baixa para saturação na amostra; nada conclui sobre o momento do sintoma | Coletar amostra simultânea; só discutir shell/serviço/rede se houver correlação repetível e teste isolando fator | Leitura baixa; alterações não autorizadas. Benefício indeterminado | Sintoma deve ocorrer durante coleta |

**Principal causa identificada: nenhuma.** H1 é o teste pequeno mais direto por
haver diferença verificável de configuração; H2 e H3 permanecem candidatos.
Não somar máximos históricos de populações diferentes para calcular E2E.
Não atribuir o problema a Bash, tmux, Wi-Fi ou JSch só porque participam do fluxo.

### Comparação com o upstream

A verificação offline confirmou os **21 arquivos upstream**, com **2 patches
manuais documentados de clipboard**; renderer/emulador não foram modificados
nesta missão. O adaptador substitui a sessão de subprocesso/JNI do Termux por
SSH. Upstream também faz emulação/callbacks na main; portanto, estar na main
por si só não demonstra erro. Seus buffers e agendamento são diferentes, não
há garantia de que copiar a arquitetura dele resolva este sintoma.
[TerminalSession upstream fixado](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java).

O cliente Termux consulta uma propriedade para entrada por caracteres;
essa propriedade tem default false na referência fixada. Isso não revela o
valor do aplicativo instalado pelo proprietário.
[Cliente Termux](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java),
[padrões da propriedade](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/termux-shared/src/main/java/com/termux/shared/settings/properties/TermuxPropertyConstants.java).

O stream de saída JSch acumula bytes antes de flush; o flush constrói/envia
pacote, portanto não é uma operação descartável. A entrega recebida usa pipe
com flush. Session configura TCP_NODELAY=true na conexão direta sem proxy,
como a aplicação atual. O bytecode do JAR em cache também confirma essa chamada;
não foi criada conexão para inspecionar socket no Moto.
[Channel 2.28.8](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/Channel.java),
[IO 2.28.8](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/IO.java),
[Session 2.28.8](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/Session.java).

## 5. Métricas comprovadas e limites

### Métricas físicas anteriormente relatadas

| Métrica existente | Mediana / p95 | Máximo relatado | N / condições |
| --- | --- | --- | --- |
| Action.Write criada→writer antes de write | Não disponíveis | ~1ms | Não fornecidos no relato; contadores da UI não vieram junto |
| Bloco copiado após read→entrada na main antes de append | Não disponíveis | ~42ms | Não fornecidos no relato; não há correlação por tecla |

`TerminalDiagnostics` só mantém contagem e máximo por vida do objeto/processo,
sem reset por conexão e sem distribuição. Não mede processamento de evento,
write+flush, bytes aguardando no pipe, parser, custo/frequência de draw ou
apresentação. Um bloco não corresponde necessariamente a uma tecla. Os valores
não permitem mediana/p95 retrospectivos nem comparação quantitativa com Termux.

### Sonda reproduzível de componentes desktop

Sonda manual `android/diagnostics/ComponentLatencyProbe.java`, executada pelo
script ao lado, **fora dos source sets e do APK**. Nenhum hook no app, flag,
serviço, timer, coleta contínua ou dependência nova. Só métricas numéricas em
stdout. Compila contra classes atuais do terminal, SDK e JAR JSch já em cache.
Transport/callback da sonda são contadores sem UI; bytes são constantes locais.

Fedora, i7-3770, OpenJDK 21.0.10/JBR, JVM Xms32m/Xmx128m. Uma execução completa
bem-sucedida, sem carga de build concorrente. NanoTime por operação, sem subtrair
custo do relógio; mediana usa ordem inferior e p95 nearest-rank. Aquecimento
5,000 por caso de adaptador/parser; 200 no pipe. Casos rodam sequencialmente,
JIT/GC/escalonamento podem afetar caudas e comparações entre casos.

| Intervalo medido | N após aquecimento | Mediana (µs) | p95 (µs) | Máximo (µs) |
| --- | ---: | ---: | ---: | ---: |
| writeCodePoint ASCII→Transport contador | 20,000 | 0.429 | 1.864 | 118.248 |
| write da sequência DEL mapeada pelo KeyHandler→Transport contador | 20,000 | 0.086 | 0.167 | 10.115 |
| append do eco sintético `x` + backspace/espaço/backspace, grade 51×17 | 20,000 | 0.162 | 0.370 | 12.323 |
| Mesmo par eco/apagar, grade 80×24 | 20,000 | 0.135 | 0.143 | 19.993 |
| Channel.write interno→IO.put/flush→read em outra thread, sem Session/socket | 1,000 | 11.618 | 17.178 | 179.002 |

As duas grades são condições sintéticas, não medidas atuais do Moto. Parser
inclui callback contador, **não** onScreenUpdated/Canvas. Adaptador não inclui
IME/Channel de coroutines/JSch nem fila. Pipe usa JSch real por reflexão isolada
na fixture, sem criptografia/protocolo/socket; a reflexão entra no custo. Não é
benchmark do aplicativo, E2E nem comparação Termux. Não inferir que DEL é mais
rápido no Moto a partir da diferença desktop ou atribuir máximos ao GC sem perfil.

Asserções verificam quantidade de bytes/callbacks, mapeamento DEL, cursor após
cada série e entrega do pipe; falha/timeout impede resultado completo. A primeira
tentativa de fixture usou Session.openChannel sem conexão e falhou com
`session is down`: sem medir pipe. Inspeção confirmou a pré-condição; a fixture
passou a construir apenas ChannelShell desconectado por reflexão. **Não foi
aberta rede para contornar o erro.** A tabela registra apenas a execução completa.

### Fedora e acesso físico

Em **2026-10-15 03:14:52 UTC** (~00:14 local), consultas de leitura:

- Linux 7.2.8-200.fc44.x86_64; i7-3770, 4 núcleos/8 threads.
- RAM total 7,819MiB, disponível 6,658MiB; swap usada 15MiB.
- Filesystem de trabalho 222GiB, 132GiB disponíveis, uso 41%.
- `vmstat 1 6`: descartada primeira linha acumulada desde boot; nas 5 amostras
  de intervalo, CPU ociosa 96–99%, iowait 0%, si/so 0, bi/bo 0.
- PSI no fim: CPU/memória avg10/60/300 0.00; IO avg10 0.00, avg60 0.01,
  avg300 some 0.45/full 0.44. Há histórico de IO, sem prova de relação com lag.
- `adb devices -l`: lista vazia; nenhum teste Android nesta execução.

Amostra curta, não simultânea à reclamação e insuficiente para excluir picos.
Não foram lidos comandos/processos de sessões de outros projetos, credenciais,
configurações pessoais do shell, pacotes de rede ou buffers de terminal.

## 6. Próximo teste físico mínimo (proposto, não executado)

1. Confirmar contexto da comparação: Termux **local ou SSH no mesmo Fedora**,
   mesma rede/rota, IME/versão, build do app, fonte/grade, acessibilidade,
   foreground, economia de bateria e presença de tmux. Comparação local↔remoto
   mistura rede/eco e não isola o aplicativo. Não alterar serviços ou tmux.
2. Reproduzir apenas o problema, com texto inofensivo conhecido e sem executar
   comandos: digitação, DEL individual e DEL mantido separados; sem dados reais.
   Registrar percepção e números, não conteúdo. Nenhuma nova prova de login,
   pwd/ls/tty é necessária.
3. Se o proprietário autorizar APK diagnóstico: coleta **opt-in, desligada por
   padrão**, limitada a 60s e buffer numérico em memória, reset por rodada.
   Instrumentar duração de commitText/onKeyDown/deleteSurroundingText (sem texto
   ou códigos), espera da fila, write+flush, despacho main, append/callback e
   onDraw; contar eventos, blocos, bytes, invalidações e draws por segundo.
   Registrar N, mediana, p95 e máximo por etapa e rodada. Não habilitar o logging
   de teclas do upstream; callbacks de logs continuam inertes. Sem SDK externo.
4. Medir quadros com API oficial de FrameMetrics quando viável; tempo de
   onDraw não inclui GPU/apresentação. Relacionar picos temporais, não conteúdo.
   Aguardar warm-up, manter condição estável e repetir três rodadas por cenário.
   Não confundir tempo de evento já despachado com toque→IME. Ferramentas de
   perfil têm custo e não devem ficar ligadas fora do teste.
5. Fazer A/B com **uma variável**: configuração IME atual vs padrão do Termux
   de referência, mantendo rede/shell/render iguais. Alternar ordem, verificar
   acentos/composição/Unicode, Enter/DEL/Ctrl/paste e ausência de bytes perdidos.
   Essa variante não foi criada nem adotada como correção nesta missão.
6. E2E real exige outra prova, **sujeita a autorização antes de tráfego ou
   comandos sintéticos remotos**: canal/sessão de teste isolada com eco conhecido,
   uma solicitação por vez, sem saída concorrente, correlacionar marcador
   efêmero e guardar só duração/contagem; distinguir envio→recepção de
   input→quadro apresentado. Não tratar o próximo bloco arbitrário do shell
   como eco da tecla. Não executar scripts em sessões de trabalho existentes.
7. Durante reprodução, amostrar Fedora somente por leitura. Se Termux via SSH
   reproduzir o mesmo atraso e métricas locais forem baixas, investigar trecho
   externo; se só My-CT atrasar, usar os intervalos para selecionar correção.

A documentação Android diferencia custo de renderização de outros tempos e
recomenda investigar quadros lentos. O orçamento depende da frequência real da
tela, não presumida para o Moto.
[Android: slow rendering](https://developer.android.com/topic/performance/issues/render),
[BaseInputConnection](https://developer.android.com/reference/android/view/inputmethod/BaseInputConnection).

**Gate para decisão A futura:** diferença repetível no aparelho, etapa dominante
identificada e variante pequena que a reduza sem regressões. Só então apresentar
correção e pedir autorização. Se o resultado continuar inconclusivo após essa
sessão limitada, apresentar custos/benefícios e permitir ao proprietário adiar.
Nenhum PASS físico, RTT, p95 Android ou ganho comparativo foi inventado aqui.

## 7. Comandos, verificações e recursos

Na raiz, sem downloads/instalações e sem teste SSH de rede:

```sh
git status --short --branch
git fetch origin
git rev-list --left-right --count HEAD...origin/myct-1-android-ssh
# Inicial: 0 0; worktree limpa.
java -version
uname -srmo
lscpu
free -m
df -h .
vmstat 1 6
cat /proc/pressure/cpu /proc/pressure/memory /proc/pressure/io
adb devices -l
python android/terminal/upstream/verify.py
# javap -c -p no JAR JSch 2.28.8 do cache: Session chama setTcpNoDelay.
/usr/bin/time -v android/gradlew -p android :app:testDebugUnitTest \
  --rerun-tasks --offline --console=plain
/usr/bin/time -v bash android/diagnostics/run-component-probe.sh
bash -n android/diagnostics/run-component-probe.sh
/usr/bin/time -v android/gradlew -p android :app:lintDebug --offline --console=plain
git diff --check
sha256sum artifacts/myct-1r-debug.apk
```

O script da sonda exige classes compiladas atuais, SDK36 e cache exato JSch;
aborta se faltarem, sem baixar nada. Executar testes/compilação antes da sonda
em checkout novo. Saída em µs, ordenação apenas após medir. Diretório gerado
`android/.work/myct-1p-probe` é ignorado pelo Git. A sonda não é incluída em
unit tests nem executada pelo aplicativo.

- Testes reexecutados offline: **7 PASS, 1 ignorado** (FedoraHandshakeTest opt-in,
  variável MYCT_TEST_LOCAL_SSH não definida). Zero falhas/erros. Pins (2), ANSI/
  adaptador (2), clipboard/OSC52/resize (3). Nenhum handshake artificial nesta
  missão. Suite total descoberta: 8; não registrar 8 PASS.
- Sonda completa: PASS, validações de bytes/callbacks/cursor/pipe; 1.42s de wall
  time incluindo javac/JVM, RSS máximo 141,876KiB (~139MiB), CPU 266%, zero swaps.
- Testes/recompilação: 37.65s, RSS máximo 1,188,792KiB (~1.13GiB), CPU 366%,
  zero swaps. Dois workers não limitam todas as threads de compilação/GC.
- Verificação de origem: 21 arquivos, 2 patches documentados, PASS.
- LintDebug offline: PASS, relatório `No issues found`; 13.90s, RSS máximo
  545,824KiB (~533MiB). Análises/relatório reutilizados como up-to-date pelo
  Gradle porque o código Android não mudou; não se afirma análise nova da sonda
  desktop pelo Android lint. Sonda compilada/executada separadamente com javac.
- `bash -n` PASS; `git diff --check` sem erros. Após verificações, RAM disponível
  6,826MiB e swap usada 15MiB na amostra; nenhum consumo do Moto medido.
- Nenhum APK novo necessário: código do app/manifest/dependências intactos.
  Artefato MYCT-1R preservado em `artifacts/myct-1r-debug.apk`, SHA256
  `4c9d303ab1086efab7a4237cc07be3fc4907905367c0b1cc4a91dc9e5dc8044c`.

## 8. Alterações e retomada

- Este relatório e entrada no diário; histórico anterior preservado.
- `android/diagnostics/ComponentLatencyProbe.java` e script de execução manual:
  instrumentação de componentes isolada, sem custo ou alteração do app normal.
- Sem mudanças em TerminalModel, SshConnection, callbacks, métricas existentes,
  MainActivity, adaptador/renderer, FGS, tmux, host keys ou autenticação.
- Fontes oficiais consultadas são versões fixadas usadas pelo projeto;
  diferenças do Termux atualmente instalado permanecem pendentes.
- Publicar nesta branch e manter PR #6 draft, sem merge. SHA final será entregue
  ao proprietário; consultar `git log -1` para retomada.

**Ponto de parada:** aguardar decisão do proprietário entre autorizar o teste B
proposto ou aceitar a pendência e avançar. Esta missão não resolve nem declara
resolvido o atraso.
