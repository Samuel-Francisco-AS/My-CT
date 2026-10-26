# MYCT-DESKTOP-F2 — gerenciamento da sessão gráfica

26/10/2026. Branch `feat/myct-desktop-vnc`, base Git `706ef2d`, preservando as
fontes locais não commitadas de Desktop-1/F1. Nova candidata local
**0.14.2-desktop-fix2 / code 55 / dev.myct**, sem publicação ou merge.
**Instalação privilegiada e aceite físico do gerenciamento continuam pendentes.**

## Aceite parcial F1 recebido do proprietário

No Moto G06, o proprietário confirmou SSH/VNC funcional, framebuffer 1280×720
recebido e desenhado, movimentação do ponteiro, clique direito e menus Openbox,
abertura/exibição de aplicação Fedora e correção da tela preta sobre os controles.
Não repetir esses gates como pendentes nem extrapolar para teclado, start/stop
ou outros comportamentos não confirmados. A F2 preserva o caminho gráfico validado.

## Causa do encerramento aparente

A implementação F1 tinha:

```kotlin
fun stop() { disconnect(); operation(DesktopOperation.STOP) }
```

Assim, o cliente/túnel e o Bitmap eram liberados **antes** de obter o resultado
administrativo. Qualquer exit não zero do backend era agrupado numa mensagem
que misturava falta do helper, falta de autorização e falha do systemd. Além
disso, o modelo trocava o estado da unidade para ERROR sem reconsultar o Fedora.
A desconexão era local; não era evidência de que o serviço tivesse parado.

Foi executado um teste de reprodução com o modelo original, VNC conectado e
backend de fixture recusando a operação (exit 127). Falhou antes da correção:
`Administrative failure must not close VNC`. XML e log vermelhos preservados em
`artifacts/desktop-f2/reproduce-stop.xml` e `reproduce-stop.log`.
Não foi repetido um `stop` real só para obter um código de saída.

O texto exibido no Moto comprova que a F1 recebeu resultado administrativo não
zero; o exit code exato e stderr daquele evento físico não foram fornecidos.
Não afirmar que foi especificamente 1 ou 127. A inspeção real desta F2 confirmou
**helper ausente** em `/usr/local/libexec/myct-desktop-control`.

Consulta real readonly, antes de mudar fontes:

```text
LoadState=loaded
ActiveState=active
SubState=running
UnitFileState=disabled
MainPID=6963
ActiveEnterTimestamp=Sat 2026-10-24 21:55:00 -03
```

A unidade continua ativa na sessão iniciada em 24/10. Evidência completa em
`artifacts/desktop-f2/unit-before.txt`; não confundir esse estado com o cliente
Android desconectado. Memória de toda a unidade na primeira consulta:
1.973.829.632 bytes; esse número inclui os programas gráficos já em execução,
não mede custo isolado do My-CT nem resultado de uma alteração de recursos.

## Comportamento corrigido

- **Desconectar** fecha somente visualização/túnel e não envia start/stop.
- **Encerrar Desktop** não desconecta antecipadamente. Verifica autorização e
  estado real, envia uma única solicitação e espera a unidade ficar desligada.
- Exit 0 do helper `--no-block` significa solicitação agendada. O app só apresenta
  sucesso após consultar o estado desejado; então fecha o cliente em stop.
- Falha, negação, helper ausente ou resultado incerto mantêm o cliente existente.
  Se o próprio servidor fechar o socket durante uma operação, isso é uma
  interrupção observada e ainda não significa encerramento confirmado da unidade.
- Após erro, o estado real é consultado novamente. A unidade pode seguir ativa
  mesmo com erro administrativo; a falha não é convertida em OFF ou em sucesso.
- Estado VNC, estado consultado da unidade, disponibilidade administrativa e
  resultado da operação são separados. Mensagens administrativas têm campo
  próprio e não são apagadas pelos quadros VNC que continuam chegando.
