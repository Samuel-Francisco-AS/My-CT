# My-CT R1 — Rodada 2/3: implementação da correção de lentidão

Data: 22/10/2026. [Issue #61](https://github.com/Samuel-Francisco-AS/My-CT/issues/61).
Base: `main`, `10172a2ef5dcd171c7d7ce602b94e8a2d62a74a9`.
Branch: `fix/myct-r1-space-metadata`.
Especificação: [investigação concluída e aprovada](MYCT-R1-RODADA-1-INVESTIGACAO-LENTIDAO.md).

Implementação da opção 2 em uma única entrega: inventário leve por metadados,
cache somente em memória por pacote e seleção local. Não houve nova investigação,
implantação de helpers pessoais, exclusão no acervo, publicação de APK ou merge.
O aceite de desempenho no Moto G06 permanece para a rodada 3/3; a issue #61
continua aberta.

## Comportamento implementado

`space()` não chama `digest_tree()`, `common.fingerprint()`, `remote_objects()` ou
Rclone. Enumera caminhos, diretórios, tamanhos, inodes, aliases, blocos, vínculos
Media e metadados Transmission. Os pequenos manifesto/metainfo locais continuam
conferidos; o SHA-256 usado para identificar o inventário incide sobre JSON de
controle, não sobre o conteúdo dos vídeos.

A associação de um hardlink atual usa os inodes e o contrato Media existente.
Quando a origem já não existe, somente a identidade registrada comprovável por
metadados permite associá-lo ao pacote. Um arquivo substituído ou sem identidade
comprovada permanece divergente/protegido: não há fallback de hashing oculto.

O inventário enumera todos os vínculos conhecidos, inclusive os não elegíveis.
Falha de enumeração é explícita e impede a liberação; não transforma o que foi
possível examinar em uma autorização parcial. Estrutura/tamanho divergentes nas
árvores também bloqueiam a operação.

A resposta informa `content_verified:false`, `remote.verified:false` e
`remote.revalidated:false`. A data remota registrada no catálogo é histórica.
A tela identifica a consulta como inventário de metadados e informa que conteúdo
e Drive não foram revalidados. Não exibe bytes, porcentagem ou velocidade de
leitura fictícios durante essa consulta.

## Contrato versionado e seleção

A resposta tem `version:2` e um objeto `inventory` independente da seleção:

- `paths`: caminhos absolutos, papéis original/restored/library, origem relativa
  no pacote e metadados; os aliases de biblioteca também têm seu nome relativo;
- `inodes`: `(dev, ino)`, `nlink`, tamanho lógico, blocos físicos conhecidos,
  caminhos conhecidos e quantidade de links desconhecidos;
- `trees`, `directories`, `library`, `library_complete`, estado Media/RPC e
  impedimentos de inventário;
- indicadores de integridade e disponibilidade remota não revalidadas.

O token usa o domínio `space-inventory-v2`, instante de emissão e JSON canônico do
inventário. Seleção, caminhos derivados da seleção, estimativas e telemetria não
mudam esse token. Tokens antigos não correspondem ao novo cálculo.

`selection_plan()` é a projeção pura no servidor. Android faz a mesma operação
com tipos de inventário e inode: caminhos das árvores mais os links opcionais
escolhidos, sem ampliar o escopo para outros links. Os blocos são somados uma vez
por inode; só são candidatos à liberação se todos os seus nomes forem removidos,
sem links desconhecidos. A soma usa conjuntos de caminhos no Android e operações
com detecção de overflow. Economia garantida continua zero: `st_blocks` não prova
extents exclusivos nem liberação imediata no Btrfs.

O protocolo de argumentos existentes é preservado: `--preview`, `--confirm`,
`--remove-library` explícito por caminho e `--confirm-library` quando necessário.
A resposta também conserva a projeção de seleção solicitada para consumidores da
CLI; o consumidor novo deriva a seleção corrente do objeto `inventory`.

Respostas de contrato antigo podem ser vistas no aplicativo, mas não permitem
liberação pelo consumidor novo. Atualizar o app não atualiza os helpers: o uso
real do contrato novo depende da atualização supervisionada na rodada final.

## Cache e confirmação no Android

`CloudModel` guarda inventários por pacote somente em memória, com validade de
até 600 segundos desde a emissão do token; edições de seleção não renovam esse
prazo. Há expiração na consulta/navegação e na atualização periódica do detalhe.
A verificação de validade também ocorre ao abrir e confirmar a ação.

Depois da primeira carga, marcar/desmarcar apenas altera a seleção e projeta
novamente caminhos/estimativa. Não chama SSH, RPC, Drive ou outra tarefa Fedora.
`consultSpace()` reutiliza um inventário válido; o botão explícito passa
`force=true` para permitir atualização deliberada. Não há obrigação de tocá-lo
após mudar os checkboxes.

Navegar e voltar, sair da composição ou ir para segundo plano preserva o
inventário válido e a seleção visual. Esses eventos revogam o diálogo aberto e a
identidade de autorização de uma consulta ainda pendente. Resposta tardia ou
histórica é consultiva: não abre diálogo, não arma liberação e não substitui um
cache vivo mais recente. Uma nova ação explícita pode abrir um novo diálogo sobre
um inventário ainda válido sem outra carga.

Reconexão, falha de transporte, mudança relevante no catálogo e início de uma
operação que altera o pacote invalidam seu cache executável. Alterações externas
não observadas pelo app são detectadas pela revalidação do servidor.

`beginRelease()` congela o plano exibido. O diálogo mostra os caminhos completos,
o escopo sobre o Transmission e a consequência para os links escolhidos. Trocar a
seleção revoga apenas esse diálogo; o inventário continua disponível. Confirmar
usa o plano congelado, com verificação de identidade, seleção, conexão e validade.
A confirmação de links opcionais ocorre no mesmo diálogo, sem nova consulta.

`release()` do modelo é o único caminho de envio dessa ação: `start()` recusa
pedidos de liberação diretos, inclusive uma tentativa de reaproveitar um trabalho
histórico. Restaurar, recuperar, arquivar e as demais ações independentes continuam
com seus próprios requisitos, sem exigir consulta de espaço.

## Execução da liberação Cloud

Sob os locks de catálogo, nomeação e Media já existentes:

1. Validar confirmação/prazo, reconstruir o inventário por metadados, comparar o
   token e projetar exatamente a seleção recebida. Nenhum impedimento é aceito
   como autorização para executar um subconjunto.
2. `verify_release_content()` lê o conteúdo uma vez por `(dev, ino)` distinto do
   conjunto a remover e compara MD5/SHA-256 com o manifesto. Dois nomes do mesmo
   inode compartilham a leitura; uma árvore restaurada independente é outro inode.
3. `remote_objects()` confere uma vez o pacote remoto completo, incluindo dados,
   manifesto e metainfo. Falha de conteúdo ou backup ocorre antes das mutações
   RPC e da exclusão local.
4. Recoletar metadados após a verificação longa, sem rehash. Só então persistir o
   journal e pausar/remover a entrada Transmission com `delete_local_data:false`.
5. Revalidar metadados após RPC. Antes de cada `unlink`, comparar caminho,
   identidade, tamanho, tempos, modo, proprietário e contagem esperada de links;
   abrir descritor do alvo com `NOFOLLOW` e conferir o mesmo inode. Nenhum vídeo é
   relido nessa parte.
6. Registrar cada caminho efetivamente removido e acompanhar o `ctime`/`nlink`
   resultante do próprio `unlink` pelo descritor ainda aberto, para reconhecer os
   aliases seguintes. Mudança incompatível, interrupção ou falha posterior ao
   journal preserva o tratamento de liberação incerta e impede repetição automática.

Um replay de token já executado é somente no-op com a mesma seleção; outra
seleção exige inventário novo, pois a primeira execução alterou os dados.

O prazo de 600 segundos é conferido antes e após aquisição dos locks, ao aceitar a
execução. A conferência de conteúdo aceita pode durar mais; não se renova o token
nem se exige que uma operação íntegra termine dentro do prazo usado para aceitar
o pedido. Mudanças durante a execução continuam sujeitas às verificações.

O trabalho efetivo tem progresso de leitura real e fases de conferência remota,
pausa, retirada da entrada e remoção dos caminhos. Percentual/ETA, quando
mensuráveis, dizem respeito à leitura, sem anunciar uma porcentagem global falsa.
Não é uma segunda prévia obrigatória. Cancelamento destrutivo e retry automático
não foram introduzidos.

## Snapshot e escopo preservado

`myct-cloud.snapshot()` carrega módulo e índice Media uma vez e compartilha esse
contexto entre os pacotes do snapshot. A carga continua efêmera; não há daemon,
banco, índice persistente novo ou réplica durável no Android.

Ao observar conclusão por polling, `CloudModel` aproveita o resultado recebido e
faz um snapshot de catálogo, evitando buscar o mesmo resultado novamente. Ver
resultado ou observar término pelo cancelamento mantém a atualização de catálogo
já exigida pelos testes anteriores.

Arquivamento, restauração e recuperação mantêm suas conferências próprias.
`myct-media`, `myct-media-cleanup` e os consumidores de exclusão local não foram
alterados. A exclusão local permanente continua independente de backup e não
herda os requisitos Cloud.

## Validação de implementação

Os testes usam arquivos pequenos e hardlinks descartáveis, transporte Drive/RPC
simulado e relógio controlado quando necessário. Não usam vídeos pessoais para
exclusão. A validação operacional completa roda em container Fedora 44 rootless,
sem rede nem mounts pessoais, como `tester`, com limite de 1536 MiB, 1 CPU e 1024
PIDs. As fontes são copiadas por tar; nenhum helper é instalado no Fedora pessoal.

Resultados finais e comandos são registrados ao concluir as verificações deste
mesmo conjunto de alterações:

| Verificação | Resultado |
| --- | --- |
| Operacional completo, Fedora descartável | **286 PASS, 0 FAIL, 2 SKIP** (288 casos), 59,453 s |
| SETUP, fontes e bundle determinístico | **58 PASS, 0 FAIL, 0 SKIP**, 6,879 s |
| JVM Android completo | **334 PASS, 0 FAIL, 2 SKIP** (336 casos) |
| JVM Cloud final, incluindo proteção de cache contra histórico de erro | **37 PASS, 0 FAIL, 0 SKIP** |
| Compilação do teste instrumentado | PASS; não executado em aparelho |
| Manifesto code 52 e geração do bundle | PASS; sem montagem/publicação de APK |
| Lint Android | PASS: 0 erros, 1 warning e 2 hints preexistentes |
| `git diff --check` | PASS |
| Moto G06, tempo de toque até prévia | Não executado nesta rodada |
| Drive real / liberação pessoal | Não executados nesta rodada |

Provas indispensáveis acrescentadas/atualizadas:

- Prévia com `fingerprint()` e Rclone substituídos por exceções, mais guardas de
  abertura/leitura de payloads; originais e restauração independente presentes.
  Mesmo token para seleções diferentes com o relógio fixo.
- Origem ausente e substituição de vínculo: associação comprovada por metadados ou
  classificação protegida, sem hash oculto.
- Liberação com contador de leituras por inode que falha em duplicidade; uma
  conferência remota; progresso da leitura efetiva; seleção diferente da usada na
  consulta aceita com o mesmo token, desde que explicitamente confirmada.
- Mudança de conteúdo de mesmo tamanho detectada na execução; mudança após hash,
  com `mtime` restaurado, bloqueada pelo inventário/`ctime` antes das mutações RPC.
- Testes JVM contam chamadas: 13 alterações de seleção, novo cálculo e reuso da
  consulta sem nenhum transporte adicional. Cobrem diálogo congelado, navegação,
  segundo plano, expiração sem renovação, reconexão, histórico e respostas tardias.
- Inventário incompleto ou vínculo não comprovado não habilita liberação parcial;
  links desconhecidos não entram na economia estimada.
- Snapshot com dois pacotes comprova uma carga compartilhada do índice Media.
- Suítes anteriores de archive/restore, RPC incerto, remoção parcial, Media e SETUP
  permanecem como regressão.

A primeira execução JVM expôs uma regressão na atualização do catálogo ao abrir
um trabalho concluído; foi corrigida preservando uma consulta de catálogo e sem
reintroduzir a busca redundante do resultado por polling. Um teste novo também
assumia uma única tarefa total, embora a fixture preserve histórico; a asserção
foi corrigida para contar a tarefa ativa. Na preparação inicial do container,
SETUP não recebeu as fontes `server/` do manifesto; a cópia foi corrigida antes da
validação final, sem mudança de produto para contornar o erro da fixture.

Os dois SKIP operacionais são as provas legadas de unidades transitórias sem
user manager/linger no container; os dois JVM são SSH/SFTP opcionais não
habilitados contra o Fedora pessoal. Não foram contados como PASS. A última
proteção contra resposta histórica de erro apagar cache vivo recebeu também a
verificação Cloud dirigida final.

Comandos de validação (as duas primeiras linhas dentro do container descartável,
com `ops/` e as fontes `server/` do manifesto copiadas):

```sh
python3 -B -m unittest discover -s ops/tests -p 'test_*.py' -v
python3 -B -m unittest discover -s ops/setup/tests -p 'test_*.py' -v
./android/gradlew -p android :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug --offline --max-workers=2 -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
./android/gradlew -p android :app:testDebugUnitTest --tests 'dev.myct.Cloud*' :app:compileDebugAndroidTestKotlin :app:lintDebug --offline --max-workers=2 -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
./android/gradlew -p android :app:processDebugMainManifest :app:buildSetupBundle --offline --max-workers=2 -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
```

Ferramentas existentes observadas: Python 3.14.7, Java 21.0.10, Gradle wrapper
8.14.3, Podman 5.8.7 e SDK Android 36. Nenhuma dependência instalada. O limite do
container é limite da validação, não medição de RAM da prévia em produção.

Logs locais ignorados pelo Git: `artifacts/r1-space-round2/`.

## Limitações concretas e retomada

Não há medição pós-correção no Moto nem promessa numérica de tempo. Testes
simulados e compilação não constituem aceite físico. A causa cara saiu da prévia,
mas latência SSH, entrega da tarefa, RPC e cadência de polling continuam existindo.

A prévia não garante integridade atual nem disponibilidade do backup. A liberação
Cloud ainda pode demorar pela única leitura forte e pela conferência remota, ou
ser recusada se os dados mudarem. Locks são cooperativos e as verificações de
metadados não prometem atomicidade contra escritores externos arbitrários.
Reflinks, snapshots e descritores abertos podem reter espaço.

Fontes e testes estão prontos para a rodada 3/3. A identidade de build foi preparada
no código como **0.13.5-cloud-space-rc1 / code 52**, preservando applicationId, ABIs
e configuração de assinatura. Nenhum APK dessa versão foi publicado e os helpers
pessoais não foram atualizados; não há merge nem encerramento da issue #61.
A validação física e eventual atualização supervisionada pertencem à única
rodada técnica final já determinada pelo proprietário, sem novo planejamento ou
nova investigação.
