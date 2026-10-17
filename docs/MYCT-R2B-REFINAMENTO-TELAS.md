# MYCT-R2B — telas internas, ergonomia e fluidez

**Abertura:** 17/10/2026. **Atualização após teste físico:** proprietário aprovou visual e organização da R2B no Moto G06, destacando melhora perceptível especialmente em Arquivos; duas pendências de Fix foram relatadas (gesto drawer interferindo na rolagem e métricas acumuladas de torrents indisponíveis por CLI antiga). **Correções entregues na candidata 0.6.3/code 20 abaixo; aceite físico da Fix pendente.** Aceite funcional exaustivo ainda não inferido. **Base visual aceita:** MYCT-R2A.1 0.6.1/code 18; branch `myct-r2b-screens` derivada de `myct-r2a-visual-shell` após o [aceite](MYCT-R2-IDENTIDADE-VISUAL-E-PLANEJAMENTO.md).

## Mandato

Reformar o conteúdo interno de **todas as telas** do aplicativo mantendo a identidade Industrial Futurista preta/dourada e o princípio de **FLUIDEZ**: ausência de saltos e recentralizações, feedback imediato, rolagem estável, informações legíveis e gestos suaves, sem elementos decorativos custosos.

**Não adicionar TopAppBar, título/status globais ou botão hambúrguer permanente.** O drawer R2A.1 está aceito; seus dois controles de persistência/captura e as ações SSH devem ficar intactos. Somente elementos funcionais locais de cada seção podem ocupar o topo.

R-01 lag de digitação SSH permanece **dívida técnica suspensa**. Não estudar/alterar JSch, PTY, IME ou a renderização ANSI para resolver latência durante R2B.

## Recorte funcional da R2B

1. **Arquivos/SFTP:** somente caminho atual no canto superior, legível e truncável sem perder acesso ao caminho completo; comando para subir um nível substitui “↑ Pai” por seta/ícone acessível; Atualizar, Enviar e Mostrar ocultos acessíveis e compactos; tirar cabeçalho “Arquivos · SFTP” e parágrafo permanente sobre notificações (ajuda breve via `i` quando útil). Lista com ícone por tipo, nome, metadados úteis (tipo, tamanho quando houver, data), toque imediato e sem realinhar/recentralizar o item antes de navegar. **Preservar o cache por diretório e restauração do scroll da R1, inclusive ao voltar, alternar abas, mudar dotfiles e reconectar.** Não confundir salto ao tocar com lag SSH. Manter ações SFTP/SAF, inspeção, symlinks, cancelamento e estados.
2. **Saúde:** substituir relatório de texto por cards compactos de CPU, RAM, disco e uptime real; usar barras/indicadores leves, com unidades corretas. Ação Atualizar, estado do Core, momento da última leitura e marcação explícita quando dados estiverem antigos/indisponíveis. Retirar texto introdutório redundante; nenhuma métrica inventada.
3. **Cofre:** painel de estado claro (bloqueado/desbloqueado/ocupado/erro), campos e ações com hierarquia, avisos curtos; preservar desbloqueio e cancelamento e **FLAG_SECURE incondicional ao visitar o Cofre**. Senhas efêmeras, jamais persistir ou logar.
4. **Downloads HTTP/HTTPS:** subtipo HTTP/Torrents funcional, formulário compacto de URL e nome opcional, botões claros, histórico em cards com nome/status/progresso real e ações existentes. Remover tutoriais fixos da vista principal; ajuda `i` discreta. Preservar progresso, stale, falhas e confirmação de cancelamento.
5. **Torrents:** cards com título truncado/copiável, status, progresso, downloaded/total, down/up atual, peers, ações pausar/retomar/remover preservando dados/abrir Arquivos. Investigar campos já disponíveis de Transmission RPC/adaptador e, **se viável com mudança mínima**, expor **baixado acumulado, enviado acumulado e ratio por torrent**. Não confundir taxa instantânea com volume acumulado, nem total de sessão com total de cada torrent. Sem números estimados ou inventados. Não mexer em motor Transmission, no comportamento do seeding ou nos diretórios.
6. **Opções e relatórios:** reorganizar conteúdo longo em seções legíveis, com explicações não críticas sob botão `i`, preservando informações críticas no momento da ação e controles funcionais. Não duplicar “Manter conexão ativa” em Opções (fica no drawer). Não retirar qualquer requisito de consentimento/proteção. Melhorar especialmente relatório de inspeção/antivírus (R-09): nome/caminho longos, resumo legível, integridade SHA-256, estados CLEAN/DETECTED/INCOMPLETE/ERROR/UNAVAILABLE, detalhes expansíveis, rolagem e ações sempre acessíveis em tela pequena.
7. **Demais estados e diálogos:** coerência tipográfica, geometria, contraste, tamanho mínimo de toque, mensagens de sucesso/erro/sem dados, campos com teclado, estados loading e acessibilidade. Evitar redistribuição de layout abrupta e animação decorativa sem propósito.

