# MYCT-R2 — identidade visual, experiência e planejamento

**Decisão do proprietário:** 16/10/2026. **Status:** R2A implementada como candidata; aceite visual/físico no Moto G06 pendente. **Princípio obrigatório: FLUIDEZ.**

**Referências:** [trilha de refinamento](09-TRILHA-REFINAMENTO-ANDROID.md), [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9), [decisões](05-DECISOES-E-QUESTOES.md), [histórico MYCT-R1](MYCT-R1-TERMINAL-FILES.md).

## 1. Propósito e limites

O My-CT é um utilitário Android nativo para administrar um servidor Fedora headless. A experiência visual deve ser coerente, legível, discreta, tecnológica e **fluida**, em vez de parecer um conjunto de formulários e relatórios. A referência estética é **Deus Ex: Human Revolution**: pretos profundos, sombras/grafite aquecido e destaques âmbar/dourados. Usar a *linguagem* visual, sem copiar assets, marcas, elementos protegidos do jogo ou produzir uma imitação literal.

**Fluidez prevalece sobre espetáculo.** Sem piscadas, saltos de item, recenter ao toque, mudanças súbitas de layout ou movimentos que retardem ações. Priorizar feedback de toque imediato, continuidade de navegação, transições curtas, leitura confortável e estabilidade de estado.

O proprietário **rejeitou explicitamente qualquer cabeçalho global permanente**, mesmo uma barra mínima com hambúrguer/título/status. A área principal fica livre para cada função. Informações globais e navegação ficam **somente no menu lateral oculto**, que surge por gesto da esquerda para a direita. Exceções são **elementos funcionais locais à tela** (p. ex. caminho atual no alto da aba Arquivos; barra de teclas do terminal; seletor HTTP/Torrents dentro de Downloads), nunca um cabeçalho global disfarçado.

**R-01, lag de digitação, é dívida técnica suspensa** após retorno físico negativo da 0.5.9: melhorar abrir/fechar teclado não corrigiu engasgos de texto. Não reabrir medições, motor SSH, JSch ou tentativa de correção de lag nesta trilha. A navegação SFTP sem salto de restauração de rolagem é melhoria aceita e deve ser preservada.

## 2. Identidade visual — tokens iniciais

Valores iniciais de design para aplicação uniforme; ajustes finos de contraste e legibilidade no Moto G06 são permitidos sem mudar a identidade preto/dourado.

| Papel | Cor | Aplicação |
| --- | --- | --- |
| Fundo principal | `#070908` | Área de conteúdo, tela livre, sem cinza lavado |
| Superfície | `#131613` | Painéis, grupos, drawer |
| Superfície elevada | `#1C1E19` | Cards e diálogos, profundidade discreta |
| Divisória/contorno | `#383327` | Bordas sutis, separadores |
| Dourado principal | `#F0B83E` | Foco, seleção, CTA, informação de maior destaque |
| Dourado secundário | `#D6A44C` | Ícones, progresso secundário, sinalização |
| Bronze | `#A97831` | Bordas/acentos de baixa prioridade |
| Texto primário | `#F1EDE4` | Texto legível e contraste no escuro |
| Texto secundário | `#B9B2A5` | Metadados e legendas |
| Erro / atenção | `#E38F83` / `#E4B35A` | Estados sem depender só da cor |
| Sucesso | `#79C2A0` | Status conectados e conclusões, usado com texto |

**Hierarquia, não inundação de dourado:** em geral superfícies pretas/grafite, texto claro e somente ações, foco e progressos com acentos dourados. Botão dourado preenchido pede conteúdo escuro e legível; títulos de conteúdo não precisam ser dourados. Evitar gradientes grandes, glow constante, textura ruidosa e efeitos pesados. Sombras/elevations leves são suficientes; tema Material3 centralizado em tokens reutilizáveis, sem repetição de hex por arquivo. Não ativar Material You/dynamic color se isso descaracterizar a paleta.

**Texto e acessibilidade:** escala do Android respeitada; estados com ícone/texto além da cor, alvos tocáveis de aproximadamente 48dp, truncamento controlado de dados longos, conteúdo alcançável com teclado/serviços de acessibilidade, suporte a redução de movimento quando possível. O texto ANSI e as cores internas do emulador PTY não devem ser arbitrariamente retematizados.

## 3. Shell da aplicação e navegação

### Drawer lateral oculto — comportamento decidido

