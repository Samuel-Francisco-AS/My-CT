# MYCT-1 — APK Android e terminal SSH real

## Critérios de aceite definidos antes da implementação

1. Build Kotlin/Compose reproduzível pela CLI com Wrapper fixado, Java/SDK existentes e sem emulador.
2. APK debug instalável; evidência física separada do resultado de build.
3. SSH com autenticação protegida, fingerprint SHA256 obrigatório no primeiro pareamento e rejeição de chave divergente, sem persistência de senha.
4. Shell com PTY Fedora, renderer ANSI existente, entrada IME, Ctrl/Esc/Tab/setas e resize.
5. Erros e desconexão explícitos; encerrar e reconectar sem travar UI.
6. No aparelho: `pwd`, `uname -a`, programa interativo equivalente a htop, Ctrl-C, navegação e reconexão.

**Estado final:** parcialmente validado (build/testes/negociação SSH), sem aceite físico. O proprietário informou que a validação no Moto G06 ficará pendente.

## Resultado executivo

**Parcialmente validado; missão não aprovada no aparelho.** Aplicativo Kotlin/Compose implementado com cliente SSH existente, terminal ANSI, canal shell/PTTY, entrada IME, Ctrl/Esc/Tab/setas, resize, encerramento, cancelamento e reconexão manual. APK debug compilado, assinatura verificada, cinco testes JVM aprovados e lint sem problemas. O proprietário informou que a validação física ficará pendente; ADB não listou dispositivos. **Nenhum login autenticado, shell/PTTY, teclado ou programa interativo foi exercitado no Moto G06 nesta missão.**

Não houve backend C++, PostgreSQL, daemon BitTorrent, instalação de pacote global, mudança de sshd/firewall ou abertura de porta pública. As provas de protocolo acessaram somente o OpenSSH existente em localhost.

## Preparação e Git

Foram lidos README, AGENTS, planejamento, requisitos, riscos e MYCT-0. Árvore inicialmente limpa em `myct-0-inventory`, commit `3f5ca6b`; `origin/main` após fetch estava em `2cebb63` e ainda não continha o inventário. Criada `myct-1-android-ssh` sobre o commit MYCT-0 para preservar as evidências. A branch local main não foi modificada; não há merge automático.

Descoberta que corrige o limite da busca MYCT-0: Gradle **8.14.3 all já estava extraído** em `~/.gradle/wrapper/dists/gradle-8.14.3-all/.../gradle-8.14.3`. A busca anterior não alcançou essa profundidade. Cache Gradle total inicial ~1.3 GiB; SDK ~1.8 GiB, JDK/JBR 21.0.10, AGP 8.13.0 e Kotlin 2.2.20 já disponíveis. Plataformas 36/36.1 e Build Tools 35/36 presentes. Compose/JSch não estavam no cache. Sem Moto G06 detectado pelo ADB na preparação ou ao concluir as verificações.

O proprietário autorizou downloads até **200 MB** após apresentação de impacto. Gradle Wrapper foi gerado offline da distribuição existente; nenhuma nova distribuição Gradle ou SDK foi baixada. O JAR Wrapper foi conferido contra o [checksum oficial](https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256):
`7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`.

## Bibliotecas e escolhas experimentais

