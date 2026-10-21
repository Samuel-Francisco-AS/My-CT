# MYCT-CLOUD-R1 — diagnóstico após teste físico: Nuvem e biblioteca local

Data: 21/10/2026. Inspeções no Fedora pessoal; coleta sanitizada registrada às
15:53–15:55 UTC (12:53–12:55 em Fortaleza), com reconferência ao finalizar.
**Somente diagnóstico; nenhuma correção
implementada.** O único arquivo criado nesta missão é este relatório local.

Código examinado: candidata `f239bee9a77042b0603d5a06f738bb723bb5e362`,
`dev.myct`, `0.13.2-cloud-r1-rc1`, code 49. Checkout em
`ad82d34a890bede7c433e8886ff8d293aee68c3c`, que acrescenta documentação ao
código auditado. [PR #60](https://github.com/Samuel-Francisco-AS/My-CT/pull/60)
consultada: **OPEN, draft, sem merge**, mesmo HEAD documental. `main` permanece
em `54618d91182efbd81c5c1c3124ab8a418d89ebe7`.

## Conclusão e ordem das causas

1. **Causa comum confirmada:** o índice Media persiste `st_dev=50`, mas 265
   arquivos presentes agora têm `st_dev=51`, com os mesmos números de inode.
   As verificações de gerenciamento recusam essa identidade persistida antiga.
   Em 264 deles, origem atual, ID e inode estão corretos; apenas o device do
   registro impede a elegibilidade. Um arquivo apresenta também outra situação,
   descrita abaixo. Não é uma falha global no reconhecimento dos diretórios.
2. **Nuvem:** os 13 links de Witch Hat Atelier existem, são hardlinks das origens
   esperadas e têm os IDs corretos no índice. Todos os 13 registros guardam o
   device antigo. O bloqueio `LIBRARY_SELECTION` é coerente com o contrato atual;
   não houve perda dos 13 caminhos no pedido nem no resultado da seleção.
3. **Lista parcial:** o Fedora monta `library` e valida a seleção no mesmo laço.
   A primeira reprovação lança uma exceção e encerra a enumeração após uma linha.
   O Android substitui suas opções pelas linhas desse resultado bloqueado, sem
   verificar se o inventário está completo. Os 13 selecionados continuam em outro
   estado do modelo, explicando **13/256 selecionados e uma única opção visível**.
4. **Segundo plano:** `ON_STOP` revoga consentimento por política explícita.
   Isso é independente do índice divergente e da lista parcial. Mesmo corrigindo
   esses defeitos, uma autorização revogada não pode voltar por histórico/polling.

Não há evidência de que seja preciso relaxar proteções ou habilitar o botão à
força. Não foi constatada perda de mídia nesta inspeção de metadados.

## Método e limites

Foram lidos código, recibos privados existentes, manifestos e índice Media;
executados `lstat/stat`, enumeração de nomes e RPC **`torrent_get`**. Os módulos
instalados foram importados com Python `-I -B`; foram usadas as funções de leitura
`records`, `media_files` e `cleanup.snapshot` com dados em memória. Não foram
chamados os despachantes de comandos de Media/cleanup, que podem abrir locks ou
salvar estado mesmo em certos fluxos de consulta.

Não foram executados `space`, `release`, `cleanup apply`, sync, approve,
reimportação, restauração, upload, exclusão, instalação, controle de serviços ou
ADB. Não foram lidos conteúdos dos vídeos nem realizada nova validação remota.
O estado remoto citado é o atestado pelo recibo já existente, não uma consulta
nova ao Drive. Os 16 recibos encontrados estavam `succeeded`, sem tarefa ativa ou
incerta nessa amostra. `succeeded` significa que a consulta terminou; não significa
que a liberação foi autorizada.

As inspeções são amostras consecutivas de leitura, sem bloqueio global nem pausa
de serviços. Os totais repetidos permaneceram iguais. A reprodução da tela no
Moto é o relato do proprietário; a associação com as transições Android decorre
da leitura do código, não de captura/instrumentação nova do aparelho.

Na reconferência final, os hashes SHA-256 dos catálogos de arquivos, de torrents
arquivados e do índice Media permaneceram iguais aos da comparação final iniciada
antes da redação. Os totais continuaram em 279 arquivos/14 elegíveis e 16 tarefas
concluídas. Não há diferença de código em `ops`/`android` entre o checkout e o
commit auditado. Git registra somente este novo relatório, ainda sem commit.

Os três helpers diretamente implicados correspondem ao Git auditado:

| Helper instalado | SHA-256 |
| --- | --- |
| `myct-media` | `7a2f1b307f934fb36f7616819b07757e4c4c21cd35c97cb2e1fcefbc4ee237d9` |
| `myct-media-cleanup` | `f208e520af85286b0cd104923e8e32f53c5817b83e133e91d3e2d66897873b54` |
| `myct-archive-torrent` | `f801b8f2be4308188bb35e8b613e65d32ec658fdcf5cbb19af8d2c02ef46c8cd` |

Não foram publicados tokens de prévia, confirmações, credenciais, URLs privadas,
catálogos brutos, nomes de arquivos de origem ou caminhos pessoais absolutos.

## Biblioteca local: primeira condição comum de inelegibilidade

| Etapa de leitura | Quantidade |
| --- | ---: |
| Torrents retornados diretamente pelo Transmission | 32 |
| Torrents aceitos por `myct-torrent.records()` como gerenciados | 32 |
| Torrents concluídos, metadados completos, sem erro | 28 |
| Torrents ainda incompletos, com metadados completos | 2 |
| Torrents sem metadados completos | 2 |
| Torrents com erro RPC de torrent diferente de zero | 0 |
| Torrents que fornecem vídeos por `myct-media.media_files()` | 28 |
| Caminhos de vídeo aceitos / inodes de origem distintos | 290 / 290 |
| Arquivos regulares na árvore Media / vídeos no snapshot | 279 / 279 |
| Symlinks de arquivos encontrados nessa árvore | 0 |
| Tamanho lógico somado dos arquivos da biblioteca | 130.409.669.078 bytes |
| Arquivos com `eligible=true` | 14 |
| Arquivos com `eligible=false` | 265 |
| Arquivos com origem atual comprovada por device + inode | 278 |
| Arquivos com `nlink=2` | 279 |
| Registros no índice Media | 307 |
| Registros cujos caminhos estão presentes / ausentes | 279 / 28 |
| Registros presentes com mesmo inode, device antigo 50 → 51 | 265 |
| Registros presentes com device e inode atuais | 14 |

Todos os `download_dir` dos 32 torrents são filhos diretos do diretório gerenciado
`~/Downloads/My-CT/Torrents`, conforme o filtro de `media_files()` (linhas 110–134).
`records()` também aceita seus diretórios segundo as regras de hash legado ou
nomeação registrada (linhas 189–206 de `ops/myct-torrent`). Não foi encontrada
divergência de raiz ou rejeição global por nomeação.

A condição que elimina os exemplos vem **depois** desses filtros. Em
`ops/myct-media-cleanup:78–85`, `snapshot()` encontra a origem por `(dev, ino)`,
mas, quando existe registro Media, exige igualdade exata de
`(info.dev, info.ino, info.id)` com os dados atuais. O campo de resposta
`managed=true` significa apenas que há um registro; não atesta que ele é válido.

Distribuição sem dupla contagem dos 279 arquivos:

- **14 elegíveis:** origem atual e registro coerentes.
- **264 bloqueados somente pelo device persistido:** origem atual é o mesmo
  inode, o ID é exato, o inode salvo é o atual, `nlink=2`, mas `dev` salvo é 50
  e o atual é 51.
- **1 bloqueado por falta de correspondência à origem RPC atual**, também com
  device salvo antigo. Seu outro hardlink foi localizado na árvore original
  indicada pelo manifesto de um pacote arquivado/verificado. O Transmission
  conserva o torrent completo, mas aponta para outra árvore, cujo arquivo é
  outro inode. Não é um arquivo sem qualquer origem conhecida; não é elegível
  pela regra que exige a origem atualmente reconhecida pelo RPC.

Os 28 registros de caminhos ausentes foram contabilizados, sem limpeza ou
conclusão de que representem perda: registros históricos não provam que os
caminhos deveriam continuar presentes. Não houve necessidade de alterá-los para
explicar o bloqueio dos arquivos existentes.

Há 17 grupos de pasta no snapshot; 16 não têm nenhum elegível e um tem 14.
Portanto, a observação de bloqueio generalizado é confirmada, com essa exceção
atual. O denominador em `MediaStorageScreen.kt:76–89` usa **somente elegíveis**;
`0/0 selecionados` não significa pasta vazia. O rótulo genérico em torno da linha
154 não distingue registro antigo de fonte ausente.

### Exemplos solicitados

| Grupo | Arquivos presentes | Elegíveis | Constatação |
| --- | ---: | ---: | --- |
| Overlord — série | 39 | 0 | Todos ligados às origens atuais; 3 torrents completos, metadados disponíveis, sem erro; device salvo antigo |
| Overlord — Season 03 | 13, incluídos nos 39 | 0 | Mesmo device/inode das 13 origens; 1 torrent completo; `nlink=2`; registros com ID/inode corretos |
| Filme de Overlord, contado separadamente | 1 | 0 | Origem atual reconhecida, device salvo antigo |
| Crystal Lake | 8 | 0 | Origem atual reconhecida, device salvo antigo |
| Dragões — Corrida até o Limite | 27 | 0 | Origem atual reconhecida, device salvo antigo |

Os episódios de Overlord não são cópias independentes nem hardlinks órfãos na
amostra atual. São vínculos físicos reconhecíveis, recusados pela comparação
com o índice antigo. Para o arquivo excepcional fora desses exemplos, só a
diferença de inode para a origem RPC não prova cópia byte a byte; nenhum hash de
conteúdo foi calculado nesta missão.

## Nuvem: pedido, recibo e os 13 vínculos

Pacote Witch Hat Atelier: catálogo `verified`, **19.349.308.130 bytes**, 19 arquivos
no manifesto e 19 na descrição do torrent; 13 vínculos no manifesto original,
13 registros Media associados ao infohash, zero vínculos externos declarados.

Último recibo `space`, identificador abreviado **`6e3c9b53…`**:

| Campo sanitizado | Valor |
| --- | --- |
| Início / término UTC | 21/10/2026 15:21:38,637 / 15:24:48,951 |
| Duração | 190,313 s |
| Estado / `ok` | `succeeded` / `true` |
| Pedido persistido | 13 pares `--remove-library CAMINHO`, sem duplicatas |
| Seleção retornada em `remove_library` | 13; conjunto exatamente igual ao pedido e aos 13 caminhos do manifesto |
| Lista retornada em `library` | **1** linha, presente, ligada ao pacote e selecionada |
| Linhas de biblioteca em `remove_paths` | **13**, todas com `managed=false` |
| Vínculos desconhecidos no agrupamento de inodes | 0 |
| Bloqueios / liberação | `[LIBRARY_SELECTION]` / `can_release=false` |
| Remoto no recibo | Verificado, incluindo manifesto e metainfo |
| Árvore original no recibo | Presente e verificada |
| Árvore de restauração no recibo | Ausente |

As linhas de `remove_paths` são um **plano**, não prova de remoção. Nenhuma
liberação foi executada nesta missão. Há uma diferença essencial entre os 13
caminhos da seleção retornada e a lista descritiva `library`, que ficou parcial.

Cada alias abaixo corresponde à ordem lexicográfica dos 13 caminhos do pedido;
não publica os nomes pessoais. Em **todas** as linhas: arquivo regular presente,
origem original presente, mesmo device/inode entre link e origem, ID Media exato
(`infohash:origem relativa`), `nlink=2`, registro existente e inode salvo igual ao
atual. O único campo divergente nesses registros é o device:

| Alias | Inode do link e da origem | Device salvo → atual | Gerenciamento exato aceito hoje |
| --- | ---: | --- | --- |
| E01 | 4057639 | 50 → 51 | Não |
| E02 | 4057627 | 50 → 51 | Não |
| E03 | 4057625 | 50 → 51 | Não |
| E04 | 4057626 | 50 → 51 | Não |
| E05 | 4057630 | 50 → 51 | Não |
| E06 | 4057641 | 50 → 51 | Não |
| E07 | 4057631 | 50 → 51 | Não |
| E08 | 4057629 | 50 → 51 | Não |
| E09 | 4057623 | 50 → 51 | Não |
| E10 | 4057624 | 50 → 51 | Não |
| E11 | 4057628 | 50 → 51 | Não |
| E12 | 4057633 | 50 → 51 | Não |
| E13 | 4057640 | 50 → 51 | Não |

Assim: **13 fisicamente comprovados**, **13 registros divergentes**, **0 sem
registro**, **0 ausentes**, **0 aceitos como gerenciados pela igualdade atual**.
As duas primeiras contagens descrevem aspectos diferentes dos mesmos arquivos.

### Evidência temporal e hipótese de origem do device divergente

Recibos anteriores de 01:13 e 01:18 UTC registram os 13 links com device 50,
seleção 13/13, sem bloqueio, em 216,294 e 231,407 s. O boot atual começou às
13:56:44 UTC. Depois desse boot, os recibos e os `stat` atuais registram device
51. Às 15:16 UTC uma consulta **sem seleção** retornou 13 linhas e nenhum bloqueio
(221,371 s); às 15:21 a consulta **com seleção** bloqueou.

Está confirmada a mudança do número observado entre as amostras e o uso desse
número antigo como identidade persistente. A atribuição exata da mudança ao boot,
remontagem ou instanciação do subvolume é **inferência**, não evento de montagem
reconstituído. `/home` usa Btrfs. Não se deve resolver isso comparando apenas
inode: no Btrfs, o namespace é por subvolume, conforme a
[documentação oficial sobre identidade de inodes](https://btrfs.readthedocs.io/en/latest/Subvolumes.html#inode-numbers).

### Por que só uma opção aparece

Em `ops/myct-archive-torrent:928`, `managed` exige igualdade integral do registro
com `{id, dev, ino}` atual. O scan encontra os 13 links; por isso eles aparecem
em `remove_paths`. No laço de `library` (linhas 932–946), a linha é acrescentada
antes de `need(matching and matching['managed'], 'LIBRARY_SELECTION', …)`.
O primeiro selecionado com registro divergente lança a exceção; o `except` externo
registra o bloqueio, mas não termina de preencher as outras 12 linhas.

Em `CloudModel.kt:247–249`, `readResult()` substitui `linkChoices[plan.id]`
incondicionalmente pela lista filtrada de `plan.library`. A linha parcial passa
nos critérios `present && linked_to_package`. `linkSelections` é separado e
permanece com 13, causando a apresentação inconsistente.

`CloudScreen.kt:25–31` invalida a prévia em `ON_STOP` e na saída da composição.
`canRelease()` ainda exige autorização da sessão, igualdade exata da seleção,
token/prazo e ausência de bloqueios. Voltar do segundo plano não deve restaurar
essa autorização. O recibo bloqueado impediria a liberação mesmo sem essa saída.

## Media: registros ausentes e registros antigos não são recompostos

Há três limitações confirmadas por código, distintas da existência física dos
arquivos:

1. `inspect()` (`ops/myct-media:236–244`) considera um ID presente no índice como
   previamente importado e retorna `existing/managed=true` sem revalidar o
   device/inode. Repetir o sync não corrige os 265 registros antigos.
2. `add_one()` (linhas 204–219) encontra destino com mesmo inode da origem e
   retorna `existing` **antes** de preencher `state['links']`. Um hardlink já
   existente sem registro não é adotado nem recomposto.
3. `approve` (linhas 323–332) só salva o índice se o resultado for `added`.
   Alterar apenas `add_one()` futuramente seria insuficiente sem rever esse fluxo.

No pacote de Witch Hat Atelier **não faltam registros**: há 13 divergentes. O
defeito de recomposição de registros ausentes é real no código, mas não explica
este caso concreto. Não foram executados sync/approve para tentar repará-lo.

## Propostas para uma missão futura, ainda não autorizadas

### Correção mínima de Nuvem

- Separar enumeração de validação: preencher todas as linhas conhecidas de
  `library`, acumulando a reprovação da seleção, sem interromper o inventário
  por `LIBRARY_SELECTION`. Se outro erro impedir completar o scan, declarar
  explicitamente a incompletude; não apresentar uma lista parcial como completa.
- O Android só deve substituir opções anteriores por inventário completo e
  coerente do mesmo pacote. Dados antigos preservados devem ser identificados
  como tal; não podem autorizar exclusão. Exibir divergência de gerenciamento
  como motivo compreensível, sem confundir com perda de seleção.
- Preservar todos os bloqueios. Nenhuma dessas mudanças torna os registros
  antigos válidos. Não escrever Media durante `space`; a reconciliação deve ser
  uma ação separada, supervisionada e autorizada. Após qualquer reconciliação,
  exigir **nova prévia** e confirmações, sem reaproveitar consentimento revogado.

### Gerenciamento local e reconciliação segura

Propor primeiro uma prévia de reconciliação de metadados, sem criar/mover/remover
mídia. Para cada entrada candidata, provar origem gerenciada, torrent completo e
sem erro, ID exato, caminhos seguros e arquivos regulares, e comparar **device e
inode atuais entre origem e biblioteca**, com `nlink` e ambiguidades avaliados.
Não basta trocar todos os `dev=50` por 51 nem ignorar o campo. Uma divergência de
inode, de origem, symlink, torrent incompleto ou vínculo ambíguo deve continuar
bloqueada. `ignored` e escolhas anteriores de remoção devem ser preservados.

Se autorizada posteriormente, a aplicação deve ter backup privado, locks e
rechecagem antes da escrita atômica. A proposta é limitada aos registros
comprovados, não uma reimportação automática. Para não repetir o problema em outro
boot, definir identidade persistente de filesystem/subvolume e migração explícita
do índice; o `(dev, ino)` atual continua obrigatório para provar o hardlink no
momento da operação. O formato final e a menor alteração segura precisam de
revisão antes de implementação; não se propõe persistir apenas inode.

O arquivo excepcional ligado à árvore original de pacote arquivado deve ficar
fora da reconciliação simples da origem RPC. Exige análise própria das duas
árvores e do contrato de arquivo, sem adotar o inode antigo como se fosse o atual.

É necessário separar as intenções do proprietário:

- **Retirar link da biblioteca:** remove apenas o nome Jellyfin, após comprovar
  os outros nomes e as regras de gerenciamento; em um hardlink com origem
  preservada, normalmente não libera os blocos do vídeo.
- **Excluir dados locais definitivamente:** pode remover o último nome, encerrar
  seeding e afetar o torrent inteiro. Exige o fluxo próprio, seleção completa e
  nova prévia autoritativa. Na Nuvem, mantém-se a verificação de backup remoto,
  metainfo e confirmação Jellyfin específica. Não se pode tornar essa operação
  disponível apenas porque a reconciliação do índice passou.

Uma melhora pequena da mensagem local pode mostrar total de arquivos, elegíveis
e motivo de proteção, evitando interpretar `0/0` como ausência de episódios.

## Regressões necessárias antes de qualquer nova candidata

Usar fixtures descartáveis e relógio controlável; não executar exclusão real.

- `ops/tests/test_cloud_space.py`: 13 links fisicamente válidos com device
  persistido antigo → 13 linhas, seleção 13/13, bloqueio mantido; falha no primeiro
  e no último link; entrada ausente/ID errado/inode diferente; erro de scan
  identifica inventário incompleto. Garantir zero escrita de catálogo por space.
- `CloudR1Test` / `CloudModelTest`: resultado bloqueado/parcial não reduz opções
  completas anteriores nem apaga seleção; 13 selecionados continuam verificáveis;
  troca de pacote e resposta tardia não misturam opções. Background, reconexão,
  expiração e seleção alterada continuam revogando consentimento. Atualizar ativo
  continua coberto por A01; nenhuma lista preservada habilita release sozinha.
- `test_media.py`, `test_media_cleanup.py` e `test_media_library.py`: reproduzir
  origem gerenciada e atual com índice do boot anterior; diferenciar device antigo
  de troca real de inode/ID, cópia independente, symlink e fonte incompleta.
  Cobrir ausência de registro, `existing`, `previously_imported` e persistência de
  `approve`, mantendo `ignored`. Simular dispositivo/subvolume distinto com inode
  numericamente igual para evitar adoção falsa.
- Reconciliação futura: prévia sem escrita, alteração concorrente, aborto antes
  de aplicar, backup/atomicidade, duplicidades de origem, árvore original versus
  restauração independente e falha de escrita sem perda do índice anterior.
- Se helpers mudarem: testes SETUP e hashes do bundle. Se Android mudar: testes
  JVM, build, lint e novo teste no Moto, sem exclusão como condição do aceite.

Nesta missão não foram executados testes de implementação, builds ou testes
destrutivos: a entrega é diagnóstico de leitura. As contagens acima são evidências
da instalação atual, não resultados de uma suíte automatizada.

## Riscos e encerramento

O bloqueio atual evita exclusão com identidade persistida divergente. Uma troca
global de device no JSON, ou aceitar qualquer inode com mesmo número, eliminaria
essa proteção e poderia atribuir gerenciamento a outro arquivo/subvolume.
Hash igual isolado também não prova hardlink. O arquivo excepcional não pode ser
misturado aos 264 candidatos que compartilham a origem RPC atual.

Não foram alterados código, helpers instalados, catálogos, hardlinks, torrents,
Drive ou serviços. Nenhuma PR, tag, release ou commit novo foi criado. A PR #60
permanece draft; o aceite funcional da R1 permanece pendente. Aguardar autorização
para uma correção delimitada e revisão independente antes de nova implantação.
