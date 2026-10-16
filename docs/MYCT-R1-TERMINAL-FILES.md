# MYCT-R1 — terminal e navegação SFTP (candidata, não aceita)

**Última retomada — MYCT-R1B:** 0.5.7 testada pelo proprietário sem melhora
perceptível de digitação; SFTP/rolagem melhoraram. Candidata diagnóstica
0.5.8/code 15 e roteiro ao final. Causa e aceite do terminal continuam abertos.

**Origem:** refinamento da [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9), itens R-01 e R-02. Iniciado em 16/10/2026. Não envolve novo serviço no Fedora, modificações SSHD, emulador ou schema.

## R-02 — pasta pai retém posição de leitura

- `FilesScreen.kt`: `LazyColumn` usa `LazyListState` explícito; antes de entrar em diretório ou subir ao pai, registra item visível, índice e deslocamento em pixels.
- `FilesModel.kt`: cache efêmero de até 64 pastas, associado à conexão SSH atual; ao receber listagem da pasta novamente, procura o nome do item para sobreviver a mudanças de ordenação e usa índice limitado como fallback. Sem persistência em disco.
- Ao trocar abas ou desmontar a tela, salva a posição da pasta ainda exibida. Ao trocar de conexão, limpa o cache. Atualizar uma pasta sem sair dela não reinicia a rolagem deliberadamente.
- Teste JVM direcionado em `FilesScrollAnchorTest.kt` cobre nome reordenado, item removido e lista vazia. Resta validação física com pasta grande no Moto G06, incluindo subir/voltar, troca de aba e reconectar.

## R-02b — mostrar/ocultar arquivos começados por ponto

- Botão simples `Mostrar ocultos` / `Ocultar ocultos` na aba Arquivos, **ocultos inicialmente**.
- Exibição filtrada no Android (inclui diretórios como `.config`, `.ssh`), mas a listagem SFTP original continua completa. Não modifica, apaga nem renomeia itens.
- Mantém posição por nome ao alternar o filtro; estado conservado no modelo durante a sessão do aplicativo, sem preferência persistente no disco.
- Testes JVM para filtragem e âncora de scroll entre os dois modos. É necessário confirmar no Moto o comportamento visual ao entrar/sair de uma pasta e alternar o filtro.

## R-01 — medição dirigida, sem declarar cura antes da prova

O diagnóstico MYCT-1P classificou a causa do lag como **não identificada**. A preferência `Entrada por caracteres = false` melhorou subjetivamente mas não eliminou lag. A escrita pelo SSH e o eco remoto continuam necessários; a fila de ações e a view não provam causalidade isoladamente.

- Adicionadas duas métricas numéricas efêmeras em `Opções → Medições locais`: duração **write+flush** por ação de envio, e duração **append** do emulador por bloco recebido (média/máximo), ambas reiniciadas em nova tentativa de conexão.
- As métricas anteriores de espera na fila IO e espera para a thread main permanecem. A medição **não** cobre RTT/eco, bytes aguardando no pipe, quadros efetivamente desenhados, nem o tempo interno do teclado Android. Não corresponde a latência ponta a ponta.
- A primeira candidata acrescentou somente medição. A continuação autorizada pelo proprietário implementa as otimizações pontuais abaixo; TCP_NODELAY, JSch, renderer e eco remoto preservados. Não registrar conteúdo ou segredo no diagnóstico.

## Experimento físico mínimo

1. Compilar APK debug no Fedora com o build offline existente e **a mesma keystore** (não desinstalar app). Evitar tocar em serviços Fedora. Se o build falhar, parar e corrigir a causa concreta.
2. No Moto G06, conectar na mesma rede e sessão ssh. Com `Entrada por caracteres = false`, digitar um texto descartável longo, pressionar espaço, apagar trechos e executar `printf 'ok\\n'` no shell livre. Observar responsividade de cada ação sem medir segredos. Repetir `true` apenas se quiser uma comparação, sem refazer investigação ampla.
3. Abrir `Opções → Medições locais` e anotar os números agregados ao lado da percepção de atraso. Comparar comandos simples fora e dentro do tmux apenas se o lag for visível; não interpretar um único máximo como causa.
4. Arquivos: em pasta com muitos itens, rolar até o meio, abrir subpasta, tocar `↑ Pai`; verificar item e deslocamento aproximado. Repetir com alternância de abas e atualização; nova sessão pode começar no topo.
5. Confirmar que terminal, Ctrl-C, tmux, arquivo simples e SFTP continuam utilizáveis.

## R-01 — candidata de otimização 0.5.7 (code 14)

Partida: branch `myct-r1-terminal-files`, HEAD `e9cbd65`, Git limpo e
sincronizado após fetch. PR #22 aberto como draft. Nenhuma instalação de
bibliotecas, alteração de Fedora ou coleta de conteúdo.

