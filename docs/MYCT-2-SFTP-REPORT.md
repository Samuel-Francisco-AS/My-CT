# MYCT-2 — SFTP nativo e preferência de entrada IME

## Resultado e critérios de aceite

**Implementado, compilado e parcialmente validado. Aceite físico MYCT-2 no
Moto G06 pendente.** Há listagem SFTP estruturada, navegação, upload/download
por SAF, progresso/cancelamento e abertura do documento baixado. A preferência
**Entrada por caracteres** persiste true/false, default true, sem reiniciar SSH.

Critérios definidos antes de codificar: reutilizar a sessão autenticada e host
key verificada, preservar PTY/FGS/tmux, navegar no home SFTP, copiar nos dois
sentidos sem carregar o arquivo inteiro, recusar sobrescrita silenciosa,
tratar cancelamento/parciais, aplicar IME reversível sem reconexão. Aceite final
exige a jornada no Moto, não apenas este build.

O proprietário informou validação física positiva das principais funções
MYCT-1R; foreground service havia sido descrito como aparentemente funcional.
Esse relato é preservado, sem transformá-lo em teste físico MYCT-2.
**Investigação MYCT-1P suspensa por decisão explícita do proprietário.**
Não se mediu nem se declarou resolvido o lag nesta missão.

## Preparação, Git e ferramentas

- Lidos AGENTS, README, requisitos, planejamento inaugural, relatórios MYCT-1R/
  1P e android/README. Planejamento inaugural preservado.
- Worktree inicialmente limpa em `myct-1-android-ssh`; fetch seguido de comparação
  HEAD/origin retornou `0 0`. Base: `cfe3949e1db48e82fdd916106c43fcd2e538805a`.
- Criada `myct-2-sftp` sobre essa base. Main e PR #6 sem merge/force push/reset.
- JDK/JBR 21.0.10, SDK36, Build Tools36.0.0, Gradle8.14.3/AGP8.13.0/Kotlin2.2.20
  existentes. Nenhum download de dependências, pacote global ou emulador.
- OpenSSH client/server **10.2p1-14.fc44**, subsistema sftp-server disponível.
  ADB sem dispositivo nesta execução.

## Arquitetura e bibliotecas

```text
MyCtApplication → TerminalModel → SshConnection → Session JSch verificada
                    │                              ├─ ChannelShell / PTY existente
                    └─ FilesModel                  └─ ChannelSftp por operação
                         ├─ FilesScreen (Compose)
                         ├─ SAF OpenDocument / ACTION_CREATE_DOCUMENT
                         └─ SftpTransfers (stream de 64KiB)
```

JSch **2.28.8**, Revised BSD, já usado para SSH, fornece ChannelSftp. Compose,
Activity Result, coroutines, ContentResolver e SAF já estavam disponíveis.
**Sem biblioteca nova, protocolo próprio, backend ou extração de arquivos pela
saída da PTY.** Renderer/emulador Termux e seus patches anteriores preservados.

`SshConnection.openFiles` só funciona com a Session conectada, que passou pela
verificação de host key do fluxo existente. Não recebe senha e não autentica
novamente. Registra o canal antes de connect para permitir fechamento; erro
fecha somente esse canal. Desconectar SSH fecha todos os canais registrados.
Não é possível preservar SFTP/PTY se a própria conexão SSH/rede morrer.

`FilesModel` pertence ao motor do processo; estado de arquivo separado do
terminal, operações IO fora da main. Um único worker de arquivos por conexão,
sem fila de transferências. Cada operação possui canal próprio, usado
sequencialmente, sem chamadas concorrentes de navegação/cópia no ChannelSftp.
Cancelamento pode fechar o transporte por outro worker IO para desbloquear a
leitura, sem desconectar Session. Resultado antigo não substitui estado de
conexão nova: seleções e resultados são vinculados a uma geração, invalidada
ao conectar/desconectar. Não há repetição automática de transferências.

