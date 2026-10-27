# MYCT-DESKTOP-UX1 — aceite visual físico e fechamento Git

Base: branch `feat/myct-desktop-vnc`, commit `d0b6d18`. Escopo: interface
Android Desktop. O proprietário instalou e testou a candidata no Moto G06.
O agente não instalou o APK nem alterou o Fedora; não há merge ou release.

## Estado de partida e aceite físico informado

O proprietário informou o aceite funcional da infraestrutura Desktop-1/F1/F2:
SSH/túnel VNC, autenticação, framebuffer real, ponteiro, clique direito, menus
Openbox, aplicação Fedora, helper instalado manualmente e Iniciar/Encerrar pelo
My-CT com estado real confirmado. Esse registro atualiza a pendência histórica
nos relatórios anteriores; não é uma repetição desses testes pelo agente.

**Resultado visual UX1 aprovado pelo proprietário no Moto G06**, com a candidata
`0.14.3-desktop-ux1`, versionCode 56, instalada e testada fisicamente. Foram
confirmados exclusivamente os seguintes pontos nesta etapa:

- Interface reformulada e significativamente melhorada.
- Barra superior compacta e funcional.
- Painel de sessão e diagnósticos acessível sob demanda.
- Iniciar Desktop funcionando; conexão VNC após a inicialização da unidade.
- Imagem real do Fedora apresentada e Firefox exibido corretamente.
- Modo paisagem funcional e tela cheia com melhor aproveitamento do espaço.

O aceite visual não comprova todos os gestos avançados, Gboard com acentos,
desempenho prolongado, todos os caminhos de navegação nem todos os demais itens
do roteiro físico. Não há novas medições de PSS/CPU/FPS/latência informadas.
Os aceites anteriores de Desktop-1/F1/F2 permanecem limitados ao seu próprio
escopo; não foram repetidos integralmente nesta UX1.

## Pendência UX2 — conexão durante rotação

**Limitação observada fisicamente:** ao girar o Moto G06, a Activity é recriada,
a visualização VNC é desconectada e o My-CT solicita novamente a senha VNC.
A sessão gráfica e os aplicativos do Fedora permanecem ativos. A paisagem
funciona após reconexão; não há continuidade transparente durante a rotação.

Proposta futura para UX2: avaliar um proprietário de cliente/túnel que sobreviva
à mudança de configuração Android, separado da View, e reanexar renderização e
entrada após a recriação. Distinguir rotação de saída real/background, manter
liberação de recursos e teclas e encerrar recursos ao perder o SSH. A conexão
já autenticada deve ser preservada **sem persistir a senha VNC ou outras
credenciais sensíveis**, nem usar credenciais gravadas para reconexão automática.
Exigir testes de rotação repetida, IME, recursos e desconexão no aparelho.

**Nenhuma implementação UX2 foi iniciada neste fechamento.** O código funcional
permanece idêntico ao APK code56 aceito visualmente.

## Hierarquia visual e comparação

| Antes (code 55) | UX1 (code 56) |
| --- | --- |
| Título, barra textual, estado, mensagem, etapa atual e diagnóstico acima da imagem | Barra de ícones de 48 dp e resumo curto; imagem recebe todo o restante |
| Grandes blocos de comandos abertos inicialmente | Painel de opções fechado inicialmente |
| Comandos extensos em linhas horizontais, alguns fora da região visível | Ações essenciais com alvos de 48×48 dp; FlowRow quebra em larguras menores; opções textuais quebram linhas |
| Etapas e mensagens técnicas persistentes | Diagnóstico em uma aba do painel, aberto explicitamente |
| Iniciar/Encerrar junto aos comandos cotidianos | Aba Sessão, separada de mouse/teclas; confirmação destrutiva mantida |
| Tela cheia escondia barras Android, mas mantinha cabeçalho e blocos | Imagem usa a área inteira; botão discreto de 48 dp recupera controles sobrepostos |
| Teclas e controles de mouse permanentemente expostos | Faixas opcionais de teclas ou zoom/pan; apenas uma aberta por vez |
| Leitura do contador/mensagem por quadro na tela principal | Contador/mensagem lidos somente pelo diagnóstico; o Canvas invalida diretamente |

