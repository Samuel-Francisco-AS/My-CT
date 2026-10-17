# MYCT-R3 — Teclas auxiliares e colagem direta

**Data:** 17/10/2026. **Estado final:** **PASS físico do proprietário**, candidata 0.6.4/code 21 aceita; fechamento e merge do PR #25 autorizados. **Base:** main após MYCT-R2. **Princípio:** FLUIDEZ.

## Layout obrigatório — referência Termux no Moto G06

Duas fileiras e sete colunas, na ordem exata da captura Termux:

    ESC      /      −     HOME     ↑      END     PGUP
    ⇥       CTRL   ALT     ←      ↓       →      PGDN

Usar ⇥ ou TAB acessível na segunda linha, primeira coluna. Sem faixa com scroll horizontal. Mostrar logo acima do teclado Android quando aberto e na borda inferior útil quando fechado, respeitando insets, rotação e navegação Android. Preserve a estética Industrial Futurista preto/dourado aceita na R2. Botões compactos, confortáveis ao toque, sem criar cabeçalho global.

**Funcionamento real, não botões decorativos:** Escape, Tab, setas, Home, End, PgUp e PgDn devem gerar códigos corretos compatíveis com Termux TerminalSession/KeyHandler, nano, tmux e shell. "/" e "−" digitam os caracteres literais. CTRL e ALT devem operar como modificadores one-shot claros; hoje CTRL já existe, mas ALT não funciona no TerminalCallbacks (readAltKey retorna false). Não enviar Escape seguido de Alt de maneira improvisada se existe o caminho correto no emulador.

A barra atual possui Ctrl-C, Colar… e Teclado. A nova grade não inclui esses três botões; preservar os equivalentes, como CTRL+C via modificador e teclado, colar pela ação de clipboard/IME e exibir teclado ao tocar na área do terminal. Se alguma ação não tiver equivalente acessível, oferecer menu contextual discreto fora da grade sem adicionar terceira linha, cabeçalho ou mudar a ordem.

## Feedback tátil e visual

Ao tocar numa tecla, fazer uma vibração curta e discreta equivalente à resposta do teclado Android, respeitando configurações de haptics do usuário. Preferir performHapticFeedback/HapticFeedbackConstants.KEYBOARD_TAP ou API Android correspondente, sem dependências ou vibração insistente. O feedback visual da tecla deve ser nítido, porém terminar mais rápido que o ripple Material atual; ação enviada imediatamente no toque sem debounce, sem aguardar animação e sem alterar os botões globais de outras telas.

## Colagem sem confirmação customizada

A captura do proprietário mostra o popup próprio do My-CT "Enviar texto ao terminal?". Em MainActivity.kt, ele vem de PendingPaste/pendingPaste, requestSystemPaste e AlertDialog. **Remover essa confirmação e enviar a colagem diretamente à sessão atual via TerminalEmulator.paste**, preservando bracketed paste quando aplicável. Manter ClipboardPolicy, limite de 64 KiB, validação de sessão ativa e tratamento de erros, sem reter ou registrar texto sensível e sem executar colagem na sessão antiga após reconectar.

Remover confirmação equivalente criada pelo próprio My-CT em quaisquer outros campos, caso haja. Não tentar eliminar avisos/overlays do Android ou Gboard, nem remover confirmações para atos distintos e sensíveis (host key, excluir dados, proteção do Cofre, terminar SSH).

**Risco conhecido e aceito:** texto multilinha colado no shell pode executar comandos imediatamente por conter quebras de linha. Não inventar nova confirmação automática.

## Limites e entrega

