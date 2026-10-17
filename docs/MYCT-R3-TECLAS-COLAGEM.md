# MYCT-R3 — Teclas auxiliares e colagem direta

**Data:** 17/10/2026. **Status:** escopo acordado com proprietário, não implementado. **Base:** main após fechamento MYCT-R2. **Princípio:** FLUIDEZ.

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
