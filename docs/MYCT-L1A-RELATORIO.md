# MYCT-L1A — IDE e arquivos locais

17/10/2026. **Implementação pronta para revisão e teste físico**, branch
`myct-l1a-ide-local`, base `41a0a0b` (main sincronizada). Issue #27.
**Atualização do proprietário na missão L1B (17/10/2026):** criação de pasta e
arquivo, edição de `teste.c` no Sora, salvamento/persistência após fechar e
reabrir e renomeação de pasta preservando arquivo foram confirmados no Moto
G06 com 0.6.7/code 24. SAF, isolamento completo e regressão remota ainda não
receberam aceite físico individual. PR #28 continua aberto/draft, sem merge.
Os trechos abaixo registram a entrega original, anterior a esse retorno.
Continuidade autorizada em [L1B](MYCT-L1B-RELATORIO.md), empilhada sobre L1A.

## Critérios registrados antes da implementação

- Sem SSH/rede: selecionar Local, criar ProjetoTeste/teste.c, editar no Sora,
  salvar explicitamente e reabrir conteúdo idêntico após reiniciar o app.
- Criar/listar/navegar/renomear pastas e arquivos na workspace privada; impedir
  escapes/symlinks/nomes inválidos e preservar âncora de scroll por diretório.
- Mesmo Sora/teclas/UX R4, um buffer independente por ambiente; alternância
  e navegação entre seções não descartam conteúdo/undo. Descarte confirmado.
- Escrita local por temporário/sync/substituição, comparação de conteúdo e
  metadados antes de publicar; erros preservam buffer e resultado incerto é explícito.
- SAF: importar cópia UTF-8 identificada, exportar cópia por CreateDocument,
  tratar cancelamento/permissão/erro sem sobrescrever silenciosamente a origem.
- Build/testes/lint offline e integridade upstream aprovados; aceite Moto é
  separado e pendente. Nenhum serviço Fedora, terminal local ou compilador.

## Implementação e arquitetura efetiva

- `LocalWorkspace.kt`: operações reais por `java.nio.file` (minSdk 26), com
  caminhos **relativos** à workspace `Context.filesDir/ide-local/projetos`.
  Pastas/projetos, arquivos, pai/raiz, refresh e renomeação sem substituição.
  Nome único até 240 bytes, sem barras/controles/dot traversal; symlinks não
  atravessam a workspace. Arquivos internos `.myct-*` são reservados.
- `IdeSession.kt`: contrato pequeno de **apresentação**, sem framework genérico
  de filesystem; `IdeModel` conserva `LocalIdeModel` e `RemoteIdeModel` no dono
  de processo já existente (`TerminalModel`). Última escolha é lembrada; novo
  processo sem SSH autenticado usa Local como fallback seguro. Uma troca manual
  para Remoto desconectado continua possível, com indicação para conectar.
- `IdeModel.kt`: buffer Sora/undo compartilhado como implementação, instâncias
  distintas por ambiente. O modelo remoto mantém a transação R4 e identidade
  host/porta/usuário/pin, com ponto de injeção de store/dispatchers apenas para
  testes. Nenhuma mudança em `RemoteEditor.kt`, JSch ou transporte.
- `LocalIdeModel.kt`: disco e providers em `Dispatchers.IO`; estado de operação,
  mensagens e buffer no escopo de processo. Salvar é explícito: snapshot do texto
  uma vez, versão do Content conferida no ACK, sem String integral no Compose
  a cada tecla. Renomear arquivo/ancestral ajusta o destino do documento aberto
  mantendo Content, undo, viewport e dirty. Back verifica **ambos** os buffers.
- `IdeScreen.kt`: uma única tela/Sora AndroidView e grade R4 2×7, com seletor
  Local/Remoto imediatamente acessível na faixa de 48 dp. Navegador reutiliza
  DTOs de listagem e âncoras visuais R4 (`RemoteListing`/`RemoteEntry` mantêm o
  nome histórico), mas caminhos/operações locais **não usam RemotePath/SFTP**.
  Cada sessão tem listagem/scroll próprios; UI temporária/saveable é separada por
  ambiente. Novos comandos ficam no overflow e renomeação no item do navegador.
  Terminal é explicitamente **Terminal · Fedora**, sem terminal local fictício.

### Salvamento privado e conflitos

Leitura UTF-8 estrita, até 1 MiB, 30 mil LF e linha de até 16384 caracteres,
reutilizando os limites R4. BOM/finais de linha preservados. Save compara bytes,
tamanho e mtime com o snapshot, escreve temporário no mesmo diretório,
`flush`/`fd.sync`, relê, compara novamente e publica via `ATOMIC_MOVE` +
`REPLACE_EXISTING`; relê o resultado antes de confirmar. Não trunca o original.
Falha antes da publicação preserva o original; falha na fase de publicação é
conservadoramente incerta, mantém buffer e bloqueia novos saves até conferência
/reabertura. Limpeza best effort só alcança o temporário da própria operação.

