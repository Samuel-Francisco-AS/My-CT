# MYCT-CLOUD-R1 — diagnóstico após teste físico: Nuvem e biblioteca local

> **Contrato atual: reconciliação somente por metadados, schema 3 e validade de
> 600 s. Ver “Simplificação autorizada — identidade por metadados” ao final e o
> [contrato atual](MYCT-MEDIA-RECONCILE-APLICADOR.md). As seções anteriores sobre
> conteúdo, duas passagens e orçamento MA01 são histórico superado.**
>
> O diagnóstico abaixo foi preservado integralmente no commit `0dd5453` antes
> das alterações. A seção final **Correção implementada para nova auditoria**
> registra a missão posterior autorizada de código/testes, sem implantação.
> A etapa seguinte implementou o escritor: ver **Aplicador supervisionado —
> implementação para auditoria**, ao final. As limitações de ausência de escritor
> descritas nas seções anteriores são históricas dos commits já auditados.

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

## Correção implementada para nova auditoria — 21/10/2026

Implementação na mesma branch `fix/myct-cloud-r1` / PR #60 draft. A autorização
desta missão abrange código, testes e documentação, **não** implantação, aplicação
de plano pessoal, publicação de APK ou exclusão. A release code 49 e sua tag
continuam vinculadas a `f239bee`, sem alteração. O fonte Android passa a
`0.13.3-cloud-r1-rc2` / **code 50**, para distinguir a futura construção da APK
imutável já publicada; a construção local é somente validação para auditoria.
Package, ABIs e configuração de assinatura foram preservados.

### Identidade Media e plano sem aplicação

`ops/myct-media` acrescenta `reconcile-plan`, uma consulta sem locks nem escrita
de catálogo. O caminho de comando é tratado antes do despachante que cria locks.
O resultado tem `application_supported=false`; **não existe `reconcile-apply`**
nesta entrega. `reconciled_state()` é uma transformação pura, usada nas fixtures
para provar a recuperação proposta, e não um aplicador seguro de catálogo.

Cada candidato exige cumulativamente:

1. Registro anterior com ID, device e inode válidos, fora de `ignored`.
2. ID exatamente igual ao da origem encontrada por `media_files()`, num torrent
   reconhecido por `myct-torrent.records()`, completo, com metadados e sem erro.
3. Origem única, regular, pertencente ao usuário, dentro da árvore confiável;
   nenhum componente simbólico. O mesmo vale para o nome da biblioteca.
4. Device **e** inode atuais iguais entre origem e biblioteca; inode atual igual
   ao persistido. Uma cópia com o mesmo conteúdo não satisfaz essa condição.
5. Nome relativo exato e tamanho igual ao metadado `files[].length` do torrent
   registrado; tamanho positivo. Não basta encontrar qualquer vídeo na árvore.
6. Uma única origem reconhecida para o inode; todos os aliases Media registrados
   com o mesmo ID; `nlink` exatamente igual a uma origem mais esses aliases. Um
   hardlink desconhecido ou origem ambígua mantém a proteção.
7. Identidade Btrfs atual comprovada nos dois nomes: UUID de filesystem e ID de
   subvolume lidos pelos ioctls Linux de consulta `FS_INFO`/`INO_LOOKUP`. Falha ou
   filesystem não suportado bloqueia reancoramento; não há fallback para inode.
8. Se já persistidos, UUID/subvolume, tamanho, `mtime_ns` e UID devem coincidir.
   Divergência de filesystem ou metadados antigos não é normalizada.
9. Rechecagem dos dois `stat` ao terminar a coleta, incluindo modo, UID, tamanho,
   tempos, device/inode e `nlink`. A evidência e o índice inteiro participam do
   fingerprint do plano; mudança entre plano e nova coleta invalida a proposta.

Os registros novos passam a guardar `id/dev/ino`, `metadata` (tamanho,
`mtime_ns`, UID) e `filesystem` (UUID/subvolume, ou `null` se indisponível).
`record_matches()` mantém a igualdade exata de ID/device/inode dos registros
antigos e acrescenta as restrições novas quando presentes. **Não aceita mudança
de device durante snapshot, space, limpeza ou remoção.** A prévia pessoal segue
bloqueada enquanto nenhuma aplicação supervisionada ocorrer.
Mudanças posteriores de device também exigirão novo plano, mesmo em registros
enriquecidos (`DEVICE_REANCHOR`): UUID/subvolume acrescentam prova e não promovem
uma migração silenciosa a cada boot. Essa intervenção conservadora permanece uma
limitação operacional até revisão específica de qualquer automatização futura.

O scan de Media agora apresenta registro divergente como pendência, em vez de
retornar `existing/managed=true` sem verificação. Sync/approve não reancoram nem
adotam silenciosamente hardlinks existentes sem registro. O fluxo explícito de
relink conserva suas pré-condições de recuperação e salva o formato enriquecido.
Cleanup/Nuvem passam a reconhecer esse formato pelo mesmo validador.

**Limite histórico explícito:** os registros legados possuem apenas ID/device/
inode; não contêm UUID, tamanho ou mtime antigos. Esses dados não podem ser
reconstituídos nem inventados. A prova adicional do plano vem da origem atual
confiável e do tamanho no metadado do torrent identificado pelo ID anterior.
Por isso são classificados `LEGACY_REANCHOR`, exigindo um aceite específico de
reinscrição da identidade, além da confirmação do fingerprint. Isso não é prova
de continuidade histórica do filesystem. Possível substituição/clonagem anterior
não pode ser excluída apenas com o índice v1. A Luna deve revisar especialmente
essa fronteira de confiança antes de qualquer aplicador/deploy; não há hash novo
de conteúdo, nem verificação de peças do torrent nesta consulta.

### Resultado do plano pessoal, somente leitura

Foi importado o código do checkout e chamada apenas `reconciliation_plan()`;
nenhum helper instalado foi substituído. A consulta usou `torrent_get` com
metadados `files`, `stat` e os ioctls de consulta, sem space/release ou escrita.
O índice foi comparado byte a byte antes/depois da chamada: **igual**.

