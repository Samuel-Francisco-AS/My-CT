# MYCT-R5-F2 — revisão visual R1

**Candidata R1; aprovação visual no Moto G06 pendente.** O proprietário testou a
F2/code 31: houve melhora discreta, mas o acabamento não atingiu a expectativa.
Esta é uma revisão da F2 no mesmo PR, não uma fase nova.

- Branch/worktree preservados: `myct-r5-f2-navigation`,
  `/home/sam/Projetos/My-CT-r5-f2`, base original `origin/main` `2dec947`.
- [PR #35 draft](https://github.com/Samuel-Francisco-AS/My-CT/pull/35), sem merge.
  PR #34 e seu checkout continuam intactos; backend/serviços/dados não alterados.
- Nova candidata **0.7.3-r5-f2-r1 / code 32**, pacote `dev.myct`.
  Branches remotas e releases consultadas: maior code encontrado 31.

## Direção visual e implementação

A primeira F2 trocava seções com fade de 80 + 150 ms e não animava a primeira
Home ou a maioria dos painéis internos. A R1 introduz movimento visível,
reutilizando Compose Animation, sem dependências novas:

- Seções: saída acelerada de **100 ms**; entrada de **280 ms**, curva
  `CubicBezierEasing(0.16, 1, 0.3, 1)` para chegar com velocidade e desacelerar
  suavemente. Total nominal **380 ms** na escala 1×, mais o fechamento real do
  menu e o custo necessário de composição. Deslocamento horizontal de **32 dp**
  entre seções irmãs, invertido ao navegar na direção oposta; saída vertical de
  **24 dp** nas navegações que envolvem a Home. A Home chega com sua entrada
  própria descrita abaixo. O fundo permanece a Surface existente do tema.
- Home: **280 ms** desde sua composição, inclusive na abertura do app e em cada
  retorno. Um único relógio revela cabeçalho, texto, IDE, Terminal e bloco Fedora
  em cinco grupos, com inícios distribuídos em apenas **67 ms**. Fade, subida de
  **28 dp** e escala **0,975 → 1**. Todos os grupos terminam juntos. Sem splash,
  bloqueio de interação, `delay` ou tarefas aguardando para iniciar carregamento.
- Cartões da Home: ripple Material mantido; escala de pressão **1 → 0,97** e
  retorno curto. Escolhas de navegação usam feedback nativo `CLOCK_TICK`, sem
  permissão Vibrate nem flags para ignorar a preferência tátil do Android.
  Teclas do Terminal e ações operacionais mantêm os mecanismos existentes.
- Drawer: o conteúdo de fundo acompanha seu **offset real**, incluindo arrasto e
  reversão, com deslocamento de **18 dp** e escala até **0,985**. A seleção é
  imediata; a troca de seção continua aguardando o fechamento real. Não há timer
  imitando o menu. Escala/translação são gráficas, sem redimensionar o PTY.
- Painéis internos: entrada de **280 ms**, opacidade **0,35 → 1** e deslocamento
  horizontal de **24 dp** ou vertical de **18 dp**. A nova informação aparece
  imediatamente; não se retém cópia de uma tela antiga aguardando animação.
  Só o estado gráfico é reiniciado, sem colocar `key` novo no conteúdo pesado.

Um valor de deslocamento assinado representa repouso, saída e entrada. Retornar
à seção ainda exibida reverte o percurso; retarget durante movimento conserva
seu eixo, sem saltar lateralmente. O commit continua lendo o último pedido.
`SaveableStateHolder` e a trava de `FLAG_SECURE` da F2 continuam presentes:
Cofre protegido desde o pedido/primeiro frame até concluir entrada/saída e
interrupções. Restauração da Activity abre o último destino com entrada nova,
sem retomar o meio de uma animação anterior.

Toda animação temporal usa o contexto Compose e sua `MotionDurationScale`,
incluindo escala zero. A animação do drawer segue o próprio Material. Leituras
por frame ficam em `graphicsLayer`, sem recompor/remedir TerminalView ou Sora.
O limite externo de desenho contém os deslocamentos na área útil da tela.

Referências: [Animatable e animações sequenciais](https://developer.android.com/develop/ui/compose/animation/quick-guide)
e [transformações na fase de desenho](https://developer.android.com/develop/ui/compose/performance/phases).

## Cobertura inspecionada

| Caminho | Tratamento na R1 |
| --- | --- |
| Primeira Home / retorno à Home | Entrada coordenada dos cinco grupos; saída pelo host comum |
| Início → IDE/Terminal/Fedora; atalhos da IDE/Downloads; retorno do aviso de buffer sujo | Navegação central e transição sequencial, um destino composto |
| IDE — entrada/saída da seção | Host comum com movimento e fade |
| IDE — Local/Remoto; privado/compartilhado | Entrada do painel de ambiente/armazenamento |
| IDE — navegador ↔ código; abrir/fechar/reabrir documento; mudar diretório | Entrada pela identidade do documento ou caminho/browsing; preparação do editor incluída |
| Terminal — Local/Remoto; sessão criada; formulário SSH ↔ terminal | Movimento horizontal do painel por ambiente/sessão, sem trocar o dono do PTY |
| Arquivos — entrada/saída; conexão; entrar/subir diretórios | Host comum e entrada da lista pelo caminho efetivamente carregado |
| Downloads — entrada/saída; HTTP ↔ Torrents; Ver em Arquivos | Host comum, painéis com direção inversa e navegação central para Arquivos |
| Saúde | Host comum; consultar métricas não reinicia animação de tela |
| Cofre | Host comum protegido; consulta/senha/desbloqueio são ações operacionais, sem transição de tela adicional |
| Menu lateral | Movimento Material existente + fundo coordenado ao offset real |
| Opções ↔ Antivírus e assinaturas | Entrada horizontal do painel substituído; janela/segurança existentes |
| Confirmações, busca, detalhes, ajuda, licenças, controles e operações | Comportamento Material existente; sem transformá-los em navegações de tela |

Chaves de animação são destinos semânticos: ambiente, sessão, armazenamento,
diretório, documento e aba HTTP/Torrents. Digitação/dirty-buffer, polling,
bytes transferidos, taxas, refresh do mesmo caminho e mensagens não reiniciam
as animações. HTTP/Torrents agora têm `SaveableStateHolder` próprio para manter
posições salvas ao trocar esses painéis. Não há cópias simultâneas dos painéis.
Os modelos de sessões, documentos Sora/undo/viewport, SFTP e transferências
permanecem com seus proprietários anteriores.

## Arquivos da revisão

`AppSection.kt` e o novo `NavigationMotion.kt` concentram o movimento;
`HomeScreen.kt`, `AppShell.kt` e `MainActivity.kt` aplicam a apresentação e os
controles; `IdeScreen.kt`, `FilesScreen.kt` e `TorrentsScreen.kt` cobrem os
painéis internos. `SectionNavigationTest.kt` acompanha o percurso assinado e
acrescenta dois testes pequenos de direção/alpha e progressão da Home.
`android/app/build.gradle.kts` atualiza a versão. Este relatório registra o aceite.

## Validação

Na primeira rodada, o Kotlin/APK compilou e dois testes falharam em asserções:
comparação binária de `Offset` distinguia `-0.0` de `0.0`, e a amostragem por
oito frames caiu exatamente no início da entrada, ainda com deslocamento 1.
Hipótese/teste mínimo: comparar componentes numericamente e aguardar a troca
real de fase antes de amostrar sua progressão, preservando as verificações da
trava do Cofre. A repetição confirmou as hipóteses e passou. O lint também
indicou a convenção de fábrica de Modifier; o helper passou a ser a extensão
`Modifier.panelEntrance`, sem suprimir o aviso.

**Candidata final: build PASS nas três ABIs; 236 testes JVM PASS, 1 SSH opt-in
ignorado, zero falhas/erros em 45 suítes; lint PASS, zero achados.** Os 11 testes
de navegação incluem escala zero, interrupções, último destino, cancelamento,
proteção do Cofre e dois testes pequenos de direção/alpha e progressão da Home.
`git diff --check` PASS. Rodada final: **2 min 23 s**, pico RSS de build
**1,92 GiB**, CPU **452%** (vários núcleos), sem swap. São medidas do build
no Fedora, não consumo Android. Os avisos preexistentes de condições redundantes
na IDE e metaspace Gradle não impediram a validação; configurações preservadas.

Comandos no worktree existente, ferramentas/caches já instalados:

```sh
MYCT_TEST_LOCAL_SFTP=1 ANDROID_HOME=/home/sam/Android/Sdk \
  /usr/bin/time -v android/gradlew -p android :app:assembleDebug \
  :app:testDebugUnitTest :app:lintDebug --offline --console=plain
adb devices -l
git diff --check
```

OpenJDK 21.0.10, Gradle 8.14.3, Compose 1.9.3/Material3 1.4.0, SDK/build-tools
36, NDK 28.2.13676358 e CMake 3.22.1 existentes, sem instalar dependências.
Logs em `artifacts/myct-r5-f2-r1-*.log`. Nesta revisão de UI não se repetiram
os testes PTY nativos nem se adicionou instrumentação: o código nativo e o
transporte não mudaram. Na F2 inicial, PTY host e proveniência passaram;
234 testes JVM passaram e um SSH opt-in foi ignorado. Essa evidência histórica
não substitui executar a candidata R1 no Android.

`adb devices -l` sem dispositivo conectado. **Sem execução visual, tátil, IME,
AndroidView, captura ou medição de frames/RAM/GPU no Moto pelo agente.** A suíte
JVM verifica lógica/relógio de frames e preservação pelos modelos, não acabamento
físico. Composição/criação de TerminalView/Sora ainda tem custo; não foi mascarado
com esperas. Camadas gráficas podem exigir memória de GPU, a medir no aparelho.

## Artefatos e atualização

APK absoluto:
`/home/sam/Projetos/My-CT-r5-f2/artifacts/myct-r5-f2-r1-0.7.3-debug.apk`.

APK de **18,377,945 bytes** (três ABIs, minSdk 26/targetSdk 36),
com pacote/code/versão conferidos por `aapt`. `apksigner verify` **PASS/v2**;
certificado SHA-256 `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`,
idêntico às codes 30/31. O APK baseline code 31 foi conferido contra o digest da
release publicada; todas as bibliotecas `.so` da R1 são byte a byte idênticas
às dele. Sem alteração/substituição de keystore ou configuração de assinatura.

SHA-256 do APK R1:
`1f4011e08ecd7f2fbdbe708765f9ea8639948e8353f2a275f7991e863c513555`.
Arquivo de somas: `artifacts/myct-r5-f2-r1-SHA256SUMS.txt`.
A cópia em artifacts foi comparada ao APK do build final.

[Release R1](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r5-f2-r1-v0.7.3-rc1)
· [APK R1](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r5-f2-r1-v0.7.3-rc1/myct-r5-f2-r1-0.7.3-debug.apk)
· [SHA256SUMS](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r5-f2-r1-v0.7.3-rc1/myct-r5-f2-r1-SHA256SUMS.txt).
A [candidata anterior code 31](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r5-f2-v0.7.2-rc1)
continua disponível como histórico.

Salvar documentos antes de instalar. Atualizar **sobre a instalação existente,
sem desinstalar nem limpar dados**. Por cabo:
`adb install -r /home/sam/Projetos/My-CT-r5-f2/artifacts/myct-r5-f2-r1-0.7.3-debug.apk`.
A atualização compatível preserva dados em disco/preferências/permissões SAF;
a substituição do processo pode encerrar shells locais e buffers em memória.
SSH pode exigir reconexão conforme o modo persistente existente. Isso é diferente
de navegar entre telas, que deve preservar sessões e buffers.

## Aceite Moto G06 — pendente

1. Abrir o app: conferir entrada do cabeçalho, título, cartões e indicador Fedora;
   nada deve esperar uma splash artificial. Rolar e repetir Início → IDE → Terminal
   → Início. Avaliar movimento, easing, resposta de pressão e feedback tátil.
2. Percorrer todas as seções pelo menu. Abrir/arrastar/reverter/fechar o drawer;
   conferir fundo coordenado e continuidade. Selecionar rapidamente destinos
   diferentes, inclusive voltar ao atual, e repetir durante entrada/saída.
3. Terminal Local ↔ Remoto; Terminal → Arquivos → Terminal: conferir a mesma
   sessão/saída/tmux, digitação, Ctrl+C e resize. Repetir com IME aberto.
4. IDE: documento com alterações → outra seção → IDE; conferir texto/undo,
   cursor/rolagem. Trocar Local/Remoto, privado/compartilhado, Projetos/código,
   diretórios e documentos. Fechar/reabrir com os diálogos de descarte existentes.
5. SFTP: entrar em pastas, rolar, subir e sair/reentrar; conferir as âncoras e a
   entrada da lista sem salto de posição. Manter transferência em andamento.
6. HTTP ↔ Torrents: conferir entrada direcional e posição de rolagem; polling e
   progresso não devem piscar ou reanimar o painel. Testar Ver em Arquivos.
7. Saúde: consultar e atualizar sem reanimar a tela inteira. Opções ↔ Antivírus:
   conferir a troca interna sem alterar ações/diálogos operacionais.
8. Permitir capturas globalmente; entrar/sair do Cofre, inclusive com drawer,
   Opções e cliques rápidos. Testar screenshot/gravação/Recentes durante todas
   as transições: nenhum frame do Cofre pode ser exposto. Consultar/desbloquear
   preservando o fluxo existente, com senha digitada somente pelo proprietário.
9. Repetir com animações removidas e escala 0,5×/1×; conferir ausência de tela
   permanentemente transparente, recortes, travamentos ou navegação atrasada.

Retomar pelo mesmo worktree/PR, sem merge, aguardando o retorno visual do
proprietário. Não declarar aprovação física a partir de build/testes JVM.
