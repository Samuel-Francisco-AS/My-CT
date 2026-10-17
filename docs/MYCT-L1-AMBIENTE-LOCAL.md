# MYCT-L1 — ambiente local independente (planejamento aprovado)

**Decisão do proprietário:** 17/10/2026. **Estado:** direção e ordem aprovadas; **L1A com aceite físico parcial do proprietário; L1B com confirmação física parcial; L1C candidata em validação, gate Moto pendente; L1D aprovada para depois**. **Próxima fase:** depois de fechar e integrar a MYCT-R4/Fix, **antes da MYCT-6 (automações/T5)**. Não iniciar L1 durante o aceite físico pendente da R4.

## Objetivo e decisão

Fazer o My-CT ser útil **sem internet, sem conexão SSH, sem Fedora disponível e sem exigir Termux instalado**. O app Android deve oferecer edição e execução locais de programas, mantendo o modo remoto atual. O proprietário aceitou começar com **programação local de console** e, somente depois de provada a base, expandir gradualmente para um ambiente Linux local mais completo (shells, Git, Make, compiladores, runtimes, eventualmente pacotes).

**Não significa eliminar código aberto upstream do Termux já incorporado no My-CT:** os componentes de terminal devidamente licenciados podem continuar sendo reutilizados internamente. A independência exigida é **operacional e de instalação:** o usuário não precisará ter o aplicativo Termux, servidor remoto, internet nem serviço externo para usar o ambiente local após seu provisionamento.

A identidade visual Industrial Futurista e o princípio **FLUIDEZ** permanecem: sem cabeçalho global, sem duplicar abas inteiras, com seleção clara de **Local (Moto)** ou **Remoto (Fedora)**. Saúde/serviços específicos do Fedora não devem aparentar funcionamento local quando não houver equivalente.

## Arquitetura-alvo mínima

- **Separar tela/edição da origem dos dados e de onde os comandos são executados.** Reutilizar Sora, Termux TerminalView/TerminalEmulator e componentes de Arquivos, sem compartilhar indevidamente estados de sessões locais e remotas.
- **Files provider:** `RemoteSftpProvider` (atual, preservar) e `LocalFilesProvider` (novo, apoiado inicialmente por diretório privado do app e SAF para árvores externas autorizadas). A nomenclatura é ilustrativa; não exigir um framework de providers.
- **Session/Executor:** sessão remota SSH/PTY intacta; nova sessão local criada com PTY/processo Android e isolamento do aplicativo, sem loopback SSH, root ou Android Debug Bridge. Cuidado com `targetSdk 36`, ciclo de vida de processos, serviço/foreground e indisponibilidade de comandos do GNU/Linux tradicional.
- **Editor/compilação:** Sora local abre e salva arquivos locais, com confirmação para substituir alterações externas. O compilador/runner opera **somente em arquivos locais autorizados** (copiar a workspace privada quando necessário); saída/stdin/stderr apresentados em uma experiência de console adequada, sem forçar shell remoto.
- **Proteção de dados:** estados `dirty` e buffers não salvos devem sobreviver à troca de área quando possível. Nunca rodar código sem ação deliberada do usuário nem conceder acesso genérico aos dados de outros aplicativos. Persistência limitada ao necessário, sem segredo em logs.

## Etapas enxutas, com provas no Moto G06

### MYCT-L1A — arquivos e IDE offline

- Criar workspace privado acessível a partir da IDE (`Projetos locais`), criar/renomear/abrir/editar/salvar arquivos de texto. O Sora deve reutilizar a grade de teclas e a faixa compacta da R4-Fix.
- Oferecer SAF para abrir/salvar documentos ou selecionar árvore externa com permissão explícita, respeitando os limites do Android; sem permissão ampla de armazenamento nem varrer dados privados de outros apps.
- Poder mudar entre ambiente local e remoto sem exigir login SSH e sem confundir caminhos/permissões/dirty buffer.
- **Gate físico:** no **modo avião** e sem Termux, editar e salvar um `teste.c` local, fechar/reabrir tela e confirmar conteúdo preservado; o modo remoto continua funcionando como antes quando conectado.