`RemoteStore` é a fronteira pequena para testar streams/caminhos/publicação
sem Android nem credenciais; implementação real é `JschRemoteStore`. Não há
novo framework de tarefas. Progresso ocupa um contador atômico por operação,
com atualização de UI a cada 100ms enquanto ativa; não enfileira uma atualização
por bloco. Todos os streams usam `use`, canais fecham ao terminar/falhar.

## Funcionalidades e segurança

### Navegação

- Abas Terminal/Arquivos não desconectam SSH. A view terminal reanexa ao mesmo
  emulador/sessão ao voltar; não pede senha nem reinicia o shell.
- Home fornecido pelo SFTP, caminho canônico do servidor, subdiretórios, pai e
  atualização manual. Nome, tamanho e mtime; estados carregando/vazio/erro.
- Pastas, arquivos regulares, links e tipos especiais identificados. Link só é
  seguido para navegação após confirmação; baixar link/tipo especial é recusado.
- Listagem limitada a 2.000 itens com aviso; nomes não suportados omitidos com
  aviso. Sem listagem ilimitada em RAM. Nomes longos têm elipse na lista.
- Caminhos absolutos e nomes validados; rejeita slash no componente, controles,
  NUL, . e .., nomes acima de 255 bytes UTF-8. Espaço, acentos e Unicode válido
  são permitidos. Não se suporta todo nome Unix arbitrário em bytes não UTF-8.
- APIs JSch que expandem glob recebem escape literal de `*`, `?` e backslash;
  mkdir/realpath recebem caminho literal conforme seus contratos distintos.
  Não há shell ou interpolação de comando; junção usa componentes validados.
- Não é sandbox restrito ao home: navegar ao pai é explícito e as permissões
  reais do usuário Linux são a autoridade. Sem root, chmod ou mudança de contas.
- Sem exclusão/rename/move de arquivos existentes pela interface. Exceção de
  limpeza limitada aos temporários próprios e novo documento SAF da operação.
- Sem telemetria externa/log de conteúdo, URI, caminho pessoal completo, senha,
  host key ou exceção remota bruta. Caminho/nome aparecem apenas na UI esperada.

### Upload Android → Fedora

OpenDocument seleciona `content://` de provedor compatível. Metadados são
consultados em IO; confirma nome editável e diretório antes de copiar. Tamanho
pode ser desconhecido. Sem permissão irrestrita de armazenamento.

Conflito é recusado e pede nova seleção/outro nome; **esta entrega não oferece
sobrescrita, mesmo confirmada**. Primeiro lstat detecta item existente, inclusive
link. Reserva por mkdir um diretório aleatório `.myct-upload-<UUID>`; escreve
somente `payload` dentro dele. Fecha stream, compara bytes com tamanho informado
quando disponível e com lstat remoto do temporário.

Publicação usa **hardlink@openssh.com** para criar o nome final sem substituir
um destino que apareça durante a cópia. JSch pode escolher posix-rename, que
sobrescreve; por isso não usamos rename como fallback. Depois, remove apenas
payload/diretório temporário próprios, sem recursão. Extensão/filesystem sem
hardlink bloqueiam publicação segura; não se contorna com substituição.
Essa é uma limitação de portabilidade deliberada e revisável, registrada em
[decisões](05-DECISOES-E-QUESTOES.md).

Se cancelar antes do ponto de publicação, não há sucesso. Depois de publicar,
um cancelamento tardio não desfaz nem apaga o destino concluído. Queda antes da
resposta final pode deixar resultado incerto: conferir destino antes de repetir.
A limpeza é best effort; canal fechado/rede/permissões podem deixar o diretório
temporário. Não há varredura/remoção automática ao reconectar.

Limite de concorrência externa: SFTP v3/JSch não oferece aqui open-no-follow/
reserva exclusiva de payload por descritor. Diretório aleatório é reservado por
mkdir e publicação final é sem substituição; isso não torna segura uma árvore
sob alteração maliciosa concorrente por outro processo com permissão de escrita.
Preferir diretórios do próprio usuário. A interface não promete atomicidade
contra mudanças arbitrárias do servidor nem modifica permissões para obtê-la.