Comparação otimista não é CAS: permanece uma janela entre última conferência e
rename. A sandbox privada e uma operação local por vez reduzem concorrência;
não há sincronização com outros escritores nem promessa de durabilidade contra
falha elétrica do armazenamento. Não há backup local automático. O backup
hardlink remoto da R4 permanece intacto e separado.

### SAF por cópias

`OpenDocument` aceita documento escolhido explicitamente; bytes são limitados e
validados independentemente de MIME/SIZE. Importa por temporário + sync para
`Importados/<nome>` com sufixo numérico em conflito, sem substituir a origem nem
o buffer aberto. Toque na cópia para abrir (descarte sujo exige confirmação).
Identificação de cópia importada é persistida em
`filesDir/ide-local/importados.properties`, fora da árvore de projetos, e
acompanha renomeação de arquivo/pasta. Falha tardia de renomeação é incerta.

`CreateDocument("text/plain")` recebe apenas o snapshot **local salvo**, capturado
antes do seletor. Exige salvar primeiro; URI SAF não vira caminho SFTP. Exporta
uma cópia no destino escolhido, fecha e tenta reler. Conteúdo idêntico confirma
sucesso; provedor sem releitura mostra resultado sem confirmação; erro de escrita,
fechamento ou divergência avisa que cópia pode estar vazia/parcial. Buffer e
workspace permanecem preservados. Cancelar seletor é tratado sem escrita.
Não guarda URIs/grants após a operação, não pede persistência de acesso nem
permissão ampla; picker ausente/permissão revogada/erro são recuperáveis.

## Escopo preservado e limitações

- Sora 0.23.6, assets/gramáticas/licenças e upstream Termux não modificados.
  Nenhuma dependência nova ou download em runtime; nenhuma dependência do
  **aplicativo** Termux, verificada pelo código/manifesto. Não requer desinstalá-lo.
- applicationId `dev.myct`, minSdk 26 e targetSdk 36 mantidos. Manifesto,
  FLAG_SECURE/Cofre, serviços, Terminal/tmux/IME, Arquivos/SFTP, downloads e AV
  preservados. Nenhum serviço, arquivo instalado ou banco Fedora alterado.
- Um documento por ambiente, undo/buffer somente durante a vida do processo;
  **arquivos salvos** persistem após fechar app. Desinstalar/limpar dados apaga
  workspace (backup Android já desabilitado); exportar cópias para preservação.
- Operações locais pequenas terminam para informar resultado; sem cancelamento
  durante gravação, nem tentativa de interromper commit de provedor SAF.
  Provedor externo bloqueado pode atrasar importação/exportação. Cancelamento
  do seletor é suportado. Sem SAF de árvores, sincronização ou autosave.
- Sem medição de RAM/bateria/fluidez no Moto, sem inspeção visual por emulador
  ou teste de Gboard/haptics/picker Android nesta sessão. JVM não executa essas
  partes. R-01 permanece suspensa, L1B/L1C e ADR-018 continuam fora do escopo.

## Validação automatizada e ambiente observado

Ferramentas existentes: OpenJDK/JBR 21.0.10, SDK plataforma 36/revisão 2,
Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20, gh 2.97.0 e apksigner 0.9
(build-tools 36.0.0). Nenhuma instalação de dependência foi necessária.

```sh
cd android
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/sam/Android/Sdk \
MYCT_TEST_LOCAL_SFTP=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest \
  :app:lintDebug --offline --console=plain
python terminal/upstream/verify.py
# Na raiz: git diff --check; git apply --reverse --check em cada patch upstream.
```

**PASS final:** BUILD SUCCESSFUL em 2m26s. 197 testes encontrados: **196
aprovados, 1 handshake SSH opt-in ignorado**, zero falhas/erros. Os **24 testes
novos** são 9 workspace, 9 modelo/isolamento e 6 streams SAF. Cobrem criação,
extensões solicitadas, navegação, renomeação/ancestral, UTF-8/BOM/CRLF, conteúdo
salvo reaberto por nova instância, nomes/escape/symlinks, conflito mesmo tamanho
/mtime, preservação dirty/undo/viewport em ambos ambientes, destinos separados,
falhas sem apagar buffer, importação sem sobrescrita/proveniência persistente,
cancelamento/picker indisponível/permissão ausente e exportação verificada ou
incerta. Fixtures privadas temporárias no filesystem real Fedora.

Suíte R4 mantida: Sora Content/gramáticas/teclas/scroll, transação remota e
SFTP OpenSSH descartável real **PASS** (0,085 s), sem porta/autenticação/rede;
framing SSH é simulado. O teste dos dois modelos usa store SFTP em memória e
Content Sora real; não equivale a conexão autenticada ou aparelho físico.
Lint: **0 erros / 2 avisos preexistentes** de dependências. Compilador ainda
aponta duas condições redundantes já existentes no bloco da view e aviso de
metaspace, sem falha nem alteração de limites de RAM. Upstream: **21 arquivos
verificados e quatro patches reverse-check PASS**. Whitespace PASS.