| Classificação | Quantidade | Consequência atual |
| --- | ---: | --- |
| Dos 264 candidatos originais, satisfazem as novas provas | **264** | `LEGACY_REANCHOR`; proposta, sem aplicação/autorização de exclusão |
| Dos 264, permanecem inelegíveis ao plano nesta amostra | **0** | Reavaliar obrigatoriamente antes de futura aplicação |
| Já coerentes | **14** | `CURRENT`; nenhuma atualização necessária |
| Vínculo excepcional | **1** | `SOURCE_INODE_MISMATCH`; protegido, sem atualização proposta |
| Caminhos de registros ausentes | **28** | `ABSENT`; preservados, sem atualização proposta |
| Total de registros examinados | **307** | Nenhum gravado |

Os 264 ainda permanecem protegidos nas operações reais, pois o índice pessoal
não foi migrado. As contagens são candidatos ao plano, **não** elegibilidade de
exclusão nem aceite funcional no Moto. O vínculo excepcional continua ligado à
árvore original registrada em um manifesto de Nuvem, enquanto o RPC aponta para
outro inode numa árvore independente. Sua investigação futura deve correlacionar
manifesto, `original_download_dir`, recuperação/relink, metainfo e as duas árvores;
depois, se autorizado, verificar integridade sem alterar origens. Não atribuir a
ele o ID do arquivo RPC atual nem retirá-lo da biblioteca automaticamente.

### Inventário e Android

`storage_inventory()` separa a enumeração do teste da seleção. Um vínculo inválido
acumula `LIBRARY_SELECTION` e não interrompe os demais. Erros em um arquivo
inspecionável são isolados; uma falha mais ampla mantém a enumeração dos alvos
conhecidos no manifesto/índice. Não promete conhecer aliases que um scan falho
não pôde descobrir.

Contrato aditivo: `library_complete` indica conclusão da enumeração/scan; cada
linha de `library` acrescenta `managed` e `status`: `managed`, `unmanaged`,
`divergent`, `absent` ou `uninspectable`. `present` e `linked_to_package` continuam
independentes da prova de gerenciamento. Resultado incompleto acrescenta
`LIBRARY_INCOMPLETE` e impede `can_release`, inclusive sem seleção. Nenhum token,
prazo máximo de 600 s ou verificação autoritativa de release foi removido.

O Android usa listas completas do mesmo pacote para atualizar opções; resultados
incompletos preservam as anteriores (ou usam os caminhos conhecidos do catálogo
se ainda não havia opções). Seleção não é marcada automaticamente. Há aviso de
incompletude e rótulos separados para vínculos divergentes, não gerenciados e
ausentes. Legado sem `library_complete`: resultado bloqueado é tratado como
incompleto; resultado válido sem bloqueios conserva o contrato anterior.
Autorização agora também exige inventário completo e prévia válida ao receber o
resultado. Segundo plano/navegação/reconexão, expiração, comparação exata,
confirmação Jellyfin e A01 de Atualizar continuam cobertos pelas regressões.

### Migração futura: procedimento ainda pendente de autorização e revisão

1. Aprovar este código e o tratamento explícito de legado na auditoria da Luna.
2. Em missão separada, preparar/revisar o escritor supervisionado: **ele não está
   implementado aqui**. Nenhuma chamada manual a `save_state(reconciled_state())`
   deve substituir esse trabalho ou ser usada no Fedora pessoal.
3. Somente após autorização, conferir instalação, caminhos privados, hashes e
   ausência de tarefas de Nuvem ativas/incertas. Não cancelar tarefas para migrar.
4. Gerar plano privado, mostrar contagens/motivos e identidade atual de filesystem;
   o proprietário confirma a procedência do filesystem e, especificamente, o
   reancoramento legado. Falta de evidência dessa procedência impede a aplicação.
5. O escritor deverá adquirir locks de nomeação por hash em ordem estável e depois
   Media (mesma ordem dos motores), reconsultar tarefas/RPC/índice e gerar plano
   fresco. Qualquer diferença exige nova prévia, sem aplicar subconjunto implícito.
6. Sob locks, criar backup exclusivo 0600 em diretório privado 0700, fora dos
   helpers/repositório, usando criação sem overwrite/symlink, hash e fsync. Validar
   dono, permissões e ancestrais do catálogo/lock/backup, inclusive `O_NOFOLLOW`.
7. Revalidar novamente arquivos/identidade/estado e confirmação; atualizar somente
   as entradas aprovadas. Preservar `ignored`, 14 coerentes, 28 ausentes e o caso
   excepcional. Escrever arquivo temporário privado no mesmo filesystem, fsync,
   rename atômico e fsync do diretório. Sem mídia, ACL ou RPC de mutação.
8. Conferir hash/contagens e registrar recibo sanitizado. Falha não permite rollback
   automático se houver tarefa ativa/incerta. Exigir nova consulta space depois,
   sem reutilizar token ou consentimento anterior, e repetir aceite no Moto sem
   executar liberação real como condição de teste.

### Arquivos de código/teste e SETUP

Alterados: `ops/myct-media`, `ops/myct-media-cleanup`,
`ops/myct-archive-torrent`; Android `Cloud.kt`, `CloudModel.kt`, `CloudScreen.kt`,
`build.gradle.kts`; `test_media_reconcile.py`, `test_cloud_space.py`,
`test_cloud_progress.py` e `CloudR1Test.kt`. Documentação: este relatório,
`MYCT-CLOUD-R1-RELATORIO.md`, `05-DECISOES-E-QUESTOES.md` e README.

Nenhuma dependência nova. O manifesto SETUP já inclui os três helpers alterados;
`build_bundle.py` e a tarefa Gradle continuam gerando hashes das fontes atuais.
As regras que recusam sobrescrever helpers preexistentes divergentes foram
preservadas. Nenhum catálogo pessoal, plano privado, identidade de filesystem ou
credencial faz parte dos assets; apenas as fontes enumeradas no manifesto.