### MYCT-L1B — terminal local real

- Implementar ponte PTY local compatível com versão Android suportada, avaliar JNI/NDK e APIs `fork/exec`/processos de forma concreta; iniciar preferencialmente `/system/bin/sh`, executável provido pelo Android, sem instalar pacote de executáveis arbitrários.
- Reutilizar tela de terminal, ANSI, resize, teclas auxiliares R3 e Gboard, com processos locais independentes das sessões SSH.
- **Gate físico:** sem rede, comandos básicos `pwd`, `ls`, `echo`, `cd`, pipe e `Ctrl+C` funcionam na sandbox; voltar à IDE e ao terminal não corrompe estados; não assumir presença de bash, coreutils completos ou sudo.

### MYCT-L1C — prova de compilação/execução C offline

- Antes de escolher tecnologia definitiva, fazer **spike curto comparando viabilidade** de compilador C embarcado + execução via WebAssembly/WASI em runtime local (p.ex. WAMR) **versus** caminho nativo Android legal e compatível (bibliotecas pré-empacotadas e restrições de execução). Escolher por demonstração real no Moto, tamanho, consumo, desempenho, licença, redistribuição e ciclo de atualização.
- Android moderno (`targetSdk 36`) restringe execução de binários arbitrários colocados em diretórios graváveis do app. **Não contornar isso baixando executáveis para HOME, baixando targetSdk, root ou explorando permissões;** reconhecer que módulos WASI não equivalem a executáveis Linux nativos, e que incorporar compilador C/headers/libc/WASI é trabalho próprio.
- Preferir solução mínima **independente do Termux** e funcional **offline após instalação/provisionamento**. A escolha exata de clang/WASI/runtime é **hipótese técnica**, não parte aprovada da implementação.
- **Gate obrigatório:** em modo avião, editar, compilar e executar um programa C simples com entrada/saída local; mostrar erro de compilação e saída ao usuário; nenhuma conexão com Fedora ou Termux. Se o gate falhar, documentar o bloqueio e a alternativa, **não declarar compilação concluída**.
- **C++ não é requisito do primeiro gate:** expandir quando a toolchain C tiver resultado comprovado.

## Expansões futuras (não implementar no primeiro ciclo)

A partir da L1 comprovada: C++, Python, JavaScript, Git, Make, shells adicionais, Rust/Cargo, ferramentas Linux e um catálogo/gerenciamento de componentes. Avaliar consumo de armazenamento/bateria, APIs Android, isolamento, atualização offline após distribuição inicial e licenças caso a caso. Não prometer instalação de pacotes Linux arbitrários nem compatibilidade POSIX integral.

## Integração, segurança e limites

1. **Não tocar no servidor Fedora** para implementar L1. Core C++20/PostgreSQL, SSH/JSch, SFTP, Cofre/FLAG_SECURE, Transmission, Antivírus e automações MYCT-6 permanecem intactos. O ambiente local não tem privilégios root.
2. Diferenciar claramente origem de arquivo e execução (`Local` vs `Fedora`), sem gravar/sobrescrever fonte remoto ao mudar de contexto. Confiar apenas em URIs SAF concedidas pelo usuário.
3. **A mesma UX:** Sora com editor local/remoto; terminal com sessões local/SSH, não duplicar renderizadores. Manter o layout industrial sem cabeçalho global.
4. A UX offline deve estar disponível ao abrir o app mesmo com a rede indisponível, sem forçar fluxo de conexão SSH. Mantidas proteções de captura e dados sensíveis.
5. Trabalhar em commits/entregas pequenas com uma prova real por etapa, sem gastar o tempo em uma arquitetura comercial generalista; tratar problemas do Android pela tecnologia nativa suportada, não por atalhos frágeis.

## Gate de entrada liberado após MYCT-R4 — 17/10/2026