### Download Fedora → Android

Só arquivo regular; confirmação → ACTION_CREATE_DOCUMENT com MIME inferido da
extensão (fallback octet-stream) → `content://` criado pelo seletor → stream
`wt`. SAF trata nomes existentes criando outro documento; não se abre documento
existente por OpenDocument para gravar. Ao concluir, ACTION_VIEW concede leitura
à aplicação escolhida; ausência de app/provedor compatível gera orientação clara.

Compara tamanho/mtime remoto antes/depois com seleção e conta bytes. Fecha/
flush do provedor antes de confirmar; confere SIZE do documento quando exposto.
SHA-256 local é calculado por streaming e mostrado, **não comparado a hash
remoto do servidor**. SFTP não possui hash portátil nessa integração; não se
executa shell para obtê-lo nem se duplica a transferência automaticamente.
Modificação concorrente de mesmo tamanho/mtime pode não ser detectada.

SAF não garante renomeação/commit atômicos universais. O provedor pode exibir o
documento parcial durante a cópia. Erro, cancelamento, falta de espaço ou origem
alterada não produzem confirmação de sucesso. Tenta excluir somente o documento
novo retornado por esta CreateDocument; se não puder, mensagem explícita PARCIAL
orienta remoção manual. Isso também vale se a abertura do canal SFTP falhar antes
de abrir o stream Android. Processo morto/reboot não consegue executar finally.

Erros de permissão, conexão/canal, arquivo ausente, conflito, tamanho alterado,
provedor recusando acesso e falha de cópia têm mensagens sanitizadas. Falta de
espaço pode vir como IOException genérica do provedor; a mensagem orienta
verificar espaço/permissões. Não foi provocado ENOSPC real nem enchido o SSD.

## Continuidade e cancelamento

- FGS terminal specialUse, permissões e opt-in preservados. Não houve novo tipo
  ou permissão para transferências longas em background.
- Cópias acompanhadas com app visível. Troca de abas/recriação por orientação
  preservam o estado do processo; `onStop` fora de mudança de configuração
  solicita cancelamento. Comportamento exato de orientação precisa de Moto.
- Cancelar registra sinal, fecha canal antes dos streams que possam esperar
  ACK e verifica sinal entre blocos. UI fica ocupada até worker finalizar, sem
  disparar outra operação por cima. Provedor lento/bloqueado pode retardar close;
  não se promete interrupção instantânea em qualquer provedor.
- Desconexão invalida seleções e mostra aviso de recuperação quando havia cópia:
  destino parcial/temporário ou upload já publicado podem precisar de conferência.
- Sem URI/permissão persistida para retomar, senha armazenada, reconnect ou
  comandos repetidos. tmux e autenticação não foram modificados.
- HTTP/torrents e persistência de downloads no servidor não fazem parte disso.

## Entrada por caracteres

`TerminalModel.enforceCharBasedInput` lê SharedPreferences `char-based-input`,
**default true**, e salva cada escolha localmente. `ViewCallbacks` consulta esse
valor. Opções oferece switch e a descrição solicitada. Ao fechar Opções com a
view do terminal, requestFocus/restartInput recria InputConnection; enquanto
Arquivos está aberta, a preferência entra ao voltar ao Terminal. Session SSH,
PTY, autenticação e renderer não são reiniciados.

Mudança reversível; true conserva comportamento anterior, false seleciona o
caminho alternativo do componente existente. Ctrl/Esc/Tab/Enter/DEL/composição/
Unicode/Paste mantêm implementação anterior. Persistência Android e efeito na
IME real dependem do gate físico. Não houve teste A/B de latência, novo coletor
ou afirmação de que false melhora o atraso.

## Verificações executadas

```sh
# Na raiz; ferramentas/caches existentes, sem dependências baixadas.
/usr/bin/time -v android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python android/terminal/upstream/verify.py
/usr/bin/time -v python android/diagnostics/sftp-subsystem-proof.py
# apksigner 36.0.0 verify --verbose --print-certs nos dois APKs;
# comparar internamente certificado, sem publicar fingerprint.
# aapt2 dump badging: pacote, versão, min/target/permissões.
git diff --check
adb devices -l
```

