# MYCT-L1 — ambiente local independente (planejamento aprovado)

**Decisão do proprietário:** 17/10/2026. **Estado:** direção e ordem de execução aprovadas; implementação **ainda não iniciada**. **Próxima fase:** depois de fechar e integrar a MYCT-R4/Fix, **antes da MYCT-6 (automações/T5)**. Não iniciar L1 durante o aceite físico pendente da R4.

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

## Dependência e ordem oficial

1. **Agora:** MYCT-R4/Fix 0.6.6/code 23 — PR #26 `myct-r4-terminal-ide` ainda draft/sem merge; aguarda teste físico do proprietário no Moto G06.
2. **Depois do aceite e merge da R4:** iniciar **MYCT-L1A → L1B → L1C**, testando no Moto e registrando limitações. A L1 foi planejada na `main` sem iniciar implementação ou branch de código antes do aceite R4.
3. **Depois:** retomar discussão de **MYCT-6/T5 Automações**, conforme requisitos originais de trabalhos predefinidos, execução headless e histórico; nada de automações nesta fase local.

**Critério de conclusão da primeira L1:** programa C editado, salvo, compilado e executado **offline no Moto G06**, sem Termux instalado, sem Fedora e sem servidor; editor/terminal remoto anteriores continuam operacionais quando o Fedora está disponível. **Gate não comprovado = fase não aprovada.**

**Situação dos recursos locais hoje:** Sora já está integrado na branch R4 e foi testado remotamente; não há prova de execução local ou compilação offline no Moto, portanto todo o ambiente local permanece **planejado**.
