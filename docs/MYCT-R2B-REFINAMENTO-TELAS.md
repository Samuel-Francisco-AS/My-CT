# MYCT-R2B — telas internas, ergonomia e fluidez

**Abertura:** 17/10/2026. **Status:** planejamento para implementação, não concluído. **Base visual aceita:** MYCT-R2A.1 0.6.1/code 18; branch `myct-r2b-screens` derivada de `myct-r2a-visual-shell` após o [aceite](MYCT-R2-IDENTIDADE-VISUAL-E-PLANEJAMENTO.md).

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