Medições de composição nativa com fixture 1280×720 (janela de teste, sem
representar dimensões físicas do Moto):

| Janela | Viewport UX1 normal | Imagem proporcional normal | Imersivo |
| --- | --- | --- | --- |
| 360×800 | 360×728, origem y=72 | 360×202,5, centralizada | viewport inteiro, controles recuperáveis |
| 800×360 | 800×288, origem y=72 | 512×288, centralizada | viewport 800×360; imagem 640×360 |

Na captura anterior desconectada de 360×800, os blocos de controle ocupavam
aproximadamente 296 px acima do viewport. A nova barra/resumo ocupa 72 px na
fixture comum. **A imagem ajustada à largura em retrato continua 16:9**; mais
área disponível não aumenta artificialmente sua altura. O ganho é ausência de
painéis permanentes, espaço para explorar a imagem e visualização paisagem.

Não há meta artificial de ocupação. Ajuste proporcional usa
`min(larguraDisponível/1280, alturaDisponível/720)`. Em retrato permanece espaço
preto ao ajustar a imagem 16:9; zoom/pan ou paisagem permitem explorar melhor o
conteúdo. O ajuste não deforma nem recorta involuntariamente o framebuffer.

## Operação da candidata

Barra: Voltar, Menu, Conectar/Desconectar, Teclado (somente conectado), Opções
do Desktop e Tela cheia. Ícones vetoriais originais, com descrições acessíveis;
sem pacote de ícones ou dependência nova. Tema preto/dourado e geometria do My-CT.
As áreas seguras continuam consumidas uma única vez pelo AppShell existente,
incluindo recorte da tela e teclado Android.

Opções abre painel inferior rolável, com fechamento explícito e três destinos:

- **Controles**, somente conectado: modo mover/arrastar, clique direito,
  teclas extras e imagem. Ativar arraste é uma ação explícita; o resumo avisa
  enquanto esse modo estiver ligado.
- **Sessão**: estado real, disponibilidade administrativa, atualização, Iniciar
  quando desligado e autorizado, Encerrar quando permitido. Encerrar abre a
  confirmação F2, informando a perda de trabalho não salvo. Desconectar continua
  sendo somente a visualização; não envia stop.
- **Diagnóstico**: etapas completas de entrada, SSH, túnel, TCP, RFB, segurança,
  autenticação, framebuffer, atualização, decodificação e primeiro desenho.
  Contador/mensagem e medição manual PSS de todo o processo também ficam aqui.

A faixa de teclas mantém Ctrl/Alt/Shift, Esc/Tab/Enter/Backspace e setas. É
horizontalmente rolável, com alvos confortáveis e fechamento fixo. Recolher
teclas libera modificadores. O teclado Gboard continua ligado ao InputConnection
existente, sem reescrita de composição ou envio de teclas.

Ajustar à área restaura zoom e pan. A faixa Zoom e pan oferece +/− e quatro
setas, enquanto a imagem continua visível. Limites: 1–4× o ajuste proporcional;
sem modo 1:1 adicional nesta candidata. Pinça, rolagem de dois dedos e o pan
horizontal existente permanecem iguais. Setas permitem deslocamento local nos
dois eixos, sem emitir eventos remotos. O pan é limitado aos limites da imagem,
inclusive quando a área muda com teclado ou controles.

Tela cheia só começa por toque intencional. Barra e resumo ficam ocultos,
com acionador persistente “Mostrar controles”. A barra recuperada oferece
“Sair da tela cheia”; “Recolher controles” é outra ação. Voltar fecha o teclado
primeiro, depois restaura modo normal, depois retorna à seção anterior/Início.
O drawer mantém seu próprio Back enquanto aberto. Erro VNC ou administrativo
continua perceptível, inclusive com barra oculta, e permite abrir opções.

## Separação e preservação

