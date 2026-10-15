# My-CT Android — Terminal, Arquivos e Saúde

**MYCT-3C (15/10/2026):** Dashboard implementado, **prova no Moto pendente**.
APK `../artifacts/myct-3c-debug.apk`, `0.3.0-health`/code 5, assinatura compatível
conferida contra MYCT-2R. Atualizar sem desinstalar. Conectar no Terminal e abrir
Saúde: consulta CPU/RAM/disco/uptime em HTTP pelo túnel loopback dinâmico da mesma
sessão SSH. Atualiza na entrada e por Atualizar; sem polling. Core precisa estar
iniciado manualmente no Fedora; PostgreSQL pode permanecer parado. Amostras após
falha/desconexão são identificadas como antigas. Nova sessão limpa dados anteriores.

[Relatório, testes e roteiro físico MYCT-3C](../docs/MYCT-3C-DASHBOARD-REPORT.md).
Build continua pelos comandos abaixo. `org.json` é da plataforma no APK; a
biblioteca JSON adicional é somente para testes JVM. Permissão HTTP restrita a
`127.0.0.1`, sem alteração da verificação de host/auth SSH. Sem nova instalação
no aparelho nesta sessão (ADB vazio).

## Histórico Android anterior

# My-CT Android — terminal SSH e arquivos SFTP (fase inicial integrada)

**Estado de 15/10/2026:** no Moto G06, o proprietário utilizou SSH real e SFTP, baixou/abriu imagens e PDF do Fedora e enviou PDF ao Fedora. A transferência SFTP em segundo plano da MYCT-2R **ainda não foi validada fisicamente**. Lag de digitação, interface rígida e scroll que retorna ao topo da pasta pai ficam para a [trilha de refinamento](../docs/09-TRILHA-REFINAMENTO-ANDROID.md). [Fechamento](../docs/08-FECHAMENTO-CICLO-ANDROID.md) · [Retomada](../docs/10-RETOMADA-PROJETO.md).

**APK mais recente da fase:** `../artifacts/myct-2r-debug.apk`, versão 0.2.1/code4, **não incluído no Git**. Atualizar sem desinstalar, preservando a mesma keystore debug local. Históricos MYCT-1/1R e MYCT-2/2R abaixo mantidos como referência; avisos de validação pendente nesses trechos são históricos, anteriores aos testes físicos posteriores.

## Build e instalação

Java 21, SDK Android platform 36 e Build Tools 35/36 já preparados:

```sh
cd android
# Definir JAVA_HOME e ANDROID_HOME para os caminhos locais existentes.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python terminal/upstream/verify.py
```

Wrapper Gradle 8.14.3 `all` com checksum fixado, AGP 8.13.0 e Kotlin 2.2.20.
Sem Android Studio ou emulador. Heap 1536 MiB, 2 workers e Kotlin no mesmo
processo. `--offline` exige caches preparados; máquinas novas precisarão de
revisão de impacto/aprovação antes de downloads volumosos. Não há instalador
de pacotes globais ou deploy automático de servidor.

APK de build: `app/build/outputs/apk/debug/app-debug.apk`.
Entrega MYCT-1R: `../artifacts/myct-1r-debug.apk`, com arquivo `.sha256` ao lado,
fora do Git. Pacote `dev.myct`, versão `0.1.1-ssh-corrective` (versionCode 2),
minSdk 26, target/compileSdk 36. APK debug de desenvolvimento, sem assinatura
release; preserve a keystore debug local para atualizar a instalação anterior.
Outra máquina pode gerar assinatura diferente. Nunca commitar keystore.

Sem ADB: transferir por USB/MTP ou cliente SFTP autenticado existente, conferir
SHA256 e abrir pelo gerenciador de arquivos Android. Permitir temporariamente
instalação por esse gerenciador e revogar ao terminar. Não abrir servidor HTTP
público. Com ADB autorizado, na raiz: `adb install -r artifacts/myct-1r-debug.apk`.
Não é necessário apagar dados/pins ao atualizar com a mesma assinatura.

