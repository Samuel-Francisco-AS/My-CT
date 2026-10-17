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