- **Padrão fechado, sem cabeçalho global, sem barra de abas, sem menu fixo ou botão hambúrguer permanente.**
- Abrir por gesto horizontal da esquerda para a direita; fechar com gesto inverso, toque no scrim ou Back, sem destruir sessão, conteúdo ou rolagem. Movimento curto/suave, com resposta no próprio gesto e sem bloqueio da UI.
- O drawer deve apresentar, em hierarquia: identidade My-CT; estado SSH real (conectado/conectando/desconectado/erro), servidor e usuário quando já disponíveis, sem expor credenciais; navegação Terminal, Arquivos, Saúde, Cofre, Downloads; opções globais (incluindo captura protegida/livre quando relevante); e ação Encerrar/Cancelar conexão, com distinção/confirmar se necessário.
- Item selecionado visível no **drawer**, não em cabeçalho permanente. Navegar toca item e fecha drawer suavemente. Estado da aba e seus conteúdos devem continuar corretos; não recriar conexão/modelos, nem repetir solicitações remotas desnecessariamente.
- Funcionar também desconectado: Terminal oferece a tela de conexão/pareamento atual; ações indisponíveis mostram estado claro sem esconder opções necessárias. Não remover validações de fingerprint nem contexto de segurança indispensável.
- **Conflito de gestos:** Android tem Back pela borda; TermuxView, listas e gestos de seleção também interagem com toques. Usar primeiro APIs maduras de Compose/Material3 (`ModalNavigationDrawer`, estado e gestos), sem recognizer invasivo que roube interação do terminal. Inspecionar gesto em dispositivo com navegação por gestos. O drawer precisa continuar acessível com serviços de acessibilidade, sem inserir cabeçalho global por conveniência.
- Respeitar insets status/navigation/IME. Não acrescentar espaços vazios no alto. O drawer não deve fazer resize remoto repetitivo, reiniciar IME nem disparar reconexões só por abrir/fechar.
- **Segurança:** manter regra de `FLAG_SECURE` na aba Cofre mesmo com captura geral liberada. Exibir apenas indicadores adequados de segurança, sem dados sensíveis em conteúdo de drawer ou logs.

### Movimento e feedback

- Priorizar transições breves e consistentes (orientação inicial ~180–260 ms para drawer/transições, ajustável na prova física), sem esperar animação para registrar o toque.
- A troca de aba não deve piscar nem saltar para uma posição aleatória. Preservar estado/scroll quando apropriado e não introduzir trabalho síncrono pesado na main.
- Sem microanimações permanentes, blur caro, sombras enormes ou efeitos que prejudiquem o Moto G06. Respeitar preferências de acessibilidade/reduced motion se suportadas.
- O terminal continuará terminal: preservar a grade PTY/ANSI, viewport e barra de atalhos no rodapé. Não reformatar saída de shell por critérios de UI responsiva.

## 4. Escopo fechado por fase

### MYCT-R2A — tema global e navegação (CANDIDATA PARA TESTE FÍSICO)

1. Criar camada reutilizável de cores/tipografia/componentes essenciais escuros+dourados sem dependências pesadas.
2. Remover o cabeçalho global atual: `My-CT · SSH ativo`, `Captura livre`, `Opções`, `Encerrar` e a fileira horizontal de cinco abas. Recolocar essas **funções** no drawer, sem perda de acessibilidade ou lógica.
3. Construir drawer por gesto acessível em todas as telas e nos estados conectado/desconectado, com indicador de seleção, fechamento suave e conflito de gestos administrado.
4. Aplicar esquema de cores aos componentes Material3 comuns, status/navigation bars, foco, buttons, dialogs e superfícies, sem trocar o renderer ANSI.
5. Preservar conteúdo e funcionalidades das cinco telas; na R2A alterar apenas o necessário para hospedar o shell e evitar regressões. A remodelagem detalhada pertence à R2B.
6. Validação enxuta: build APK offline, testes JVM/lint/upstream e teste físico breve de navegação, teclado, SSH, SFTP, Cofre, Downloads, abertura/fechamento do drawer e ausência de cabeçalho. O aceite de fluidez visual depende do proprietário no Moto.

**Fora de R2A:** refazer listas e cartões internos, mudar motor HTTP/Torrent, acrescentar métricas RPC, corrigir salto dos itens na lista SFTP, reorganizar relatórios AV, adicionar teclas Home/End/PgUp/PgDn, refatorar SSH/JSch ou diagnosticar lag. Não reabrir serviços Fedora/PostgreSQL.