O AndroidView permanece na mesma posição estrutural durante abertura de painéis,
recolhimento, tela cheia e mudanças de tamanho. Canvas mantém `clipRect` nativo;
Compose mantém `clipToBounds`, preservando a correção F1. Desenho e toque usam
exatamente o mesmo `DesktopViewport`. Novos botões apenas ajustam zoom/pan local;
a interpretação de gestos, autenticação, RFB e escrita de entrada são preservadas.

`DesktopModel`, `DesktopDiagnostics`, `Desktop.kt` (incluindo forwarding e comandos),
`DesktopAdministration`, `DesktopRfb`, `SshConnection`, `TerminalModel`, `MainActivity`,
`AppShell`, `AppSection`, helper C++, sudoers e demais funcionalidades não foram
alterados. Nenhuma consulta ou operação real de serviço Fedora é necessária
para testar esta UX. Administração nos testes usa resultados simulados.

Não mudou a política de ciclo de vida: sair/background/recriação da Activity
fecha somente cliente e túnel, preservando aplicações Fedora. **Rotação que
recria a Activity exige reconectar manualmente**, como antes. Os testes de layout
exercitam redimensionamento em retrato/paisagem e altura reduzida pelo teclado;
não comprovam continuidade de uma conexão física durante recriação. Não há
reconexão automática nova nem mudança global do manifesto/Activity para contornar
esse comportamento.

## Arquivos alterados

- `android/app/src/main/java/dev/myct/DesktopScreen.kt`: hierarquia, barra,
  painéis, contexto, faixas, resumo, saída e recuperação imersiva.
- `android/app/src/main/java/dev/myct/DesktopView.kt`: métodos visuais zoom/pan
  e limitação do pan ao mudar tamanho; gestos e Canvas clip existentes preservados.
- `android/app/build.gradle.kts`: `dev.myct`, code 56 / `0.14.3-desktop-ux1`.
- `DesktopUxRegressionTest.kt`: composição de produção, controles, orientação,
  tela cheia, entrada, administração simulada, desconexão e reentrada.
- `DesktopCanvasRegressionTest.kt`: alinhamento de transformação após zoom/pan,
  redimensionamento e restauração do ajuste, além dos guardas F1 existentes.
- `DesktopCompositionRegressionTest.kt`: mantém verificação nativa dos controles
  com a nova disposição do cabeçalho.
- `DesktopF1InstrumentedTest.kt`: localiza descrições acessíveis dos ícones e
  adiciona roteiro instrumentado de imersivo/IME real/recuperação/Back.
- README, Android README, decisões, diário, retomada e este relatório: estado e retomada.

## Validação e evidências

Ambiente preservado: Java/JBR 21.0.10, Gradle 8.14.3, AGP 8.13.0,
Kotlin 2.2.20, SDK/target 36, NDK 28.2.13676358. Sem nova dependência ou instalação.

```bash
ANDROID_HOME="$HOME/Android/Sdk" ./android/gradlew -p android \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --offline --console=plain
python3 -m unittest discover -s ops/tests -p test_desktop.py -v
git diff --check
```

| Verificação executada | Resultado |
| --- | --- |
| Suíte JVM completa | **391 casos: 389 PASS, 2 SKIP, 0 falhas/erros** |
| Testes Desktop incluídos no total | **53 PASS**, dos quais **7** usam runtime Android/Skia nativo |
| Helper restrito, fixtures sem systemctl real | **4 PASS**; código C++ e regra preservados |
| `assembleDebug` | **PASS**, APK de atualização gerado |
| `assembleDebugAndroidTest` | **PASS de compilação**, instrumentação não executada |
| `lintDebug` | **PASS**, 0 erros, 5 avisos e 2 hints preexistentes |
| Assinatura / pacote / versão | **PASS**, mesmo certificado da code55, `dev.myct`, code56 |
| `zipalign -c -P 16 4` | **PASS** |
| Revisão das capturas nativas | Barra, retrato, paisagem, imersivo, faixas e três painéis revisados |
| Integridade do escopo | Modelos/RFB/SSH/administração/helper e demais telas preservados por hashes |
| UX1 no Moto G06 | **ACEITE VISUAL informado pelo proprietário**, somente no escopo listado acima; rotação com desconexão confirmada |