- Consultar a unidade não exige helper nem root. Capacidade de administrar é
  consultada separadamente; ausência/incompatibilidade/permissões inseguras/
  negação/erro têm mensagens específicas e botões indisponíveis.
- Conectar permanece independente do helper. Durante uma alteração administrativa
  em andamento, evita-se uma conexão nova que cancelaria a verificação daquela
  alteração. Consultas readonly não bloqueiam Conectar.
- Um busy guard impede pedidos concorrentes. Após desconexão/reentrada, a
  disponibilidade é reconsultada. Nada inicia automaticamente no boot ou no app.

O estado da unidade é o da última consulta, não um monitor permanente. Atualizar
estado e cada tentativa administrativa fazem nova consulta. Erros de transporte
após envio podem deixar o resultado incerto: não há repetição automática.

## Helper e autorização revisados

`myct-desktop-control.cpp`, C++20, aceita exclusivamente **um** argumento
`status`, `start` ou `stop`. Unidade e executável systemctl constantes. Consulta
status é readonly e também pode rodar como usuário normal; start/stop exigem
euid 0. Ambiente do execve é só PATH=/usr/bin e LANG=C. Sem shell, comandos
arbitrários, unidades/destinos escolhidos pelo app ou parâmetros extras.
Instalação deve ser arquivo regular root:root 0755, sem setuid/setgid e sem
escrita por terceiros nos diretórios pais.

A regra de exemplo contém exatamente três comandos, `(root) NOPASSWD: NOSETENV:`;
sem wildcard, sudo ALL, shell, editor ou instalador. O app chama sudo com caminho
absoluto, `-n` (não interativo), `-u root`, `--` e `-N` (não atualiza credenciais
em cache; verificado no manual local do sudo 1.9.17p2).

A prévia verifica ownership, permissões e symlinks do helper/pais, compatibilidade
com status, status sob sudo não interativo e permissão de cada comando via
`sudo -l`. **A prévia nunca executa start/stop.** Essa disponibilidade é uma
fotografia: política, credenciais e execução podem mudar/falhar depois. Por isso
cada alteração é revalidada e seus erros são tratados sem derrubar a visualização.
O helper F1 antigo não suportava status; se instalado, exige atualização do
binário e da regra, sem bloquear a conexão gráfica.

Prova real readonly realizada nesta F2: binário candidato compilado, executado
como UID 1000 com `status`, retornou `active`. Prévia exata do APK no Fedora com
o helper ainda ausente retornou:

```text
MYCT_DESKTOP_ADMIN_V1 missing 0 0
```

`visudo -cf ops/desktop/sudoers.example` retornou `parsed OK`. Isso comprova
sintaxe do exemplo, **não instalação, autorização efetiva ou start/stop real**.
Nenhum sudo privilegiado, alteração sudoers/Polkit, start/stop/restart ou enable
foi executado nesta entrega.

## Ativação manual, idempotência e reversão

Procedimento completo e revisável:
[ops/desktop/README.md](../ops/desktop/README.md).

O proprietário deve primeiro compilar/conferir hash e revisar os três comandos.
Se os arquivos finais, owner e modos já forem iguais, as gravações são puladas.
Antes de atualizar, o procedimento guarda arquivo anterior ou marcador de ausência,
com backup privado. Instala por staging root-owned e rename; valida a regra com
visudo antes de publicá-la, confere modos, hashes e contextos. Não substitui o
sudoers global, não modifica a unidade e não inicia nada durante a instalação.

A verificação inicial usa só status e sudo -l. A prova dos botões é posterior:
salvar trabalhos gráficos, Encerrar com confirmação, observar unidade desligada,
Iniciar, observar unidade ativa e Conectar pelo My-CT. Instalação e teste real
são decisões do proprietário; não foram presumidos como autorizados nesta missão.