## Execução proporcional

- Reutilizar `MyCtTheme.kt`, `MyCtControls.kt`, `MyCtGeometry` e modelos existentes. Criar só componentes reutilizáveis que realmente eliminem duplicação e reforcem identidade; não montar um framework UI.
- É permitido organizar a implementação em commits por grupo de telas, mas entregar **uma candidata R2B testável** com as telas revisadas. Se uma funcionalidade pedir mudança extensa e ameaçar a entrega visual, documentar essa parte como pendência distinta em vez de reescrever o backend.
- Preservar insets status/navigation/IME, scroll por pasta, histórico de downloads, seleção HTTP/Torrents, navegação drawer, FGS e segurança.
- Build Android offline, testes JVM e lint, verificação upstream/patches, assinatura e versionCode incremental; validação visual física no Moto G06 é requisito para considerar o redesign aceito. Não presumir Testes JVM = visual aprovado.
- Não modificar serviços Fedora, PostgreSQL, arquivos remotos reais ou credenciais. Não fazer merge automático.

## Branching e aceites

- PR #22 `myct-r1-terminal-files → main` draft/sem merge; PR #23 `myct-r2a-visual-shell → myct-r1-terminal-files` draft/sem merge. O **aceite visual da R2A.1** foi registrado; eventuais testes funcionais não relatados não devem ser presumidos.
- Branch `myct-r2b-screens` parte do HEAD aceito/documentado de R2A.1. Codex deve trabalhar nela, abrindo PR **draft baseado em `myct-r2a-visual-shell`** para diff isolado; não mesclar #22, #23 ou R2B à main sem autorização explícita.
- **R-07 teclas adicionais Home/End/PgUp/PgDn**, dívida de transferências em background e **lag R-01** permanecem fora de R2B, salvo nova solicitação.


## Implementação candidata — 17/10/2026

- **Arquivos:** caminho compacto consultável/copiável, seta acessível para subir, Atualizar/Enviar/ocultos na mesma faixa, ícones vetoriais por tipo e linhas com nome em duas linhas/metadados. Sem “Arquivos · SFTP” ou tutorial permanente. Ajuda `i` e permissão de notificação no início da escolha SAF; recusa mantém uso em primeiro plano, com aviso. Ações SAF/SFTP, inspeção, symlinks, cancelamento e abertura local preservadas.
- **Deslocamento ao tocar:** o código anterior inseria progresso e Cancelar acima da LazyColumn ao iniciar `load`, diminuindo a viewport enquanto a lista antiga ainda aparecia. Agora status/ações ocupam faixa fixa de 64 dp e progresso tem 4 dp reservados, sem pedir scroll/centralização. Chave por caminho/filtro, âncora nome/offset, cache e modelos R1 permanecem idênticos. Esta é a correção do mecanismo identificado por revisão; **o sintoma físico ainda precisa ser reensaiado em arquivos/pastas parcialmente visíveis e no fim da lista**, além de voltar/trocar aba/filtro/reconectar. Não há alegação de reprodução ou resolução comprovada no Moto.
- **Saúde:** painéis CPU/RAM/disco/Uptime do Core com valores reais, barras simples, estado/última consulta e marcação de dados anteriores. Sem novas medições/polling. **Cofre:** estado explícito, ações e senha efêmera, avisos de alcance do desbloqueio visíveis no momento da ação; ajuda secundária. Lifecycle/autenticação e FLAG_SECURE incondicional preservados.
- **HTTP/Torrents:** formulários compactos e histórico em painéis, nomes completos consultáveis/copiáveis, progresso e ações existentes, vazios/erros/stale explícitos. Taxas antigas não são rotuladas como atuais. Aviso de seeding permanece visível antes de adicionar; remoção e cancelamento mantêm seus consentimentos e preservação de dados.
- **Opções/relatórios:** grupos Terminal, Proteção/clipboard e Ferramentas; controles/callbacks existentes mantidos, diagnóstico somente sob ação manual. Persistência continua exclusivamente no drawer. Inspeção mostra resumo, SHA-256/cópia/comparação, avisos, detalhes expansíveis e estados AV completos. Relatórios/ajuda têm corpo rolável limitado à janela/insets e ações fora da rolagem; caminhos/títulos extensos não empurram Fechar para fora da tela. Nova camada visual pequena em `ScreenComponents.kt`, reutilizando o tema/controles aceitos, sem dependências/assets externos.