Os dois SKIP são integrações preexistentes `FedoraHandshakeTest` e
`IdeFedoraSftpTest`, sem acesso físico habilitado. A suíte exercita pares VNC e
respostas administrativas de teste; não enviou start/stop ao servidor. O pipeline
final terminou **BUILD SUCCESSFUL em 1min51s**. Pico RSS da construção medido por
`time -v`: **1.971.364 KiB (~1,88 GiB)**, não consumo do APK no Android.

Novos cenários de composição executam `DesktopScreen` de produção, Canvas e
semântica reais: entrada/saída/reentrada, estados, alvos 48 dp, diagnóstico
recolhido, mesma View durante redimensionamento/imersivo/painéis, recuperação da
barra, Back, foco/entrada nativa com português, teclas/modificadores, clique
direito, zoom/ajuste e confirmação/cancelamento administrativo simulados.
Canvas testa zoom/pan, restauração, alinhamento e áreas de 360×700, 800×280,
800×140 e 280×600, incluindo redução semelhante ao teclado. Navegação, falhas,
recursos e F2 também continuam cobertos pelos testes existentes.

As primeiras rodadas do novo harness detectaram referências de Looper/Choreographer
retidas pelo Compose entre testes Robolectric e travessias WindowManager que
reaplicavam o tamanho padrão ao ocultar barras. Os testes passaram a usar um
recomposer lifecycle-aware com Handler/frame clock da janela atual e aguardar
composição/medida. Nenhuma lógica do APK foi alterada para contornar o runtime
de testes; as asserções de desenho, identidade, tamanho, retorno e confirmação
foram mantidas. Hipóteses, falhas iniciais e dump do worker interrompido estão
preservados em `artifacts/desktop-ux1/hipoteses.txt` e logs correspondentes.

Não há emulador operacional/KVM neste ambiente; a tentativa sem aceleração já
falhou na F1 e não foi repetida. Nenhuma instrumentação foi executada pelo
agente, que também não instalou o APK no Moto. O proprietário posteriormente
instalou e realizou o aceite visual descrito acima. O teste instrumentado adicional de IME real, fullscreen e
Back está **compilado, não executado**. Foco e InputConnection no Robolectric não
comprovam abertura/fechamento real do Gboard nem comportamento de insets no Moto.

Evidências locais: `artifacts/desktop-ux1/final-checks3.log`, `results.json`,
`test-results/`, `lint-results.xml`, `ui/`, assinatura, badging e manifestos de
integridade. Capturas são **fixtures sintéticas**, não sessão Fedora ou prova
física. As imagens dos painéis mostram sua janela isolada, sem compor a janela
Android que permanece por baixo.

### Candidata e recursos