### MYCT-R2B — telas internas e ergonomia (planejado, não implementar agora)

- **Arquivos:** eliminar título redundante “Arquivos · SFTP”; canto alto com caminho real atual; seta discreta para diretório pai; retirar aviso fixo longo de notificações (disponibilizar orientação por ícone `i` contextual quando útil); linhas com ícones por tipo, nome/data/tamanho, ações claras, nomes longos legíveis. **Corrigir o deslocamento/recentralização do item clicado antes de navegar**, preservando o scroll recuperado em R-02.
- **Saúde:** remover texto introdutório, apresentar CPU/RAM/disco/uptime em cards organizados, estados verdadeiros de Core/última consulta e botão Atualizar bem posicionado; não inventar medições.
- **Cofre:** reduzir textos fixos, priorizar status/campos/ações/avisos essenciais e `FLAG_SECURE` incondicional na seção.
- **Downloads HTTP(S):** introduções extensas saem; formulário de URL/nome e histórico em cards com status/progresso/ação legíveis. Manter falhas e estados importantes acessíveis.
- **Torrents:** formulário magnet, cards de progresso, taxas download/upload, peers, estado e ações; expor baixado/enviado acumulados e ratio **apenas após conferir campos realmente disponíveis** no Transmission RPC/adaptador, sem inventar valores. Respeitar unidades e diferença entre “total da sessão”, “histórico” e “por torrent”.
- **Opções e diálogos:** mesma identidade em todas as seções não citadas acima. Remover introduções repetitivas e colocar explicações não críticas sob botão `i` reduzido. **Preservar avisos de risco/consentimento obrigatórios no ponto da ação**; ícone de ajuda não deve ocultar informações essenciais a uma decisão destrutiva.
- **R-09:** diálogo de inspeção/antivírus deve exibir resumo compacto, caminhos extensos truncáveis/copiáveis, detalhes expansíveis, scroll e botões alcançáveis.
- **Barra de teclas:** Home/End/PgUp/PgDn (R-07) numa intervenção funcional própria, não alterar em R2A.

R2B deve contemplar **todas** as áreas/abas do app, inclusive telas não fotografadas; a consistência global é requisito, não uma lista limitada às capturas.

## 5. Critérios de aceite visual e funcionais

A implementação é candidata até teste físico. Na MYCT-R2A, conferir:

1. Início conectado e desconectado; drawer surge com swipe da esquerda e fecha com swipe contrário/Back/toque externo.
2. **Zero cabeçalhos globais e zero barra fixa de abas** em Terminal, Arquivos, Saúde, Cofre e Downloads; espaço vertical liberado sem blocos vazios.
3. Navegar em todas as abas, abrir Opções, conectar/encerrar com fluxos existentes, voltar à seção selecionada; menus e diálogos sem perda de estado.
4. Terminal editável e atalhos funcionais; teclado abre/fecha e não é ocultado indevidamente; abertura do drawer não prejudica foco, seleção/clipboard, PTY/tmux.
5. Arquivos/SFTP conserva rolagem e operações; Saúde, Cofre e Downloads funcionam; FLAG_SECURE ainda protege Cofre.
6. Cores legíveis com seleção/foco/erros claramente identificáveis; transições fluidas no Moto G06, sem piscadas, saltos ou efeitos pesados.
7. Nenhuma mudança em Fedora, PostgreSQL ou serviço remoto; **nenhuma alegação de correção do lag de digitação**.

## 6. Git e ordem de integração

- Em 16/10/2026, **PR #22** `myct-r1-terminal-files → main` continua **draft e sem merge**. Ele contém melhorias R-02 e implementação R1C, com lag R-01 explicitamente pendente/suspenso.
- Para separar o redesign, o planejamento MYCT-R2 é registrado na branch `myct-r2a-visual-shell` **criada a partir do HEAD do PR #22**. R2A deve permanecer nesta branch e em PR **draft com base `myct-r1-terminal-files`** enquanto #22 não estiver integrado. Essa dependência é consciente; evita duplicar o histórico de R1 como se fosse código novo de R2A.
- **Não fazer merge automático** nem na branch R1 nem em `main`. Depois de aceites explícitos, é possível integrar #22 primeiro e retargetar/rebasear R2A à `main`, sem force-push inadvertido.
- Não misturar R2A e R2B na mesma rodada. Evitar cerimônia, refatorações não pedidas e uma nova sessão de diagnóstico de performance.


## 7. Entrega candidata R2A — 16/10/2026