- Ler AGENTS.md, MainActivity.kt, TerminalModel.kt, TerminalCallbacks.kt, MyCtControls.kt e fonte upstream do TerminalSession/KeyHandler.
- Não mexer em JSch, arquitetura SSH, serviços Fedora, SFTP, Transmission, Cofre, tema, drawer ou demais telas. Lag R-01 continua dívida suspensa.
- Testar ordem e semântica de teclas/modificadores e ação imediata, colagem normal e multiline, clipboard inválido e sessão trocada, acessibilidade e insets.
- Build offline Android, testes JVM, lint e integridade upstream; criar APK atualizável após 0.6.3/code 20, sem mudar package e assinatura.
- Commit/push na branch myct-r3-extra-keys-paste; abrir PR draft contra main, sem merge, publicar candidata e checksum.
- Aceite físico no Moto: confirmar teclado em duas linhas iguais ao Termux, tecla Alt real, Ctrl+C, Home/End/PgUp/PgDn, haptics sutis, feedback rápido, colar sem confirmação My-CT e estabilidade do espaço da TTY.

Fechar a R3 somente após retorno físico do proprietário.


## Implementação candidata — 17/10/2026

Versão **0.6.4-r3-keys-paste / code 21**, pacote `dev.myct`. Alterações limitadas ao Android, sem instalação no Fedora. O layout e os critérios acima continuam sendo o contrato de aceite físico.

- `TerminalExtraKeys.kt`: as 14 posições em duas fileiras fixas de sete colunas. Escape, Tab, setas, Home, End, PgUp/PgDn usam o `KeyHandler` existente, incluindo modo de cursor application. `/` e `−` passam os code points ASCII 47/45 à `TerminalView.inputCodePoint()`.
- `TerminalCallbacks.kt` e `TerminalModel.kt`: CTRL e ALT one-shot, combináveis e com cancelamento por segundo toque. A view pode ler o mesmo modificador duas vezes num KeyEvent; agora a leitura não o consome antecipadamente. Consumo ao aceitar code point ou tecla mapeada; limpeza ao sair do terminal, parar a tela, desconectar ou encerrar a sessão. Teclas de sistema mantêm seu tratamento Android. Ctrl+C segue a transformação upstream.
- `MyCtControls.kt`: estilo exclusivo da grade, usando a paleta/geometria R2, cor pressionada imediata e sem ripple/tween. Um `performHapticFeedback(KEYBOARD_TAP)` por acionamento, após encaminhar a tecla, sem flags que ignorem configurações do Android, permissões ou dependências novas. Altura mínima de 48 dp por tecla; largura distribuída igualmente, rótulos ajustáveis ao espaço e descrições acessíveis. Ordem LTR fixa também em locale RTL. O terminal mantém a área restante por peso e a união de insets já existente.
- `MainActivity.kt` e `TerminalPaste.kt`: removidos `PendingPaste`, estado do texto, preview e diálogo “Enviar texto ao terminal?”. Colagem síncrona lê somente texto simples, valida até 65.536 bytes UTF-8 e verifica identidade, estado conectado e sessão em execução antes e depois da leitura. Encaminha diretamente a `TerminalEmulator.paste()`, preservando bracketed paste, sanitização upstream e ordem de entrada. Cada callback de clipboard continua vinculado à sessão que o originou. Nenhum conteúdo de clipboard é registrado ou retido em estado Compose.
- A inspeção dos campos comuns não encontrou outra confirmação própria de colagem. Confirmações de desconexão, pareamento e segurança foram preservadas. Tema, drawer, FLAG_SECURE/Cofre, SFTP, downloads, JSch, filas/workers SSH e backend permanecem fora desta alteração; dívida de lag não investigada.

### Ações acessíveis preservadas

**Ctrl+C:** toque CTRL e depois C no teclado Android. **Teclado:** toque na área do terminal. **Colar:** mantenha pressionado o texto do terminal e use a ação de colagem da seleção; o clipboard do teclado Android também permanece disponível. A opção **Mais** da seleção (ou clique secundário do mouse) abre um menu contextual com **Colar**, **Teclado** e **Ctrl+C**. As três ações também têm rótulos nas ações de acessibilidade do terminal. Não existe terceira fileira ou cabeçalho adicional.

