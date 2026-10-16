# MYCT-R1 — terminal e navegação SFTP (candidata, não aceita)

**Origem:** refinamento da [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9), itens R-01 e R-02. Iniciado em 16/10/2026. Não envolve novo serviço no Fedora, modificações SSHD, emulador ou schema.

## R-02 — pasta pai retém posição de leitura

- `FilesScreen.kt`: `LazyColumn` usa `LazyListState` explícito; antes de entrar em diretório ou subir ao pai, registra item visível, índice e deslocamento em pixels.
- `FilesModel.kt`: cache efêmero de até 64 pastas, associado à conexão SSH atual; ao receber listagem da pasta novamente, procura o nome do item para sobreviver a mudanças de ordenação e usa índice limitado como fallback. Sem persistência em disco.
- Ao trocar abas ou desmontar a tela, salva a posição da pasta ainda exibida. Ao trocar de conexão, limpa o cache. Atualizar uma pasta sem sair dela não reinicia a rolagem deliberadamente.
- Teste JVM direcionado em `FilesScrollAnchorTest.kt` cobre nome reordenado, item removido e lista vazia. Resta validação física com pasta grande no Moto G06, incluindo subir/voltar, troca de aba e reconectar.

## R-01 — medição dirigida, sem declarar cura antes da prova

O diagnóstico MYCT-1P classificou a causa do lag como **não identificada**. A preferência `Entrada por caracteres = false` melhorou subjetivamente mas não eliminou lag. A escrita pelo SSH e o eco remoto continuam necessários; a fila de ações e a view não provam causalidade isoladamente.

- Adicionadas duas métricas numéricas efêmeras em `Opções → Medições locais`: duração **write+flush** por ação de envio, e duração **append** do emulador por bloco recebido (média/máximo), ambas reiniciadas em nova tentativa de conexão.
- As métricas anteriores de espera na fila IO e espera para a thread main permanecem. A medição **não** cobre RTT/eco, bytes aguardando no pipe, quadros efetivamente desenhados, nem o tempo interno do teclado Android. Não corresponde a latência ponta a ponta.
- Não alterar batching de teclado, TCP_NODELAY, motor JSch, callbacks/renderer, nem inserir eco local até haver diferença física reproduzível. Não registrar conteúdo ou segredo no diagnóstico.

## Experimento físico mínimo

1. Compilar APK debug no Fedora com o build offline existente e **a mesma keystore** (não desinstalar app). Evitar tocar em serviços Fedora. Se o build falhar, parar e corrigir a causa concreta.
2. No Moto G06, conectar na mesma rede e sessão ssh. Com `Entrada por caracteres = false`, digitar um texto descartável longo, pressionar espaço, apagar trechos e executar `printf 'ok\\n'` no shell livre. Observar responsividade de cada ação sem medir segredos. Repetir `true` apenas se quiser uma comparação, sem refazer investigação ampla.
3. Abrir `Opções → Medições locais` e anotar os números agregados ao lado da percepção de atraso. Comparar comandos simples fora e dentro do tmux apenas se o lag for visível; não interpretar um único máximo como causa.
4. Arquivos: em pasta com muitos itens, rolar até o meio, abrir subpasta, tocar `↑ Pai`; verificar item e deslocamento aproximado. Repetir com alternância de abas e atualização; nova sessão pode começar no topo.
5. Confirmar que terminal, Ctrl-C, tmux, arquivo simples e SFTP continuam utilizáveis.

**Estado:** implementação remota para revisão; Kotlin completo e APK não compilados neste ambiente. Não foi feito teste físico. **Não fazer merge nem declarar R-01/R-02 concluídos** sem revisão, build e teste no Moto.