Custo do build final medido por `/usr/bin/time -v`: 2m26,53s parede,
747,05 s CPU acumulada, pico RSS **2170544 KiB (~2,07 GiB)** e zero swaps.
Isso é custo de build Fedora, não consumo no Moto. Logs locais ignorados:
`artifacts/myct-l1a-validation/final-build.log`, `test-summary.json` e relatórios
Gradle em `android/app/build/`. ADB mostrou zero aparelhos; nenhum teste físico
ou instalação no Moto foi feito pelo agente.

## APK, distribuição e Git

- **0.6.7-l1a-local-ide / versionCode 24**, `dev.myct`, minSdk 26 / targetSdk 36.
- Artefato local: `artifacts/myct-l1a-0.6.7-debug.apk`, **17100238 bytes**
  (~16,31 MiB), crescimento de 88676 bytes (~86,6 KiB) sobre a R4-Fix.
- SHA-256 APK: `ca06ed6e783070eca053e0dd7d301dbfe6248056727825a14c3aaec0e409c242`.
- Checksum: `artifacts/myct-l1a-SHA256SUMS.txt`, incluindo fonte Sora anexada.
- Certificado SHA-256: `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
  `apksigner verify` confirmou assinatura v2 e comparação direta com o APK
  R4-Fix/code 23 aceito (cujo SHA-256 também confere com o fechamento R4).
  Mesmo pacote/certificado e code maior permitem atualização **sem desinstalar**;
  instalação efetiva permanece responsabilidade do proprietário.
- [Pré-release candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-l1a-v0.6.7-rc1),
  com APK, SHA256SUMS e fonte Sora 0.23.6. Sem binário/keystore no Git.
- Branch remota `myct-l1a-ide-local`. Commits de implementação: `3a33961`
  (workspace/testes reais) e `2418539` (IDE integrada, SAF, modelos/testes e versão).
  Documentação/entrega em commit separado na mesma branch.
- PR draft vinculado à issue #27: [#28](https://github.com/Samuel-Francisco-AS/My-CT/pull/28). Manter draft enquanto o aceite
  físico estiver pendente. Não houve merge, exclusão de branches ou fechamento
  da issue #27.

Retomada: instalar a candidata manualmente e executar o checklist abaixo;
registrar resultados por teste. Em caso de falha, preservar os projetos salvos e
relatar operação/versão/erro, sem expor código sensível. Rollback funcional sem
reinstalar: selecionar Remoto e usar os fluxos R4 preservados; Android não aceita
APK com versionCode menor como atualização normal. Não desinstalar para voltar
à R4 sem antes exportar projetos.

## Checklist de aceite físico — proprietário / Moto G06

Instalar a candidata **sobre** 0.6.6, sem desinstalar/limpar dados. Autorizar a
instalação manual de APK pelo gerenciador Android, se solicitado. O agente não
instalou nem pressupôs acesso ao aparelho. Não é preciso desinstalar Termux.

1. **Offline:** ativar modo avião; abrir My-CT sem SSH; drawer → IDE → seletor
   **Local · Moto G06**; conferir Projetos locais, sem pedido de autenticação.
2. **Persistência:** ⋮ → Nova pasta/projeto `ProjetoTeste`; entrar; Novo arquivo
   `teste.c`; abrir, digitar código no Sora e **Salvar**; anotar conteúdo. Fechar
   app e reabrir, navegar ao arquivo e conferir conteúdo idêntico. Para testar
   persistência de disco, pode encerrar o processo após salvar; não limpar dados.
3. **Navegação/edição:** criar outra pasta, renomear arquivo pelo ⋮ do item,
   navegar e voltar a pasta com vários itens, conferindo posição anterior.
   Conferir Gboard, grade 2×7, seleção/clipboard, undo/redo, busca/substituição,
   indentação, linhas e destaque. Tentar abrir outro arquivo sujo e cancelar o
   descarte; buffer deve continuar intacto.
4. **SAF:** importar texto real escolhido no Android (também cancelar uma vez);
   abrir cópia em Importados, conferir identificação, editar e salvar workspace;
   exportar cópia salva via SAF; abrir exportado e conferir texto. Origem externa
   deve permanecer intacta. Cancelar exportação não altera workspace.
5. **Isolamento:** editar Local sem salvar; alternar Remoto e voltar Local;
   conferir texto/undo/dirty preservados. Alternar IDE/Terminal/outra seção;
   voltar e salvar localmente. Nenhum conteúdo local deve aparecer no Fedora.
   Se desejar, manter também arquivo remoto sujo e testar os dois sentidos.
6. **Remoto:** desativar modo avião; autenticar no Fedora normalmente; selecionar
   Remoto, abrir arquivo descartável, editar/salvar por SFTP, conferir conteúdo
   no Terminal · Fedora. Confirmar terminal anterior utilizável. Isso é regressão
   nova da integração L1A, não repetição do aceite histórico da R4.

Registrar PASS/FAIL por teste e versão Android/teclado, com conteúdo de teste
não sensível. **Todos os seis testes físicos estão pendentes. LOC-01 e LOC-04
não estão aprovados; L1A só será concluída após aceite expresso do proprietário.**