Hipóteses, mudanças e provas mínimas:

| Custo demonstrável no código | Mudança | Prova / limite |
| --- | --- | --- |
| Cada codepoint passa por char[], Strings, UTF-8 e cópia defensiva | `TerminalSession.writeCodePoint` codifica em um único byte[] de propriedade do transporte | Teste de limites UTF-8, acentos, combinante, emoji, Alt e inválidos; não mede GC no Moto |
| Um commit da IME gera uma ação por codepoint, pressionando a fila limitada a 64 ações | Escopo síncrono em `sendTextToTerminal`: combina texto já disponível na mesma chamada; descarrega ao retornar ou após ~4 KiB | Fixture de 110 codepoints passa de 110 envios a 1; não espera próximo evento/frame/timer. Não elimina o limite para commits enormes ou escritor bloqueado |
| Enter/DEL/controles não podem ficar retidos pelo escopo | Descarrega texto anterior antes de enviar controle, Escape/Alt ou sequência mapeada | Testes verificam entrega antes de `endInputEvent` e ordem byte a byte; exclusões IME continuam pelo KeyEvent upstream |
| Cópia de cada bloco recebido antes de `withContext(Main)` que já aguarda append | `append(bytes, count)` consome o buffer reutilizado antes da próxima leitura | Teste com UTF-8 repartido entre leituras e cauda não utilizada; emulador continua exclusivamente na main |
| Fechar Opções reinicia InputConnection mesmo sem mudar preferência | `restartInput` somente se o valor aplicado difere do escolhido | Inspeção do efeito; confirmação de composição/foco no Moto pendente |

Não há nova coroutine por tecla, atraso deliberado, eco local ou mudança na
escrita JSch: um escritor mantém ordem e `write+flush` por ação. JSch já usa
TCP_NODELAY no caminho direto conforme MYCT-1P. A sincronização IO→main foi
preservada: removê-la exigiria outra estratégia de propriedade/backpressure,
sem evidência física que justifique esse risco. `TerminalCallbacks` não registra
conteúdo; o parser/renderer e as transformações por codepoint da view são
preservados, inclusive LF→CR, Ctrl, Shift e os caminhos de composição upstream.
Não há afirmação de que a causa principal do lag tenha sido isolada.

R-02/R-02b não foram reimplementados. Testes existentes de filtro/âncora fazem
parte da validação; aparência e ausência de salto continuam dependentes do Moto.

## Validação desta candidata

Comando executado, sem instalar dependências:

```sh
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
git diff --check
```

- Build completo: **SUCCESS**, 1m54s; JVM: **98 testes, 97 aprovados, 1 ignorado**
  (handshake SSH local opt-in), zero falhas. Adaptador: 8; âncoras/filtro SFTP: 5.
- Lint: **0 erros, 2 avisos de versões e 1 hint** preexistente em TorrentsScreen.
  Gradle também avisou pressão de metaspace ao finalizar; build passou com os
  limites existentes, sem ajuste de recursos. `/usr/bin/time -v`: pico RSS
  2.033 MiB, CPU acumulada 554s, zero swaps; custo do build, não do app no Moto.
- Upstream: **21 arquivos verificados**, dois arquivos com patches documentados;
  `myct-r1-ime.patch` aplica sobre a view com o patch clipboard MYCT-1R.