### Testes e limitações desta correção

Resultados finais, sem somar execuções repetidas:

| Validação | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| Python operacional completo (233 casos) | **231** | **0** | **2** |
| Python SETUP completo | **58** | **0** | **0** |
| JVM Android (331 casos) | **329** | **0** | **2** |
| Subconjunto Cloud JVM, incluído no total | **37** | **0** | **0** |
| Reconciliação em fixtures, incluída no operacional | **17** | **0** | **0** |
| Space em fixtures, incluído no operacional | **12** | **0** | **0** |

Os dois SKIP operacionais são testes de unidades transitórias reais sem user
manager/linger no contêiner. Os dois JVM são os testes opcionais de transporte
SSH/SFTP sem ambiente real habilitado. Não são PASS nem validação física.

Build Android `assembleDebug`: **PASS**. Lint: **PASS**, 0 erros, 1 warning
`UseKtx` e 2 hints anteriores; compilador mantém os dois avisos anteriores de
condição sempre verdadeira em `IdeScreen.kt`. `git diff --check` e sintaxe Python:
PASS. Nenhuma dependência foi instalada para os testes.

Bundle SETUP reconstruído e conferido no APK local: 16 fontes, todas iguais ao
checkout e aos hashes do manifesto injetado; payload dentro do APK idêntico ao
gerado. Testes de determinismo e recusa de helper divergente passaram.

| Artefato local SETUP | SHA-256 |
| --- | --- |
| `payload.pyz` | `44e714fafb6285fd47c8be6e3a3e0d9b86f6a81217ed7b40f8c236379ae3455c` |
| `diagnose.py` | `496beacee8d16aaef854e5c217c16c147bb3f3b39d565f56ee3627b5fa32863a` |

Comandos principais reproduzíveis (Python **dentro** do contêiner descartável,
com cópia de `ops` e `server`, sem mounts pessoais):

```bash
python3 -m unittest discover -s ops/tests -p 'test_*.py' -v
python3 -m unittest discover -s ops/setup/tests -p 'test_*.py' -v
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  --offline --console=plain \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
# Após a última alteração operacional, regeneração/verificação do payload no APK:
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:buildSetupBundle :app:assembleDebug :app:lintDebug \
  --offline --console=plain \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
```

Logs locais ignorados em `artifacts/myct-cloud-r1-posttest/`:
`operational-final.log`, `setup-final.log`, `android-final.log`,
`android-bundle-final.log`, `reconcile-final.log` e
`progress-synchronized.log`. As execuções completas finais incluem as alterações
mais recentes; o teste de reconciliação teve 13 casos inicialmente e 17 no final.

As suítes Python usam cópia das fontes em contêiner rootless descartável Fedora
44, usuário `tester`, rede desabilitada, sem bind mounts pessoais, limite de
1536 MiB/1 CPU/1024 PIDs. Exclusões de fixtures nos testes de ciclo de vida não
atingem o acervo. Testes Android usam transporte e relógio simulados.

Tentativas iniciais: a regressão Android detectou a inclusão indevida de uma opção
antiga do catálogo ao preservar opções; corrigido no modelo. O teste existente
de telemetria assíncrona esperava que o arquivo `ready` do produtor significasse
amostra já publicada pelo consumidor; falhou também isoladamente. A fixture agora
sincroniza a evidência publicada com a continuação do produtor. A infraestrutura
de telemetria em produção não mudou. A configuração de Metaspace foi ampliada
somente na invocação Gradle de validação (768 MiB), sem mudar configuração pessoal.

Reconferência final: os três catálogos pessoais continuam com os hashes do
diagnóstico; os helpers instalados `myct-media`, `myct-media-cleanup` e
`myct-archive-torrent` continuam byte a byte iguais ao commit publicado `f239bee`.
`main` continua `54618d9` e a tag da candidata 49 continua em `f239bee`. Nenhum
plano pessoal foi salvo no repositório/APK. O contêiner criado para estes testes
foi descartado ao terminar; logs de validação permanecem locais.

**Pendências:** auditoria independente da Luna; revisão/implementação do escritor
supervisionado em missão autorizada; verificação de procedência do filesystem
legado e investigação individual da exceção; somente depois, autorização de
implantação/reconciliação e novo teste físico no Moto. Esta entrega não declara
R1 aceita ou encerrada e não inicia R2.

## Aplicador supervisionado — implementação para auditoria

