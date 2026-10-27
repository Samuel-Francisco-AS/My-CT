# MYCT-DESKTOP-UX2 — candidata, aceite físico pendente

Branch `feat/myct-desktop-vnc`, base `0d1a39b9e38c2e123899688066405a419b352287`.
UX1 aceita visualmente no Moto G06 no escopo registrado. UX2 não é considerada
fisicamente aprovada por build, testes de fixtures ou composição. Após a entrega
da candidata, o proprietário autorizou commit e push das fontes na mesma branch,
antes do aceite físico. Sem merge, release ou instalação Android automática.

## UX2-A — continuidade e propriedade

Causa confirmada: `DesktopScreen` chamava `hide()` no ON_STOP e descarte; `hide`
fechava cliente/túnel. O leitor de `connect()` também capturava o renderer antigo.
`DesktopModel` já pertence a `TerminalModel`, por sua vez de `MyCtApplication`:
o proprietário estável existia. Reaproveitado esse controlador, sem serviço novo,
sem `configChanges`, sem mudar Terminal/SSH e sem guardar a senha.

A superfície possui identidade própria (DesktopImage), renderer e clear, sob um
lock. O leitor consulta o vínculo atual a cada atualização. Detach remove essas
referências e solta entrada; uma configuração em andamento tem janela de **3 s**
para reanexação. Sem nova superfície nesse prazo, o cliente é encerrado. ON_STOP
sem `isChangingConfigurations` fecha imediatamente a visualização. Descarte de
uma superfície antiga não pode encerrar a nova. ON_START reanexa a superfície
atual, sem autenticação ou túnel extra quando o cliente permanece conectado.

O último `RfbFramebuffer` é referenciado pelo controlador e pelo decoder já
existente; não se cria outro framebuffer inteiro para a rotação. Ao reanexar,
a cópia para o novo bitmap ocorre no IO worker, mesmo com servidor ocioso.
Pixels e dimensões são lidos sob o monitor do framebuffer. O vínculo é validado
novamente sob lock; detach espera apenas cópia de pixels, nunca leitura de rede.
Bitmap antigo é reciclado no detach (inclusive no segundo plano) e o callback
da View antiga é fechado no descarte. ACK de desenho inclui identidade
da superfície e da tentativa, rejeitando callbacks de telas destruídas.

| Evento | Política UX2 |
| --- | --- |
| Configuração/rotação | Reusar cliente e túnel autenticados; reanexar em até 3 s |
| Painéis, imersivo, teclado | Mesma conexão; dimensões e viewport recalculados |
| Desconectar | Fechar cliente/túnel e imagem, preservando programas Fedora |
| Sair da seção ou segundo plano real | Fechar cliente/túnel, sem reconexão automática |
| Perder SSH | Fechar cliente e descartar framebuffer; novo SSH não reautentica VNC sozinho |
| Encerrar Desktop | Fluxo F2 intacto: confirmação/autorização/estado real |
| Processo encerrado | Não há continuidade prometida; conectar manualmente |

A senha VNC não integra Bundle, SavedStateHandle, preferências, banco, arquivo ou
logs. O CharArray transitório continua zerado após autenticação/cancelamento.
Senha ainda não enviada no diálogo é descartada ao sair/rotacionar. Somente os
booleanos visuais de imersivo/controles usam estado salvável; zoom/centro do
viewport e ponteiro são dados não sensíveis em memória do controlador.

## UX2-B — interação explícita e fila

Aparência UX1 mantida. Opções → Controles oferece **Toque direto** e **Trackpad**.
O modo ativo aparece no resumo; não há troca de interpretação por gesto oculto.
Trackpad usa deslocamento relativo, independente do zoom, com sensibilidades
0,75/1,5/2,25 pixels remotos por dp (Precisa/Normal/Rápida). Toque curto clica na
posição atual; toque longo abre clique direito; arraste é opção explícita e
libera botão ao terminar/cancelar.

