# MYCT-DESKTOP-1 — candidata Android com cliente RFB nativo

**SUPERADA / REPROVADA NO MOTO G06 em 25/10/2026:** code 53 instalada, mas Desktop
preto sem controles, reproduzido três vezes pelo proprietário. Resultados locais
abaixo são históricos e não constituem aceite. Correção e candidata code54:
[MYCT-DESKTOP-F1](MYCT-DESKTOP-F1-RELATORIO.md).

Data: 24/10/2026. Branch `feat/myct-desktop-vnc`, base local válida
`706ef2d` (merge #63), sem alterações pendentes no início. Nenhum fetch/merge,
release definitiva, instalação administrativa ou mudança de serviço executados.
**Estado: implementação candidata; aceite físico Moto G06 pendente.**

## Contrato e critérios definidos antes da implementação

Prioridade: primeira imagem **real e interativa no My-CT**, com o TigerVNC
existente. O roteiro do proprietário é o gate: abrir Desktop, autenticar,
ver Openbox, abrir menu/aplicativo, mover/clicar, digitar português, desconectar
sem encerrar aplicativos e reconectar; depois iniciar/encerrar realmente e
confirmar regressão das demais funções. Build, fixture e screenshot sintética
não comprovam esse aceite. Nenhum viewer externo foi utilizado.

Não tocar firewall, SSH, SELinux Enforcing, configurações TigerVNC/Openbox,
unidades existentes, banco, downloads ou cofres. Unidade permanece de sistema
`vncserver@:1.service`, display :1, endpoint Fedora `127.0.0.1:5901`.

## Caminho de dados e escolha RFB

`DesktopView (Android Canvas + InputConnection)` → `DesktopModel` →
`RfbClient (socket local 127.0.0.1:porta dinâmica)` → `DesktopForwarding` →
**mesma Session JSch autenticada e host Ed25519 verificado** →
`127.0.0.1:5901` no Fedora. Nenhum acesso ao VNC pelo IP LAN, WebView,
serviço TCP adicional ou transporte novo no Fedora.

Alternativas investigadas em fontes primárias:

| Componente | Licença/integração | Decisão neste recorte |
| --- | --- | --- |
| [noVNC](https://novnc.com/info.html) | MPL-2.0; cliente mantido de browser/Canvas/WebSocket | Exigiria WebView e ponte TCP/WebSocket, mesmo dentro do APK. Não adotado para a tela nativa solicitada. |
| [LibVNCClient](https://github.com/LibVNC/libvncserver) | C nativo, GPL-2.0-or-later conforme [COPYING](https://github.com/LibVNC/libvncserver/blob/master/COPYING); manutenção e [advisories](https://github.com/LibVNC/libvncserver/security/advisories) ativos | Requer integração NDK, distribuição de fonte correspondente e observância da GPL na combinação distribuída. Não incorporar sem decisão de licença e atualização de segurança. Não adotado nem copiado. |
| [Guacamole](https://guacamole.apache.org/doc/gug/configuring-guacamole.html) | Apache-2.0 na integração; VNC depende de libvncclient/gateway | Acrescentaria gateway e não eliminaria a dependência anterior. Não adotado. |
| Perfil RFB Kotlin original | Sem biblioteca RFB de terceiros adicionada; API Android/JVM existente | Escolhido para o fluxo restrito TigerVNC sobre SSH. Código menor e auditável, com testes de protocolo; responsabilidade de manutenção fica no projeto. |

A opção original é uma escolha proporcional **deste perfil**, não uma alegação
de equivalência funcional/segurança com bibliotecas completas. Não se copiou
código de viewer GPL ou MPL. Não se alterou a licença global do projeto, que
não tem LICENSE raiz. Os avisos existentes do APK permanecem, com nota Desktop.
Referência de interoperabilidade: [especificação comunitária RFB](https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst).

Perfil implementado: RFB 3.3/3.7/3.8, **VncAuth obrigatório**, ClientInit shared,
pixel format true-color 32bpp/24bit little-endian, Raw, CopyRect sobreposto,
Hextile, Zlib com dicionário persistente e DesktopSize. Não anunciar codificações
não implementadas. None é recusado. DES/ECB/NoPadding é o desafio padrão VncAuth,
executado por `javax.crypto.Cipher`; não há algoritmo criptográfico próprio.
A confidencialidade/integridade do transporte vêm do SSH já existente.

Senha VNC solicitada por campo Password, não persistida ou incluída em
comando/log/arquivo. Apenas até oito caracteres Latin-1 significativos de
VncAuth, conforme senha existente. Strings efêmeras do campo Compose não são
zeráveis; CharArrays/arrays do desafio/resposta/chave são apagados após uso.
Sem clipboard remoto automático ou gravação de conteúdo do desktop.

## Interface, entrada e recursos

Desktop é destino salvo do drawer, ícone vetorial original, tema preto/dourado
e transição existentes. O observador de abertura lateral é desabilitado neste
destino; **Menu** permanece visível. Estados distinguem serviço consultado,
negociação/autenticação e recebimento de imagem. Ativo no systemd significa
“disponível”; autenticação sem quadro diz “aguardando imagem”.

A imagem usa AndroidView/Canvas, IntArray de framebuffer e Bitmap ARGB reutilizado.
Reader/decoder e writer executam em Dispatchers.IO. Nenhuma leitura de socket
ocorre sob o monitor do Bitmap. Há um writer sequencial com fila limitada a 128
ações; texto IME é uma ação limitada, com eventos down/up ordenados. Só existe
um pedido de atualização por vez; pausa de 33 ms entre respostas limita a demanda
a aproximadamente 30 atualizações/s. Isso não é uma medição de FPS no Moto.
Buffer de linha e bloco comprimido reutilizados; sem Bitmaps por retângulo.

Limites: 4096 em cada dimensão e 4.194.304 pixels, retângulos validados antes
de escrita, nomes/clipboard/blocos comprimidos limitados, erro explícito para
mensagens/codificações não negociadas. Cancelamento interrompe socket e loops
de decodificação. Render/bitmap são descartados ao sair ou ir para segundo
plano, sem continuar decoding oculto. Não reconectar automaticamente.

| Interação | Comportamento |
| --- | --- |
| Toque curto | Clique esquerdo nas coordenadas inversas de fit/zoom/pan; letterbox não clica |
| Um dedo, modo Mover | Move cursor sem pressionar botão |
| Modo Arrastar | Pressiona esquerdo ao tocar, move pressionado, solta no final/cancelamento |
| Toque prolongado / Clique direito visível | Clique direito no cursor |
| Dois dedos | Rolagem vertical; pinça reconhecida fica em zoom local sem emitir wheel |
| Pinça / Ajustar imagem | Zoom 1–4×, translação local com limites; Ajustar recupera fit |
| Teclado | InputConnection Android, composição local até commit/finish; Unicode/Latin-1, Backspace, Enter, Tab, Esc |
| Ctrl/Alt/Shift | Seleção visível; acordes down/up pareados; limpar ao perder foco/desconectar |
| Tela cheia | Barras imersivas, controles recolhíveis; Menu, Teclado, Controles e saída permanecem recuperáveis; Back sai do modo |

Controles se recolhem após o primeiro quadro, dando espaço à imagem. Retrato e
paisagem usam fit na área real. Rotação recria a visualização e desconecta VNC
para liberar recursos; sessão gráfica permanece e exige Conectar novamente.
Sair da seção ou minimizar também desconecta apenas a visualização.

## Serviço Fedora e autorização

Consulta não privilegiada por comando fixo `systemctl show --value
--property=ActiveState vncserver@:1.service`. A arquitetura SETUP tem instalador
supervisionado para outros alvos, sem autorização permanente para esta unidade;
não há endpoint Core de execução arbitrária a reutilizar.

Iniciar/Encerrar usam `sudo -n /usr/local/libexec/myct-desktop-control start|stop`.
Helper C++20 aceita exatamente uma operação de duas, verifica euid root, chama
`execve` de `/usr/bin/systemctl --no-block <ação> vncserver@:1.service`, com
ambiente limpo. O app consulta o estado por até 60 s e não interpreta agendamento
como sucesso. Encerrar exige confirmação das perdas possíveis. Desconectar não
chama helper nem systemctl. Não há enable no boot.

**Instalação manual e autorização pendentes**, separadas do APK:
[ops/desktop/README](../ops/desktop/README.md) e [regra exata de exemplo](../ops/desktop/sudoers.example).
Sem isso, visualização já é utilizável com o serviço ativo; botões administrativos
exibem recusa/falha. **Gerenciamento completo não aprovado** até instalação
revisada e prova real dos dois comandos pelo proprietário.

## Arquivos da entrega

- `Desktop.kt`: contrato, túnel com lease, coordenadas, teclas e composição.
- `DesktopRfb.kt`: negociação, autenticação e decoder/framebuffer.
- `DesktopModel.kt`: propriedade/cancelamento, consultas e fila de entrada.
- `DesktopView.kt`: Bitmap/Canvas, toque e InputConnection.
- `DesktopScreen.kt`: tela, senha temporária, confirmação e imersão.
- Integração em `AppSection`, `AppShell`, `MainActivity`, `SshConnection`,
  `TerminalModel` e versão em `android/app/build.gradle.kts`.
- `DesktopTest`, `DesktopRfbTest`, `DesktopModelTest`,
  `DesktopUiInstrumentedTest` e `ops/tests/test_desktop.py`.
- `ops/desktop`: helper, exemplo e procedimento; notices e documentação.

## Validação e artefato

Comando completo, a partir da raiz (SDK existente; sem baixar dependências):

```bash
ANDROID_HOME="$HOME/Android/Sdk" ./android/gradlew -p android \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  :app:assembleDebugAndroidTest --offline --console=plain
python3 -m unittest discover -s ops/tests -p test_desktop.py -v
git diff --check
```

Ambiente inspecionado antes de qualquer instalação: JBR/OpenJDK 21.0.10,
Gradle wrapper 8.14.3, AGP 8.13.0, Kotlin 2.2.20, compile/targetSdk 36,
minSdk 26, NDK 28.2.13676358 e CMake 3.22.1 configurados. Build-tools 36.0.0,
GCC 16.2.1, Python 3.14.7. Nenhuma dependência foi instalada.

Instrumentação no aparelho **não executada**; o APK AndroidTest compila mas não
foi instalado/executado. Ele usa peer sintético dentro do próprio My-CT para
Bitmap real/Canvas, toque, arraste e InputConnection. Isso não é aceite físico.
Não houve conexão deste cliente ao desktop real nem viewer externo; fixtures
são exclusivamente de teste.

Testes de Desktop cobrem autenticação/vetor OpenSSL, RFB 3.3/3.7/3.8, shared,
recusa de None/senha errada, limites, pixels/parciais/CopyRect/Hextile/Zlib,
dicionário entre retângulos, resize, eventos wire de mouse/teclado, reader idle,
leases exclusivos/reutilizáveis e corrida open/close, coordenadas, pinça lenta,
composição portuguesa, navegação/ícones, duplicatas, SSH trocado, cancelamento
na aquisição, foco/acordes, falha do writer e confirmação do serviço.
O teste final de descarte serializa um callback de cópia com a saída da tela.
Os três testes do helper interceptam execve/geteuid apenas na fixture.

Logs locais: `artifacts/desktop-validation/candidate-checks.log`, `helper-tests.log`.
A rodada anterior completa de build/test/lint, medida com `/usr/bin/time -v`,
terminou em 4min02s, máximo RSS 2.642.900 KiB (~2,52 GiB), zero swap e exit 0.
Isso mede **compilação/testes no Fedora**, não RAM/CPU do cliente em execução.
O Gradle avisou pressão no metaspace de 512 MiB, sem falha; os limites existentes
não foram aumentados. O lint tem 0 erros, 4 avisos e 2 hints: os três novos avisos
são DES/ECB exigido pelo desafio VncAuth (proteção SSH), construtor de View criado
por factory com dependências (não XML), e sugestão KTX para createBitmap. Os
restantes pertencem à Busca existente; não foram mascarados por suppress global.
Avisos Kotlin existentes de condições sempre true na IDE e CXX5304 de XML SDK
não bloquearam a compilação. Os resultados e hash finais constam abaixo.

## Memória e limites de evidência

Consulta somente leitura ao Fedora durante desenvolvimento: ActiveState=active,
SubState=running, UnitFileState=disabled. Xvnc RSS 64.024 KiB, Openbox 19.440 KiB,
soma 81,5 MiB; CPU instantânea 0,0% para ambos. MemoryCurrent de toda a unidade
611.926.016 bytes = 583,6 MiB, CPUUsageNSec=2.656.383.000. A unidade inclui mais
que os dois processos; não atribuir sua memória inteira ao decoder Android.
Nenhum serviço foi iniciado/encerrado/reconfigurado para obter essas medidas.

1280×720: IntArray ~3,52 MiB + Bitmap ~3,52 MiB, além de textura GPU, buffers,
Zlib, SSH e demais telas. Isso é orçamento calculado, não RSS/PSS medido no Moto.
Limite de 4 MP: ~16 MiB por superfície, com bloco comprimido limitado por
retângulo. **Medir memória** no painel conectado fornece PSS de todo o My-CT
sob demanda, fora da thread principal. Registrar antes/depois/durante no Moto.
Sem medições de latência, fluidez ou tráfego real nesta candidata.

Limitações conhecidas: sem áudio, clipboard compartilhado, transferência VNC,
Tight/JPEG/ZRLE, cursor remoto dinâmico, redimensionamento solicitado ao servidor
ou recuperação automática. Cursor local é uma mira sobre a imagem. Unicode
precisa também de suporte da aplicação/layout do Xvnc; Gboard real e acentos
continuam no roteiro de aceite. Não é indicado para vídeo/jogos.

## Instalação do APK e roteiro de aceite do proprietário

Atualizar **sobre** o My-CT existente, sem desinstalar nem apagar dados, pins ou
projetos. O APK é debug instalável, como as candidatas habituais do projeto;
não é uma release definitiva. A keystore privada não acompanha o artefato.
O code 53 é posterior ao code 52 da candidata anterior; manter o mesmo
applicationId e certificado permite atualização Android normal.

1. Instalar `artifacts/myct-desktop-0.14.0-rc1.apk` por transferência habitual
   ou, se o proprietário optar por USB já autorizado,
   `adb install -r artifacts/myct-desktop-0.14.0-rc1.apk`.
2. No My-CT, conectar o SSH ao host já pareado e abrir **Menu → Desktop**.
   **Atualizar estado** deve consultar a unidade real. Com a sessão ativa,
   **Conectar** solicita a senha VNC diretamente no telefone.
3. Confirmar a imagem real de Openbox e que a contagem aumenta quando há
   mudanças (desktop parado pode não enviar novos quadros). Nenhuma tela de
   demonstração/fallback está embutida. Erro de senha não é sucesso.
4. Segurar um ponto vazio ou usar **Clique direito** para abrir o menu Openbox.
   Abrir uma aplicação disponível, por exemplo terminal/editor do Fedora.
   Um dedo no modo Mover apenas move; toque curto clica. Alternar Arrastar para
   mover uma janela/selecionar e voltar a Mover. Verificar soltar também após
   interrupção e troca de foco.
5. **Teclado** abre o IME. Testar no Gboard `ação, café, português, João`,
   espaços, números, pontuação, correções de composição, Backspace, Enter,
   Tab, Esc, Ctrl+C/Ctrl+A e Alt+Tab em contexto apropriado. Os controles
   recolhidos são recuperados por **Controles**. Validar também long press,
   scroll de dois dedos e pinça sem clique/rolagem acidental.
6. **Desconectar**, depois **Conectar** novamente: verificar a mesma aplicação
   aberta e texto preservado. Testar sair/voltar da seção, minimizar e rotação
   (reconexão manual), tela cheia, Back e recuperação de Menu/Controles.
7. **Medir memória** durante a sessão e após alguns ciclos: registrar PSS de
   todo o app, orientação, resolução, número de ciclos, fluidez e eventual erro.
   Se disponível ao proprietário, complementar antes/durante/depois com
   `adb shell dumpsys meminfo dev.myct`. Isso não é viewer externo.
8. Verificar Terminal remoto/local, IDE, Arquivos/SFTP, Saúde e Downloads
   existentes. Não iniciar transferência/limpeza de mídia apenas como teste.
9. Somente após revisão e instalação manual autorizada do helper: salvar os
   trabalhos gráficos, **Encerrar Desktop** com confirmação, confirmar estado
   Desligada, então **Iniciar Desktop**, esperar estado Disponível e conectar.
   Comparar estado no app ao systemd. Não declarar esses dois passos aprovados
   com base em fixtures ou se a regra administrativa ainda estiver ausente.

| Gate físico | Resultado desta entrega |
| --- | --- |
| Instalação/atualização no Moto G06 | PENDENTE — proprietário |
| Imagem real Openbox e abrir aplicativo | PENDENTE — proprietário, exclusivamente My-CT |
| Mouse/toque/arraste/rolagem/zoom | PENDENTE — proprietário |
| Gboard/português/composição/combinações | PENDENTE — proprietário |
| Desconectar/reconectar preservando sessão | PENDENTE — proprietário |
| Retrato/paisagem/imersão/background | PENDENTE — proprietário |
| Iniciar/Encerrar reais | PENDENTE — helper não instalado/autorizado nesta entrega |
| Memória/latência do cliente no Moto | PENDENTE — medição física |
| Regressão real das demais seções | PENDENTE — proprietário |

Retomada: manter esta branch e o APK correspondente aos checks finais, registrar
os resultados físicos sem promover mocks a aceite. Não integrar à main,
publicar release final ou alterar o Fedora para contornar problemas sem o escopo
explícito do proprietário. Em falha, anotar mensagem, etapa e condição mínima
antes de novas correções; não pedir a senha VNC/SSH ao agente.


## Fechamento dos checks e identificação da candidata

| Check final | Resultado |
| --- | --- |
| testDebugUnitTest | **366 casos: 364 PASS, 2 SKIP, 0 falhas/erros** |
| Desktop JVM | **28 PASS** (RFB 10, modelo 9, contrato/gestos/navegação 9) |
| Helper restrito | **3 PASS**, compilação GCC com -Wall -Wextra -Werror |
| assembleDebug | **PASS**, três ABIs existentes preservadas |
| assembleDebugAndroidTest | **PASS de compilação**, não executado |
| lintDebug | **PASS**, 0 erros, 4 avisos e 2 hints descritos acima |
| git diff --check | **PASS** |
| Assinatura / atualização | **PASS**, mesmo certificado da APK publicada code 52 |
| zipalign -c -P 16 4 | **PASS** |
| Imagem/teclado/mouse Moto e start/stop reais | **PENDENTES** |

Os dois SKIP são integrações Fedora preexistentes `FedoraHandshakeTest` e
`IdeFedoraSftpTest`, não habilitadas contra o servidor nesta missão. Nenhum teste
Desktop foi ignorado. Rodada final Gradle: **BUILD SUCCESSFUL em 3min52s**.
Resumo legível por máquina e lista de arquivos:
`artifacts/desktop-validation/results.json` e `changed-files.txt`;
fontes correspondentes em `source-manifest.sha256`. Não aumentar os números com
o APK de instrumentação apenas compilado nem com provas físicas de outras fases.

APK: [myct-desktop-0.14.0-rc1.apk](../artifacts/myct-desktop-0.14.0-rc1.apk),
**0.14.0-desktop-rc1 / code 53 / dev.myct**, 19,585,374 bytes
(~18.68 MiB), debug instalável. Arquivo .sha256 ao lado.

```text
SHA-256 APK: d9a227c40088ca323d4838b557fdad80755e0949bf47d80e18708f3a39a7e07e
Certificado SHA-256: b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3
```

O SHA-256 do APK copiado foi conferido contra o resultado do build final. Certificado conferido também contra a candidata publicada
`myct-r1-space-0.13.5-rc1.apk` (code 52), não apenas contra a keystore local.
Nenhuma keystore ou senha acompanha esta entrega. APK e logs ficam em artifacts,
ignorado pelo Git; manter/copiar o artefato antes de trocar de máquina.

Compartilhar a Session SSH preserva autenticação e identidade, mas desktop com
muitas mudanças compete por CPU/banda com PTY/SFTP; não há medição que permita
prometer latência constante. Validar isso no uso real, junto da memória do Moto.
Não houve instalação do helper, alteração de sudoers/Polkit/firewall/SELinux,
start/stop/restart de serviços, instalação no telefone, publicação, commit de
merge ou integração à main. O restante do projeto permanece na base local.
