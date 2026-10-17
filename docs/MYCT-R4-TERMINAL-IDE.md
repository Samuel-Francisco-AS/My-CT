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
