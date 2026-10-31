> **Proveniência vendorizada.** Este arquivo registra o upstream Termux, os patches e a exceção de licença aplicáveis; consulte [licenciamento geral](../../../docs/LICENSING.md), [arquitetura atual](../../../docs/ARCHITECTURE.md) e execute `python3 android/terminal/upstream/verify.py` da raiz para conferir os hashes. As descrições cronológicas das fases abaixo são históricas.

# Fonte do terminal e licença

Arquivos Java/res de `termux/termux-app` em `v0.118.3`, commit
`5b657c6adf4304e5198951ce815fe0205dcac29c`, obtidos via HTTPS e conferidos
contra o git blob SHA-1 de cada arquivo da árvore do commit. `manifest.json`
registra caminho original, SHA256 e bytes para revisão/reprodução.

Somente os módulos `terminal-emulator` e `terminal-view`, cobertos pela exceção
Apache 2.0 do `LICENSE.termux.md`, são usados. Não há `termux-app`,
`termux-shared`, JNI, NDK ou processo shell Android. MYCT-1R altera somente dois arquivos da view para separar os gestos manuais
de clipboard dos callbacks OSC; o renderer/emulador ANSI permanece inalterado.
`myct-1r-clipboard.patch` registra o diff original;
MYCT-R1 acrescenta `myct-r1-ime.patch` sobre essa base para delimitar um único
commit/finish da IME: agrupa somente texto já disponível, sem timer, com
controles enviados imediatamente. Transformações de teclas, composição e
preferência IME do upstream são preservadas; `local-patches.json` preserva o SHA
original e o SHA atual. Validar offline: `python terminal/upstream/verify.py`
a partir de android/. `src/main/java/com/termux/terminal/TerminalSession.java` é um
adaptador My-CT independente que oferece à view a API mínima de sessão e
encaminha bytes/resize ao SSH. Ele substitui o gerenciamento de subprocesso
local do upstream, que não foi importado. `ByteQueue` também não foi importado.

MYCT-R1B acrescenta `myct-r1b-timing.patch` sobre a base MYCT-R1, na view e
na interface cliente, com hooks numéricos de IME/KeyEvent/atualização/draw.
São inertes por padrão, ativados por amostra de 60 s no app; preservam os
corpos originais de processamento. Verificador agora registra três arquivos
upstream com patches explícitos.

MYCT-R1C acrescenta `myct-r1c-layout.patch`: a view delega dimensões de célula
à sessão mesmo sem mudança de grade; o adaptador deduplica. Workers e filas
SSH ficam no app, sem importar subprocessos/JNI. Emulador/renderer preservados.

Preservar as licenças e avisos ao redistribuir. O APK inclui os avisos de
terceiros em assets e permite lê-los na tela de conexão. Para atualizar o
terminal, comparar segurança/API/licenças, fixar novo commit, regenerar o
manifesto e repetir testes ANSI, IME, teclado e resize no aparelho físico.


MYCT-L1B acrescenta no **módulo app** uma ponte JNI própria para PTY Android,
sem importar o código de subprocesso upstream. O adaptador TerminalSession agora
serve tanto ao transporte SSH quanto ao PTY local, com instâncias independentes.
As descrições sem JNI acima registram as fases anteriores; a lista de fontes
vendorizadas, seus hashes e os quatro patches continuam iguais.