| Componente | Versão / licença | Avaliação e limite |
| --- | --- | --- |
| [mwiede/JSch](https://github.com/mwiede/jsch/tree/jsch-2.28.8) | 2.28.8, Revised BSD | Fork mantido, Java 8+, API shell/PTY/resize e host-key repository. Evita implementar SSH/criptografia. Usado em negociação real com OpenSSH; runtime Android ainda pendente |
| [Bouncy Castle](https://github.com/bcgit/bc-java/tree/r1rv86) | bcprov-jdk18on 1.86, MIT | Implementações existentes de Ed25519/X25519 utilizadas pelo JSch no Android; evita depender das APIs Java 11/15 do host. Custo de bytes no APK; não houve alteração de provider global do sistema |
| [Termux terminal-emulator/view](https://github.com/termux/termux-app/tree/v0.118.3) | v0.118.3; commit `5b657c6adf4304e5198951ce815fe0205dcac29c`; exceção Apache 2.0 | Renderer ANSI, buffers, IME, seleção e teclas existentes. 21 fontes/recursos upstream, 358,727 bytes, verificados contra git blobs e manifestos SHA256. Não inclui aplicativo Termux GPL, termux-shared, JNI/NDK ou shell Android |
| Compose UI / Foundation | 1.9.3, Apache 2.0 | Android nativo e formulário simples; view terminal integrada por AndroidView |
| Material3 | 1.4.0, Apache 2.0 | Campos, botões, diálogo de pareamento e avisos de licença |
| Activity Compose | 1.11.0, Apache 2.0 | Activity/Compose; parte do ecossistema AndroidX já em cache |
| Lifecycle ViewModel KTX | 2.9.4, Apache 2.0 | Sessão preservada durante recriação de Activity; trabalho IO separado da UI |
| Kotlin / plugin Compose | 2.2.20, Apache 2.0 | Compiler e plugin fixados |
| AGP / Gradle | 8.13.0 / 8.14.3 all, Apache 2.0 | CLI com Wrapper e checksum da distribuição fixados; reutilização do cache existente |
| JUnit | 4.13.2, EPL 1.0 | Apenas testes, não incluído no APK |

Escolhas são revisáveis e registradas em docs/05. SSHJ permanece alternativa se JSch falhar fisicamente, com avaliação de suas dependências; um renderer próprio foi evitado para não reimplementar ANSI/IME. Os módulos Termux foram incorporados como fonte para dispensar JitPack/builds externos e preservar revisão/licenças. `terminal/upstream/manifest.json` permite conferir os arquivos inalterados. A classe `TerminalSession` do subprocesso upstream foi substituída por adaptador My-CT independente; a emulação não foi reimplementada. O APK contém licenças completas e a tela “Licenças”.

## Comportamento e segurança implementados

- Endereço, porta (1–65535), usuário e senha efêmera; autenticação SSH `password`, sem importação de chave privada/MFA neste recorte.
- Host key **Ed25519** e fingerprint SHA256 obrigatório obtido por canal confiável. `StrictHostKeyChecking=yes`, comparação do hash da chave pública e pin fixado por endereço/porta. Não há aceite automático/scan TOFU nem fallback para algoritmos legados.
- Persistência somente de pin e metadados de conexão no sandbox Android. Chave divergente é recusada antes da autenticação; refazer pareamento exige confirmação explícita e nova conferência externa do fingerprint.
- Senha não entra em preferências, saved state, arquivos ou logs; campo é limpo ao conectar/sair do app. Buffers mutáveis são zerados, inclusive ao falhar. Strings imutáveis da UI/JVM impedem prometer apagamento forense imediato da RAM.
- Backups e transferência Android de dados foram desabilitados explicitamente; janela segura impede screenshots/previews. OSC do host remoto não pode ler/escrever clipboard por meio do adaptador; copiar/colar pela seleção local é ação do usuário.
- JSch abre canal **shell com PTY `xterm-256color`**; dimensões seguem a view/teclado/orientação via `window-change`. O Fedora executa o shell, sem executor de comandos por API.
- Stream remoto alimenta o emulador na main thread em blocos limitados; rede, leitura, escrita e close ficam em IO. Fila de entrada limitada a 64 ações e transcript de 2,000 linhas; entrada durante autenticação não é enviada ao shell. Excesso de fila encerra com aviso, evitando acúmulo ilimitado.
- Ctrl aplica-se à próxima entrada; há Ctrl-C dedicado, Esc, Tab, setas e botão de teclado. View upstream fornece ANSI, IME, buffers alternativos, rolagem e seleção.
- Tentativas antigas não sobrescrevem estado da conexão nova. Cancelar/encerrar fecha canal e sessão; falha de escrita fecha também o stream para liberar leitura bloqueante. Erros de host, conexão/autenticação e encerramento aparecem na UI; reconexão é manual, sem reexecutar comandos.
- ViewModel preserva sessão ao girar Activity. Não há foreground service: Android pode matar o processo em background/Doze. Não se promete sobrevivência de shell remota depois de fechar SSH.

## Build reproduzível e APK

Instruções completas em [android/README.md](../android/README.md). Java 21 e SDK existentes:

```sh
cd android
# JAVA_HOME e ANDROID_HOME devem apontar ao JDK/SDK existentes.
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
# Após preparar dependências, sem rede:
./gradlew clean :app:assembleDebug :app:testDebugUnitTest --offline --console=plain
```

Gradle: 2 workers, heap 1536 MiB e metaspace 512 MiB, compilador Kotlin no mesmo processo, daemon de uso único. O Wrapper usa distribuição `all` por já existir no cache; uma máquina nova precisará baixar essa distribuição e as dependências, com impacto revisado antes de downloads volumosos. Build CLI reproduzido offline; **não se afirma reprodutibilidade bit a bit** de APKs debug assinados.

- APK de build: `android/app/build/outputs/apk/debug/app-debug.apk`.
- Cópia para entrega manual: `artifacts/myct-1-debug.apk` (ignorada pelo Git).
- Pacote: `dev.myct`; versão `0.1-ssh-proof`; minSdk **26**, target/compileSdk **36**.
- APK universal: arm64-v8a, armeabi-v7a, x86, x86_64 (lib AndroidX graphics path pré-compilada; sem NDK instalado para a missão).
- Tamanho final: **13,316,126 bytes (~13.3 MB)**. SHA256: `4374fe9440b10a872cd5becf4ebc2d3ef6ea106b0b4b587eadfff3109ee0083e`.
- Assinatura debug Android v2 validada; não é release pública. Keystore debug local não é commitado. Outra máquina pode gerar identidade de assinatura diferente.

Instalação manual: transferir a cópia do APK por USB/MTP ou SFTP autenticado já existente; conferir SHA256, abrir no gerenciador de arquivos Android e permitir temporariamente instalação por esse gerenciador. Não foi criado servidor HTTP para transferir. Opcional com aparelho ADB autorizado: `adb install -r artifacts/myct-1-debug.apk` a partir da raiz. O app solicita Internet; AndroidX acrescenta uma permissão interna de receiver com proteção de assinatura, sem prompt de runtime.

## Evidências e verificações executadas

| Verificação | Resultado observado |
| --- | --- |
| Java/SDK/Gradle/cache/ADB; estado Git e fetch | Ambiente descrito acima; nenhum dispositivo listado; branch dedicada criada |
| `ssh` localhost, BatchMode e chave pública local como known_hosts temporário no repositório | Host identificado sem aceite automático; autenticação recusada. Nenhum shell aberto; não foram lidas/copiadas chaves privadas |
| Primeiro `assembleDebug` + testes com downloads autorizados | PASS, 3m14s (194.38s no time); APK gerado |
| Testes `HostKeysTest` (2) | Pin válido aceita somente host/chave corretos; chave divergente/outro host/pin ausente ou malformado recusados |
| Testes `TerminalAdapterTest` (2) | ANSI/cursor, buffer alternativo, resize 80x24→40x12, UTF-8, Ctrl-C como byte 0x03 e sessão fechada sem envio |
| `FedoraHandshakeTest` (1), `MYCT_TEST_LOCAL_SSH=1` | Negociação SSH **real** em localhost com pin incorreto rejeitado e chave pública autoritativa local aceita; sem credenciais/login/shell. Usa implementações BC explicitamente, como no app |
| Primeiro `lintDebug --offline` | FAIL: tema tinha atributo API27 em min26 e LocalContext foi convertido em Activity; quatro avisos. Hipótese registrada, atributo removido e LocalActivity adotado; backup/ícone/KTX corrigidos |
| `assembleDebug testDebugUnitTest lintDebug --offline` após correções | PASS, 58.16s; cinco testes, zero falhas/ignorados; lint “No issues found” |
| `clean assembleDebug testDebugUnitTest --offline` | PASS, 41.51s; APK reconstruído e cinco testes aprovados sem downloads |
| Verificação de JAR Wrapper e 21 arquivos upstream | Checksums conferidos contra fonte oficial/manifesto |
| `apksigner verify --verbose`; `aapt dump badging` | Assinatura v2 válida; pacote, SDKs e ABIs identificados |
| ADB ao fim | Zero dispositivos; proprietário optou por deixar teste físico pendente |
| Última compilação/testes/lint após revisão da falha de escrita SSH | PASS offline, 52s; cinco testes sem falhas/ignorados; lint “No issues found”; APK final assinado e verificado |

Os testes de emulação são JVM, sem renderer/IME Android em execução; não são mocks de uma jornada aprovada. O teste SSH real valida somente transporte/identidade antes da autenticação. `pwd`, `uname -a`, `top`/htop, PTY autenticada, Ctrl/setas no IME, orientação, perda de rede e reconexão no aparelho **não foram executados**.

## Consumo observado

- Amostra anterior ao build: 7.6 GiB RAM total, ~6.6 GiB disponíveis; 133 GiB livres no volume principal; zram sem uso.
- Primeiro build: `time -v` máximo RSS **2,314,972 KiB (~2.21 GiB)**, CPU média **279%**, 194.38s; zero swaps reportados.
- Sampler de processos Java (168 amostras, começou depois do bootstrap): pico agregado **2,478,432 KiB (~2.36 GiB)**; mínimo MemAvailable **4,599,924 KiB (~4.39 GiB)**. Valores amostrados, não limites absolutos; não houve uso de zram nas amostras.
- Build+lint offline após correções: máximo RSS **1,512,212 KiB (~1.44 GiB)**; 58.16s, CPU média 388%.
- Build limpo offline: máximo RSS **1,950,744 KiB (~1.86 GiB)**; 41.51s, CPU média 464%. Dois workers não limitam todos os threads JVM/GC/D8 nem o uso de cores.
- Artefatos de dependências no cache: de **328,574,940** para **376,267,812 bytes**, aumento **47,692,872 bytes (~47.7 MB)**; fontes de terminal adicionais ~0.36 MB. Tráfego bruto de metadados/retries não foi medido por contador dedicado; crescimento de artefatos não deve ser confundido com medição exata de rede. Nenhum download de distribuição Gradle/SDK/emulador foi necessário.
- Cache Gradle total cresceu de ~1.3 para ~1.6 GiB (inclui transformações/dex); árvore Android com builds ~58 MiB e cópia de APK ~13 MB. Compilação usou espaço pré-existente disponível, sem limpeza de outros projetos.
- RAM/CPU/bateria do APK no Moto G06 e consumo do shell SSH real ainda não medidos.

## Riscos, limites e retomada

1. Nenhuma prova física: renderer, IME/teclas, insets/resize e UX de erro podem precisar de correção no Moto G06.
2. Autenticação limitada a password e host key Ed25519. Host com chave diferente, somente publickey/MFA ou outra política pode bloquear conexão; não flexibilizar sshd unilateralmente.
3. Android background/Doze pode interromper a sessão; reconexão é manual. Testar rotação e alternância de app antes de decidir se foreground service é necessário.
4. API26+ e universal APK indicam compatibilidade de build, sem prova do aparelho. Não há distribuição release/assinatura de produção/atualização automática.
5. Cache e dependências fixadas reduzem downloads repetidos, mas manutenção de segurança/licenças e builds em outra máquina continuam exigindo revisão; `all` custa mais disco/download numa máquina sem cache.

**Retomada mínima:** instalar o APK no Moto G06 por método manual descrito, obter fingerprint por canal confiável e executar o checklist de android/README.md. Registrar resultados físicos no diário antes de aprovar SSH-01/02/03 e AND-01. Confirmar `tty` e `stty size`, `pwd`, `uname -a`, `top`/vi, Ctrl-C/Tab/Esc/setas, teclado/orientação, chave incorreta e desconexão/reconexão. Não ampliar para SFTP ou Core antes de corrigir eventuais falhas centrais do terminal.

O planejamento inaugural não foi reescrito. Branch publicada: `myct-1-android-ssh`. Commit da implementação/testes: `19289c90e7f04c3b63aa1b4d59a7331a15fa7c22`. [PR #6](https://github.com/Samuel-Francisco-AS/My-CT/pull/6) aberto para main em **draft**, com validação física pendente e sem merge automático. O registro desta publicação é um commit documental posterior, sem alteração do APK.