Reversão restaura somente o helper/regra anteriores ou remove somente os arquivos
novos quando havia ausência, após comparar hashes para não apagar alterações
posteriores. Não para serviços, remove configurações VNC, apaga dados ou muda
SELinux/SSH/target. Não fazer cleanup global nem restaurar um backup antigo sobre
uma configuração mais nova. Diretórios pais desconhecidos e AVC exigem revisão,
sem chmod/chown recursivos nem política SELinux ampla.

## Escopo preservado e arquivos

Arquivos gráficos preservados byte a byte, comprovados pelo manifesto antes/depois:
`DesktopRfb.kt` (inclui VncAuth), `DesktopView.kt`, `DesktopDiagnostics.kt`.
O trecho de gestos/teclas/coordenadas de `Desktop.kt` não mudou; só os comandos
administrativos de start/stop passaram a usar sudo explícito root e sem renovar cache.
AppShell, MainActivity, AppSection, TerminalModel e encaminhamento VNC não mudaram.

Alterados/adicionados F2:

- `DesktopAdministration.kt`: prévia readonly, protocolo restrito de capacidade,
  permissões por operação, mensagens e resultados administrativos tipados.
- `DesktopModel.kt`: sequência consultar/autorizar/solicitar/confirmar, mensagens
  separadas e preservação do cliente após erro.
- `DesktopScreen.kt`: habilitação de Iniciar/Encerrar, mensagens e estado da
  unidade dentro do painel rolável já limitado; área gráfica conectada preservada.
- `Desktop.kt`: apenas comandos sudo administrativos; `SshConnection.kt`: consulta
  fixa de disponibilidade em exec isolado. Nenhum comando arbitrário novo no Core.
- `build.gradle.kts`: candidata code55/0.14.2-desktop-fix2, assinatura habitual.
- Helper, regra de exemplo e procedimento; testes de gerenciamento/parser/C++;
  ajustes das asserções administrativas anteriores e documentação.

## Testes, limites e aceite

Ambiente: JBR 21.0.10, Gradle 8.14.3, AGP/Kotlin/SDK/NDK anteriores, GCC 16.2.1,
Python 3.14.7, sudo 1.9.17p2. Sem instalação de dependências, emulador novo ou
alteração de limites do Fedora. Os testes C++ substituem geteuid/execve na
fixture; não lançam systemctl de alteração. Os testes JVM usam SSH/serviço/VNC
simulados onde indicado; os testes de protocolo e renderer F1 existentes seguem
na suíte de regressão, sem alteração da implementação gráfica.

Cobertura direcionada: helper ausente e removido durante solicitação; autorização
negada e parcial; falha administrativa; perda do resultado; estado ativo após
falha; mensagens preservadas durante atualização de quadros; stop agendado sem
fechar o cliente até observar inactive; sucesso start/stop; busy guard;
desconexão e reentrada com administração indisponível; parser estrito e revisão
C++ de operações/ambiente/argumentos.

```bash
ANDROID_HOME="$HOME/Android/Sdk" ./android/gradlew -p android \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --offline --console=plain
python3 -m unittest discover -s ops/tests -p test_desktop.py -v
visudo -cf ops/desktop/sudoers.example
git diff --check
```

Nesta F2 não há dispositivo Android conectado selecionado para testes, nem nova
instrumentação executada. AndroidTest compilado não significa executado. Os três
testes F1 de runtime Android/Skia são parte da suíte JVM e são identificados
separadamente, sem substituir o Moto. Não repetir emuladores sem KVM que já
falharam no host na F1 sem uma mudança que justifique a tentativa.

O aceite físico F2 permanece: com helper ausente, disponibilidade claramente
indisponível sem impedir o VNC; após instalação manual autorizada, Iniciar/
Encerrar diretamente no My-CT com estado real confirmado e sem regressão do
framebuffer, ponteiro e menus já aceitos. Não declarar esses botões aprovados
apenas por teste verde, compilação ou validação sintática de sudoers.


## Fechamento dos checks e artefatos