CTRL/ALT ficam dourados quando armados; uma tecla efetivamente enviada os desarma. Não há modo de trava por toque longo. A colagem não aplica modificadores ao conteúdo e desarma os modificadores após envio.

### Validação e retomada

Ferramentas inspecionadas: JBR/OpenJDK 21.0.10, SDK/Build Tools 36, Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20, Compose UI 1.9.3/Material3 1.4.0, GitHub CLI 2.97.0. Nenhuma dependência instalada.

```sh
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
for patch in android/terminal/upstream/*.patch; do git apply --reverse --check "$patch" || exit; done
git diff --check
```

Os novos testes em `TerminalExtraKeysTest.kt` e `TerminalPasteTest.kt` cobrem as 14 posições, sequências normais/application, CTRL/ALT combinados e one-shot, Ctrl+C, literais ASCII, ordem IME/teclas/colagem, colagem simples/multilinha/bracketed imediata, clipboard inválido/limite/exceção, sessão ausente/desconectada/substituída/encerrada e mudança durante a leitura. Os testes de code points executam o método real da view upstream numa instância sem construtor Android, com cliente de teste; isso **não simula renderização, toque, integração Gboard, acessibilidade ou vibração reais**.

Fontes lidas: adaptador local `TerminalSession`, `TerminalView`, `KeyHandler`, `TerminalEmulator.paste()` e [TerminalSession upstream fixada no commit usado pelo projeto](https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java). Nenhum arquivo vendorizado foi alterado. Integridade offline: **21 arquivos, três arquivos com patches documentados e quatro patches reversos verificados**.

### Resultados automatizados e APK

**Rodada final offline: BUILD SUCCESSFUL**, build/JVM/lint. **150 testes: 149 aprovados, um handshake local opt-in ignorado, zero falhas/erros**; os 14 novos testes direcionados também passaram isoladamente. Lint com **zero erros e dois avisos preexistentes**, sobre versões de Gradle e org.json; nenhuma supressão acrescentada. A fixture JVM inicialmente não compilou por referência direta a uma API interna do JDK; o acesso foi limitado ao runtime de teste por reflexão e as rodadas direcionada e completa passaram após a correção. Isso não adiciona dependência/API privada ao app.

APK local: `artifacts/myct-r3-0.6.4-debug.apk`, **14.074.882 bytes (13,42 MiB)**, +440.511 bytes sobre 0.6.3. `aapt2` confirmou `dev.myct`, **0.6.4-r3-keys-paste / code 21**. `apksigner verify --verbose --print-certs` validou o APK e confirmou o mesmo certificado SHA-256 da candidata 0.6.3 cujo hash consta no fechamento R2. Atualizar sem desinstalar/apagar dados. Checksum em `artifacts/myct-r3-0.6.4-debug.apk.sha256`:

```text
32752516b8c715370922b219b39a14ccdb2abb7422e31886c0db93a094fb1f3b
```

Build final medido por `/usr/bin/time -v`: **1 min 54,06 s**, CPU de usuário 508,38 s e sistema 7,08 s, pico RSS **1.771.804 KiB (~1,69 GiB)**, zero swap. Os limites Gradle existentes foram preservados. Logs/relatórios locais ignorados pelo Git: `artifacts/myct-r3-build-final.log`, `myct-r3-build-time.txt`, `myct-r3-targeted-tests.log`, `myct-r3-validation-notes.txt`, `myct-r3-validation.json`, `myct-r3-jvm-results/` e `myct-r3-lint-results-debug.{html,xml}`.

### Publicação e continuidade

