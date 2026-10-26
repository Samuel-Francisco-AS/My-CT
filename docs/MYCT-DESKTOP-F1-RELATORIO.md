# MYCT-DESKTOP-F1 — correção da tela preta e navegação

**Atualização 26/10/2026 — ACEITE PARCIAL DO PROPRIETÁRIO:** SSH/VNC e framebuffer
1280×720 recebido/desenhado no Moto G06; ponteiro, clique direito, menus Openbox,
aplicação Fedora real e correção da tela preta confirmados. Não extrapolar esse
aceite para teclado ou gerenciamento. [F2](MYCT-DESKTOP-F2-RELATORIO.md) corrige a
desconexão antecipada ao recusar Encerrar; instalação do helper ainda pendente.

25/10/2026. Branch `feat/myct-desktop-vnc`, base Git `706ef2d`; fontes não
commitadas da DESKTOP-1 preservadas e corrigidas no mesmo checkout.
**Code 53 REPROVADO no Moto G06 pelo proprietário**, em três entradas na seção.
A instalação como atualização funcionou; isso não valida imagem, mouse ou teclado.
Nova candidata: **0.14.1-desktop-fix1 / code 54**, `dev.myct`.
**Aceite físico da F1 ainda depende do proprietário.**

## Causa raiz e evidências