- `AppShell.kt`: `ModalNavigationDrawer`/`DrawerState`, fechado inicialmente, cinco destinos com ícones próprios/seleção dourada, identidade e estado SSH real, servidor/usuário, Opções, preferência de captura com consentimento existente e Encerrar/Cancelar SSH. **Nenhum cabeçalho global, barra de abas ou hambúrguer permanente.**
- `colors.xml` é a fonte única da paleta; `MyCtTheme.kt` aplica cores, tipografia e formas Material3 a todas as telas/diálogos existentes. Janela nativa usa os mesmos recursos; barras Android com ícones claros. Sem novas dependências, imagens ou alterações ANSI.
- Drawer sobreposto: não altera a área medida do terminal ao abrir/fechar, não chama foco/IME/rede e registra seleção antes de aguardar fechamento. Movimento padrão do Material3 existente, sem recognizer próprio ou duração forçada por API obsoleta; suavidade/duração percebida ainda precisam de teste físico.
- `AppSection.kt` conserva estado salvível Compose por seção; HTTP/Torrents mantém a subseção selecionada. **Arquivos continua exclusivamente com as âncoras por nome da R1**, sem substituição pelo índice bruto do estado genérico. Senhas continuam efêmeras em `remember`, sem salvamento.
- Proteção de captura aplicada por efeito com chave, no ciclo de composição, incluindo **Cofre incondicionalmente protegido**, mesmo com drawer/Opções e preferência global livre. Consentimento, clipboard, autenticação, foreground services e modelos operacionais preservados.

**Gestos e acessibilidade:** `ModalDrawerSheet(drawerState)` trata Voltar/predictive Back; scrim e swipe são do Material3. A ação semântica “Abrir menu de navegação” permite abrir sem gesto por acessibilidade; há também Fechar menu dentro do drawer. Na **região central da borda esquerda**, 24 × 160 dp, uma exclusão limitada evita que o Back do Android prevaleça; restante das bordas continua disponível. O alvo semântico não intercepta toques. Inspecionados `PointerInteropFilter` Compose UI 1.9.3 e TerminalView vendorizado, sem alteração do renderer. **Conflitos de gesto/seleção, IME e acesso por TalkBack ainda não foram ensaiados no aparelho.**

Validação local com ferramentas existentes: JBR/Java 21, SDK 36, Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20, Compose UI 1.9.3 e Material3 1.4.0; nenhuma dependência instalada.

```sh
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
for patch in android/terminal/upstream/*.patch; do git apply --reverse --check "$patch" || exit; done
git diff --check
```

**JVM: 122 testes, 121 aprovados e 1 ignorado** (handshake local opt-in). Quatro testes novos cobrem contraste mínimo 4,5:1 dos textos/ações/estados e política de captura ao navegar pelo Cofre com preferência global livre/protegida; testes existentes de âncoras SFTP continuam aprovados. Upstream: 21 arquivos, três arquivos com alterações documentadas; os quatro patches passam na aplicação reversa. **Build offline SUCCESS; lint com 0 erros e dois avisos preexistentes de versões** (Gradle e org.json). Não há teste instrumental do drawer/estado/IME. Execução final: 2m09s, RSS máximo do build ~2.005 MiB, zero swaps; aviso de metaspace ao encerrar, sem alteração dos limites. Logs/checks locais em `artifacts/myct-r2a-validation/`.

**APK candidato:** `artifacts/myct-r2a-0.6.0-debug.apk`, **0.6.0-r2a-visual / code 17**, pacote `dev.myct`; assinatura verificada por apksigner e idêntica à 0.5.9 local. Tamanho 14.040.094 bytes (13,39 MiB; +156.637 bytes sobre 0.5.9). [Prévia e checksum](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r2a-v0.6.0-rc1). SHA-256:

```text
88a70c84a0bb0ed24a5b78b9dba07e9959c658f42bb4dbfe670ca71b867520b5
```

**Gate físico:** ADB sem dispositivo conectado nesta sessão; nenhum teste visual ou funcional novo no Moto. Atualizar sobre 0.5.9 sem desinstalar/apagar dados e executar o checklist da seção 5, incluindo seleção/clipboard e abertura pela região central da borda esquerda com navegação Android por gestos. Não há aceite de fluidez nem prova de consumo Android; risco de conflito de gesto/IME permanece para verificação física. R-01/lag continua suspenso; R2B, Fedora e serviços remotos não foram alterados.
