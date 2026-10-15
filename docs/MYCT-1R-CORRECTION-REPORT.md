# MYCT-1R — correção do terminal e continuidade operacional

## Critérios e hipóteses antes das alterações

Referência: MYCT-1R-CORRECTIVE-PLAN.md. Aceite requer layout/IME e PTY coerentes,
Copy/Paste explícitos sem OSC 52, captura protegida por padrão com opt-out,
continuidade Android iniciada pelo usuário e recuperação de trabalho no Fedora.
A prova física anterior (`tty=/dev/pts/3`, `stty size=17 51` com IME aberta,
login e ls/cd/pwd) é preservada; não será repetida sem razão de regressão.

- **Confirmado no código:** callbacks manuais de Copy/Paste vão aos mesmos
  no-ops usados para bloquear OSC; FLAG_SECURE fixo causa screenshot preta;
  ViewModel não fornece foreground service; duas barras de teclas tomam altura.
- **Hipótese R1:** tornar edge-to-edge explícito, consumir a união systemBars/
  displayCutout/IME uma única vez, medir a AndroidView e compactar barras reduz
  ambiguidades da área útil. A causa exata do inset no Moto não foi medida.
  Teste mínimo: abrir/fechar IME, comparar dimensões locais e stty size físico.
- **Latência:** JSch já chama Socket.setTcpNoDelay(true), confirmado por javap.
  Não alterar Nagle, não inserir batching de teclado sem medição. Instrumentar
  somente contadores/tempos, sem texto de terminal ou dados pessoais.
- **Clipboard:** separar explicitamente a seleção do usuário dos callbacks OSC;
  testar fluxo manual e bloqueio remoto independentemente.
- **Continuidade:** motor em escopo de processo, FGS opt-in specialUse com subtipo
  honesto, notificação e desconectar. Sem boot receiver, START_STICKY, wakelock,
  senha persistida ou repetição de comandos.
- **Fedora:** tmux 3.7c disponível; usar namespace dedicado e comando visível,
  sem execução automática. Prova local isolada e evidência SSH física separadas.

Estado inicial: branch limpa `myct-1-android-ssh`, HEAD/origin em `61b417a`.

## Resultado e evidências físicas preservadas

**Resultado: correções implementadas e verificadas por build/testes; aceite
físico MYCT-1R pendente.** A evidência fornecida pelo proprietário confirma a
MYCT-1 no Moto G06: autenticação SSH, `ls`, `cd`, `pwd`, `/dev/pts/3` e 17×51
com IME aberta. Não repetimos login ou esses comandos no aparelho. A versão
corretiva, IME/TUI, clipboard Android, opt-out de captura e foreground service
não foram executados no Moto por esta sessão. ADB permaneceu sem dispositivo.
A declaração de sucesso de build não altera esses gates.

## Problemas, causas e correções

| Frente | Evidência / causa | Alteração | Limite da prova |
| --- | --- | --- | --- |
| IME/layout | Duas barras e múltiplas linhas superiores consumiam altura. Causa exata da falha de insets no Moto ainda não medida | Edge-to-edge explícito; união systemBars/cutout/IME consumida uma vez; AndroidView medida; uma barra de 48dp rolável; fonte local 10–20sp, inicial 12sp | Layout novo não observado no aparelho; não se atribui o problema anterior a padding duplo sem evidência |
| Fonte/resize | Renderer recebe pixels; conversão antiga já era única. Reenvio após conectar sobrescrevia pixels medidos por zero; chamadas repetidas podem reenviar mesma grade | Conversão sp→px única mantida; fonte ajustável; preservar tamanho real no resend; deduplicar grade/células idênticas e atualizar diagnóstico com medidas atuais | Teste JVM verifica rows/cols/pixels e resend; SIGWINCH remoto, cursor e TUI no Moto pendentes |
| Digitação | JSch já usa TCP_NODELAY, verificado por javap. Não houve perfil de latência no Moto | Mantido envio imediato por tecla, sem batching ou troca de renderer. Contadores/tempos locais de fila IO e despacho main; redraws desanexados quando UI oculta | Não alegamos ter eliminado lag; medições de RTT/echo, IME e tempo de desenho no aparelho pendentes |
| Copy/Paste | Seleção manual alcançava os mesmos no-ops de TerminalOutput usados por OSC | API UserClipboard exclusiva para gesto; selection/mouse usam essa API. ClipboardManager/ClipData oficiais, texto simples até 64KiB e confirmação antes de Paste; marcação sensível | Parser OSC e fronteiras testados em JVM; clipboard real entre apps não exercitado |
| Screenshot | FLAG_SECURE fixo era a causa confirmada de captura preta | Preferência local ligada por padrão, confirmação para liberar e indicador; flag muda sem reiniciar SSH | Comportamento efetivo de screenshot/recents no Moto pendente |
| Background Android | ViewModel e ausência de FGS não asseguravam conexão ao encerrar Activity/processo | Motor em MyCtApplication, escopo de processo; FGS opt-in controla continuidade e parada, UI reanexa sem autenticar/criar outro socket | API/permissões no APK e build conferidos; ciclo Activity/FGS e duração real não executados em Android |
| Trabalho Fedora | Vida do processo Linux é separada da conexão Android | Comando tmux fixo, mostrado/copiado explicitamente; namespace dedicado e criação/attach manual | Tarefa real sobreviveu à queda de PTY local; teste com SSH autenticado no Moto ainda pendente |

