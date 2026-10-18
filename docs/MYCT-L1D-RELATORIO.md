# MYCT-L1D — Sora, pasta compartilhada Android e Termux

**17/10/2026 · candidata 0.7.0-l1d-shared-storage / code 28 · aceite físico PENDENTE.**
Branch `myct-l1d-shared-storage`, criada sobre L1B `e1deac6`, após decisão
registrada em `8a4e8d2`. L1C cancelada; PR #30 fechado sem merge. Nenhum código,
compilador, worker, linker ou runtime WCPL/WAMR foi transportado para esta entrega.
Issue #27 continua aberta; [PR #31 draft](https://github.com/Samuel-Francisco-AS/My-CT/pull/31)
empilhado sobre `myct-l1b-local-terminal` enquanto
#28/#29 não forem integrados. Não fazer merge sem aceite físico expresso.

Implementação e testes no commit `6c48d38`; commits seguintes desta entrega
apenas registram os links de publicação.

## Critérios e implementação

Critérios definidos pelo proprietário na missão antes da implementação:
autorizar `Documents/MyCT`, criar `ProjetoC/teste.c` no Sora, salvar diretamente,
reabrir o app, acessar e compilar o **mesmo arquivo** no Termux, voltar ao Sora,
editar/salvar e recompilar; preservar workspace privada e SSH. Permissão revogada,
conflito externo e descarte de buffer precisam de tratamento explícito.

Na **IDE → Local**, o seletor **Projetos privados / Pasta compartilhada** escolhe
a origem. No código aberto, essas opções também estão em **⋮**. As três origens
(privada, compartilhada, remota) mantêm buffers/undo independentes no mesmo Sora.
O rótulo sob o nome identifica a origem. Tema Industrial Futurista, linhas,
gramáticas, teclas, teclado Android, busca/substituição e undo/redo são reutilizados.
Não há nova aba global nem outra IDE.

- `ACTION_OPEN_DOCUMENT_TREE` pede leitura/gravação explícitas. A URI da árvore
  e os IDs da trilha de navegação ficam em preferências privadas separadas;
  `takePersistableUriPermission` conserva a autorização. A reabertura consulta
  os grants reais, redescobre a trilha pelos filhos do provedor e recua ao último
  ancestral existente se uma pasta desapareceu.
- `DocumentsContract` lista/cria/renomeia; a IDE usa as URIs retornadas e flags
  do provedor. Nomes iguais não se tornam IDs: documentos de mesmo nome têm
  chaves de UI distintas. Não há tradução URI → caminho POSIX nem exploração
  de dados de outros apps.
- **Salvar** abre a URI do próprio documento em `wt`, escreve e relê o conteúdo.
  Não usa `CreateDocument`, exportação ou arquivo novo por edição. Comparação de
  bytes integrais e metadados antes de escrever recusa alteração externa,
  inclusive quando tamanho e data não mudaram. Metadados ausentes são aceitos;
  os bytes continuam sendo comparados. Não há opção de sobrescrever conflito
  silenciosamente: preserve/copie o buffer e reabra explicitamente.
- Falha a partir da abertura para escrita, inclusive na releitura, marca
  **resultado incerto** e bloqueia novos saves daquele buffer. O provedor pode
  já ter truncado ou escrito parcialmente. Buffer e undo ficam em memória;
  confira o arquivo no Termux/Arquivos, copie o buffer e reabra antes de continuar.
  Não há promessa de substituição atômica, backup, CAS ou durabilidade remota.
  Outro escritor no intervalo entre comparação e escrita ainda pode causar
  corrida: não editar simultaneamente nos dois apps.
- Revogação/ausência do grant solicita nova seleção. Cancelar o seletor ou falhar
  na leitura não descarta conteúdo. Trocar árvore com buffer sujo/incerto exige
  confirmação; a troca só descarta o buffer após validar a nova pasta. Alternar
  origens preserva todos os buffers sem exigir descarte.
- Criar/renomear confere colisões e nomes antes da mutação; sem retry automático.
  Se o provedor falhar ao criar, atualize a pasta antes de repetir: pode existir
  um item vazio. Na renomeação, segue-se a nova URI retornada. Renomear pasta
  que contém o documento aberto exige fechá-lo antes: provedores podem invalidar
  IDs dos descendentes e não há inferência de identidade por prefixo de URI.
- Limites existentes: UTF-8 textual, 1 MiB, 30 mil linhas; lista de 2.000 itens,
  navegação de até 64 níveis. Grant e diretório persistem após morte do processo;
  buffers não salvos, undo, viewport e scroll por diretório permanecem somente
  durante a vida do processo, como na IDE anterior. Salve antes de encerrar.