No toque direto o clique usa a transformação inversa do Canvas, rejeitando
letterbox. Pressão é adiada até intenção de clique/arraste/segurar ser conhecida.
Pinça e pan locais não emitem clique nem roda. Dois dedos têm escolha explícita:
**rolar Fedora** ou **pan local**. Após limiar do sistema, classificação fica
fixa até levantar os dedos. Para mudar entre pinça e rolagem, reiniciar o gesto.
Rolagem direta ancora o ponteiro sob os dedos após reconhecer rolagem; trackpad
rola na posição atual. Arraste interrompido, cancelamento, foco e mudança de
geometria soltam o botão; superfícies desvinculadas não enviam entrada.

`DesktopViewport` continua sendo a fonte única de escala/coordenadas. Zoom 1–4×
e centro normalizado persistem em RAM e são limitados ao recalcular dimensões;
sem distorção ou bitmap sobre controles. Pan nos dois eixos fica disponível na
pinça, no modo explícito de dois dedos e nas setas UX1.

A fila de entrada continua limitada a 128 itens. Junta somente movimentos
adjacentes com mesmo botão; eventos de botões, cliques, teclas e correções IME
mantêm ordem. Não atravessa bordas de pressão/soltura para juntar movimentos.
Overflow continua sendo erro recuperável com limpeza, sem fila ilimitada nem
logs de eventos. Escritas permanecem no worker IO.

## UX2-C — editor local e interoperabilidade

InputConnection usa `BaseInputConnection` com Editable local. Pré-edição permanece
local; commit/finish envia diferenças do sufixo conhecido, em keysyms. NFC
normaliza formas decompostas de acentos. Histórico limitado a 1024 pontos
Unicode digitados pelo próprio cliente, descartado em perda de foco, troca de
superfície, clique que altera contexto ou conexão. Não se lê o documento remoto.
Não há clipboard novo nem comandos de shell para texto; Ctrl+V é só um atalho
RFB para o clipboard que já existe no Fedora.

Composição, seleção no fim, região de composição no sufixo, batches e notificações
de seleção são tratados por APIs Android. Exclusão em Java chars (UTF-16) difere
de exclusão em code points: não cortar surrogate e não contar um emoji como dois
backspaces. Sem contexto conhecido, um pedido de apagar encaminha apenas uma
tecla, sem adivinhar o tamanho de uma palavra remota. Movimentos arbitrários de
seleção local e cursor dentro de pré-edição não são simulados; operações remotas
de navegação limpam o contexto local. Gboard com correção normal é suportado pelo
contrato local, sujeito ao aceite físico e ao app X11 usado.

Ctrl/Alt/Shift são aplicados como acordes com pressionar/soltar. Atalhos não são
tratados como texto confiável do documento. InputConnections antigos verificam
tentativa, superfície e editor atual; não enviam texto após detach/reconexão.
Fechamento normal do IME e ações explícitas de mouse/controles finalizam a
pré-edição válida antes de limpar seu contexto; invalidação de uma superfície
ou conexão antiga descarta a pré-edição sem reenviar depois. Redimensionar para abrir/fechar IME cancela gestos e preserva a pré-edição. Não se
capturam Home, Voltar, volume ou combinações Meta do Android. Flags solicitam
não usar modo de edição fullscreen e não aprender pessoalmente o texto.

Limites reais: keysyms Unicode dependem de X11, toolkit, layout e fontes da
aplicação. As letras portuguesas normalizadas em Latin-1 evitam parte dessa
variação, mas os testes de RFB não provam que todo app aceita todo caractere.
Emoji, scripts complexos, cursor arbitrário e autocorreção de texto digitado
fora do cliente não têm garantia. O teclado do sistema também pode reservar
atalhos físicos antes que cheguem à View; a faixa de teclas fornece alternativa.