Não houve reimplementação de ANSI/SSH/criptografia. Dois patches mínimos da
view Termux estão em `terminal/upstream/myct-1r-clipboard.patch`, com hashes
originais/atuais em `local-patches.json`. Renderer/emulador upstream permanecem
inalterados. `python android/terminal/upstream/verify.py` confere os 21 arquivos
(19 originais, 2 patches documentados). A sessão de transporte My-CT possui
interfaces distintas para gesto manual e OSC; callbacks remotos continuam no-op.

## Continuidade Android: implementação e permissões

`MyCtApplication` contém um único `TerminalModel` por processo, com CoroutineScope
próprio; não é mais ViewModel. Activity e `SshForegroundService` compartilham
o mesmo motor e sessão. Abrir/retornar/girar a Activity não chama connect.
Tentativa duplicada enquanto conectando/conectado é ignorada e senha descartada.
Sem modo persistente, encerrar Activity definitivamente fecha a conexão;
rotação não fecha. Em background, a UI desanexa redraw e UserClipboard e reanexa
ao retornar, preservando o buffer limitado sem reter callback de Activity morta.

Após SSH autenticado, **Opções → Manter conexão ativa** inicia o serviço apenas
por ação do usuário na Activity visível. No Android 13+ a UX exige autorização
de notificações; permissão/canal bloqueado não ativa o modo. Notificação
ongoing identifica a conexão, abre a mesma UI e oferece Desconectar. Conteúdo
é privado na tela bloqueada. Serviço não exportado, `stopWithTask=false`,
`START_NOT_STICKY`; sem senha ou comandos em Intent. Desativar o modo ou usar
Encerrar/Desconectar fecha SSH e para o serviço. Erro/EOF encerra foreground.

Permissões declaradas: INTERNET, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE e POST_NOTIFICATIONS; AndroidX acrescenta seu
receiver interno protegido por assinatura. Não há CHANGE_NETWORK_STATE,
CHANGE_WIFI_STATE, wakelock, Wi-Fi lock, receiver de boot, solicitação de
exceção global de bateria ou nova biblioteca binária para o serviço.

