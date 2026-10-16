# MYCT-R1 — terminal e navegação SFTP (candidata, não aceita)

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