21/10/2026, após a [revisão estática da Luna](https://github.com/Samuel-Francisco-AS/My-CT/pull/60#issuecomment-6323672244)
sobre `4b71fdb`. Implementado na mesma PR #60 draft, **sem implantação, migração
pessoal, release nova, merge ou Ready for Review**. Não altera a candidata code
49 nem sua tag. Fonte Android continua `dev.myct`, code 50; nenhuma mudança de
Kotlin, ABI ou assinatura nesta etapa, apenas o input adicional do SETUP.

Código e testes: `246953e047e522ed6bf5637012a548080a50bbc7`.

O contrato completo, incluindo JSON de procedência, argumentos de confirmação,
backup, recuperação e roteiro de implantação futura, está em
[MYCT-MEDIA-RECONCILE-APLICADOR.md](MYCT-MEDIA-RECONCILE-APLICADOR.md).

- `reconcile-plan` continua diagnóstico somente leitura. `reconcile-preview`
  acrescenta SHA-256 dos candidatos e vínculo com índice, RPC, catálogos, tarefas
  e declaração privada de procedência; não grava plano/lock/catálogo/cache local.
- A prévia apresenta contagens, motivos, caminhos a atualizar e ID exato; expira
  em 600 s após emissão. `reconcile-apply` exige o mesmo ID em `--confirm` e,
  para o legado, em `--accept-legacy`. Não aceita confirmação genérica.
- A declaração do proprietário deve identificar UUID/subvolume atuais exatos e
  referenciar evidência independente da procedência, reconhecendo expressamente
  a ausência de identidade histórica. O código verifica coerência/hash, **não**
  consegue comprovar a veracidade dessa evidência humana. Não inventa continuidade.
- Locks exclusivos não bloqueantes: delivery → catálogo de arquivos → catálogo
  de torrents → nomeação por hashes ordenados → Media. Sob locks, reconsulta tudo
  e recalcula o plano. Tarefa pendente/ativa/cancelando/incerta, catálogo ambíguo,
  RPC indisponível, origem/índice/arquivo alterados ou confirmação expirada abortam.
- Backup privado exclusivo, byte a byte, em diretório 0700 sob
  `~/.local/state/myct/media-reconcile/operations/ID/`, fora dos helpers/repositório.
  Valida ancestrais por dirfd/O_NOFOLLOW, owner/permissões/nlink; fsync de arquivos
  e diretórios, temporário no filesystem do destino e rename atômico.
- Intent persistente antes da troca e recibo sanitizado após ela. Recuperação
  apenas classifica/conclui recibo: anterior → `not_applied`, proposto →
  `succeeded` com backup/fsync conferidos, diferente → `superseded`. Nunca
  sobrescreve catálogo posterior nem restaura backup automaticamente.
- Repetição concluída retorna `already_applied` sem reescrever índice/backup.
  Transação incerta/incompleta bloqueia outra aplicação até diagnóstico/recuperação.

A fixture principal aplica **264** registros simulados e confere integralmente
os **14 coerentes, 1 excepcional, 28 ausentes e ignored** preservados; mídias,
inodes/nlink/conteúdo e ambos os catálogos de arquivo permanecem iguais. Nesta
missão **nenhuma prévia transacional com hashing nem aplicação foi executada no
acervo pessoal**. A contagem real anterior de 264 continua sendo apenas evidência
do plano antigo; o novo escritor acrescenta provas e pode recusar condições que
mudaram. Exige nova contagem/plano em missão futura autorizada.

### Validação final do aplicador

| Suíte | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| Python operacional completo — 262 casos | **260** | **0** | **2** |
| Aplicador, incluído no operacional | **29** | **0** | **0** |
| SETUP completo | **58** | **0** | **0** |
| JVM Android — 331 casos | **329** | **0** | **2** |
| Cloud JVM, incluído no total | **37** | **0** | **0** |

Dois SKIP operacionais por ausência de user manager/linger nas unidades reais do
contêiner; dois JVM opcionais SSH/SFTP sem ambiente real. Build Android e lint:
**PASS**, 0 erros, 1 warning UseKtx/2 hints anteriores. Sintaxe Python e
`git diff --check`: PASS. Não são testes físicos nem auditoria independente.

As suítes Python foram executadas em cópia de fontes/fixtures, contêiner rootless
Fedora 44 como `tester`, rede desabilitada, sem bind mounts pessoais, com limite
1536 MiB/1 CPU/1024 PIDs. Concorrência usa dois processos reais apenas nas
fixtures. Testes cobrem alterações de plano/RPC/índice/conteúdo, symlinks/locks,
backup e temporário, rename, fsync antes/depois da troca, SIGINT simulado,
recibo final indisponível, corrupção de backup, catálogo posterior e repetição.

Primeira execução direcionada: falha de readonly por criação de `__pycache__`
na importação do adaptador; corrigida a importação para compilação em memória.
Outra falha era preparação da fixture tentando criar symlink sobre lock legítimo
da tentativa anterior; a fixture passou a preparar explicitamente esse cenário.
As execuções finais completas incluem todas as correções e novos casos.

Logs ignorados em `artifacts/myct-cloud-r1-writer/`: `operational-final-receipt.log`
(262 casos, 54,537 s), `setup-final-receipt.log` (58, 5,802 s), `jvm-verified.log`,
`android.log` e `android-final-receipt.log`. Os logs finais incluem a distinção
de contagem planejada/comprovada nos recibos de recuperação. Invocações reproduzíveis:

```bash
# Somente no contêiner descartável, com cópia das fontes:
python3 -m unittest discover -s ops/tests -p 'test_*.py' -v
python3 -m unittest discover -s ops/setup/tests -p 'test_*.py' -v
# Local: apenas build/cache de desenvolvimento, sem instalação/distribuição:
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:testDebugUnitTest --rerun --offline --console=plain \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:buildSetupBundle :app:assembleDebug :app:lintDebug --offline --console=plain \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
```

SETUP atualizado: manifesto e inputs Gradle incluem o novo módulo; helper `.py`
recebe permissão privada 0600 pelo instalador existente. **17 fontes/hashes**
conferidos no payload e payload conferido dentro do APK local; determinismo e
recusa de componentes preexistentes divergentes passaram. Nenhum plano, backup,
declaração pessoal ou credencial faz parte do bundle.

| Artefato SETUP desta etapa | SHA-256 |
| --- | --- |
| `payload.pyz` | `4ed4b6586cf3b37917e1714f58e3cf0e7a310f4d4b676a4a7100034f54abfdb8` |
| `diagnose.py` | `96f2cdd3cc14a6f099c58bf0531cff589ccf533b99db4e4170d5583fc91792ee` |

Arquivos: novo `ops/myct_media_reconcile.py`, dispatch/importação readonly em
`ops/myct-media`, manifesto SETUP/inputs Gradle, novo teste do aplicador,
regressões do plano e do SETUP, contrato operacional e referências documentais.
Sem alteração dos motores de exclusão, telemetria, serviços ou helpers instalados.

### Pendências e riscos residuais

Os locks são cooperativos; ctimestamps, hashes, reconsultas e conferência de
descritores não equivalem a freeze contra root/escritor externo que ignore locks
no intervalo final. Nenhum serviço é pausado para migrar. O legado não contém
prova histórica completa; a procedência deve ser conferida por evidência humana
independente antes de declarar aceite. Prévia/aplicação fazem leituras integrais
dos candidatos: custo real e adequação do prazo no acervo grande não foram
medidos; expiração recusa a escrita, sem extensão automática.

Próximo gate: auditoria independente da Luna sobre o escritor. Depois, somente
com nova autorização: implantação coerente dos helpers/módulo, evidência de
procedência, plano fresco revisado, confirmação supervisionada, conferência do
recibo/backup e novo teste Moto sem exclusão real. **R1 segue aberta; R2 não
iniciada.**

## Histórico superado — correção MA01/MA02 com hashing (não usar como procedimento atual)

21/10/2026. [Auditoria integral da Luna](https://github.com/Samuel-Francisco-AS/My-CT/pull/60#issuecomment-6325881368)
sobre código `246953e` / HEAD documental `5f92a3d`: CHANGES REQUESTED, MA01
bloqueante para implantação. Esta seção substitui o contrato temporal e a
limitação de recuperação sem intent descritos na etapa histórica acima.
**Somente desenvolvimento/fixtures/documentação; nenhuma consulta de conteúdo,
aplicação ou implantação no acervo pessoal.** PR #60 permanece draft.
Código/testes corretivos: `d01644f3f87f4c4584e4ae595d1e70e36c7d2982`.

### MA01 — causa confirmada e contrato ajustado

A prévia anterior emitia a confirmação depois de um SHA-256 integral; apply
repetia o hash sob locks e exigia os mesmos 600 segundos até o rename. Portanto,
uma segunda passagem >600 s tornava impossível publicar, apesar de toda a prova
estar correta. Se chegasse à última checagem depois do intent, exigia recuperação.
Não foi demonstrada uma falha real nos 264: seu volume/tempo de hashing não foi
medido. A taxa anterior de ~74,4 MiB/s pertence a outro trabalho, não é benchmark
desses candidatos. Os 130.409.669.078 bytes do diagnóstico são dos **279 arquivos
da biblioteca**, não uma soma identificada dos 264 elegíveis ao escritor.

Solução mínima: separar o prazo para aceitar o plano do tempo de uma execução
contínua sob locks. Mantidos os **600 s para iniciar**, com conferência antes e
sob todos os locks. Ao aceitar, fixa um deadline monotônico exclusivo daquela
execução: `max(600, ceil(3 * preview_seconds + 120))`, limitado a **24 horas**.
O orçamento inteiro integra o ID schema 2 aprovado; não há renovação, retomada
com consentimento vencido ou liberação dos locks entre prova e publicação.

A duração medida inclui RPC, hashing e reconsultas. Fator 3 e reserva de 120 s
acomodam variabilidade e IO do catálogo, sem declarar garantia estatística. O teto
é um limite operacional explícito de publicação, não benchmark ou licença para
esperar indefinidamente. Projeção acima dele recusa antes da segunda leitura e
antes de criar intent/backup (`TEMPORAL_PREFLIGHT`). Se a máquina ficar mais lenta
que a margem, excesso observado após hashing aborta **antes** do intent; excesso
posterior preserva intent/backup para recuperação. IO/RPC bloqueado não é morto
pelo deadline; nenhum catálogo pode ser publicado depois da checagem expirada.

As duas passagens completas continuam obrigatórias, com hashes exatos, provas de
origem/dev/inode/UUID/subvolume/metadados e revalidação integral. Antes do rename,
reconfere o ID e os consentimentos aceitos, além do deadline de execução e da
coleta final sem novo hashing. Locks, backup privado e publicação atômica não
mudaram. O token de `space/release` continua com prazo máximo independente de
600 s; reconciliação nunca autoriza exclusão.

Diagnóstico novo, somente leitura:

- `reconcile-estimate --provenance ARQUIVO`: contagem de candidatos/inodes
  distintos, bytes por passagem, duas passagens e bytes totais lógicos; sem
  hashing/locks/escrita. Duração/taxa/viabilidade ficam `null` sem amostras.
- `reconcile-preview`: acrescenta tempo total observado, taxa efetiva lógica,
  duração projetada da aplicação, orçamento/teto e `temporal_viable`. Tempos
  `collect`, `hash`, `recheck` distinguem coleta/RPC/metadados, leitura e reconsulta.
  São diagnósticos de fases, sem afirmar IO físico ou gargalo não medido.
- Aliases do mesmo `(dev, ino)` são deduplicados exatamente como no hashing;
  reflinks/inodes distintos continuam exigindo leituras separadas. Sem rede,
  paralelismo, cache de integridade ou otimização R2 nova.

Planos antigos schema 1 são recusados para apply: gerar e revisar schema 2.
Journals anteriores continuam recuperáveis. Antes de qualquer migração pessoal,
ainda é obrigatório medir volume e prévia não destrutiva em missão autorizada,
conferir carga/cache/projeção e procedência e então confirmar o plano fresco.

### MA02 — primeiro intent indisponível

A publicação inicial de `transaction.json` estava fora do bloco que convertia
falhas em `RECOVERY_NEEDED: ID`. O diretório já criado bloqueava apply, mas recover
não sabia classificá-lo. Agora a falha de publicação inicial ou de fsync da
criação do diretório conserva a pasta e retorna esse diagnóstico com o ID.

Recover sem journal pede `MISSING_INTENT_REQUIRES_PLAN: ID`. A via explícita
`reconcile-recover --operation ID --plan ARQUIVO` exige o plano canônico exato da
operação. Pode estar expirado porque essa via só classifica; não aplica catálogo
nem revalida autorização de migração. Sob os mesmos locks e checagem de tarefas,
só publica `not_applied`, `updated=0`, quando o catálogo atual tem o hash anterior
exato e a pasta está vazia ou contém apenas temporários privados esperados.
Temporários órfãos permanecem byte a byte; seus nomes/hashes são registrados em
`preserved_evidence`, com `recovered_missing_intent=true` no recibo. A recuperação
sincroniza também o diretório pai da operação antes de publicar seu recibo.

Plano errado, catálogo diferente/mais recente, backup/arquivo desconhecido,
symlink, permissões inseguras ou journal ilegível mantêm bloqueio para diagnóstico.
Não remove evidências, não cria backup retroativo e não sobrescreve catálogo.
Falha de fsync/publicação da recuperação continua bloqueada e pode ser inspecionada
novamente; outra aplicação exige nova prévia/ID e confirmação específica.

### Validação desta correção

Resultados finais das fontes desta etapa, sem somar reexecuções:

| Suíte | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| Python operacional completo — 275 casos | **273** | **0** | **2** |
| Aplicador, incluído no operacional | **42** | **0** | **0** |
| SETUP completo | **58** | **0** | **0** |
| JVM Android — 331 casos, execução forçada | **329** | **0** | **2** |
| Cloud JVM, incluído no total | **37** | **0** | **0** |

Build `assembleDebug`, geração/conferência SETUP, lint, sintaxe Python e
`git diff --check`: PASS. Lint: 0 erros, 1 warning UseKtx/2 hints anteriores.
SKIP: duas unidades transitórias sem user manager/linger no contêiner e dois
transportes opcionais JVM SSH/SFTP sem ambiente real. Nenhuma dependência nova.

Cobertura adicional: hashing real em arquivos pequenos com avanço controlado de
**750 s na prévia e 750 s na aplicação**, início aos 599 s da confirmação e sucesso
após expiração civil, dentro do orçamento de **2370 s**. Plano expirado ao iniciar,
expiração durante aquisição dos locks, teto inviável, segundo hashing inesperadamente
lento, deadline após backup/retrocesso do relógio civil, orçamento inválido e ID
alterado antes da publicação continuam recusados. Sem espera de quatro minutos.

Falhas iniciais simuladas: antes da escrita, fsync do arquivo temporário, rename,
fsync do diretório antes/depois da publicação e fsync da criação da operação.
Também: recuperação sem plano, plano errado, órfão preservado, evidência inesperada,
catálogo posterior e falha de fsync/publicação da própria recuperação. As regressões
anteriores continuam cobrindo 264 atualizações simuladas, preservação dos 14
coerentes/1 excepcional/28 ausentes/ignored, concorrência, backups, troca atômica,
arquivamento/restauração/cleanup/release exclusivamente em fixtures.

Ambiente Python: contêiner rootless Fedora 44 descartável, usuário tester, rede
desabilitada, sem mounts pessoais, limite 1536 MiB/1 CPU/1024 PIDs. Somente cópias
de `ops`/`server`. Android local/offline, mesmos limites JVM da etapa anterior;
nenhum aparelho ou serviço pessoal usado. Logs ignorados:
`artifacts/myct-cloud-r1-ma/{operational-final,setup-final,android-final,android-bundle-final}.log`.
Invocações reproduzíveis: mesmas suítes completas da seção anterior; a primeira
invocação Gradle combina `testDebugUnitTest --rerun`, `buildSetupBundle`,
`assembleDebug` e `lintDebug`; bundle/build/lint reconferidos após a revisão final.

### Arquivos, compatibilidade e próximos passos

Código: `ops/myct_media_reconcile.py`, dispatch de `reconcile-estimate` em
`ops/myct-media`, `ops/tests/test_media_reconcile_apply.py`. Documentação: contrato
do aplicador, este relatório e registro de decisão temporal. Nenhuma mudança em
Kotlin, manifesto SETUP, política de exclusão ou infraestrutura de telemetria.
O manifesto já enumera os helpers; bundle regenerado com os **17 hashes** atuais,
incluindo o módulo alterado. Bloqueio de helpers legados divergentes permanece.
Package/assinatura/ABIs e fonte code 50 preservados, code49/tag/release imutáveis.
Nenhum catálogo, plano pessoal, backup ou credencial incorporado aos assets.
Conferidos bytes/hashes de todas as 17 fontes no manifesto injetado,
`integrity.json` e igualdade do payload no APK local:

| Artefato SETUP MA01/MA02 | SHA-256 |
| --- | --- |
| `payload.pyz` | `4b539385e443777ffffd9593f185dd6ad70ef295d486608315113332068733c0` |
| `diagnose.py` | `82e0518a15c29653779ffc5d15aafd3549ff807f1ae573681c9b8840c6cb0f26` |

Execução operacional final: 65,283 s; SETUP: 16,469 s. O contêiner desta missão
foi descartado após os testes; os logs locais ignorados foram preservados.

Riscos restantes: projeção depende de carga/cache; IO bloqueado pode prolongar
locks sem permitir publicação expirada. Locks continuam cooperativos contra
escritores externos; legado sem identidade histórica exige evidência humana de
procedência, nunca fabricada pelo programa. Recuperação sem intent deliberadamente
não classifica catálogo diferente nem evidência desconhecida. Volume e gargalo
dos 264 continuam pendentes de medição autorizada.

Próximo gate: **reauditoria independente da Luna**, especialmente separação do
prazo de aceitação/execução e recuperação sem intent. Depois, somente com nova
autorização: implantação coerente/backup, procedência, estimate/prévia medida,
revisão do plano exato e aplicação supervisionada. Não implantar, migrar, publicar
APK/release, fazer merge ou iniciar R2 nesta missão. R1 continua aberta.

## Simplificação autorizada — identidade por metadados

21/10/2026. Por solicitação expressa do proprietário, substitui o procedimento
com hashing/MA01 do HEAD `5e5f5889b238ac48d4d2a17ddb340ee132606c65`.
Código/testes: `136b1636e1abdafdaa8617164f655f756f27571d`.
**Contrato atual: schema 3, somente metadados, confirmação de 600 s.** Histórico
acima preservado para rastreabilidade, sem servir de orientação operacional atual.
Mesma branch/PR #60 draft; nenhuma implantação, migração pessoal ou nova APK.

### Código removido e provas preservadas

Removida `content()` de `ops/myct_media_reconcile.py`: abertura/leitura sequencial
de vídeo em blocos de 1 MiB, SHA-256 por conteúdo e mapa `snapshot.contents`.
Removidos `workload()`, `estimate()`, `reconcile-estimate`, taxas, volume/passagens
de hashing, tempos associados, `MAX_EXECUTION`, teto de 24 h e deadline especial.
O módulo passa de **552 para 493 linhas**, sem dependência nova ou framework.
SHA-256 continua protegendo os controles pequenos: plano, índice, backup,
procedência, recibos e evidências de journal. Nuvem mantém todas as verificações
próprias de integridade; archive/restore/space/release não foram modificados.

`candidate_metadata()` usa stat por diretório seguro/O_NOFOLLOW em origem e
biblioteca, sem ler dados; preserva dono/permissões e compara device/inode,
tamanho, nlink, UID, mtime e ctime com as evidências. `reconciliation_plan()` mantém
origem gerenciada única, ID registrado, torrent completo/metadados/sem erro,
tamanho exato no torrent, ausência de symlinks/aliases desconhecidos, inode antigo
e UUID/subvolume coerentes. Não aceita inode ou conteúdo igual isoladamente.

Prévia: somente leitura, sem lock de escrita, com `counts` (examinados,
candidatos, coerentes, ausentes, protegidos), motivos, filesystems comprovados,
caminhos propostos e ID canônico. Protegidos incluem `ignored`; nenhuma opção é
aprovada automaticamente. Planos schemas 1/2 são recusados para aplicação.

Confirmação: `--confirm ID` e, para legado, `--accept-legacy ID`, ambos exatos e
revisados pelo proprietário, dentro de **600 s após a prévia**. Sem orçamento
longo/renovação; validade reconferida antes/sob locks, após recoleta e antes do
rename. Metadados/RPC lentos podem expirar, sem autorização artificial.

Aplicação: ordem de locks delivery → catálogos → nomeação ordenada → Media;
reconsulta/compara o snapshot completo, cria backup exclusivo privado e intent,
escreve temporário/fsync, reconsulta metadados/RPC/ambiente e autorização, publica
por replace/fsync e recibo. Somente registros candidatos mudam. Backup, journal,
concorrência, idempotência e recuperação MA02 foram preservados, sem restauração
ou exclusão automática de evidências. Recuperação de journal válido continua
compatível com o formato anterior; via sem primeiro intent requer plano schema 3
exato, catálogo anterior intacto e evidências reconhecidas. Não reutilizar plano
antigo de hashing nem editar o catálogo manualmente.

### Origem de dev 50/51 — evidência e limites

**Confirmado no código/histórico:** a persistência de `{id, dev, ino}` e comparação
estrita de device datam de `6ada496875a6abbad6e6bbac15a90e714d15b636`
(18/10/2026, implementação inicial Media). É anterior ao SETUP (`8a2212f`,
20/10/2026) e às correções R1. Comparar um número de dispositivo persistido como
se fosse identidade estável entre boots pode bloquear vínculos legítimos.
`bec0484` acrescentou UUID/subvolume e plano explícito; não normalizou device.

No Linux/Btrfs upstream, `btrfs_getattr()` publica em `stat.dev` o `anon_dev` da
raiz do subvolume; a inicialização dessa raiz pode alocar um novo dispositivo
anônimo via `get_anon_bdev()`. Portanto, esse número é atribuído em memória,
não é o UUID persistente do volume. Isso explica um mecanismo possível para
renumeração após nova instanciação/montagem, **não reconstitui o evento pessoal**.
Fontes primárias: [inode.c](https://code.googlesource.com/linux/torvalds/linux/+/23eb7901811a06805ec7d6e766dd82f083d03204/fs/btrfs/inode.c),
[disk-io.c](https://code.googlesource.com/linux/torvalds/linux/+/f24ba334afafc70c3149e9db9c0cf8ecc6d52a09/fs/btrfs/disk-io.c).
A [documentação Btrfs](https://btrfs.readthedocs.io/en/latest/btrfs-subvolume.html)
esclarece o namespace próprio dos inodes e o ID persistente do subvolume; inode
isolado não identifica um arquivo no filesystem inteiro.

Inspeção objetiva desta missão, exclusivamente de leitura (`findmnt`, stat do
home, `/etc/fstab`, diários de kernel de dois boots, histórico/fontes Git):

| Evidência sanitizada | Resultado |
| --- | --- |
| Filesystem do home / raiz de subvolume | Btrfs / `/home` |
| `stat(home).st_dev` atual | **51** |
| fstab raiz/home | Mesmo dispositivo configurado; home com `subvol=home` |
| Diários de kernel atual/anterior | 7 mensagens Btrfs em cada, uma montagem inicial e um UUID por boot |
| Comparação dos UUIDs nos dois boots | **Iguais**; UUID pessoal não publicado |
| Mensagens Btrfs de erro/read-only nessa amostra | 0 / 0; não é auditoria completa de saúde |
| Primeira entrada de kernel disponibilizada, boot atual | 21/10/2026 **10:56:45,084720 UTC** |
| Primeira entrada disponibilizada, boot anterior | 20/10/2026 13:48:45,073785 UTC |

O horário 13:56:44 UTC anotado na seção histórica não coincide com esta leitura
do diário; ele não é usado como prova do instante exato da mudança. Não foi
reconstituída a origem dessa diferença de anotação. A primeira mensagem disponível
também não atesta sozinha a hora de alocação do device do subvolume.

**Hipótese sustentada:** reinicialização/nova instanciação de subvolume Btrfs
renumerou o dispositivo anônimo, sem mudar os hardlinks observados. Os recibos
anteriores já documentados têm dev 50 e os posteriores/stat têm 51. Mesmo UUID
nos dois boots é compatível com essa hipótese, mas não exclui clonagem/restauração
anterior nem comprova continuidade histórica dos arquivos.

**Sem evidência suficiente:** qual alocação/ordem de montagem produziu exatamente
50 ou 51. O diário disponível não registra essa associação por inode/subvolume.
Na inspeção de SETUP não foi encontrado código que monte/desmonte, altere fstab
ou subvolumes; nos helpers Nuvem, `check_mounts()` somente lê mountinfo para
recusar árvores inseguras. Não há prova para atribuir a mudança ao SETUP/Nuvem.
Não foram alterados fstab, montagens, serviços, torrents ou configurações.

O registro enriquecido não elimina automaticamente um futuro bloqueio por device:
`record_matches()` continua estrito. UUID/subvolume/tamanho/mtime/UID permitem
uma proposta `DEVICE_REANCHOR` mais bem fundamentada, mantendo a exigência de
prévia/confirmação explícita. Essa intervenção futura é uma limitação consciente,
sem migração insegura ou normalização automática. Teste específico a cobre.

### Testes e compatibilidade

Somente fixtures descartáveis; contêiner rootless Fedora 44 como tester, rede
desabilitada, sem mounts pessoais, 1536 MiB/1 CPU/1024 PIDs. Mesmas ferramentas
existentes: Python 3.14.7, Java 21, Gradle/SDK offline. Nenhuma dependência instalada.

| Validação final | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| Operacional completo — 274 casos | **272** | **0** | **2** |
| Aplicador, incluído acima | **40** | **0** | **0** |
| Plano, incluído acima | **18** | **0** | **0** |
| SETUP completo | **58** | **0** | **0** |
| JVM Android — 331 casos, execução forçada | **329** | **0** | **2** |
| Cloud JVM, incluído no total | **37** | **0** | **0** |

Build, lint, sintaxe Python, diff --check e bundle SETUP: PASS. Lint: 0 erros,
1 warning UseKtx/2 hints anteriores. SKIP: duas unidades transitórias sem user
manager/linger e dois transportes opcionais JVM SSH/SFTP, sem ambiente real.
Operacional: 64,859 s; SETUP: 11,483 s. Não são testes físicos no Moto.

A fixture principal usa 264 candidatos, 14 coerentes, 1 origem divergente, 28
registros ausentes e ignored. Preserva arquivos/inodes/nlink/conteúdo e catálogos
de arquivo. Durante preview/apply, guardas falham se houver leitura de mídia via
Python open/io.open ou descritor read/pread; arquivos pequenos de controle seguem
legíveis. Preparação/conferência dos conteúdos das fixtures ocorre fora da operação
supervisionada. Não se usa mídia pessoal e não se exige hash para confirmar o teste.

Mantidos negativos de inode/ID/origem/tamanho/UUID/metadata, symlink/caminho inseguro,
alias desconhecido, confirmação/plano divergentes, expiração sob locks e após
backup, mudança de arquivo/RPC/tarefas/catálogo, concorrência, falhas de escrita/
fsync/replace e recuperação conservadora. Teste adicional altera bytes mantendo
tamanho e restaurando mtime; ctime divergente bloqueia. Não se promete detectar
alteração que não apareça nas evidências ou manipulação privilegiada.

Logs ignorados em `artifacts/myct-cloud-r1-metadata/`: `reconcile-initial.log`,
`operational-final.log`, `setup-final.log`, `android-final.log`. Comandos:

```bash
# Dentro do contêiner descartável com cópia de ops/server:
python3 -m unittest discover -s ops/tests -p 'test_*.py' -v
python3 -m unittest discover -s ops/setup/tests -p 'test_*.py' -v
# Build local, sem instalação/publicação:
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:testDebugUnitTest --rerun :app:buildSetupBundle \
  :app:assembleDebug :app:lintDebug --offline --console=plain \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'
```

Manifesto SETUP continua com 17 fontes; geração determinística/hashes atualizados
automaticamente pelos fontes. Compatibilidade dos módulos e recusa de helper
preexistente divergente testadas. Payload conferido no APK local, sem catálogo,
plano pessoal, backup ou credenciais. Android/Kotlin/assinatura/ABIs/code 50
inalterados; release/tag code 49 e main preservadas.

| Artefato SETUP metadata-only | SHA-256 |
| --- | --- |
| `payload.pyz` | `2173d9e0283b7fb81293c29ea90930eca3819e5081aa11507cef5b2ce70a383e` |
| `diagnose.py` | `38db0f5ccd410eac885181e63bb79fb1bc65f599a7a2da2d3d765383214a7960` |

Conferidos bytes/hashes das 17 fontes, integridade dos dois artefatos e igualdade
do payload nos assets do APK local. Contêiner desta missão descartado após os
testes, logs ignorados preservados. Arquivos alterados: `ops/myct_media_reconcile.py`,
dispatch em `ops/myct-media`, testes `test_media_reconcile.py` e
`test_media_reconcile_apply.py`; contrato, este relatório, decisões e notas atuais
no README/relatório R1. Nenhum motor Nuvem ou código Kotlin foi alterado.

### Limites e próxima operação, ainda não autorizada

Metadados não garantem integridade audiovisual, conteúdo original ou continuidade
histórica. Locks cooperativos/recoletas não são freeze contra root ou outro
escritor que ignore a coordenação. Mudança observada expira o plano; não aplicar
subconjunto novo silenciosamente. Exceção de origem, ausentes, coerentes e ignored
permanecem preservados. As quantidades pessoais 264/14/1/28 continuam históricas;
nenhum plano ou catálogo pessoal foi reavaliado/aplicado nesta missão.

Após a revisão da Luna e autorização separada: pré-voo de tarefas, backup e
instalação coerente dos helpers/módulo; verificar procedência do filesystem;
gerar/revisar prévia metadata-only nova; confirmar ID e legado dentro de 600 s;
conferir recibo/backup. Erro requer recuperação/classificação, sem restauração
manual ou sobrescrita. Só depois novo teste Moto/Nuvem, sem exclusão real como
condição. Sem benchmark integral de vídeos, R2, implantação ou migração agora.