Contratos consultados: [SAF e autorização persistente](https://developer.android.com/training/data-storage/shared/documents-files),
[modos de escrita do ContentResolver](https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri,%20java.lang.String))
e [URI retornada por renameDocument](https://developer.android.com/reference/android/provider/DocumentsContract#renameDocument(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String)).

A workspace L1A e suas preferências não são migradas nem apagadas. O Terminal
Local mantém `/system/bin/sh` e seu HOME privado; a autorização SAF da IDE não
lhe concede acesso POSIX à pasta compartilhada. SSH/SFTP/Cofre/Downloads/AV,
Core/PostgreSQL e serviços Fedora não recebem alteração/deploy.
Não foram adicionadas permissões de armazenamento, dependências de produto,
root, distribuição, symlinks ou compilador. `dev.myct` e targetSdk 36 preservados.

## Arquivos alterados

- `AndroidSharedDocuments.kt`: contrato do seletor, ContentResolver/DocumentsContract e preferências da árvore.
- `SharedWorkspace.kt`: navegação por IDs, criação/rename, snapshots, conflitos e escrita verificada.
- `SharedIdeModel.kt`: sessão compartilhada e preservação de buffer/undo/erros.
- `IdeSession.kt` e `IdeScreen.kt`: escolha de armazenamento e referências imutáveis de linhas no Sora existente.
- `TerminalModel.kt`: apenas construção da sessão IDE compartilhada e preferência da origem; lógica dos terminais intacta.
- `app/build.gradle.kts`: somente versão/code; manifest do produto e dependências preservados.
- `SharedWorkspaceTest.kt`, `SharedDocumentsInstrumentedTest.kt` e manifest exclusivo de testes.
- README, planejamento inaugural/local, decisões, diário e este relatório.

## Validação automatizada e artefatos

[Pré-release candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-l1d-v0.7.0-rc1)
com APK principal, APK de instrumentação opcional e
[SHA-256](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-l1d-v0.7.0-rc1/myct-l1d-SHA256SUMS.txt).
A atualização real no Moto permanece pendente.
Logs locais ignorados: `artifacts/myct-l1d-validation/`.

| Artefato | Tamanho | SHA-256 |
| --- | ---: | --- |
| `myct-l1d-0.7.0-debug.apk` (principal) | 18.308.720 bytes (~17,46 MiB) | `01f51e258e14bc3dd7a251d2c13d61d9da0f197f849e69e573938b3c0b33fa38` |
| `myct-l1d-0.7.0-androidTest.apk` (opcional, instrumentação) | 330.668 bytes | `9c52a3e5d4d872e5943a74345588e4b36c7087d2e7586ea6b950182f42e4d022` |

Certificado de desenvolvimento (SHA-256):
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Verificação APK Signature Scheme v2 PASS; certificado idêntico às candidatas
L1A/code 24 e L1C RC2/code 27. `aapt`: `dev.myct`, minSdk 26, targetSdk 36,
versionCode 28. Manifest/permissões de produto preservados. Bibliotecas nativas
no APK: `libmyct-pty.so` (bytes idênticos à L1B nas três ABIs) e a dependência
AndroidX Graphics Path preexistente. Nenhum asset/biblioteca WCPL/WAMR/WASI.

JVM final: **224 testes, 223 aprovados, 1 handshake SSH opt-in ignorado, zero
falhas/erros**. Inclui **21 testes L1D** e o SFTP real descartável via
`MYCT_TEST_LOCAL_SFTP=1` (subprocesso `sftp-server`, sem sshd ou login/autenticação).
Instrumentação: três testes L1D compilados, **não executados em Android**.
Build/assemble principal e testes + JVM + lint **PASS com `--offline`**.
Relatório final lint: `No issues found.` Nenhuma dependência instalada.
Ferramentas existentes: JBR 21.0.10, Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20,
SDK/build-tools 36/36.0.0, NDK 28.2.13676358 e CMake 3.22.1.
Rodada final: **2m47,60s**, CPU 843,12s usuário + 10,55s sistema, pico RSS
**2.009.936 KiB (~1,92 GiB)**, zero swaps no processo medido. Avisos de metaspace
não impediram a execução; limites Gradle preservados. Não são medições no Moto.
Upstream: 21 arquivos verificados (3 com patches documentados), quatro
reverse-checks PASS; `git diff --check` PASS.

Testes JVM novos exercitam seleção/persistência simuladas, IDs opacos,
criar/ler/escrever no mesmo documento sem duplicatas, trilha após nova instância,
navegação/retorno/ancestral removido, grants negados/revogados, conflitos por bytes
ou metadados, leitura instável, texto inválido/limites, capacidades do provedor,
colisões, truncamento/falha/releitura divergente, rename com novo ID, preservação
de buffers/undo, confirmação de troca e isolamento das três origens.

Testes instrumentados preparados no APK de testes: intent de árvore, persistência
de preferências, `ContentResolver`/`DocumentsProvider` real com fixtures geradas,
criação/listagem, `wt` com conteúdo menor, rename com novo ID e recusa de caminho
POSIX. **Compilar esses testes não prova o seletor do sistema nem o Moto.**
O provider de fixtures existe só no APK de testes e expõe somente cache gerado;
não faz parte do APK candidato. Sua declaração é protegida por MANAGE_DOCUMENTS,
como [exige o DocumentsProvider Android](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsProvider.java).
Somente o teste adota temporariamente essa permissão de shell para acessar suas
fixtures e a libera no finally; o produto não solicita essa permissão.

Comandos (SDK e caches já provisionados; não instalar dependências):

```sh
mkdir -p artifacts/myct-l1d-validation
MYCT_TEST_LOCAL_SFTP=1 ANDROID_HOME=/home/sam/Android/Sdk \
  android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 android/terminal/upstream/verify.py
for patch in android/terminal/upstream/*.patch; do git apply --reverse --check "$patch" || exit; done
git diff --check
```

Para instrumentação, em aparelho de testes explicitamente autorizado, instalar
os dois APKs sem limpar dados e executar apenas a classe desta fase:

```sh
adb shell am instrument -w -r \
  -e class dev.myct.SharedDocumentsInstrumentedTest \
  dev.myct.test/androidx.test.runner.AndroidJUnitRunner
```

Na inspeção inicial `adb devices -l` não encontrou dispositivo. Nenhum APK foi
instalado pelo agente; sem teste físico, emulador ou login SSH autenticado.
Medições de build são do host, não custo de RAM/bateria da edição no Moto.

## Roteiro físico do proprietário — obrigatório antes de merge

1. Atualizar por cima da RC2/code 27 usando a candidata code 28. **Não desinstalar
   nem limpar dados.** Conferir projetos privados existentes e configurações.
2. IDE → Local → Pasta compartilhada → Selecionar pasta. No seletor Android,
   escolher/criar **armazenamento interno → Documents → MyCT** e autorizar.
   Não escolher nuvem: um grant SAF de nuvem não garante arquivo POSIX no Termux.
   Raízes bloqueadas/Download/Android/data/obb não são contornadas.
3. ⋮ → Nova pasta/projeto → `ProjetoC`; entrar na pasta. ⋮ → Novo arquivo →
   `teste.c`; abrir no Sora, escrever e tocar **Salvar**:

   ```c
   #include <stdio.h>
   int main(void) {
       puts("My-CT L1D: primeiro teste");
       return 0;
   }
   ```

4. Conferir indicador limpo, navegar/subir/retornar e posição da lista. Fechar e
   reabrir My-CT: a pasta autorizada e a trilha devem persistir. Abrir `teste.c`
   novamente e conferir o conteúdo salvo, sem novo pedido de grant.
5. No Termux, com **Clang previamente instalado pelo proprietário**:

   ```sh
   termux-setup-storage
   cd ~/storage/shared/Documents/MyCT/ProjetoC
   ls
   cat teste.c
   clang teste.c -o "$HOME/teste"
   "$HOME/teste"
   ```

   Conceder ao Termux a permissão solicitada. O executável fica no HOME do
   Termux porque o armazenamento compartilhado normalmente não executa binários.
   Instalação do Clang e permissão do Termux são externas à L1D.
6. Voltar ao Sora, alterar a mensagem para `My-CT L1D: segundo teste`, salvar,
   retornar ao Termux, repetir `cat`, compilação e execução. `ls` deve continuar
   mostrando o mesmo `teste.c`, sem `teste(1).c`/cópias manuais.
7. Conferir Sora: linhas, destaque C, undo/redo, busca/substituição, grade/IME.
   Renomear um arquivo de teste e uma pasta sem documento aberto, sair/retornar,
   confirmar nomes e IDs pelo uso normal. Cancelar seletor e diálogo de descarte.
8. **Conflito:** abrir/salvar a versão inicial no Sora; deixar uma edição não
   salva. No Termux alterar o arquivo com outro conteúdo. Tocar Salvar no My-CT:
   deve recusar, manter buffer/undo e conservar o conteúdo externo. Copiar o
   buffer antes de confirmar descarte/reabrir; então conferir a versão externa.
9. **Permissão:** com um buffer não salvo, revogar o grant da árvore nas
   configurações Android (ou em dispositivo de testes com revogação explícita).
   Não usar “Limpar dados”. Atualizar/tentar salvar: erro e nova seleção,
   preservando buffer. Selecionar novamente a **mesma** pasta e conferir conflito
   antes de salvar. Se o aparelho não oferecer revogação isolada na UI,
   registrar essa limitação; não simular aprovação física nem apagar dados.
10. Alternar para Projetos privados e conferir criação/abertura/salvamento e
    arquivos anteriores. Conferir Terminal Local L1B e sessão SSH real existente,
    inclusive terminal/IDE remotos, sem alterar serviços Fedora.

Registrar versão Android, build/code, resultado de cada passo e saídas do Termux
sem credenciais/caminhos sensíveis. Falha em gate real mantém a L1D **não aceita**.

## Retomada

Retomar com árvore Git limpa:

```sh
git switch myct-l1d-shared-storage
git pull --ff-only
```

Continuar nesta branch, usando o relatório e os resultados finais. Não transportar
L1C nem reinstalar codes 26/27. Após PASS físico expresso, decidir integração
empilhada #28 → #29 → L1D; nenhuma integração está autorizada nesta entrega.
Refinamento posterior e MYCT-6 continuam fora do escopo.

## Revisão antes da entrega

A auditoria identificou dois cuidados: capturar a URI de cada linha antes de
LazyColumn agendar keys/callbacks, para a troca de diretório não resolver um
índice contra outra lista; e proteger o DocumentsProvider de testes com a
permissão exigida pelo Android (somente APK de testes). Há teste JVM que mantém
referências das linhas anteriores enquanto publica outra pasta.

A primeira rodada limpa encontrou timeout no teste L1B já existente
`LocalTerminalModelTest.readerAndWriterFailureStopChildAndAllowRestart`, linha 143,
esperando ERROR. Hipótese registrada: intermitência de concorrência do teste,
sem causa determinada. O método isolado passou em 14 s, **sem editar o terminal,
o teste ou seu timeout**. XML original e hipótese preservados nos logs locais;
a rodada final completa passou (223 PASS/1 ignorado), sem apagar esse histórico.

O caso de refresh com novo ID e mesmos nome/tamanho/data também tem teste. A
lista compartilhada publica nova referência e revisão para a composição não
reutilizar URI antiga por igualdade apenas dos metadados de apresentação.


## Fechamento físico posterior — proprietário, 17/10/2026

**Gate principal aprovado no Moto G06 com escopo limitado:** Android `Documents/My-CT/Project-C/teste.c` criado/editado/salvo no Sora via SAF, localizado pelo Termux, compilado por GCC e executado com saída `My-CT e Termux funcionando juntos!`. A execução direta de `./teste` na pasta compartilhada falhou com `Permission denied`; funcionou ao gerar binário no HOME do Termux com `gcc teste.c -o "$HOME/teste" && "$HOME/teste"`. Isso é restrição de execução da área compartilhada, não falha da IDE ou da compilação. O proprietário autorizou **fechamento do MVP e merge**, sem novos recursos de compilação interna.

**Testes ainda pendentes de aceite físico individual:** segunda edição/recompilação do mesmo documento, revogação e conflito SAF, persistência de grant após reiniciar, rotação/clipboard do terminal, regressão SSH/SFTP. Não declarar PASS desses cenários. [Relatório consolidado](MYCT-L1-FECHAMENTO.md). PR #31 integrado à main por merge `e4d0a57ae130963a2ff087c5641f8bc709b98036`. Próxima atividade: pequena rodada de UX.


## Fechamento físico — 17/10/2026 (aceite do proprietário)

**Fluxo principal PASS no Moto G06:** o proprietário selecionou/autorizou pasta compartilhada no Android, criou `Documents/My-CT/Project-C/teste.c` pelo Sora, salvou o documento original, acessou-o pelo Termux e compilou com GCC. O binário gerado diretamente na pasta compartilhada não pôde ser executado (`Permission denied`, restrição esperada de execução nesse armazenamento). Recompilação com `gcc teste.c -o "$HOME/teste" && "$HOME/teste"` passou e exibiu `My-CT e Termux funcionando juntos!`. A IDE também mostrou o arquivo salvo e o binário gerado no armazenamento compartilhado.

**Escopo do aceite:** interoperabilidade essencial Sora → armazenamento compartilhado → Termux → compilação/execução: **PASS**. Não alegar que todas as verificações secundárias (revogação da permissão, conflitos externos, renomeação e regressão remota após esta versão) foram executadas; permanecem pendências não bloqueantes para uso pessoal. A experiência de compilar via Termux e direcionar o executável ao HOME é inconveniente; registrar como limitação de UX, não como defeito de compilação do My-CT. O proprietário solicitou **encerrar a L1 e executar apenas pequeno refinamento de UI/UX**, sem nova infraestrutura de compilação. PRs #28/#29/#31 já integrados à main; PR #30 cancelado sem merge; issue #27 encerrada.