## Acumulados de torrents — consulta aditiva

Inspecionados [RPC 4.1.3](https://github.com/transmission/transmission/blob/4.1.3/docs/rpc-spec.md#33-torrent-accessor-torrent_get) e [definições tr_stat](https://github.com/transmission/transmission/blob/4.1.3/libtransmission/transmission.h). Acrescentados somente `downloaded_ever`, `uploaded_ever` e `upload_ratio` à lista de campos solicitados por `ops/myct-torrent`. Contadores são bytes históricos por torrent, separados de `rate_download`/`rate_upload` e do tamanho atual. Ratio é o valor informado pelo Transmission (4.1: enviado / tamanho final), sem cálculo a partir das taxas ou dos acumulados; −1 = indisponível, −2 = infinito. Campos faltantes/inválidos continuam indisponíveis, nunca zero inventado.

**Na entrega inicial 0.6.2, o adaptador atualizado estava apenas no repositório, sem instalação no Fedora. A instalação autorizada foi executada na Fix abaixo.** A CLI anterior continua compatível com o APK e operações existentes; acumulados/ratio aparecem como Indisponível até instalação manual autorizada da revisão `ops/myct-torrent`. Git pull não atualiza `~/.local/bin/myct-torrent`. Nenhuma alteração de RPC endpoint, serviço, motor, seeding, diretório, conta ou configuração. Teste Python confirma consulta única `torrent_get` e encaminhamento dos campos sem mutação.

## Validação e retomada

Ferramentas existentes: JBR/Java 21, SDK 36, Gradle 8.14.3, AGP 8.13.0/Kotlin 2.2.20, Compose UI 1.9.3/Material3 1.4.0. Nenhuma dependência instalada.

```sh
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 -m unittest discover -s ops/tests -p test_torrent.py
python3 android/terminal/upstream/verify.py
for patch in android/terminal/upstream/*.patch; do git apply --reverse --check "$patch" || exit; done
git diff --check
```

**Build offline final SUCCESS; lint com 0 erros e dois avisos preexistentes de versões** (Gradle e org.json). JVM: **130 testes, 129 aprovados e 1 handshake local opt-in ignorado**, incluindo os cinco casos existentes de âncoras/filtros SFTP e três novos casos de contadores opcionais/legados, separação taxa/volume/progresso e sentinelas de ratio. Python: **8 testes aprovados**. Upstream: 21 arquivos, três arquivos com alterações documentadas e quatro patches reversos íntegros. Revisão de fonte confirmou drawer/tema/controles aprovados, modelos de conexão/execução, FGS, autenticação/Cofre, polling, renderer/PTY/IME e efeito FLAG_SECURE inalterados. Sem supressões de lint.

ADB sem dispositivo nesta execução. **Nenhum aceite R2B, medida de consumo Android, prova de notificação real ou teste visual/acessível foi realizado pelo agente.** Avaliar no Moto os sete critérios deste documento, incluindo fonte ampliada/TalkBack, nomes/caminhos extensos, inspeção/AV/cancelamento/comparação, diálogo com teclado aberto, estados vazios/erro/stale, transferência SAF com permissão aceita/recusada e preservação do drawer/FGS/Cofre. R-01 e teclas adicionais permanecem suspensos/fora do escopo; nenhuma instalação ou mudança no Fedora.


Execução final em 1m58s, CPU acumulada ~580 s, RSS máximo do build ~1.832 MiB, zero swaps; aviso de metaspace ao encerrar, sem alteração dos limites. Logs/checks/hipótese SFTP em `artifacts/myct-r2b-validation/`. Nenhuma alegação de otimização de consumo/lag Android.

**APK candidato:** `artifacts/myct-r2b-0.6.2-debug.apk`, **0.6.2-r2b-screens / code 19**, pacote `dev.myct`, 14.047.517 bytes (13,40 MiB). Apksigner confirmou o mesmo certificado da 0.6.1; atualizar sem desinstalar/apagar dados. [Prévia com APK e checksum](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r2b-v0.6.2-rc1). SHA-256:

```text
b3c0764a5322a48fd1e7f5feaf95fa31265239fcc9366cd8fa72e49b485991e6
```

Continuar em `myct-r2b-screens`, PR draft com base `myct-r2a-visual-shell`; sem merge. Esta candidata foi posteriormente avaliada pelo proprietário; retorno, autorização de instalação e execução da Fix estão registrados abaixo.


## Retorno físico do proprietário e Fix pós-R2B — 17/10/2026

**Relato:** “Ficou bom, de verdade. Senti as melhorias, principalmente na navegação em arquivos que ficou bem melhor.” Capturas do Moto G06 mostram drawer, Arquivos, Saúde, HTTP/Torrents e Opções com estilo aprovado. **R2B aprovada visualmente e em navegação geral de Arquivos**, não significando teste exaustivo de transferência, Cofre, AV ou que o deslocamento pontual do item ao abrir pasta foi revalidado isoladamente.

**FIX-01 — Drawer rouba gestos de scroll (defeito real relatado):** ao tentar rolar verticalmente, a gaveta lateral pode iniciar abertura/consumir o gesto e dificultar subir/descer. Origem introduzida na R2A; reproduzido/percebido ao usar telas R2B. O `AppShell.kt` usa `ModalNavigationDrawer` com gestos padrão e `systemGestureExclusion` numa faixa da borda esquerda. **Corrigir reconhecimento entre intenção horizontal de abrir drawer e rolagem vertical de qualquer seção**, preservando swipe esquerda→direita, swipe inverso, toque fora, Back/predictive Back, acessibilidade e terminal/seleção. Preferir restrição de ativação pela borda e discriminador direcional com slop adequado, sem intercepção invasiva ou botão fixo; provar fisicamente arraste vertical próximo à borda, com início diagonal, e abertura deliberada do drawer. Não declarar mecanismo exato como causa provada sem reproduzir; a existência do conflito foi confirmada pelo proprietário.

**FIX-02 — Métricas acumuladas torrents ausentes (integração pendente, não falha da UI):** a versão Android R2B já exibe `downloaded_ever`, `uploaded_ever`, `upload_ratio` quando o adaptador as fornece. A CLI anterior no Fedora (`~/.local/bin/myct-torrent`) ainda não foi substituída pela revisão do repositório (`ops/myct-torrent`); por isso UI mostra “Indisponível”. As **taxas instantâneas** de download/upload (p.ex. 0 bytes/s nas capturas) são distintas e não representam acumulados. **Instalação no Fedora autorizada explicitamente pelo proprietário na conversa de 17/10/2026:** o Codex pode atualizar exclusivamente `~/.local/bin/myct-torrent` com `ops/myct-torrent`, após validar fonte/testes e preservar backup com rollback em caso de erro. Conferir dados via comando `list` de consulta somente leitura sem expor nomes/hashes nos logs, sem tocar em daemon Transmission/serviços/torrents/diretórios. A autorização não equivale a instalação já efetuada; operação permanece pendente até execução no Fedora. Não alterar serviço, motor, torrent, seeding ou pastas.

**Correções expressamente autorizadas para execução na mesma Fix:** (1) corrigir reconhecimento do drawer sem interceptar rolagem vertical; (2) atualizar somente o adaptador `myct-torrent` instalado no Fedora com backup, testes e rollback. Não há autorização para alterar daemon Transmission ou serviços. Nenhuma nova rodada de diagnósticos de lag SSH (R-01 segue dívida suspensa), nenhuma nova reforma visual, nenhum merge automático. PR #24 continua draft, baseado em R2A; PRs #22/#23 ainda draft. Sem afirmar que essas pendências invalidam a melhora visual aceita.


### Autorização recebida — 17/10/2026

O proprietário respondeu “Autorizo, Luna” à proposta das duas correções. Estado naquele registro: **FIX-01 e FIX-02 autorizadas, implementação/instalação ainda não executadas naquela atualização documental**. Instruir Codex a limitar a correção Android ao gesto, atualizar somente a CLI versionada, executar os testes existentes, conferir retorno real e publicar APK candidato sem merge. Não reabrir a dívida de lag R-01.


## MYCT-R2B-Fix executada — 17/10/2026

**FIX-01:** abertura gestual padrão do `ModalNavigationDrawer` desabilitada enquanto fechado. `DrawerOpeningGesture.kt` observa a cadeia de toque do conteúdo, sem overlay de interceptação. Exige início nos primeiros **24 dp da esquerda**, touch slop do aparelho e deslocamento à direita pelo menos **3×** o deslocamento vertical. O primeiro movimento vertical/diagonal além do slop rejeita a abertura até levantar o dedo; não há captura tardia ao mudar de direção. Movimentos consumidos pelos filhos, toque longo e múltiplos dedos também impedem a abertura. Down/tap não são consumidos; somente após intenção horizontal aceita o gesto é consumido e `DrawerState.open()` é lançado sem bloquear a UI.

O passe Main deixa os filhos tratarem primeiro rolagem/seleção; o TerminalView conserva sua entrada até eventual abertura deliberada. Com drawer aberto, Material3 mantém arraste de fechamento, arbitragem com sua coluna rolável, scrim acessível/toque externo e Back/predictive Back pela `ModalDrawerSheet` existente. A ação acessível de abrir e o trecho de exclusão Android **24 × 160 dp** foram preservados, sem ampliar a exclusão do Voltar nem criar cabeçalho/hambúrguer. Nada foi alterado em foco, renderer, PTY/IME ou telas internas. **Esses comportamentos ainda exigem reensaio físico; a inspeção de fonte não prova fluidez no Moto.**

**FIX-02 instalada realmente no Fedora 44:** fonte `ops/myct-torrent` validada sintaticamente e por oito testes Python; comparação AST confirmou diferença exclusivamente nos três campos de consulta. Executável anterior regular, do usuário atual, `0700`, preservado com bytes, permissão e mtime em:

```text
/home/sam/.local/bin/myct-torrent.bak-r2b-fix-20261017T044529Z
```

Substituição atômica somente de `~/.local/bin/myct-torrent`, permissão **0700**, com bytes/SHA-256 idênticos à fonte validada (`5c9a76ca8692382efcf35fc133e30c150bd7d912dcadc3b16e9bfceaca574dfe`). Consulta real **`list`, exit 0**, retornou **2 registros**, ambos com `downloaded_ever`, `uploaded_ever` e `upload_ratio` válidos. O parser de produção Android aceitou os dois registros e preservou exatamente os três valores de cada registro. Nenhuma regressão detectada nessa consulta; rollback não foi necessário e o backup permanece disponível. Resposta real temporária `0600` removida após conferência, sem nomes, hashes, magnets, credenciais ou caminhos privados nos relatórios. Nenhuma operação de mutação de torrent, reinício/reconfiguração de daemon, serviço ou diretório executada.

**Validação:** build Android offline SUCCESS (1m55s), **136 testes JVM: 135 aprovados e 1 handshake opt-in ignorado**, incluindo seis novos testes de decisão direcional (borda, slop/tap, vertical/diagonal sem captura tardia, consumo dos filhos, toque longo/multitouch e decisão estável). **8 testes Python aprovados**, lint **0 erros/2 avisos preexistentes de versões**, upstream **21 arquivos/3 arquivos com alterações documentadas e 4 patches reversos íntegros**, `git diff --check` limpo. Nenhuma dependência instalada. Build: RSS máximo ~2.015 MiB, zero swaps; aviso de metaspace ao encerrar já observado na R2B, sem alterar limites. Logs e evidências agregadas em `artifacts/myct-r2b-fix-validation/`.

**APK candidato:** [prévia 0.6.3](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r2b-fix-v0.6.3-rc1), `artifacts/myct-r2b-fix-0.6.3-debug.apk`, **0.6.3-r2b-fix / code 20**, pacote `dev.myct`. Assinatura verificada e certificado idêntico ao APK 0.6.2/code 19; atualizar sem desinstalar. SHA-256:

```text
3a1ed031b7de8a540690744cf63d914a9536ab7d85056c51143542eb7ba1d76e
```

### Roteiro curto de aceite no Moto G06

1. Rolar Arquivos verticalmente, inclusive começando perto da borda esquerda e em diagonal, sem abrir o menu.
2. Rolar Saúde e Downloads sem abertura acidental nem perda de posição.
3. Abrir com swipe horizontal deliberado da borda esquerda (experimentar o trecho central reservado contra Voltar).
4. Rolar o próprio drawer; fechar por swipe inverso, toque externo e Back.
5. Abrir Torrents e atualizar a lista.
6. Conferir baixado acumulado, enviado acumulado e ratio reais, separados das taxas instantâneas.

Conferir também foco/IME e seleção de texto no Terminal, ação de abrir/fechar via acessibilidade e navegação Voltar fora do trecho reservado. ADB sem dispositivo; **aceite físico da Fix pendente do proprietário**. Visual R2B permanece aprovado; nenhum novo diagnóstico de lag SSH ou reforma visual. Continuar no [PR #24 draft](https://github.com/Samuel-Francisco-AS/My-CT/pull/24), base `myct-r2a-visual-shell`, sem merge.
