# Fonte do terminal e licença

Arquivos Java/res de `termux/termux-app` em `v0.118.3`, commit
`5b657c6adf4304e5198951ce815fe0205dcac29c`, obtidos via HTTPS e conferidos
contra o git blob SHA-1 de cada arquivo da árvore do commit. `manifest.json`
registra caminho original, SHA256 e bytes para revisão/reprodução.

Somente os módulos `terminal-emulator` e `terminal-view`, cobertos pela exceção
Apache 2.0 do `LICENSE.termux.md`, são usados. Não há `termux-app`,
`termux-shared`, JNI, NDK ou processo shell Android. MYCT-1R altera somente dois arquivos da view para separar os gestos manuais
de clipboard dos callbacks OSC; o renderer/emulador ANSI permanece inalterado.
`myct-1r-clipboard.patch` registra o diff; `local-patches.json` preserva o SHA
original e o SHA atual. Validar offline: `python terminal/upstream/verify.py`
a partir de android/. `src/main/java/com/termux/terminal/TerminalSession.java` é um
adaptador My-CT independente que oferece à view a API mínima de sessão e
encaminha bytes/resize ao SSH. Ele substitui o gerenciamento de subprocesso
local do upstream, que não foi importado. `ByteQueue` também não foi importado.

Preservar as licenças e avisos ao redistribuir. O APK inclui os avisos de
terceiros em assets e permite lê-los na tela de conexão. Para atualizar o
terminal, comparar segurança/API/licenças, fixar novo commit, regenerar o
manifesto e repetir testes ANSI, IME, teclado e resize no aparelho físico.