- Build/testes/lint finais: **PASS, 68.93s**; lint `No issues found`.
- **19 testes descobertos: 18 PASS, 1 ignorado**, zero falhas/erros. Handshake
  SSH opt-in sem credenciais não habilitado nesta missão (não comprovaria login).
- 7 regressões existentes: pins, ANSI/UTF-8/resize, clipboard manual/OSC52.
- 11 novos testes: nomes/traversal/glob literal (inclui unquote real JSch),
  publicação/limpeza, conflito prévio e corrida no commit, falta de extensão,
  cancelamento antes/durante, tamanho incorreto/falha de leitura, commit incerto
  sem apagar final, download/tamanho/mtime/link, streaming limitado/fechamento
  de recursos e estados de transferência/cancelamento. Camada remota simulada;
  não exercitam ContentResolver, Activity, IME ou conexão SSH autenticada.
- Origem Termux: **21 arquivos verificados, 2 patches prévios documentados**.
- Assinatura APK válida; certificado debug comparado internamente e igual ao
  MYCT-1R. Pacote/permissões inspecionados: min26, target36, sem permissão ampla
  de armazenamento e sem novo serviço. Nenhum binário versionado no Git.

### Integração local possível sem credenciais

`android/diagnostics/sftp-subsystem-proof.py` usa cliente OpenSSH `sftp -D`
com `/usr/libexec/openssh/sftp-server`, **processo filho sem porta, sshd novo,
conta/chave/senha ou configuração global**. Arquivos apenas em TemporaryDirectory
próprio sob android/.work, removidos ao final. Saída privada do cliente suprimida;
resultado publicado contém só números e booleanos.

**PASS:** upload/download de **8,388,608 bytes por direção**, nomes com acento/
espaço, publicação hardlink (mesmo inode), tentativa de conflito preservou sentinel,
SHA-256 de origem/arquivo publicado/recebido iguais. Script completo ~0.27s pelo
time; trecho de prova interno 0.170s. Sem alteração do serviço OpenSSH existente.

**Limite:** cliente nativo + subsistema real local por pipes; não é teste JSch
sobre Session autenticada nem Android/SAF, não mede Wi-Fi, consumo do app,
progresso visual, cancelamento físico ou desempenho de grande arquivo no Moto.
Não foram usadas credenciais do proprietário para forçar esse teste. Integração
SSH/SFTP compartilhada permanece pendente do aparelho ou de credencial de teste
separadamente autorizada. Não foi criado servidor de teste em porta nova.

## Recursos observados

- Antes: 7,819MiB RAM, 6,671MiB disponíveis, swap 15MiB; filesystem 132GiB livres.
- Build/testes/lint final: RSS máximo **1,709,240KiB (~1.63GiB)**, CPU média
  **435%**, 68.93s, zero swaps reportados pelo processo. Dois workers/heap1536MiB
  preservados; compilador/GC podem usar mais threads. Ciclo anterior emitiu aviso
  de metaspace512MiB ao concluir com sucesso; não se ampliaram limites globais.
- Prova SFTP nativa: RSS máximo **18,268KiB (~17.8MiB)** reportado pelo time,
  CPU96%, 0.27s, zero swaps. Não é memória agregada Android+servidor em rede.
- Depois: 6,781MiB disponíveis, swap15MiB; disco132GiB livres. Builds app59MiB,
  terminal3.3MiB; artefatos locais39MiB. APK MYCT-2 acrescenta ~13.5MB no disco;
  fixtures de prova removidas. Nenhum cache/dependência pesada adicionado.
- Cópia do app usa buffer próprio64KiB mais buffers JSch/provedor; arquivo não
  é carregado inteiro. Teste de componente gerou5MiB incrementalmente sem
  armazenar resultado. RAM/CPU/bateria/throughput das transferências no Moto
  **não medidos**. Não extrapolar números da prova local para o aparelho.