Código e validação enviados em **`3b21629f699bb41463d4cbb864a18564a3bd1e8a`**, branch `myct-r3-extra-keys-paste`. **[PR #25 draft contra main](https://github.com/Samuel-Francisco-AS/My-CT/pull/25), aberto e sem merge.**

**[Pré-release MYCT-R3 0.6.4/code 21](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r3-v0.6.4-rc1)** publicada com [APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r3-v0.6.4-rc1/myct-r3-0.6.4-debug.apk) e [SHA-256](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r3-v0.6.4-rc1/myct-r3-0.6.4-debug.apk.sha256). O tamanho e o digest SHA-256 do asset informado pelo GitHub conferem com o APK local acima. Tag aponta para o commit da implementação; esta atualização de documentação não altera o APK.

Retomada: permanecer nesta branch, instalar a candidata manualmente sobre 0.6.3, executar o gate abaixo no Moto e registrar somente os resultados observados. Não declarar aceite nem integrar o PR antes do retorno do proprietário.

### Gate físico — roteiro original (histórico, anterior ao aceite)

ADB não apresentou dispositivo conectado nesta execução. Não houve instalação no Moto, conexão SSH autenticada, medição de RAM/CPU Android ou aceite físico. O custo de build e o tamanho do APK não são medidas de consumo do app.

1. Atualizar sobre 0.6.3 sem desinstalar nem apagar dados; conectar normalmente ao Fedora.
2. Conferir as duas fileiras na ordem exata, IME aberta/fechada, retrato/paisagem, fonte ampliada, insets e área do terminal; conferir descrições/ações com TalkBack.
3. Exercitar ESC, Tab, `/`, `-`, setas, Home/End e PgUp/PgDn em shell, nano e tmux. Conferir CTRL+C, ALT+B/F, combinações com setas e desarme após um uso; segundo toque cancela modificador.
4. Avaliar vibração breve, ausência de duplicação e respeito à configuração desativada do Android; avaliar resposta visual rápida ao pressionar/soltar.
5. Colar texto simples e multilinha pelas ações de seleção/menu e clipboard do teclado, sem popup My-CT; testar bracketed paste num programa compatível. Campos comuns mantêm o fluxo Android/Gboard.
6. Desconectar/reconectar e confirmar que ações antigas não colam em outra sessão. Confirmar estabilidade do teclado e ausência de regressão nos fluxos preservados.

Este era o roteiro anterior ao retorno físico; o proprietário concedeu passe posteriormente, conforme registro abaixo.


## Aceite físico final e decisão de integração — 17/10/2026

O proprietário apresentou captura do **Moto G06** com a barra 2×7 instalada imediatamente acima do teclado Android e confirmou em texto: **“Sim, ficou bom, o toque e a disposição das teclas, e testei suas funcionalidades e funcionaram bem. Acredito que já possamos aplicar o passe nessa etapa. E se você não tiver nenhuma contestação, pode realizar o procedimento de fechamento padrão no remoto.”**

**PASS/aprovado pelo proprietário:** disposição das duas fileiras, toque/feedback e funcionamento das teclas exercitadas no aparelho. A captura mostra `^C` devolvendo o prompt, coerente com Ctrl+C funcionando. Código e testes automatizados também comprovam a existência do caminho de **colagem direta sem diálogo próprio**.

**Limites do relato:** o proprietário não descreveu um ensaio individual separado de colagem simples/multilinha/bracketed paste, ALT com aplicações específicas, TalkBack, rotação e haptics com vibração desligada. Não registrar esses cenários como fisicamente testados; ficam como validação oportunista, **não bloqueiam** o passe explícito. A confirmação My-CT “Enviar texto ao terminal?” foi removida no código, sem alterar diálogos de segurança distintos.

**Decisão operacional:** encerrar a MYCT-R3 e integrar **PR #25 à `main` com merge commit**, preservando histórico, mediante autorização expressa do proprietário; não alterar serviços Fedora ou funcionalidades R2. R-01 de lag SSH continua dívida suspensa e não é parte deste passe. O relatório final de fechamento na `main` registrará SHA real do merge e situação Git. Não apagar branch remota antes de orientar sincronização local.

Os resultados anteriores de implementação e testes permanecem históricos; o aceite físico posterior prevalece para o status da fase.
