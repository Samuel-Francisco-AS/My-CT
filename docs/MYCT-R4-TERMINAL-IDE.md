# MYCT-R4 — terminal e IDE remota

Data: 17/10/2026. Base `main` sincronizada `d63ab60`; árvore inicialmente limpa.
Branch `myct-r4-terminal-ide`. Sem merge/deploy/alteração de serviços Fedora.

## A — última investigação SSH (encerrada, sem mudança de comportamento)

Reaberta por autorização R4; lidos AGENTS, README, planejamento, arquitetura,
MYCT-1P, R1, R2 e R3. Auditados IME/Gboard→InputConnection/DEL→adaptador UTF-8→
FIFO→write/flush→JSch→pipe→ring→Handler main→emulador→callback/view/Canvas.
As otimizações R1/R1C e barra/clipboard R3 já estão presentes; não são novidades.

Fontes inspecionadas:
- [Termux TerminalSession/PTY](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)
- [Termux InputConnection](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/terminal-view/src/main/java/com/termux/view/TerminalView.java)
- [JSch 2.28.8 Channel](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/Channel.java), [Session](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/Session.java), [IO](https://github.com/mwiede/jsch/blob/jsch-2.28.8/src/main/java/com/jcraft/jsch/IO.java)
- [ConnectBot Relay](https://github.com/connectbot/connectbot/blob/main/app/src/main/java/org/connectbot/service/Relay.kt), [transporte SSH](https://github.com/connectbot/connectbot/blob/main/app/src/main/java/org/connectbot/transport/SSH.kt)

Conclusões técnicas: Termux executa OpenSSH como subprocesso em PTY local;
My-CT envia bytes diretamente por canal SSH Java, portanto compartilhar view não
iguala transporte/agendamento. ConnectBot também separa leitura bloqueante do UI,
mas usa outro transporte/emulador: não demonstra defeito do JSch. As esperas
10 ms/100 ms em Session.write são condicionais a rekey/janela remota insuficiente,
não um timer por tecla. IO.put faz write+flush no pipe: não há evidência para
remover flush ou atribuir um segundo de espera habitual ao PipedInputStream.
JNI/OpenSSH, substituição do renderer e eco local seriam apostas sem validação.
Acessibilidade pode extrair texto no callback; estado físico não disponível.
O parser continua na main como no Termux; não há Compose state por byte.

Sonda existente `ANDROID_HOME=/home/sam/Android/Sdk bash android/diagnostics/run-component-probe.sh`:
20 mil operações/caso, p95 adaptador ASCII 0,092 µs, DEL 0,272 µs;
parser eco/apagar 51×17 0,462 µs; pipe JSch desktop (1000) p95 20,110 µs,
máximo 238,754 µs. Não mede Gboard, Android, SSH autenticado, rede, GPU ou Moto.
Nenhum aparelho em `adb devices -l`. Não repetir diagnóstico indefinidamente:
**lag permanece aberto; transporte/emulador preservados no APK R4**.
Comparação física com Termux SSH no mesmo Fedora/rede continua pendente.

## Critérios B (antes de implementar)

IDE no drawer sem cabeçalho global; Sora AndroidView com destaque local C/C++,
Rust, Python, Kotlin, Shell, JavaScript; linhas, indentação, seleção/clipboard,
undo/redo, busca; navegação SFTP e salvar só por toque explícito. Buffer permanece
em memória ao navegar/desconectar; confirmar descarte/troca/saída; rejeitar
binário/UTF-8 inválido/arquivo grande. Save deve conferir baseline completo,
preparar temporário, preservar modo, verificar bytes e publicar com recuperação.
Testes de conflito/cancelamento/desconexão e build/JVM/lint/patches obrigatórios.
Aceite físico e desempenho Moto não podem ser declarados por build.

## B — implementação

[Sora 0.23.6](https://github.com/Rosemoe/sora-editor/tree/0.23.6) escolhido:
Android nativo/AndroidView, minSdk 26 do My-CT compatível, Kotlin 2.2.20/JDK 17,
Maven Central disponível, análise incremental/undo/busca/IME existentes. Apenas
editor + language-textmate (Joni Java), sem NDK/Oniguruma nativo, LSP ou WebView.
Registro de gramáticas em IO uma vez; conteúdo Sora/undo no modelo do processo,
sem String/Compose state integral por tecla. Copy integral só no Salvar explícito.
LGPL/EPL, avisos/gramáticas, fontes e reconstrução: [licenças](../android/IDE-LICENSES.md).
C/C++ usam bundle TextMate original permissivo; Rust/Python/Shell/JavaScript
usam gramáticas distribuídas pelo VSCode e Kotlin fwcd, com commits fixados.
Não foi adotado o gerador better-cpp devido à divergência de licença do seu
commit com o cgmanifest do VSCode. Gramáticas/configurações finais testadas.

Operação:
1. Conectar pelo Terminal com pin de host existente; abrir drawer → IDE.
2. Home, Pai ou caminho absoluto → Ir; tocar pasta/projeto e arquivo UTF-8.
3. Editar com linhas, destaque por extensão, indentação automática/Tab de quatro
   espaços, seleção/clipboard nativo, Desfazer/Refazer, busca literal e troca da
   ocorrência. `*`/ponto indicam buffer alterado; Salvar é explícito.
4. Terminal alterna à sessão existente; executar manualmente GCC/G++, Cargo,
   Python ou Git no diretório desejado. Nenhum comando/cd é injetado no PTY.
   Criar arquivos/projetos novos também pode ser feito no terminal existente.
5. Projetos volta ao navegador sem perder buffer. Arquivos abre a pasta na seção
   SFTP existente. Voltar à IDE mantém conteúdo/undo. Trocar/fechar arquivo sujo
   exige descarte explícito; Back do app avisa. Desconectar conserva o buffer,
   cuja gravação fica vinculada ao host/porta/usuário/pin da conexão original.

Save: canais SFTP isolados via openFiles/TransferControl. Comparação integral e
metadata, diretório exclusivo privado 0700, novo conteúdo temporário relido,
uid/gid/modo conferidos, backup hardlink do original, nova comparação, rename
atômico OpenSSH e releitura final. Backup permanece em `.myct-edit-UUID/original`
(aparece em Detalhes; backups anteriores não são apagados automaticamente).
Falha antes do commit preserva original; falha/desconexão no commit é marcada
incerta, preserva buffer/backup e bloqueia novo save até conferência/reabertura.
Cancelamento fecha só canal SFTP, sem encerrar PTY. Cleanup interrompido pode
exigir remoção manual dos temporários privados após conferência.

Limites: um arquivo por vez, 1 MiB, até 30 mil LF e linhas de até 16384 caracteres;
binário/UTF-8 inválido e symlink não são editados. UTF-8/BOM e finais de linha
originais são preservados pelo Content; novas linhas usam estilo CRLF/CR/LF
existente. Salvar exige arquivo de propriedade do usuário SSH, escrita de dono,
sem bits especiais e extensões hardlink/posix-rename. Não há save forçado.
ACL/xattr/contextos especiais e outros hardlinks não são copiados para o novo
inode; original permanece no backup. SFTP não tem CAS: comparação otimista não
elimina corrida com outro escritor entre última conferência e rename. Não editar
simultaneamente. Conteúdo/undo sobrevivem só enquanto o processo Android existir;
sem recuperação em disco/autosave silencioso. Consumo/fluidez Moto não medidos.

## C — validação e retomada

Ferramentas existentes: Fedora 44, JBR/OpenJDK 21.0.10, SDK/compile 36, Gradle
8.14.3, AGP 8.13.0, Kotlin 2.2.20, gh 2.97.0. Nenhum pacote/serviço instalado.
Novas dependências resolvidas em build online único; demais builds offline.
Fixtures tiveram ajustes de APIs Java indisponíveis no SDK de compilação e
ACK de subsistema simulado; sem alteração no transporte de produção. Teste de
gramática usa engine real e shim JVM só do logger Android (warning/error falham).

O teste real JSch→subprocesso SFTP OpenSSH passou (0,160 s), incluindo leitura,
save/releitura/backup, modo 0640, Unicode/CRLF/BOM, nome com aspas/`[*?]`, conflito
externo e cancelamento. Não abriu porta TCP, não usou credenciais e não iniciou
sshd. O framing SSH é simulado; o protocolo SFTP e filesystem Fedora são reais.
O smoke de gramáticas detectou três includes locais inexistentes nos bundles
Shell/Kotlin. Foram removidos (antes eram ignorados com warnings repetidos),
com mudanças documentadas em `assets/ide/GRAMMAR-COMPATIBILITY.txt`.

Revisão de concorrência: a confirmação do save compara a versão do Content
com a versão enviada. Evento de IME enfileirado antes do bloqueio da view não
pode fazer uma edição posterior ser marcada como salva; teste dirigido cobre
esse interleaving. Undo e BOM/finais de linha são exercitados no Content Sora real.

Roteiro físico pendente (sem dispositivo/emulador disponível nesta sessão):
abrir arquivo descartável no Fedora pela IDE, digitar/selecionar/colar/indentar,
undo/redo/buscar/trocar e salvar; conferir conteúdo pelo Terminal/Arquivos;
alternar IDE→Terminal→Arquivos→IDE, teclado/rotação e buffer preservados;
alterar arquivo externamente e confirmar recusa; cancelar/desconectar durante
operação e conferir buffer/backup. Medir responsividade/consumo no Moto e
comparar digitação SSH/Backspace com Termux. Não repetir aceite das fases antigas.

Validação final:
```sh
MYCT_TEST_LOCAL_SFTP=1 JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
# Conferir também cada patch com git apply --reverse --check.
git diff --check
```
**BUILD SUCCESSFUL**, 165 testes: **164 aprovados, 1 handshake opt-in ignorado**;
lint **0 erros/2 avisos preexistentes**; 21 arquivos upstream e os quatro patches
reversos aprovados. Tokenização real das sete linguagens e configurações passou;
shim de logger só em testes. Build final: 2m16s, ~687s CPU acumulada, pico RSS
2087088 KiB (~1,99 GiB), zero swaps; aviso de metaspace sem falha, sem mudar
limites. Isso mede build Fedora, não consumo Android. Logs locais ignorados em
`artifacts/myct-r4-validation/`. Sem emulador/ADB, navegação visual/IME não foi
ensaiada; inspeção de ciclo de vida e testes de buffer não equivalem ao Moto.

APK **0.6.5-r4-ide/code 22**, `dev.myct`, minSdk 26/target 36. Certificado SHA-256
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`, idêntico à
0.6.4 local por apksigner. APK 17015408 bytes (~16,23 MiB), crescimento 2,80 MiB;
assets IDE ~1,18 MiB sem compressão; sem nova biblioteca nativa.
[Release candidata, APK, checksum e fonte Sora](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r4-v0.6.5-rc1).
SHA-256 APK: `9ffe4fad0ec02ea67e8b5598331025e2ad3120a58d704fe0c8c0578356f6f7c2`.

Retomar na branch `myct-r4-terminal-ide`, revisar o PR draft e instalar o APK
sobre 0.6.4 sem apagar dados; executar apenas o roteiro novo acima. **Não há
aceite físico nem merge.** O commit A é `146b016`; implementação/validação IDE
ficam em commit separado na mesma branch. SSH continua com a dívida conhecida.


## D — teste físico da IDE e ajustes solicitados (17/10/2026)

**Resultado físico parcial comprovado no Moto G06:** o proprietário abriu `/home/sam/Projetos/IDE-Teste/teste.c` no Sora, editou e salvou no Fedora. Pela sessão Terminal existente compilou com `gcc` e executou o programa, obtendo a saída **`My-CT IDE funcionando!`**. As capturas comprovam editor com realce e linhas, navegador remoto e programa executado. O proprietário informou **não perceber lag na digitação dentro da IDE**. Isso não demonstra por si só a causa do lag intermitente na sessão SSH; a auditoria A e a dívida de performance permanecem inalteradas.

**A IDE foi validada funcionalmente nesse fluxo básico, mas a R4 ainda NÃO recebeu PASS de UX/fechamento nem autorização de merge.** Foram solicitadas três correções na mesma branch, em candidato posterior:

1. **Teclas auxiliares na IDE:** incluir a grade Termux **2×7** já aprovada no terminal (`ESC / − HOME ↑ END PGUP` / `TAB CTRL ALT ← ↓ → PGDN`) imediatamente acima do Gboard na edição, e comportamento na parte inferior útil quando IME fechado, respeitando insets. Reusar estilo/feedback tátil/feedback visual curto da R3. **Destinar eventos ao Sora CodeEditor ativo, não ao SSH**, com semântica correta de texto, navegação, Home/End/PgUp/PgDn, TAB, CTRL/ALT quando suportados; sem criar teclas decorativas nem destruir foco/cursor/undo/seleção.
2. **Área de edição:** remover a redundância do cabeçalho local grande: barra compacta com identificação curta do arquivo, marcador `*` de alteração, Salvar, menu de ações/overflow acessível. Mover Projetos/Terminal/Detalhes/Desfazer/Refazer/Tab/Buscar/Fechar para menu contextual onde adequado, mantendo acesso claro e salvamento explícito. Condensar mensagens informativas, mas preservar erros e conflitos de save. A prioridade é maximizar espaço para o código ao abrir Gboard + grade 2×7; sem cabeçalho global permanente, sem perda do buffer ao abrir/fechar menu.
3. **Navegador de projetos:** padronizar apresentação e interação com `FilesScreen.kt`: `FileGlyphs`, `MyCtGeometry.Control`, metadados legíveis, texto truncável, rolagem `LazyColumn` estável, chaves por item, salvar/restaurar posição por diretório (nome+índice+offset) ao entrar/voltar, e área operacional de altura fixa para evitar movimento vertical quando `busy`/mensagem/progresso mudam. Preservar capacidades de navegação `Home`/`Pai`/caminho absoluto/Arquivos e buffers sujos. Se extrair componente reutilizável, evitar regressão na aba Arquivos aceita; **não compartilhar instâncias de estados de listagem, apenas componentes reutilizáveis**.

**Critérios de aceite da Fix:** código com Gboard aberto continua legível e ocupa a maior área viável; tecla auxiliar move/edita o texto local; CTRL/ALT não se tornam shortcuts SSH involuntários; salvar/undo/busca e troca de abas permanecem acessíveis; navegador IDE não pula/recentraliza ao tocar nem perde posição ao voltar; prova física no Moto com arquivo descartável e recompilação. Não repetir investigação SSH, não alterar transportes/serviços Fedora e não fazer merge do PR #26 antes de novo retorno físico.

## E — MYCT-R4-Fix: ergonomia (17/10/2026)

Partida limpa, fast-forward para `02ca37d`, preservando o relato físico parcial
acima. **Aceite da Fix continua pendente no Moto; PR #26 permanece draft, sem
merge.** Não reaberta a investigação de SSH.

- Editor: faixa local de 48 dp com nome curto, dirty, Salvar e overflow. Projetos,
  Terminal, Arquivos, Detalhes, undo/redo, busca/troca, fechar e recolher/mostrar
  teclas ficam no menu. Mensagens informativas viram feedback breve; erros e
  conflitos continuam visíveis e completos em Detalhes. Busca/caminho em diálogo,
  sem deslocar o editor. Menu não toma foco; fechar diálogo devolve foco ao Sora.
- Grade 2×7 R3: geometria, cores de pressão e um haptic nativo por toque
  compartilhados por componente visual; estado/ações da IDE são independentes
  do Terminal. Grade inferior só na edição, sempre acessível com/sem IME, usando
  os insets já consumidos pelo shell (sem duplicar padding).
- `IdeEditorView` chama **onKeyDown/onKeyUp do Sora**, nunca Activity/SSH: ESC
  desfaz seleção, setas/Home/End/PageUp/PageDown navegam, TAB usa indentação nativa
  inclusive seleção; `/` e `-` usam commitText + notificação nativa da IME.
  CTRL+setas navega por palavra; CTRL+Home/End pelo documento. CTRL apresenta
  atalhos locais A/C/X/V/Z/Y/W/D; ALT apresenta CTRL+ALT+ENTER (linha acima).
  A paleta temporária sobrepõe o código sem mudar sua altura. **Atalhos de letras
  são escolhidos nessa paleta; digitação normal no Gboard continua texto e cancela
  o modificador.** Não se interceptou InputConnection/composição do Gboard.
  Modificadores são consumidos antes da ação e limpos ao digitar, perder foco,
  abrir menu, trocar de tela/documento, iniciar operação ou ir para background.
  Os eventos ALT/CTRL nativos são liberados em finally; binding não suportado
  não pode cair no PTY. Sem sequências de escape remotas.
- Buffer Content e undo permanecem no modelo. Abrir menu/busca/teclado não
  reatribui texto nem recria a AndroidView. Ao alternar Projetos/Terminal/Arquivos,
  guarda viewport do Sora no documento e restaura após layout, com limites novos;
  seleção/cursor ficam no Content original. Sem estado Compose por caractere.
- Navegador: FileGlyphs, superfícies MyCtGeometry.Control, nome até duas linhas,
  tipo/tamanho/data, caminho consultável, seta Pai, Home/Atualizar/Ir. Status de
  40 dp + progresso reservado de 4 dp mantêm a região operacional constante;
  lista antiga permanece durante carregamento, sem encolher/recentralizar.
  Histórico próprio limitado a 64 pastas, usando valor de âncora nome/índice/offset
  já aprovado em Arquivos; salva antes de navegar e inicializa a LazyColumn correta
  antes de desenhar a pasta seguinte. FilesModel/FilesScreen não foram alterados.

Referência inspecionada na versão fixada:
[EditorKeyEventHandler](https://github.com/Rosemoe/sora-editor/blob/0.23.6/editor/src/main/java/io/github/rosemoe/sora/widget/EditorKeyEventHandler.java),
[CodeEditor](https://github.com/Rosemoe/sora-editor/blob/0.23.6/editor/src/main/java/io/github/rosemoe/sora/widget/CodeEditor.java),
[KeyMetaStates](https://github.com/Rosemoe/sora-editor/blob/0.23.6/editor/src/main/java/io/github/rosemoe/sora/text/method/KeyMetaStates.java).
Sora 0.23.6, dependências e licenças preservados; nenhuma instalação. Transporte,
TerminalView, motor RemoteEditor, Fedora, Core/banco, torrents, Cofre e drawer
inalterados. Única extração compartilhada é o visual/haptic da grade R3, mantendo
assinatura e callback do componente Terminal anterior.

Testes novos proporcionais: códigos nativos/literais e grade, CTRL/ALT one-shot,
atalhos suportados e falha sem latch, independência do Terminal, limites de viewport,
histórico de pastas por nome/offset, fallback/clamp/evicção e isolamento, Content
Sora real com seleção/versão/dirty/undo intactos após mudar estado visual. Os testes
JVM não executam Canvas/Gboard/haptics; validação dos oito critérios físicos da Fix
continua com o proprietário. Não há Moto em `adb devices -l` nem AVD nesta sessão.

Validação da Fix: `MYCT_TEST_LOCAL_SFTP=1` + assembleDebug/testDebugUnitTest/lintDebug
**offline PASS**, 173 testes (**172 aprovados, 1 handshake opt-in ignorado**),
lint **0 erros/2 avisos preexistentes**, 21 arquivos upstream e quatro patches
reversos aprovados, whitespace PASS. O build ainda avisa sobre duas condições
redundantes inferidas por smart cast e metaspace, sem erros. Build final 2m16s,
pico RSS 2173212 KiB (~2,07 GiB), zero swaps; configurações de recursos preservadas.
Logs locais em `artifacts/myct-r4fix-validation/`.

APK **0.6.6-r4-ide-fix/code 23**, `dev.myct`, 17011562 bytes.
Apksigner confirmou certificado SHA-256
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`, igual à
0.6.5/code 22. APK atualizável sem apagar dados:
`artifacts/myct-r4-fix-0.6.6-debug.apk`; checksum em `artifacts/myct-r4-fix-SHA256SUMS.txt`.
SHA-256 APK: `63a2f6622fc0e92b166262f5d9c84eb55b7052f5dbd0b1ab4eebb71b85273c11`.

Publicação: fonte/commit enviados na branch do PR #26, que deve continuar draft.
A API da CLI GitHub retornou HTTP 401, apesar de Git SSH e conector do PR
funcionarem. Release candidata preparada com tag `myct-r4-fix-v0.6.6-rc1`; a
publicação do binário depende de renovar `gh auth login -h github.com` pelo
proprietário. Nenhum token/senha solicitado ao agente. Não confundir APK local
validado com release já publicada. Retomar pela conclusão dessa publicação e
pelos oito critérios físicos solicitados, sem reabrir SSH nem fazer merge.