**R4-Fix 0.6.6/code 23 recebeu PASS físico e foi integrada à main:** [PR #26](https://github.com/Samuel-Francisco-AS/My-CT/pull/26), merge commit `266e799a0fe3d5a4dd6d9a270095c3d2f3da6005`. [Fechamento R4](MYCT-R4-FECHAMENTO.md). A condição de entrada para L1 está **satisfeita**. O proprietário decidiu iniciar L1 **em uma nova conversa**, após sincronizar main e limpar branch R4. A missão L1A foi executada na branch `myct-l1a-ide-local`; o retorno físico parcial posterior está registrado no relatório L1A. [Relatório L1A](MYCT-L1A-RELATORIO.md). MYCT-6 permanece posterior.

## Dependência e ordem oficial

1. **Concluído:** MYCT-R4/Fix 0.6.6/code 23 — aceita fisicamente e integrada à `main`, merge #26 `266e799`. Essa pré-condição foi satisfeita; limpeza da branch depende de sincronização local.
2. **Em revisão:** **MYCT-L1A** (PR #28 draft; aceite físico parcial), com **L1B empilhada sobre L1A**, candidata a teste no Moto; L1C posterior, ainda não iniciada. A L1 foi planejada na `main` sem iniciar implementação ou branch de código antes do aceite R4.
3. **Depois:** retomar discussão de **MYCT-6/T5 Automações**, conforme requisitos originais de trabalhos predefinidos, execução headless e histórico; nada de automações nesta fase local.

**Critério de conclusão da primeira L1:** programa C editado, salvo, compilado e executado **offline no Moto G06**, sem Termux instalado, sem Fedora e sem servidor; editor/terminal remoto anteriores continuam operacionais quando o Fedora está disponível. **Gate não comprovado = fase não aprovada.**

**Situação atual L1A:** workspace privada em `filesDir/ide-local/projetos`, criação/renomeação/navegação, Sora offline e importação/exportação de cópias via SAF implementados. Uma única tela/editor, com sessões e buffers independentes Local/Remoto. Importação usa OpenDocument e cópia identificada; exportação usa CreateDocument e exige salvar primeiro. Sem permissão ampla, componente baixado, dependência do app Termux ou mudança no Fedora. **O proprietário confirmou criação/edição/salvamento/persistência e renomeação no Moto com code 24; SAF, isolamento completo e regressão remota permanecem sem aceite individual.** L1B acrescenta PTY local real em candidata code 25, sem aceite físico; compilação permanece não implementada, ADR-018 continua aberta. [Evidências e roteiro L1A](MYCT-L1A-RELATORIO.md).


**Situação L1B:** `/system/bin/sh -i` por JNI própria/openpty, workspace da L1A,
seletor Local/Remoto no terminal existente, processos/emuladores/filas/modificadores
independentes. Sessão local pertence ao processo Android; não acrescenta FGS.
Início/fechamento explícitos, sem comandos por navegação. [Arquitetura, testes,
APK e gate A–F pendente](MYCT-L1B-RELATORIO.md). L1C/toolchains não investigadas
nesta fase, nenhum merge autorizado.


## Atualização da missão L1C e nova L1D — 17/10/2026

O proprietário confirmou no Moto G06/code 25: shell real, pwd/ls/echo/cd,
workspace dos projetos, Ctrl+C e digitação local mais fluida. Demais regressões
e interface da L1B seguem pendentes. A L1C acrescenta candidata WCPL → WASI +
WAMR interpretado, console na IDE existente e worker fixo no APK; **nenhum
resultado físico L1C foi confirmado**. [Relatório L1C](MYCT-L1C-RELATORIO.md).
PRs #28/#29 abertos na entrada, L1C empilhada sobre L1B, sem merge automático.

Ordem atual aprovada: **L1A → L1B → L1C → L1D → refinamento final**;
automações MYCT-6 permanecem fora deste ciclo.

### MYCT-L1D — acesso direto ao armazenamento compartilhado (futura)

IDE e Terminal Local deverão navegar diretamente por diretórios compartilhados
autorizados, como Downloads, DCIM e Pictures, sem importação obrigatória de
cópias. Avaliar APIs Android, SAF e MANAGE_EXTERNAL_STORAGE, consentimento
explícito e restrições da plataforma. Um symlink sozinho não concede acesso.
Critérios e tecnologia precisam ser definidos na missão L1D; **não implementada
na L1C**, que mantém a workspace privada e SAF por cópias.