- APK: [0.5.7-r1-terminal/code 14, pré-release candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r1-v0.5.7-rc1),
  local `artifacts/myct-r1-0.5.7-debug.apk` (12.9 MiB). `applicationId=dev.myct`; `apksigner verify --print-certs` confirmou
  assinatura idêntica ao APK 0.5.6 local: SHA-256 do certificado
  `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
  Atualização preserva dados/pins; não se verificou a instalação no aparelho.
- SHA-256 do APK: `540277f0da8b919ddaba8cc9a979d24200317413a03d6f7885f733565b4aafe0`.
- Sonda desktop existente (`bash android/diagnostics/run-component-probe.sh`),
  uma execução após o build: ASCII mediana/p95 0,070/0,094 µs;
  DEL 0,187/0,368 µs; pipe JSch 11,190/17,797 µs. Não compara builds A/B,
  não inclui Android/IME/rede e não demonstra ganho perceptível.
- Logs locais em `artifacts/myct-r1-validation/`, fora do Git; sem dados do terminal.
  Os testes exercitam adaptador/parser e bytes sintéticos, **não** uma IME Android
  real, socket autenticado ou rede Moto→Fedora.

Nenhum Moto apareceu em
`adb devices -l`; não houve instalação nem teste físico nesta execução.
**Não declarar melhora perceptível, nem R-01/R-02 aceitos, antes do proprietário.**

### Comparação física curta (proprietário)

1. Atualizar por cima da instalação existente, sem desinstalar nem apagar dados.
   Registrar teclado/versão e valor de Entrada por caracteres. Começar em false;
   comparar true depois, sem mudar outras condições.
2. Na mesma rede/Fedora, alternar candidata e Termux **via SSH**, com mesma IME,
   fora do tmux primeiro. Três rodadas curtas: digitar continuamente uma frase
   descartável, inserir espaços, apagar rápido/segurar Backspace, digitar
   `ação`, acento composto e emoji. Conferir ausência de duplicação/perda.
3. Em shell livre, executar `printf 'ok\\n'` e comandos comuns; observar Enter
   imediato e Ctrl-C. Repetir em `nano` (buffer descartável, sair sem salvar) e
   na sessão tmux escolhida pelo proprietário; conferir setas/Ctrl/Alt.
4. Abrir/fechar Opções sem mudar IME, depois alternar true/false; conferir foco,
   composição e teclado. Anotar percepção e métricas agregadas por conexão,
   sem texto, senha ou saída privada. Máximos não equivalem a RTT/p95/E2E.
5. Arquivos: pasta longa → subpasta → Pai; trocar aba e mostrar/ocultar dotfiles.
   Confirmar posição, ausência de salto e acesso SFTP normal.

PR #22 deve permanecer **draft**, issue #9 aberta e main sem merge.

## MYCT-R1B — 0.5.8/code 15, diagnóstico de engasgos

**Retorno físico da 0.5.7 pelo proprietário:** sem melhora perceptível no
terminal; fluidez intermitente, pior ao apagar continuamente. Termux via SSH
no mesmo Fedora responde melhor. SFTP/retorno de rolagem melhoraram e foram
preservados. Isso substitui a expectativa de ganho da candidata anterior,
sem encerrar R-01 nem declarar todo o SFTP fisicamente aprovado.

Valores fornecidos do Moto: 285 envios; fila IO máximo **556,78 ms**;
write+flush média/máximo **0,76/3,14 ms**; 271 blocos; entrega à main máximo
**73,96 ms**; append média/máximo **0,56/2,94 ms**; PTY **49×55**.
Essas populações e máximos não podem ser somados para obter E2E. Falta saber
frequência, backlog e coincidência temporal; **causa ainda não identificada**.

### Hipóteses e decisão

- Espera antes de write pode ser despertar no pool IO, backlog, resize anterior,
  pausa global do processo/GC ou agendamento do SO. Write curto não distingue
  essas alternativas. Não há evidência para substituir Dispatchers.IO agora.
- `deleteSurroundingText` mantém o laço upstream: `leftLength` chamadas de
  `sendKeyEvent` com ACTION_DOWN/DEL, reutilizando um KeyEvent; uma chamada não
  equivale necessariamente a uma exclusão. Backspace mantido pode chegar como
  chamadas sucessivas ou KeyEvents repetidos. Não foi demonstrada duplicação;
  nenhum DEL foi agrupado, suprimido ou reinterpretado.
- `onTextChanged` chama a view diretamente; `onScreenUpdated` ajusta seleção/
  histórico, invalida e, quando acessibilidade está ativa, extrai texto.
  Append pequeno não mede custo de draw, apresentação nem o retorno main→IO.
- A [sessão Termux de referência v0.118.3](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)
  usa threads próprias de leitura/escrita, ByteQueues de 4096 bytes e Handler
  na main para append/callback. My-CT usa Channel de ações, pool IO, JSch e
  espera pela main por bloco. A versão instalada do Termux não foi inspecionada;
  a diferença de arquitetura não comprova que migrar para thread dedicada cure o lag.

### Implementação desta candidata

- **Opções → Amostrar engasgos · 60 s** inicia/reinicia amostra somente numérica,
  desligada por padrão; **Medições locais** mostra o resultado em texto rolável.
  O limite é verificado nos eventos, sem timer, tarefa periódica ou arquivo.
- Cada etapa mostra N, média, **limite superior do p95** em histograma fixo,
  máximo e contagens ≥16/50/100 ms. O p95 cobre toda a amostra, não uma janela
  dos últimos eventos; acima de 1000 ms aparece `>1000`.
- Escrita e resize têm espera/duração separadas. Fila observa profundidade na
  admissão (exclui execução) e separa writes admitidos sem ação anterior pendente
  ou em execução. `TerminalActionQueue` apenas isola o FIFO existente para medir
  e testar: mantém Channel(64), escritor IO único, ordem e write+flush por ação.
  O lock protege contadores/admissão, nunca envolve rede/resize/append.
- Hooks numéricos na view medem commit/finish/delete, onKeyDown, idade do
  KeyEvent (uptime), DEL entregues/repetidos, onScreenUpdated e onDraw CPU.
  Total e máximo de `leftLength` e total de `rightLength` ajudam a comparar
  solicitações/entregas. Teclados que enviam DEL diretamente podem ter zero
  chamadas deleteSurroundingText; fronteiras da amostra também afetam totais.
- Medem-se entrega à main, append+callbacks, onTextChanged+UI e retorno ao
  leitor IO. Atualização→draw mede espera desde a primeira atualização pendente,
  não GPU/apresentação. Nada foi coalescido no renderer; emulador permanece na main.
- Até 60 bins de 1 segundo mostram etapas com máximo ≥50 ms, profundidade e
  contagens de exclusão naquele segundo. Latências usam o segundo de conclusão; profundidade usa o de admissão. Coincidência
  não prova causalidade nem associa uma tecla ao eco remoto.
- Histogramas/bins usam arrays fixos (menos de 32 KiB de dados numéricos por
  amostra, sem contar cabeçalhos JVM). Snapshot copia números sob lock e formata
  fora dele. Nova conexão troca o coletor para evitar contaminação por IO antigo.
  Operações iniciadas antes da amostra ou concluídas após seu limite são excluídas.

### Roteiro no Moto (proprietário)

1. Atualizar sobre 0.5.7, sem desinstalar/apagar dados. Manter rede, IME e
   preferência iguais. Iniciar **Amostrar engasgos · 60 s** e voltar ao terminal.
2. Digitar frase descartável longa; depois manter Backspace e apagar trechos.
   Alternar digitação/espaço/exclusão/Enter. Conferir acentos, emoji e Ctrl-C.
3. Aguardar completar 60 s antes de abrir Opções (para não misturar custo do
   diálogo com o sintoma). Consultar **Medições locais**, anotando etapas com
   ≥50/100 ms, p95, fila máxima e bins lentos. Repetir amostra para exclusão isolada.
4. Repetir uma rodada curta em nano (sem salvar) e tmux; conferir Escape,
   setas, seleção, histórico e resize ao abrir/fechar teclado.
5. Comparar com Termux via SSH no mesmo Fedora/rede/IME. Informar percepção e
   números, sem texto/comandos privados. Não interpretar só um máximo ou uma
   diferença entre DEL solicitado/entregue como prova de defeito.

Revisão dirigida: hipótese de que observar profundidade só no dequeue ocultaria
backlog durante uma parada do escritor. Teste mínimo: admitir 64 ações sem
iniciar o consumidor; observar máximo 64 e zero writes processados. A coleta
foi movida para a admissão bem-sucedida, fora do lock do FIFO; rejeição não conta
como admissão. Esse ajuste corrige a instrumentação, não demonstra a causa física.

Validação final, offline, Java 21/JBR, Gradle 8.14.3 e SDK 36 existentes:

```sh
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
git apply --reverse --check android/terminal/upstream/myct-r1b-timing.patch
git diff --check
```

- **BUILD SUCCESSFUL**, 105 testes: **104 aprovados, 1 ignorado** (handshake
  opt-in), zero falhas. Sete testes novos: histogramas/expiração/bins/concorrência,
  limite FIFO/resize e oito rajadas totalizando 160 envios, incluindo 128 DEL,
  espaços, Unicode, Enter e Ctrl-C. Testes SFTP existentes preservados/aprovados.
- Lint: **0 erros, 2 avisos de versões e 1 hint preexistente**. Upstream:
  **21 arquivos, três arquivos com patches documentados**, verificação reversa
  do patch R1B e whitespace aprovados. Sem nova bateria de benchmarks.
- Build final: 1m49s, CPU acumulada 526s, pico RSS ~1920 MiB, zero swaps
  (`/usr/bin/time -v`); Gradle avisou metaspace ao finalizar mas passou com
  os limites existentes. Isso não mede consumo/desempenho no Moto.
- [APK candidato e checksum](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r1b-v0.5.8-rc1):
  `artifacts/myct-r1b-0.5.8-debug.apk`, **0.5.8-r1b-diagnostics/code 15**,
  pacote `dev.myct`; certificado idêntico à 0.5.7 local, conferido por apksigner.
  SHA-256: `f389095c350202316f7287d2d8c0e5588222032e4334a0b344bcf6f6861bfec1`.
- Logs locais: `artifacts/myct-r1b-validation/`. Testes sintéticos exercitam
  adaptador/FIFO/parser e agregação; não executam IME, Canvas ou SSH autenticado
  no aparelho. Nenhuma melhoria de responsividade foi demonstrada nesta execução.
MYCT-R1 permanece aberta; PR #22 draft e main sem merge. Sem Moto no ADB nesta execução.