Referências primárias: [ciclo de vida Android](https://developer.android.com/guide/components/activities/activity-lifecycle)
e [contrato InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection).
O contrato distingue chars/code points e batches; a decisão de limitar edição
ao sufixo conhecido é específica deste cliente remoto.

## UX2-D — validação, recursos e integridade

Ambiente: Java/JBR 21.0.10, Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20,
SDK/target 36, NDK 28.2.13676358. Nenhuma dependência instalada/adicionada.

```bash
ANDROID_HOME="$HOME/Android/Sdk" ./android/gradlew -p android \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --offline --console=plain
python3 -m unittest discover -s ops/tests -p test_desktop.py -v
git diff --check
```

| Check final | Resultado |
| --- | --- |
| JVM completa | **409 casos: 407 PASS, 2 SKIP, 0 falhas/erros** |
| Desktop incluídos no total | **71 PASS**, dos quais **18** em runtime Android/Skia nativo |
| Continuidade | 20 trocas de superfície e 6 ciclos Activity, cliente/túnel/autenticação únicos nas fixtures |
| Gestos/fila | Direto/trackpad, bordas/letterbox, soltura/cancelamento, pinça/pan/scroll e FIFO/coalescing aprovados nas fixtures |
| IME | Português, NFC, correção, UTF-16/code points, batches, fechamento, editores antigos e acordes testados |
| Administração/helper | Regressões F2 e **4 testes C++ PASS**, sem systemctl real de alteração |
| `assembleDebug` | **PASS**; candidata code57 gerada |
| `assembleDebugAndroidTest` | **PASS de compilação**, não execução instrumentada |
| `lintDebug` | **PASS**, 0 erros, 5 avisos e 2 hints preexistentes |
| Assinatura, pacote, versão, zipalign | **PASS**, mesmo certificado UX1; `dev.myct`; code57; `-c -P 16 4` |
| Escopo | Protocolo/encodings/VncAuth/SSH, administração F2, helper/sudoers, manifesto fonte e demais telas preservados por hashes |
| Aceite Moto G06 UX2 | **PENDENTE**, nenhuma instalação ou execução física pelo agente |

Os SKIP são integrações preexistentes `FedoraHandshakeTest` e
`IdeFedoraSftpTest`, não habilitadas contra Fedora real. RFB/SSH/serviços são
fixtures onde indicado; não houve start/stop real no Fedora nesta fase.

O teste Robolectric 4.13/Android API34 com Skia destrói e cria Activities de verdade no runtime Robolectric,
com o mesmo controlador e alternância de dimensões. A flag Android de mudança de
configuração é simulada pela fixture; não é rotação física de um aparelho.
`ActivityController.recreate()` do Robolectric 4.13 falhou internamente ao focar
sua janela ainda não anexada; foi usado o ciclo equivalente pause/save/stop/
destroy/create(state), mantendo renderização real e verificando imagem ociosa,
bitmaps antigos fechados e um único túnel. Testes também cobrem prazo sem
reattach, saída/background, SSH perdido durante handoff e cópia em andamento.
O teste instrumentado adicional usa `Activity.recreate()` do framework e peer
TCP-RFB interno: **compilado, não executado**.

Primeiras rodadas identificaram problemas das fixtures (Vsync/indicador com relógio
sem pausa, View solta sem entrega de long-press, e uso de Unconfined em modelo
cujo proprietário deve ser a thread UI). Foram usados relógio pausado/16 ms,
janela Android real e Handler da UI atual nos respectivos testes. Acordes são
verificados integralmente e sem teclas pendentes; releases idempotentes de
segurança não são confundidos com atalhos. A revisão também corrigiu o caso
DOWN dentro/UP fora do framebuffer e deixou a chamada super do editor explícita
para lint. Hipóteses, falhas iniciais e dump do worker próprio interrompido estão
em `artifacts/desktop-ux2/hypotheses.txt` e logs anteriores.

Sem `/dev/kvm` ou emulador operacional; a tentativa sem aceleração da F1 falhou
e não foi repetida nas mesmas condições. **Nenhuma instrumentação executada**;
APK de AndroidTest compilado não comprova IME/Gboard nem rotação no Moto.
As capturas nativas de barra/paisagem/painéis são fixtures, não imagem Fedora.

### Medidas disponíveis e limites

- Pipeline final: **BUILD SUCCESSFUL, 3min55s** (tempo externo `time`: 3min56,13s).
- Pico RSS da construção: **2.739.108 KiB (~2,61 GiB)**. Não é PSS Android.
  Gradle alertou sobre seu Metaspace de 512 MiB e concluiu com exit 0; heap
  1536 MiB/limites do projeto foram mantidos, sem alterar limites do Fedora.
- Teste funcional da fila: 10.001 movimentos oferecidos, 9.998 agrupados,
  preservando eventos de botão/tecla. **Não é benchmark de latência/FPS**.
- Sem bitmap novo a cada quadro. Retido somente o framebuffer já existente;
  bitmap é liberado no detach e recriado para a nova View no worker. Retângulo
  de destino Canvas reutilizado. Cópia integral dos pixels por atualização
  continua existente (1280×720 ARGB: ~3,52 MiB por buffer); não houve reescrita
  de decodificação/encodings para otimização antecipada.
- Diagnóstico sob demanda exibe PSS de **todo My-CT**, atualizações RFB
  (**não FPS**), superfícies apresentadas, pico/fusões/falhas da fila.
- Tempos por `System.nanoTime`: Conectar→primeiro ACK de desenho; detach de
  configuração→primeiro ACK da superfície reanexada. O segundo exclui a parte
  da rotação anterior ao detach; não é medição ponta a ponta por câmera.
  Contador de superfícies registra primeiro desenho por vínculo, não quadros/s.
- **Não foram medidos PSS, CPU, FPS, aquecimento, ANRs, latência ou estabilidade
  prolongada no Moto G06.** Liberação/corridas nas fixtures não provam ausência
  de todo vazamento na plataforma física; usar o roteiro abaixo.

### Candidata

- `artifacts/myct-desktop-0.14.4-ux2.apk` — **19741879 bytes (~18,83 MiB)**.
- `applicationId=dev.myct`, `versionCode=57`, `versionName=0.14.4-desktop-ux2`.
  Base/artefatos locais anteriores tinham code56; não havia candidata local superior.
- SHA-256: `7851f402694fdac6deb348d94e184c0ebfbbda474c2fc87eb0e53aad34e29a41`.
- Certificado SHA-256: `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`, igual ao da UX1/code56.
- Checksum: `artifacts/myct-desktop-0.14.4-ux2.apk.sha256`.
- Comparação descompactada dos APKs: só `AndroidManifest.xml` (versão) e
  `classes6.dex` diferem; DEX cresce 50.156 bytes. Bibliotecas nativas, assets,
  recursos e demais DEX são idênticos. O tamanho do contêiner APK também varia
  com seu layout/compressão/alinhamento, não apenas com esse delta de código.

Sem armazenamento de senha e sem necessidade de reinstalar helpers. Sem
mudança de permissões/manifesto fonte. Atualização mantém dados e identidade.

### Arquivos e justificativas

- `DesktopModel.kt`: proprietário estável, vínculos por identidade, cache,
  grace de configuração, entrada limitada e medidas; bloco administrativo intacto.
- `DesktopView.kt`: pixels/dimensões sob lock, reanexação/descarte, transformação,
  modos/gestos/cancelamento e encaminhamento ao editor; clip F1 preservado.
- `DesktopInput.kt` (novo): modo/transformação, FIFO com coalescing e delta Unicode.
- `DesktopIme.kt` (novo): Editable/InputConnection local, composição, correções,
  batches, exclusão e invalidação, sem acesso a texto remoto.
- `Desktop.kt`: somente classificador de dois dedos; forwarding, comandos,
  parser de unidade e mapeamento de teclas preservados.
- `DesktopScreen.kt`: associação de superfície/lifecycle, estado visual salvável
  sem segredos, opções nos painéis UX1 e diagnóstico sob demanda.
- `build.gradle.kts`: somente versão code57/0.14.4-desktop-ux2; sem dependências novas.
- `DesktopContinuityTest.kt`, `DesktopRotationRegressionTest.kt`,
  `DesktopInteractionRegressionTest.kt`, `DesktopInputBufferTest.kt`,
  `DesktopKeyboardRegressionTest.kt`: novas regressões direcionadas.
- `DesktopF1InstrumentedTest.kt`: cenário de recriação real do framework preparado.
- README, Android README, arquitetura, decisões, diário, retomada e este relatório:
  contrato da candidata, evidências, limitações e retomada.

Evidências em `artifacts/desktop-ux2/`: `final-checks2.log`, `results.json`,
`test-results/`, `lint-results.xml`, `preservation.json`, `source-manifest.sha256`,
`apk-payload-diff.json`, assinatura/badging/zipalign, recursos e imagens `ui/`.

### Limitações que o aceite deve avaliar

Janela de reanexação de configuração limitada a 3 s: se o Android não devolver
uma superfície iniciada nesse prazo, há desconexão segura e nova autenticação
manual. Processo morto, saída real ou background não preservam a conexão.

Composição é local: a palavra pode aparecer no Fedora somente no commit/finish,
inclusive ao fechar normalmente o teclado. Contexto de edição restrito ao sufixo
conhecido, sem leitura do documento remoto; regiões/cursor arbitrários não são
simulados. Editor invalidado não reenvia a pré-edição depois. Unicode fora das
letras normalizadas/Latin-1 pode variar entre apps X11. Gestos classificam uma
intenção por contato: levantar os dedos para passar de rolagem para pinça.
Trackpad/sensibilidade, Gboard real e recuperação repetida requerem aceite físico.

## Aceite físico no Moto G06

Instalar manualmente a candidata por atualização, mantendo `dev.myct`, certificado
e dados. Não desinstalar nem reinstalar o helper. Encerrar serviços não é necessário
para testar rotação/gestos/teclado; gerenciamento F2 permanece preservado.

1. Conectar SSH/VNC, abrir Firefox ou editor gráfico e girar entre retrato e
   paisagem pelo menos dez vezes, incluindo dentro/fora do imersivo. A imagem
   deve reaparecer **sem senha**, mantendo aplicação, ponteiro e uma conexão.
   Verificar recuperação após rotação rápida; registrar eventual demora/erro.
2. No painel Controles, alternar explicitamente Toque direto/Trackpad e as três
   sensibilidades. Testar clique, segurar, arrastar e soltar/cancelar. Tocar nas
   bordas e no letterbox; zoom/pan não devem clicar ou rolar involuntariamente.
3. Alternar dois dedos entre rolar e pan. Usar pinça 1–4× e ajuste UX1, em duas
   orientações, imersivo e com Gboard aberto. Confirmar alinhamento de conteúdo.
4. Em editor Fedora, digitar `ação`, `coração`, `João`, `café`, `português`, `maçã`,
   `Olá, mundo!`; corrigir palavra com sugestão Gboard, apagar, usar Enter/Tab/Esc,
   Ctrl+A/C/V, Alt+Tab e Shift. Anotar aplicação, teclado/layout e casos que não
   se comportem como esperado. Nenhum resultado físico é pressuposto.
5. Abrir/fechar Gboard e painéis, usar Voltar primeiro para IME/imersivo e depois
   sair da seção. Sair/reentrar exige autenticação manual conforme política;
   programas Fedora devem continuar abertos. Testar segundo plano e retorno;
   não deve ficar cliente gráfico indefinidamente ativo sem visualização.
6. Observar sessão prolongada: responsividade, aquecimento, travamentos, memória
   e falhas. Medir PSS de todo My-CT antes de conectar, durante uso, após 10–20
   rotações e após desconectar. Registrar orientação, tempo e outros recursos
   do app em uso. Atualizações RFB não são FPS; métricas de fixtures não são
   desempenho físico do Moto.
7. Confirmar navegação geral, Terminal/IDE/Arquivos/Saúde/Downloads/Cloud/Torrents/
   Cofre no uso habitual. Desconectar deve preservar a aplicação remota. Não
   exigir encerramentos Fedora repetidos para testar esta mudança de interface.

O proprietário realizará o aceite. A autorização posterior de commit/push publica
somente fontes, testes e documentação da candidata UX2; não representa aceite
físico, merge ou release. O fechamento Git não altera implementação, versão ou
APK, nem reexecuta build/lint/testes: os resultados acima são os da entrega
validada anteriormente. Binários gerados, evidências locais, credenciais e
keystores permanecem fora do Git.