## APK e instalação

- `artifacts/myct-2-debug.apk`, cópia de
  `android/app/build/outputs/apk/debug/app-debug.apk`, ambos ignorados pelo Git.
- **13,496,124 bytes**; `dev.myct`, versionCode **3**, versionName **0.2-sftp**.
- SHA256: `4d2cd343d9685c8795f29e215e270cd2c1de63808ea6f6828bf3ef633778c86a`.
- Checksum ao lado em `artifacts/myct-2-debug.apk.sha256`. Mesma identidade de
  assinatura debug; atualizar por USB/MTP/SFTP existente sem desinstalar.
- ADB opcional: `adb install -r artifacts/myct-2-debug.apk`. Não abriu hospedagem
  pública para transferência. Checklist de 6 passos em android/README.md.

## Pendências e próximo passo

1. No Moto: navegar home/pai/pastas e nomes Unicode; enviar e conferir arquivo
   no Fedora; baixar por SAF e abrir; testar conflito e cancelamento em ambos
   sentidos sem derrubar terminal; alternar abas mantendo sessão.
2. Alternar preferência IME true/false/true, confirmar persistência e teclas/
   composição/Paste, sem reiniciar SSH. Não reabrir investigação de lag.
3. Validar orientação, app em background/cancelamento, provedor indisponível,
   espaço insuficiente e rede perdida; registrar parciais/limpeza real.
4. Sem retomada, sobrescrita, hash remoto portátil ou transferências persistentes
   em background. Limitações não impedem prova vertical mínima, mas precisam
   permanecer explícitas. Não iniciar outra etapa antes da decisão do dono.

**Estado físico:** nenhuma execução MYCT-2 no Moto pelo agente; aparelho não
presente em ADB. O proprietário respondeu explicitamente: **registrar validação física pendente**. Não declarar FILE-01/02/03/04
aceitos no hardware só por esta implementação.

## Arquivos e publicação

- `SftpFiles.kt`, `FilesModel.kt`, `FilesScreen.kt`: integração, estado/IO e UI SAF.
- `SshConnection.kt`, `TerminalModel.kt`: canais e vínculo de sessão/geração.
- `TerminalCallbacks.kt`, `MainActivity.kt`: preferência IME, restartInput, abas,
  cancelamento ao ocultar Activity; preservação das funções anteriores.
- app/build.gradle.kts: somente versão3/0.2-sftp, sem dependências novas.
- `SftpTransfersTest.kt`, `diagnostics/sftp-subsystem-proof.py`: testes dirigidos.
- README, android/README, decisões, diário e este relatório.

Branch **myct-2-sftp**, PR com base **myct-1-android-ssh**, dependente do
[PR #6](https://github.com/Samuel-Francisco-AS/My-CT/pull/6), que continua draft.
URL/estado da publicação registrados no fechamento abaixo após envio; nenhum
merge automático. SHA final documental entregue ao proprietário e consultável
com `git log -1`; código/APK identificados pelo commit de implementação.

## Referências primárias

- [Android SAF e comportamento de CreateDocument](https://developer.android.com/training/data-storage/shared/documents-files).
- [InputMethodManager.restartInput](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager#restartInput(android.view.View)).
- [JSch ChannelSftp 2.28.8](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/ChannelSftp.java).


### Fechamento da publicação

Implementação, testes e documentação enviados no commit
`7bdef5123e95230a5702d654353f596f5c1e7d77`.
[PR #7](https://github.com/Samuel-Francisco-AS/My-CT/pull/7) criado **open/draft**,
base `myct-1-android-ssh`, dependente do PR #6. Ambos confirmados sem merge;
PR #6 permaneceu draft na consulta. Main local preservada em
`2cebb637a94cc73d1dbf309688070d7550a3537a`.

Fechamento documental posterior registra a publicação e a decisão do
proprietário de deixar a validação física pendente. O APK e seu checksum
continuam os descritos acima, sem mudança de código neste fechamento.
SHA final da branch será entregue na resposta e é recuperável por `git log -1`.