| Check | Resultado |
| --- | --- |
| Reprodução pré-fix de Stop com helper ausente | **FALHA confirmada** no código F1, XML preservado |
| Suíte JVM completa | **387 casos: 385 PASS, 2 SKIP, 0 falhas/erros** |
| Testes Desktop, incluídos no total | **49 PASS**, incluindo 3 de runtime Android/Skia nativo |
| Helper C++ restrito | **4 PASS**, argumentos, privilégio e ambiente de execve verificados |
| Binário C++ de produção | **Compilado com -Wall -Wextra -Werror**, status real readonly retornou active |
| Exemplo sudoers | **visudo parsed OK**, não instalado |
| Prévia real no Fedora | **missing 0 0**, nenhum start/stop enviado |
| assembleDebug / assembleDebugAndroidTest | **PASS de compilação**; instrumentação não executada |
| lintDebug | **PASS: 0 erros, 5 avisos e 2 hints** |
| git diff --check | **PASS** |
| Assinatura e zipalign -c -P 16 4 | **PASS**, mesmo certificado da F1/code54 |
| Protocolo/render/input e telas externas ao escopo | **Hashes preservados** conforme manifesto |
| Start/stop físico pelo app após instalação | **PENDENTE — proprietário** |

Gradle final: **BUILD SUCCESSFUL em 3min54s**. Os dois SKIP são integrações
Fedora preexistentes (`FedoraHandshakeTest` e `IdeFedoraSftpTest`), não testes
Desktop. Os cinco avisos lint são os já conhecidos: parâmetro Modifier no
AppShell, desafio DES/ECB do VncAuth, construtor de View por factory e sugestões
KTX em Bitmap/Busca; hints da Busca. Sem suppress global nem mudança dos limites
de memória/threads Gradle. Não atribuir PASS de instrumento apenas ao APK de teste.

Consulta real ao final: **active/running, MainPID 6963, UnitFileState disabled,
mesmo ActiveEnterTimestamp de 24/10**, e SELinux **Enforcing**. Helper final
continua ausente; nenhum arquivo administrativo foi instalado. Só MemoryCurrent/
CPU acumulados variaram na unidade com programas do proprietário, sem alteração
ou redução de limites e sem inferir economia de recursos causada por este trabalho.
Snapshots `unit-before.txt` / `unit-after.txt` em artifacts/desktop-f2.

APK para atualizar a F1 **sem desinstalar**:
[myct-desktop-0.14.2-fix2.apk](../artifacts/myct-desktop-0.14.2-fix2.apk),
**dev.myct / 0.14.2-desktop-fix2 / code 55**, 18418789 bytes
(~17.57 MiB).

```text
SHA-256 APK: 94032f12bfa4af4e2089892e1ecbbfbceee583cd3446fbd39805359135f56780
Certificado SHA-256: b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3
SHA-256 helper candidato: 5a341bd4b98ee6707ea9f56300e15432053a514593d9e09a787ce6a8fa6a8cf1
```

O helper compilado foi copiado para
[artifacts/desktop-helper-v2/myct-desktop-control](../artifacts/desktop-helper-v2/myct-desktop-control),
com .sha256 ao lado, **sem instalação**. Fonte e recompilação estão no procedimento
manual; nenhuma dependência extra no Android ou Fedora. A APK foi comparada à F1
por conteúdo descomprimido: só `AndroidManifest.xml` (versão) e `classes6.dex`
(código da aplicação) mudaram; todos os outros payloads, inclusive bibliotecas
nativas e assets, são byte a byte idênticos. Não houve remoção de componentes.

Evidências em `artifacts/desktop-f2`: `checks.log`, `targeted.log`,
`helper-tests.log`, `sudoers-syntax.txt`, `admin-probe.sh`/`admin-probe-result.txt`,
`graphic-preservation.json`, `apk-payload-preservation.json`, XMLs e `results.json`.
O novo APK foi produzido localmente; sem instalação no Moto, publicação, push,
release ou merge. O aceite F1 parcial permanece válido para os itens informados,
e o novo aceite físico do gerenciamento depende da instalação manual autorizada.