**Escolha técnica revisável:** `specialUse`, com
`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` descrevendo terminal SSH
interativo iniciado pelo usuário. Não é transferência agendada nem mensageria;
connectedDevice exigiria pré-requisito que este app não utiliza. Evitamos pedir
permissão sem função só para habilitar categoria. A documentação exige tipo,
permissão e justificativa especial; não equivale a aprovação da Play Store ou
validação física. [Tipos FGS oficiais](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

Início é condicionado à Activity visível, sem partida automática em background.
Não há reconexão, replay de comandos ou armazenamento de senha. START_NOT_STICKY
não tenta recuperar processo morto. Force-stop, parada no gerenciador do Android,
process kill, reboot e perda de rede continuam limites reais; Doze/política do
fabricante podem interromper rede. Não prometemos conexão indefinida ou
sobrevivência ao kill. [Partida FGS](https://developer.android.com/develop/background-work/services/fgs/launch),
[parada pelo usuário](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping),
[timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).

**Estado R3:** implementado com enquadramento documental e manifest compilado;
notificação, permissões, parada e retorno à mesma conexão precisam de teste real
em Android 15/targetSdk 36. Nenhum bloqueio de plataforma foi observado em
execução porque o serviço ainda não foi executado no aparelho.

## Clipboard, captura e diagnóstico

Copy selecionado usa somente o callback manual anexado à UI STARTED. Paste
lê `ClipData.Item.text`, sem coerção de URI, recusa vazio/mais de 65,536 bytes
UTF-8 e confirma inclusive risco de quebras de linha executarem comandos.
Confirmação é vinculada à sessão que a abriu e descartada ao sair da tela;
não envia para outra conexão. OSC não lê/escreve clipboard nem responde com
seu conteúdo. O Android recebe marcação IS_SENSITIVE; isso não impede acesso
ao clipboard conforme política do SO. [Clipboard Android](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste).

Proteção de captura usa preferência local, default true, com opt-out consciente
e FLAG_SECURE aplicado/removido na janela existente. Não se reinicia SSH.
Pins/host key, senha efêmera e exclusão de backups continuam preservados.

Diagnóstico manual contém apenas área em pixels, linhas×colunas, fonte e
máximos de espera local IO/main. Não registra terminal, teclas, fingerprints,
usuários ou endereços. Não mede RTT/echo/desenho. A união de insets segue as
APIs Compose existentes; confirmar resultado no Moto em vez de inferi-lo de
código. [Insets Compose](https://developer.android.com/develop/ui/compose/system/insets-ui).

## Persistência do trabalho no Fedora e prova tmux

Encontrado `/usr/bin/tmux`, versão **3.7c**, sem instalação. O app apresenta e
copia, sem executar nem acrescentar newline:

```sh
tmux -L myct -f /dev/null new-session -A -s myct-main
```

O proprietário deve usar prompt de shell vazio, colar e pressionar Enter. Socket
`myct` é independente do tmux padrão; `-A` anexa/cria `myct-main`, sem sobrescrever
ou destacar clientes existentes. `-f /dev/null` evita configuração pessoal em
novo servidor. Sem root; permissões do usuário SSH. Não há attach automático
após login nem scripts remotos silenciosos. Procedimento de tarefa inofensiva,
detach com Ctrl-B d e recuperação manual em `android/README.md`.

**Prova real local no Fedora:** servidor tmux exclusivo com socket de nome
aleatório em android/.work e configuração /dev/null, sessão `myct-1r-proof`.
Tarefa shell escreveu contadores uma vez por segundo. Cliente conectado por PTY
local foi encerrado fechando o master; zero clientes após fechamento. Contador
passou de **2 para 4** enquanto desconectado. Novo cliente PTY reanexou ao mesmo
pane/processo (PID comparado internamente, não publicado). Socket tinha modo
0700. Servidor de teste e tarefa foram encerrados ao final usando somente esse
socket dedicado; nenhum tmux padrão ou sessão de outro projeto foi consultado
ou alterado. Evidência sanitizada em android/.work/myct-1r-tmux-proof.json.

**Limite:** foi fechamento de **PTY local**, não queda de SSH autenticado. Não
foram solicitadas/lidas chaves privadas ou senhas do proprietário para forçar
essa prova. A recuperação por SSH no Moto precisa de teste manual. tmux não
preserva processos após reboot do Fedora ou encerramento explícito da sessão.
**Estado R4:** procedimento/app de auxílio prontos, continuidade local provada,
gate SSH remoto pendente.

## Verificações e resultados

Com JDK 21/SDK/Gradle já disponíveis, sem downloads de dependências:

```sh
MYCT_TEST_LOCAL_SSH=1 /usr/bin/time -v android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python android/terminal/upstream/verify.py
# Script de prova tmux isolada mantido apenas no diretório ignorado android/.work.
python android/.work/myct-1r-tmux-proof.py
```

- Primeira etapa R1/R2: build/testes offline PASS em 44s.
- R3: build/testes/lint offline PASS em 66.83s; zero erros/warnings e um hint
  de primitive state. Hint corrigido com mutableIntStateOf, sem alterar dependência.
- Verificação final após preservar pixels no resend: **PASS em 60.71s**,
  **8 testes, zero falhas/erros/ignorados**, lint **No issues found**.
- HostKeysTest (2): pins/host/chave divergente e entradas inválidas.
- TerminalAdapterTest (2): ANSI/buffer alternativo/UTF-8/Ctrl-C/resize e sessão
  encerrada sem envio.
- ClipboardAndResizeTest (3): seleção manual e OSC 52 de escrita/query via parser,
  callbacks remotos inertes, UI nova não removida por detach antigo, limites
  UTF-8, resize deduplicado, pixels preservados no resend e sessão fechada.
  O teste JVM possui adaptador de android.util.Base64 para java.util.Base64
  apenas em src/test; não é plataforma Android simulada nem código do APK.
- FedoraHandshakeTest (1): regressão de segurança com JSch/OpenSSH local, pin
  incorreto recusado e chave autoritativa aceita **antes da autenticação**.
  Não foi prova de login/shell no novo APK. Suíte repetida após mudanças para
  regressão; comandos físicos básicos já comprovados não foram repetidos.
- Manifest do APK inspecionado com apkanalyzer: serviço não exportado,
  stopWithTask=false, tipo `0x40000000` (specialUse), propriedade de subtipo e
  permissões corretas presentes. Isso não substitui startForeground em Android.
- APK final verificado com apksigner (v2 válida); hashes upstream/patches
  conferidos. ADB sem dispositivos. PR #6 consultado: open, draft=true, merged=false.

**Não executados:** medições físicas IME/TUI, Copy/Paste entre apps, screenshots,
início/retorno/parada FGS e 5/15min com tela apagada, consumo de bateria, force-stop,
rede caída e attach tmux após queda SSH real no Moto. Não há PASS físico novo.

## Consumo observado

- Antes: 7.6GiB RAM, ~6.7GiB disponíveis, 15MiB de zram já utilizados; disco
  principal com 132GiB livres.
- R3 (build/testes/lint): máximo RSS **1,815,336KiB (~1.73GiB)**; CPU média 408%,
  66.83s, zero swaps reportados pelo processo.
- Última verificação: máximo RSS **1,732,616KiB (~1.65GiB)**; CPU média **458%**,
  60.71s, zero swaps reportados pelo time. Dois workers não são limite rígido
  de threads JVM/GC ou de uso de CPU.
- Depois: ~6.6GiB disponíveis e zram ainda em 15MiB na amostra. Árvore Android
  com builds ~64MiB; cache Gradle ~1.6GiB; artefatos locais MYCT-1/MYCT-1R ~26MiB.
- Zero downloads de dependências, novas ferramentas, emulador ou pacote global.
  Nenhum serviço existente, SSH/firewall ou projeto externo foi modificado.
- RAM/bateria do foreground service e custo de rede em repouso no Moto ainda
  não medidos. Keepalive JSch existente foi mantido, sem wakelock/polling novo.

## APK e instalação manual

- Arquivo: `artifacts/myct-1r-debug.apk`; build original em
  `android/app/build/outputs/apk/debug/app-debug.apk`; ambos fora do Git.
- Pacote `dev.myct`, versão **0.1.1-ssh-corrective**, versionCode **2**,
  minSdk **26**, target/compileSdk **36**. Assinatura debug v2 verificada; certificado comparado internamente e igual ao APK MYCT-1, sem publicar fingerprint.
- Tamanho: **13,383,461 bytes (~13.38MB)**.
- SHA256: `4c9d303ab1086efab7a4237cc07be3fc4907905367c0b1cc4a91dc9e5dc8044c`.
- Checksum também em `artifacts/myct-1r-debug.apk.sha256`.
- Instalar por transferência manual USB/SFTP autenticado, conforme android/README.
  Não foi criada porta ou hospedagem pública para transferir. Preservar assinatura
  local para atualização da versão anterior sem apagar pins/dados.

## Arquivos alterados e retomada

- `MainActivity.kt`: insets, barra/fonte, clipboard confirmado, proteção de captura,
  uso do owner de processo, opt-in/notificações e instrução tmux.
- `TerminalModel.kt`, `MyCtApplication.kt`, `SshForegroundService.kt`: ownership,
  conexão única, IO/teardown, foreground e parada deliberada.
- `ClipboardPolicy.kt`, `TerminalDiagnostics.kt`, `TmuxCommand.kt`: limites simples,
  métricas sem conteúdo e comando fixo sem execução automática.
- Manifest e app/build.gradle.kts: serviço/permissões e versionCode 2; sem nova lib.
- TerminalSession.java e dois patches da view: manual/OSC separados e dimensões;
  upstream/README, patch, hashes e verify.py para revisão/continuidade.
- ClipboardAndResizeTest.kt e adaptador Base64 somente JVM.
- README, android/README, relatório MYCT-1, este relatório, plano corretivo,
  docs/05 e diário atualizados com histórico preservado; .gitattributes preserva
  whitespace de contexto do patch. Planejamento inaugural e main preservados.

**Próximo gate:** instalar este APK e focar nas correções, sem repetir login/ls/cd/pwd
sem motivo. Medir stty size com IME aberta/fechada e orientação, TUI/teclas,
Copy/Paste/OSC, proteção de captura e retorno à mesma sessão com FGS por 5/15min.
Testar parada da notificação e recuperação manual do trabalho em tmux após queda
SSH. Registrar versão Android, bateria/tempo e resultado por frente. Se falhar,
registrar hipótese/teste mínimo antes de corrigir; não ampliar para SFTP/Core.

**Publicação:** branch `myct-1-android-ssh`, PR #6 mantido em draft e sem merge.
Commit da implementação e confirmação de publicação registrados no fechamento
Git abaixo; SHA documental final também entregue na resposta ao proprietário.