## Conexão e identidade

Use rede privada e usuário Linux sem privilégios. Primeiro pareamento exige
fingerprint obtido por um canal já confiável, diretamente no servidor:

```sh
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

Informe apenas `SHA256:...`; não confie em scan de rede isolado. Host Ed25519,
StrictHostKeyChecking e pin por endereço/porta continuam obrigatórios. Chave
alterada é recusada; “Refazer pareamento…” exige confirmação e nova conferência
externa. Autenticação atual é `password`, sem importação de chave privada/MFA.
Não alterar sshd para acomodar limitações do app.

Senha não é salva em preferências, saved state, arquivos ou logs. Campo limpo
após conectar/sair da tela, buffers mutáveis zerados; Strings da UI/JVM não
permitem prometer apagamento forense imediato de memória. Pins/metadados ficam
no sandbox local com backup e transferência de dados Android desabilitados.

## Layout, teclado e medições

Há uma única barra de teclas de 48dp, rolável na horizontal: Ctrl (próxima
entrada), Ctrl-C, Esc, Tab, setas, Colar e Teclado. Toque no terminal para abrir
IME. **Opções → Fonte** ajusta 10–20sp; valor inicial 12sp. A conversão para
pixels acontece uma vez, conforme a densidade de fonte Android.

Insets de sistema/cutout/IME são unidos antes da AndroidView. Área medida e
fonte alimentam o renderer Termux e o resize SSH. **Opções → Medições locais**
mostra área em pixels, linhas×colunas do emulador e tempos máximos de espera IO/
main. Os valores não medem RTT/echo nem comprovam o tamanho remoto; compare com
`stty size` ao abrir/fechar IME e girar o Moto. Diagnóstico não guarda conteúdo,
fingerprint, host ou usuário. Não há log de terminal ou batching de teclado.

## Clipboard e screenshots

Copy na seleção é ação manual Android; Colar (barra/seleção/mouse) lê somente
texto simples e pede confirmação antes de enviá-lo. Quebras de linha podem
executar comandos. Limite de 64 KiB em UTF-8, sem truncamento silencioso ou
leitura de URI. Texto copiado é marcado como sensível para evitar preview do
SO, mas o clipboard ainda pertence ao Android e pode ser acessado conforme a
política do aparelho. IME pode inserir texto por seus próprios gestos.

Callbacks OSC 52 de escrita/leitura permanecem inertes; o servidor não obtém
acesso ao ClipboardManager. Dois patches mínimos da view separam seleção/mouse
dos callbacks remotos; renderer/emulador ANSI não foram reimplementados.

**Proteger capturas** vem ativado por padrão. Em Opções, desativar exige
confirmação; a preferência é local e a flag muda sem reiniciar SSH. Ao liberar,
conteúdo pode aparecer em screenshots e apps recentes. Religue a proteção
quando terminar. Nenhum conteúdo é enviado a logs.

## Manter conexão ativa no Android

Após autenticar, abra **Opções → Manter conexão ativa**. É uma ação explícita
na Activity visível. No Android 13+, autorize notificações; se a permissão ou
canal estiver bloqueado, o modo não é ativado. Uma notificação identifica a
conexão, abre o app e oferece **Desconectar**; conteúdo é privado na tela
bloqueada. A notificação não substitui a preferência de screenshot.

Motor SSH em escopo do processo é compartilhado por Activity e foreground
service. Voltar ou girar a Activity reanexa ao mesmo objeto/sessão autenticada,
sem novo login, socket ou comandos. Enquanto UI está oculta, callbacks de
redraw e clipboard são desanexados; o emulador mantém histórico limitado.

Desativar a opção, usar Encerrar ou Desconectar na notificação fecha SSH e para
o serviço. Queda de rede gera erro e encerra o modo; não há reconexão automática
nem repetição de comandos. Morte do processo, force-stop, reboot, parada pelo
gerenciador Android, Doze/rede e políticas do fabricante continuam limites
reais. Foreground service reduz o risco de encerramento comum, sem garantir
conexão indefinida. Não solicitamos desativação global de bateria, wakelock,
Wi-Fi lock ou boot receiver.

Tipo escolhido: `specialUse`, subtipo explícito de terminal SSH interativo;
permissões `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` e
`POST_NOTIFICATIONS` (além de Internet). Serviço não exportado,
`START_NOT_STICKY`, sem credenciais em Intent. Essa escolha técnica não é
aprovação da Play Store nem validação física da plataforma. Justificativa e
[referências oficiais](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
no relatório corretivo. O serviço do terminal não usa dataSync nem permissões de mudança de rede.
A transferência SFTP possui serviço dataSync separado na MYCT-2R, descrito abaixo.

## Recuperar trabalho no Fedora com tmux

Conexão Android e programa remoto são camadas distintas. tmux 3.7c foi
observado no Fedora; outro host pode não o ter. Em **Opções → Recuperar trabalho
· tmux**, o app apresenta/copia este comando fixo **sem executá-lo**:

```sh
tmux -L myct -f /dev/null new-session -A -s myct-main
```

No prompt de shell vazio do Fedora, cole e pressione Enter. Namespace/socket
`myct` é separado do tmux padrão do usuário; `-A` cria ou anexa a `myct-main`,
sem sobrescrever nem destacar clientes existentes. `-f /dev/null` evita carregar
configuração pessoal para um servidor tmux novo; sem root. Não cole em programa
interativo que não seja shell.

Dentro do tmux, faça o trabalho. **Ctrl-B, depois d** destaca o cliente mantendo
o trabalho; não use `exit` se quiser preservar o shell. Após queda de SSH,
autentique novamente e use o mesmo comando, por ação manual. Nenhum comando ou
senha é reenviado automaticamente. tmux não sobrevive a reboot do Fedora,
encerramento da sessão/processo ou falta de recursos.

Prova física inofensiva sugerida, somente dentro dessa sessão:

```sh
n=0; while [ "$n" -lt 30 ]; do n=$((n+1)); printf 'tick %s\n' "$n"; sleep 2; done
```

Anote progresso, desconecte SSH no app, conecte novamente e reanexe manualmente.
Confira que houve progresso enquanto o cliente esteve desconectado. A prova
local MYCT-1R já confirmou sobrevivência após fechar PTY local e attach ao mesmo
processo, em servidor tmux isolado de teste; **não foi prova autenticada de
queda SSH no Moto**. Veja evidência no relatório.

## Gate físico corretivo

Login, `ls`, `cd`, `pwd`, `/dev/pts/3` e `17 51` com IME aberta já foram
observados na MYCT-1 pelo proprietário. Foco novo:

- Alternar IME e orientação, comparar área/linhas/colunas locais com `stty size`,
  manter prompt visível; TUI (`top`/vi), Unicode e teclas sem regressão.
- Selecionar/Copy e colar em outro app; Paste confirmado no terminal; OSC 52
  não altera clipboard. Não usar segredos como conteúdo de teste.
- Captura protegida por padrão, opt-out consciente sem reconectar e proteção
  reativada. Screenshot liberada só durante o teste.
- Com modo ativo: sair, bloquear tela e retornar após 5/15 minutos; mesma sessão
  sem senha repetida; notificação e parada deliberada funcionam. Registrar
  versão Android/bateria sem logs sensíveis.
- Queda de rede, parar FGS e force-stop são testes distintos; sem reenvio de
  comandos. Nova autenticação após morte do processo é esperada.
- Programa em tmux progride após desconectar SSH e é recuperado manualmente.

JVM: `MYCT_TEST_LOCAL_SSH=1 ./gradlew :app:testDebugUnitTest --offline` inclui
handshake real local sem autenticação/shell. O adaptador Base64 em src/test é
somente para alimentar OSC no teste JVM, não faz parte do APK. Sem variável,
esse teste SSH é ignorado. Licenças em assets e `terminal/upstream/`.

## MYCT-2 — Arquivos via SFTP e preferência IME

O proprietário informou validação física positiva das principais capacidades
MYCT-1R. O histórico acima foi preservado. **MYCT-2 ainda exige o checklist
físico abaixo**; build/JVM/subsistema local não são aceite no Moto.

Atualização: APK `../artifacts/myct-2-debug.apk`, versão **0.2-sftp**, code **3**,
mesmo pacote/assinatura debug. Instalar por USB/MTP ou SFTP existente e abrir
pelo gerenciador Android; com dispositivo autorizado, `adb install -r
artifacts/myct-2-debug.apk` na raiz. Não desinstalar a versão anterior.

Após conectar normalmente e verificar a host key, toque **Arquivos**. O canal
SFTP reutiliza a sessão autenticada, sem pedir outra senha. Começa no home
SFTP; toque nas pastas, use **↑ Pai**, **Atualizar** e **Enviar…**. Itens mostram
nome, tipo, tamanho e data; links exigem confirmação para navegar e não são
baixados/modificados. Máximo de 2.000 itens por listagem, com aviso se limitada.
Não há exclusão, movimentação ou renomeação de itens existentes.

- **Enviar:** escolha um documento no seletor Android, confira o nome remoto
  e confirme. O nome pode ser alterado antes de enviar. Conflito é recusado;
  selecione novamente com outro nome. Não há substituição nesta versão.
- Upload usa streaming de 64KiB e diretório temporário exclusivo
  `.myct-upload-<UUID>`. Depois de fechar/conferir tamanho, publica por hardlink
  OpenSSH que falha se o destino já existe. Não usa rename POSIX que sobrescreve.
  Exige `hardlink@openssh.com` e filesystem compatível; sem isso, recusa.
  Limpa somente o payload/diretório temporário da operação; queda/cancelamento
  pode impedir limpeza. Não elimina automaticamente resíduos após reconectar.
- **Baixar:** toque em arquivo regular, **Escolher destino…**, crie um documento
  pelo SAF e acompanhe. O seletor trata nomes existentes sem sobrescrever.
  Ao terminar, **Abrir arquivo baixado** usa o provedor e um app compatível.
- Download confere contagem/tamanho e tamanho/mtime remoto antes/depois; calcula
  SHA-256 local por streaming, sem comando shell/hash remoto. Não prova hash de
  duas pontas nem detecta toda alteração concorrente de mesmo tamanho/mtime.
- **Cancelar:** fecha só o canal/streams da operação; a PTY é preservada se a
  sessão SSH continuar válida. Download incompleto tenta excluir apenas o novo
  documento criado por essa operação; se o provedor recusar, avisa sobre PARCIAL.
  O provedor pode mostrar o destino antes de concluir; não há atomicidade SAF
  universal nem retomada. Não abra um documento até o app confirmar conclusão.

### MYCT-2R — correção de confiabilidade e segundo plano

APK `../artifacts/myct-2r-debug.apk`, versão **0.2.1-sftp-corrective**, code **4**.
Mesma assinatura debug conferida contra MYCT-2; atualizar sem desinstalar.
`adb install -r artifacts/myct-2r-debug.apk` (opcional, na raiz, com ADB autorizado).
O proprietário já comprovou navegação e download/abertura de duas imagens na
MYCT-2; PDF e arquivo grande motivaram esta correção. Novo aceite físico pendente.

Uma operação por vez, sem fila. Download/upload iniciado por você tenta ativar
um **serviço foreground dataSync separado do specialUse do terminal**, enquanto
o app está visível. Permita notificações pelo botão na tela Arquivos (Android
13+) e mantenha o canal **Transferência SFTP** habilitado nas configurações do
Android. Sem permissão/canal/serviço disponível, a mensagem informa modo visível:
sair cancela. Permitir notificações durante uma cópia vale para a próxima.

Com serviço ativo, trocar abas/apps, girar, bloquear a tela ou recriar a Activity
não cancela apenas por ocultar a tela; a notificação mostra progresso e
**Cancelar**, e tocar nela retorna ao app. Estado/IO pertencem ao processo,
não à tela Arquivos. O serviço para após conclusão/falha/cancelamento. O limite
Android 15+ para dataSync (6 horas em 24 horas em background) é respeitado via
onTimeout; não há garantia de rede/processo durante Doze ou políticas do Moto.
Não é necessário habilitar o FGS interativo para transferir em segundo plano.
Desconectar SSH deliberadamente continua interrompendo SFTP.

Cancelamento fecha somente o canal SFTP; streams são fechados pelo worker,
sem fechamento simultâneo. Provedor bloqueado pode atrasar o cancelamento.
Contagem e tamanho/mtime remoto são conferidos; destino SAF é fechado antes
de consultar SIZE. Divergência é erro; SIZE indisponível é aviso explícito.
SHA-256 é local, sem comparação remota. Abrir com visualizador é uma operação
posterior, cuja falha não prova corrupção. MB/GB usam divisão decimal por 1000;
os bytes exatos continuam em detalhes/resultados para diagnóstico.

Force-stop/reboot/morte do processo não executam limpeza nem retomada. Apenas
um marcador booleano local indica interrupção no próximo início; não guarda
senha, URI ou caminhos. Confira documentos PARCIAIS/temporários antes de
repetir manualmente. A queda TCP real afeta todos os canais da mesma sessão.
Downloads HTTP/torrent independentes no Fedora continuam fora deste fluxo.

**Opções → Entrada por caracteres:** preferência local, padrão **ligado/true**.
Desligar usa false; religar restaura true. Feche Opções para a InputConnection
ser reiniciada com a preferência, sem nova conexão SSH. Na aba Arquivos, será
aplicada ao retornar ao Terminal. Pode mudar composição/teclado; não há promessa
de resolver lag. A investigação MYCT-1P permanece suspensa por decisão do dono.

### Checklist curto no Moto G06 — MYCT-2R

1. Baixar PDF sintético/não sensível por SAF e abrir pelo botão do app.
2. Provocar erro isolado SFTP (ex.: arquivo de teste removido após seleção) e
   confirmar terminal utilizável; queda de rede/TCP é um caso distinto.
3. Com notificações permitidas, transferir arquivo grande sintético, trocar de
   app e bloquear tela; conferir notificação de progresso.
4. Voltar e conferir progresso/resultado real, tamanho e abertura do destino.
5. Cancelar manualmente pela tela ou notificação; conferir mensagem, limpeza de
   parcial e terminal. Não repetir automaticamente.
6. Conferir MB/GB e contraste de título, caminho, textos secundários/progresso.

Upload/conflito ainda carecem do aceite já pendente da MYCT-2; não considerar
esta rodada como aprovação implícita. IME permanece reversível, default true e
sem nova investigação de lag; não se exige repetir provas já aprovadas.

Relatório corretivo: [MYCT-2R](../docs/MYCT-2R-CORRECTION-REPORT.md).
Histórico: [MYCT-2](../docs/MYCT-2-SFTP-REPORT.md).
Testes sem rede: `./gradlew :app:testDebugUnitTest --offline`.
Prova de subsistema OpenSSH local, sem autenticação/porta, na raiz:
`python android/diagnostics/sftp-subsystem-proof.py` (gera só temporários próprios).


## MYCT-4B — aba Cofre (em desenvolvimento)

A branch `myct-4b-android-vault` acrescenta a aba **Cofre** ao lado de Terminal, Arquivos e Saúde. Após conectar SSH, apresenta o estado do GNOME Keyring `login`; se bloqueado e compatível, o usuário pode solicitar desbloqueio e digitar a senha em campo temporário. A operação utiliza um canal PTY SSH separado, com as verificações humanas já existentes no `myct-vault`; nenhuma senha é enviada ao Core ou a agentes. **Ainda não foi compilada/testada no Moto nesta branch**. Validação e riscos: [MYCT-4B](../docs/MYCT-4B-ANDROID-VAULT.md). 