`DesktopView.onDraw()` executava `canvas.drawColor(BLACK)` sem delimitar o
Canvas aos limites da View. `AndroidView` não deve ser tratado como um recorte
implícito. `drawColor` preenche o **clip atual do Canvas**, não simplesmente o
retângulo local da View, conforme a [API Android](https://developer.android.com/reference/android/graphics/Canvas#drawColor(int)).
Na composição, o clip disponível alcançava os controles do painel. O renderer,
desenhado depois deles, podia pintar de preto toda a área útil da seção,
enquanto as barras de sistema, fora dessa área, permaneciam visíveis.
O defeito ocorria inclusive **antes de autenticar ou receber qualquer pixel VNC**.

Teste mínimo executado **antes da correção**, com o código original do renderer,
em Robolectric API 34 com **Skia nativo**: desenhar a View 320×200 num Canvas
400×400, deslocada (40,100), com magenta sentinela na área dos controles.
O pixel (100,30), acima da View, deveria permanecer magenta `0xffff00ff`,
mas tornou-se preto `0xff000000`:

```text
Desktop overpainted the toolbar before any VNC connection
expected:<-65281> but was:<-16777216>
```

Evidência local: `artifacts/desktop-f1/reproduce-canvas.xml` e
`reproduce-canvas.log`. O mesmo teste passou após aplicar o recorte.
Isso é uma reprodução executada do mecanismo de desenho; não é captura de uma
sessão Fedora nem aceite do Moto. O teste original da DESKTOP-1 desenhava a View
em Bitmap do mesmo tamanho, sem pixels de controles ao redor: não detectaria
esse vazamento. A instrumentação original só havia sido compilada.

Havia também um erro de navegação independente: o BackHandler de Desktop só
estava ativo quando `immersive=true`. Fora desse modo, Back podia cair no
comportamento padrão da Activity e encerrá-la. O modo imersivo iniciava `false`;
não foi identificado como causa da pintura preta. Criação de túnel, TCP e
leitura RFB já eram despachadas para IO, não para a thread da interface.

**Limite do diagnóstico físico anterior:** não havia instrumentação das etapas
na APK code 53 nem log do aparelho fornecido nesta correção. O relato comprova
seleção da seção/tela preta e saída da Activity. Não comprova criação do túnel,
autenticação ou recebimento de framebuffer. O código não inicia uma conexão
VNC ao entrar: ela exige Conectar e senha. Não atribuir essa falha ao TigerVNC
ativo ou à ausência do helper administrativo.

## Correções na interface e navegação

- `onDraw` salva o Canvas, aplica `clipRect(0,0,width,height)` e restaura no
  `finally`. A imagem ampliada e o cursor também ficam recortados.
- `AndroidView` e contêiner do viewport recebem `clipToBounds` explícito.
- Título **Desktop**, estado, **Voltar**, **Menu** e acesso aos controles ficam
  fora do framebuffer e não somem ao receber a primeira imagem.
- Removido recolhimento automático dos controles no primeiro quadro. Imersão e
  recolhimento só são acionados pelo usuário. Controles e diagnóstico têm altura
  limitada com rolagem, preservando a saída em telas pequenas.
- Sem VNC, a área gráfica mostra orientação; durante conexão há progresso;
  falhas mantêm estado, tentativa recuperável e saída. Um framebuffer preto
  legítimo continua cercado pelos controles do My-CT.
- Back: fechar IME primeiro, depois sair da imersão/restaurar controles, depois
  voltar à seção anterior (ou Início na restauração de processo). Não finalizar
  a Activity ao simplesmente deixar Desktop.
- Drawer aberto mantém seu próprio Back: a seção só registra o handler quando
  a navegação está disponível. Proteção de código não salvo da IDE preservada.
- View nativa criada na factory do AndroidView. Entrada/saída e stop/start de
  ciclo de vida mantêm o descarte da visualização sem encerrar aplicativos Fedora.

## Conexão observável e erros recuperáveis

Trace por tentativa, somente em memória, sem senha, nome do desktop, bytes de
imagem, chaves ou mensagens brutas do servidor/exceções. **Diagnóstico** mostra
etapa atual, etapas concluídas e última conclusão real:

1. Entrada na tela.
2. Sessão SSH já autenticada e com host verificado pelo app.
3. Criação do túnel loopback exclusivo.
4. TCP no endpoint local encaminhado.
5. Versão RFB.
6. Seleção de segurança VncAuth.
7. Autenticação VNC.
8. Inicialização do framebuffer/formato/pedido de atualização.
9. Primeira atualização gráfica recebida.
10. Decodificação.
11. Primeiro framebuffer pronto.
12. Primeiro quadro desenhado na View.

`frames` mede quadros decodificados/preparados. `presented` só é marcado após
`onDraw`, com o token da tentativa; preparar um Bitmap não equivale mais a
apresentá-lo. Callbacks antigos não confirmam uma conexão nova.

Categorias: SSH, túnel, TCP, versão, segurança, autenticação, protocolo,
decodificação, renderização, interrupção e timeout. O primeiro framebuffer
mantém timeout de inatividade de 12 s no socket; após ele, desktop parado pode
ficar sem tráfego indefinidamente. Tentar novamente pede a senha outra vez,
sem repetição automática nem persistência. Erros ao preparar o cliente ou
Bitmap e falhas de desenho são recuperáveis dentro da seção.

A recuperação rápida também foi corrigida: `retiring=true` é publicado antes de
ERROR, para que Tentar novamente aguarde o término da limpeza do cliente/túnel.
Consulta do serviço e disponibilidade do helper não são pré-condições do botão
Conectar. Start/stop continuam operações separadas, com instalação manual não
executada nem autorizada nesta F1.

## Testes e ambiente

JBR 21.0.10, Gradle 8.14.3, Kotlin 2.2.20/AGP 8.13.0, compile/targetSdk 36,
minSdk 26, NDK e assinatura anteriores preservados. Adicionado **Robolectric
4.13 somente aos testes JVM**, com recursos Android habilitados; sem nova
biblioteca no runtime do APK. Runtime Android API 34/Skia e maioria das
dependências já estavam no cache; Guava de teste foi resolvida pelo Gradle.

Testes direcionados incluem desenho fora dos limites, framebuffer colorido e
descarte, composição real da tela, Back/reentrada, prioridade IME/imersão,
seção anterior/Início, falhas de túnel/autenticação/versão/segurança, progresso
RFB, separação decode/desenho, callback antigo, recuperação e despachos IO.
Asserção antiga que dependia da palavra “Senha” passou a verificar a categoria
AUTH. Testes do protocolo/framebuffer/gestos/teclado anteriores preservados.

Instrumentação Android acrescentada no próprio My-CT: navegação pelo drawer e
Back sem SSH; conexão TCP/RFB real contra fixture local de teste com framebuffer
preto, verificação dos pixels do botão Voltar por PixelCopy, toque e texto
português pela wire, desconexão/reconexão e retorno após autenticação recusada.
StrictMode detecta rede na thread principal nesse teste. Compilação não será
registrada como execução.

Não havia Moto conectado por ADB nem AVD configurado. Para tentar executar a
instrumentação sem instalar dependências, foi criado AVD temporário com a imagem
API 35/x86_64 já existente, sem KVM, sob TCG, sem janela ou áudio. Tentativa
inicial SwiftShader/Vulkan terminou **SIGSEGV/139 antes do boot**. Segunda
configuração desabilitou Vulkan e limitou o guest a 768 MiB/um core. Resultados
finais de execução, limites e limpeza serão registrados abaixo.

Comandos, a partir da raiz:

```bash
ANDROID_HOME="$HOME/Android/Sdk" ./android/gradlew -p android \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --offline --console=plain
git diff --check
```

## Arquivos da F1 e preservação

- `DesktopView.kt`: recorte nativo, token do Bitmap, confirmação de desenho e
  recuperação de erro de renderização.
- `DesktopScreen.kt`: controles permanentes, Back, ciclo da View, progresso,
  diagnóstico, retry independente do helper e viewport recortado.
- `DesktopDiagnostics.kt` novo: etapas/categorias tipadas e política de Back.
- `DesktopModel.kt`/`DesktopRfb.kt`: trace real do pipeline, timeout inicial,
  confirmação de desenho, erros e bloqueio de retry durante limpeza.
- `AppSection.kt`/`MainActivity.kt`: destino anterior e callback para sair.
- `build.gradle.kts`: candidata code54 e dependência JVM de teste.
- Testes `DesktopCanvasRegressionTest`, `DesktopCompositionRegressionTest`,
  `DesktopDiagnosticsTest`, `DesktopF1InstrumentedTest`, extensões em
  `DesktopModelTest` e `DesktopRfbTest`; documentação correspondente.

Comparação F1 feita contra as fontes da DESKTOP-1 copiadas em
`artifacts/desktop-f1/before`. `SshConnection`, `TerminalModel`, helper e serviços
não receberam mudanças F1. Nada foi instalado/modificado no Fedora para contornar
o problema. Não foram alterados firewall, SSH, SELinux, unidade VNC, Openbox,
PostgreSQL, Jellyfin, Transmission, Prowlarr, terminal, IDE, arquivos ou cofre.
Não houve publicação ou integração à main.

## Limitações e aceite do proprietário

O mecanismo de sobrepintura foi reproduzido e corrigido em runtime Android;
isso não comprova a cadeia real Android → SSH → TigerVNC/Fedora nem Gboard no
Moto. Instalação como atualização, controles visíveis, Back, imagem real,
toque, digitação e reentrada devem ser confirmados no aparelho pela F1.
Start/stop físicos continuam pendentes, separados do aceite de visualização.
Rotação/background continuam desconectando a visualização e exigindo nova
senha; os aplicativos gráficos permanecem no servidor. Limites de codecs,
memória e input da DESKTOP-1 permanecem, salvo as correções descritas aqui.

Roteiro curto: atualizar sem desinstalar; abrir Desktop antes de conectar,
confirmar título/Voltar/Menu e retornar; reabrir com SSH ativo, Conectar com
senha VNC, observar Diagnóstico até **Primeiro quadro desenhado**. Testar menu
Openbox, aplicação real, toque/arraste e português no Gboard. Back deve fechar
IME antes de sair da imersão e da seção. Desconectar/reconectar deve manter a
aplicação Fedora. Em falha, registrar categoria e última etapa concluída, sem
fornecer senha/chaves ao agente.


## Resultado da tentativa de instrumentação

As duas configurações do emulador API 35 sem KVM encerraram o próprio processo
com **SIGSEGV/exit 139 antes de concluir o boot**. A primeira usou SwiftShader/
Vulkan, a segunda GLES sem Vulkan. Não havia outro dispositivo ADB conectado.
**Nenhum teste de instrumentação foi executado.** Compilação do AndroidTest APK
não é PASS de execução. Não houve terceira tentativa, mudança de KVM/kernel,
instalação de outra imagem ou cliente externo para contornar o ambiente.

O AVD temporário `myct-desktop-f1-test` e seu registro foram removidos pelo
avdmanager; logs sanitizados, hipótese e resultados permaneceram em
`artifacts/desktop-f1/emulator*.log`, `emulator-hypothesis.txt` e
`avd-cleanup.log`. Nenhum processo de emulador foi mantido; o servidor ADB iniciado para esses testes também foi encerrado após confirmar a lista vazia de dispositivos. As tentativas em
software chegaram a cerca de 2,6 GiB RSS no host; não são custo do Desktop
Android/Fedora em produção. Não foi feita limpeza de dados pessoais ou global.

A verificação de UI **executada** usa Robolectric API 34/Skia nativo:
`DesktopCompositionRegressionTest` monta a tela real no MainActivity com o
mesmo tema/Surface, mede o viewport, confere pixels dos controles e legibilidade
do título, aciona o dispatcher Android Back e recompõe a seção após sair.
Os testes Canvas verificam pixels fora da View e pixels do framebuffer dentro
dela, inclusive limpeza. A imagem de evidência é gerada por essa composição,
sem VNC/Fedora: `artifacts/desktop-f1/desktop-ui-runtime.png`. Não é um mockup
embutido nem captura do Moto. O teste mínimo foi vermelho antes do recorte e
verde depois, como registrado no XML de reprodução.


## Fechamento final e APK para o proprietário

| Check executado | Resultado |
| --- | --- |
| JVM completo | **377 casos: 375 PASS, 2 SKIP, 0 falhas/erros** |
| Desktop direcionados, incluídos no total | **39 PASS**, incluindo 3 testes com runtime Android/Skia nativo |
| Reprodução do defeito antes do recorte | **FALHA esperada confirmada**, pixel externo tornou-se preto |
| Renderer após recorte e composição real | **PASS**, controles e título pintados; Back não finaliza Activity e há reentrada |
| assembleDebug | **PASS**, dev.myct/code54, ABIs anteriores preservadas |
| assembleDebugAndroidTest | **PASS de compilação**, execução não realizada pelos limites acima |
| lintDebug | **PASS**, 0 erros, 5 avisos e 2 hints |
| Assinatura comparada à code53 | **PASS**, certificado idêntico |
| zipalign -c -P 16 4 | **PASS** |
| git diff --check | **PASS** |
| Aceite real Moto/Fedora/Gboard da F1 | **PENDENTE — proprietário** |

Rodada final: **BUILD SUCCESSFUL em 3min30s**. Os dois SKIP são integrações
Fedora preexistentes (`FedoraHandshakeTest` e `IdeFedoraSftpTest`), não testes
Desktop. Os avisos lint são ModifierParameter no AppShell, DES/ECB exigido por
VncAuth, construtor de View usado por factory e sugestões KTX no Bitmap/Busca;
os hints são da Busca. Nenhum suppress global ou aumento dos limites Gradle.
As condições sempre true da IDE e pressão no metaspace de 512 MiB são avisos
sem falha, preservados fora desta correção.

APK: [myct-desktop-0.14.1-fix1.apk](../artifacts/myct-desktop-0.14.1-fix1.apk),
**0.14.1-desktop-fix1 / code 54 / applicationId dev.myct**, 19635941 bytes
(~18.73 MiB). Atualizar sobre code53, **sem desinstalar**.

```text
SHA-256 APK: 05cb480dfc87b672e9a5d7402ecb7c2e34e82595decb41ea0c8ecf3d845b953e
Certificado SHA-256: b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3
```

Arquivo .sha256 ao lado. O APK entregue foi conferido contra o resultado do build
final e a assinatura contra a APK code53 efetivamente entregue, não apenas contra
a keystore local. Instalação será manual pelo proprietário; se optar por USB já
autorizado: `adb install -r artifacts/myct-desktop-0.14.1-fix1.apk`.
Não houve instalação no Moto, release, push, merge ou operação administrativa.

[Evidência da UI executada no runtime Android](../artifacts/desktop-f1/desktop-ui-runtime.png)
mostra a tela desconectada; não representa imagem real do Fedora. Resultados,
XMLs, log final e diff das seis fontes principais contra a DESKTOP-1 ficam em
`artifacts/desktop-f1/`: `results.json`, `candidate-checks.log`,
`TEST-dev.myct.Desktop*.xml`, `source-manifest.sha256` e `f1-source-diff.patch`.
O XML vermelho pré-fix foi preservado separadamente em `reproduce-canvas.xml`.