- APK: `artifacts/myct-desktop-0.14.3-ux1.apk`.
- `applicationId=dev.myct`, `versionCode=56`, `versionName=0.14.3-desktop-ux1`.
- Tamanho: **18,451,553 bytes (~17,6 MiB)**, aumento de 32.764 bytes sobre a F2.
- SHA-256: `0df04b9b395a02968ba9a532ef420c8f3fb88e94ad92ef54efa891bbe839ed6b`.
- Certificado SHA-256, igual à F2: `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
- Checksum adicional: `artifacts/myct-desktop-0.14.3-ux1.apk.sha256`.

Framebuffer/bitmap continuam com os buffers existentes; não foi adicionado um
bitmap por quadro ou por modo de tela. Em 1280×720, um bitmap ARGB ocupa cerca
de 3,52 MiB de pixels, além dos buffers RFB e do restante do processo. **PSS,
CPU, FPS e latência reais desta candidata no Moto não foram medidos.** Usar a
medição sob demanda e o roteiro abaixo para registrar valores observados.

Limitações deliberadas: escala ajustada em retrato mantém a altura 16:9; zoom
limitado a 4×, sem novo 1:1/trackpad; faixas extras rolam horizontalmente; painéis
rolam verticalmente; rotação com recriação exige reconexão humana, agora
confirmada fisicamente e registrada para UX2. O aceite visual não amplia a
cobertura física além dos pontos expressamente confirmados pelo proprietário.

## Fechamento Git

A auditoria confirmou os 13 arquivos previstos da UX1, as fontes correspondentes
à candidata validada, `dev.myct`/code56 e o SHA-256 do APK registrado acima.
Não foram encontrados arquivos locais não relacionados entre os pendentes.
Staging limitado às fontes, testes e documentação UX1; APK, builds, evidências
locais, credenciais e keystores ficam fora do commit. A publicação é somente
da branch `feat/myct-desktop-vnc`, sem force push, merge na main ou release.

Neste fechamento foram atualizados apenas os registros de aceite/retomada;
**não foram reexecutados build, lint, testes JVM ou instrumentação**. Os
resultados da tabela são as execuções reais anteriores da implementação UX1.
A instalação e o teste físico posteriores foram feitos pelo proprietário.

## Instalação e roteiro físico complementar

Instalar manualmente `artifacts/myct-desktop-0.14.3-ux1.apk` como atualização do
My-CT existente, caso seja necessário reinstalar a candidata já aceita visualmente.
Manter dados; não desinstalar. Nenhuma nova instalação de helper ou configuração
Fedora é exigida pela UX1. O roteiro original abaixo é mantido para verificações
complementares; sua presença não significa que todos os passos foram executados.

1. Conectar SSH no fluxo habitual; abrir Desktop. Confirmar barra compacta,
   resumo e ausência de diagnóstico detalhado. Abrir/fechar Menu e usar Voltar
   para retornar à seção anterior; reentrar sem encerrar a Activity.
2. Com unidade ativa, Conectar usando a senha VNC. Confirmar imagem real e estado
   Conectado · 1280×720, sem controles cortados em retrato. Abrir menu Openbox
   por toque longo e pelo botão Clique direito, e abrir aplicação gráfica real.
3. Em Opções → Controles, conferir modo mover e ativar/desativar arraste.
   Confirmar cliques alinhados em cantos e centro da imagem, antes/depois de
   pinça, zoom +/−, pan nos dois eixos e Ajustar. Confirmar rolagem de dois dedos.
4. Exibir teclas extras e usar Ctrl/Alt/Shift com combinações, Esc, Tab, Enter,
   Backspace e setas. Abrir Gboard e digitar `ação, coração, João, ç, ã, é`.
   Fechar teclado por Voltar sem sair da seção. Recolher teclas e verificar que
   modificadores não permanecem ativos.
5. Testar paisagem, inclusive com Gboard. Se Android recriar a Activity,
   reconectar manualmente; os aplicativos Fedora devem permanecer abertos.
   Conferir alvos visíveis, barras seguras e ausência de cliques deslocados.
6. Entrar em tela cheia; conferir imagem prioritária e botão Mostrar controles.
   Recuperar/recolher barra, abrir teclado/teclas extras/opções, sair por
   “Sair da tela cheia” e também por Voltar. Confirmar que Menu segue acessível.
7. Abrir Diagnóstico apenas quando necessário; verificar etapas e medir PSS
   após estabilizar, por exemplo após 60 s de uso. Registrar orientação, tempo,
   PSS, fluidez/latência percebida e eventuais falhas. Sem perfil físico de
   memória/CPU/latência nesta entrega.
8. Desconectar/reconectar e confirmar a mesma aplicação remota. Sair/reentrar
   na seção e reconectar; não deve enviar encerramento administrativo.
9. Somente quando o proprietário puder fechar seus programas: Opções → Sessão,
   Encerrar, primeiro Cancelar e confirmar que nada é encerrado; depois executar
   a confirmação e observar estado real desligado. Iniciar e reconectar.
   Não repetir ações destrutivas com trabalho não salvo.
10. Observar Terminal, IDE, Arquivos, Downloads/Torrents/Cloud, Cofre e Saúde
    no uso habitual; relatar qualquer regressão. Aceite final pertence ao dono.
