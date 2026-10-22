# My-CT R1 — Rodada 1/3: investigação definitiva da lentidão

Data: 22/10/2026. Pendência: [issue #61](https://github.com/Samuel-Francisco-AS/My-CT/issues/61).
Base examinada: `main` após a [PR #60](https://github.com/Samuel-Francisco-AS/My-CT/pull/60),
merge `255bb8f4dcc26b148ac6e95abfc41575730202f9`.

**Investigação concluída. Nenhuma implementação foi iniciada.** Este documento
registra integralmente as conclusões e a recomendação apresentadas na conversa,
por solicitação posterior do proprietário de criar o Markdown e fazer seu commit.
A autorização documental não inicia a rodada de implementação nem autoriza push,
PR, APK ou implantação.

## Conclusão

A demora vem principalmente do **hashing integral dos vídeos durante `space`**.
A repetição vem de **vincular o inventário à seleção e descartar ambos a cada
alteração**. Há evidência suficiente para executar a correção em uma única rodada
de implementação, sem outra investigação ou planejamento complementar.

Recomendação: inventário inicial leve por metadados, reutilizado em memória por
pacote; seleção e estimativas calculadas localmente; confirmação dos caminhos
exatos; verificações fortes de conteúdo e backup reservadas à execução da
liberação Cloud.

O gerenciamento e a exclusão de arquivos locais continuam independentes de backup.
Esta proposta não transfere os requisitos de arquivamento/restauração para Media.

## Escopo, método e limites

Foram lidos `README.md`, `docs/04-PLANEJAMENTO-INAUGURAL.md`, orientações do
repositório, relatórios existentes, código, testes e recibos/telemetria já
persistidos. A issue #61 e o estado de integração da PR #60 foram conferidos pelo
conector GitHub; a referência remota `main` foi conferida por `git ls-remote`.

Na investigação, o checkout estava em `fix/myct-cloud-r1`, commit
`267c5649bba582d9060bdb0a46bcc045ae39baa3`. O objeto do merge foi obtido sem trocar
a branch. A comparação confirmou que `ops` e `android` desse checkout eram
idênticos aos da base `255bb8f`; a diferença entre os commits era documental.
Os quatro helpers instalados conferidos também correspondiam ao código examinado:

- `myct-archive-torrent`;
- `myct-archive`;
- `myct-cloud`;
- `myct_cloud_progress.py`.

A coleta de evidências usou Python 3.14.7 com `-I -B`, leitura direta de JSONs
existentes e hashes dos arquivos de código. Não foram chamados os despachantes dos
helpers pessoais. Nenhuma dependência foi instalada.

**Não houve nova execução de `space`, leitura de conteúdo de mídia, consulta real
ao Drive ou ao Transmission, exclusão, movimentação, alteração de serviço,
instalação de helper, build ou publicação de APK.** Não houve nova medição no Moto.

Para este registro posterior, a `main` local foi avançada por fast-forward até o
merge já existente `255bb8f`. O commit documental acrescenta apenas este arquivo;
não contém implementação nem modifica o comportamento instalado no Fedora.

## 1. Causa da lentidão: etapas, evidências e tempos

### Evidência existente no Fedora

Foram encontrados dez recibos de `space` em
`~/.local/share/myct/cloud-jobs`. Dois preservam tempos por fase, referentes ao
mesmo pacote de **19.349.308.130 bytes**, em 21/10/2026:

| Etapa | Sem seleção — início 15:16:22 UTC | 13 links selecionados — início 15:21:38 UTC |
| --- | ---: | ---: |
| Drive — `remote_check` | 6,060 s | 4,580 s |
| Inventário local, sem leitura integral — `local_inventory` | 0,041 s | 0,039 s |
| Leitura integral e hashes — `local_read` | **214,947 s** | **185,380 s** |
| Transmission — `transmission_check` | 0,058 s | 0,055 s |
| Inventário da biblioteca — `library_inventory` | 0,028 s | 0,036 s |
| Preparação e finalização instrumentadas | 0,017 s | 0,017 s |
| Restante não atribuído pelas fases | 0,221 s | 0,207 s |
| **Total do trabalho no Fedora** | **221,371 s** | **190,313 s** |

O total foi calculado por `finished - created` dos recibos. Os tempos das fases
vieram de `phase_seconds` nos arquivos `.progress`. O restante é a diferença
entre essas duas medidas, não uma medição de SSH ou de UI.

A leitura e o cálculo de hashes representam **97,1% e 97,4%** desses totais.
São tempos históricos reais, anteriores às últimas correções funcionais; não
representam uma nova medição da candidata atual. A primeira consulta retornou
13 vínculos e `can_release=true`. A segunda retornou apenas uma linha e
`LIBRARY_SELECTION`, pelo problema de seleção já tratado na PR #60.

A fase `local_read` mede tempo de leitura, hashing e instrumentação; não separa
CPU de espera de I/O. Os bytes dessa telemetria são retornados por `read()` e
podem vir do cache: não são prova de tráfego físico do SSD.

### Operações responsáveis

Em `ops/myct-archive-torrent`, `storage_inventory()` executa:

1. Conferência de estado de liberação/catalogação.
2. `remote_objects()`: duas invocações de Rclone, para listar o diretório pai e
   listar recursivamente o pacote com hashes. Confere tamanho/MD5 de dados,
   manifesto e metainfo. **Não baixa os vídeos nessa consulta.**
3. Conferência local dos arquivos pequenos de manifesto e metainfo.
4. Para cada árvore original/restaurada presente, `digest_tree()` chama
   `common.fingerprint()` para todos os arquivos.
5. Consultas RPC do torrent e das demais entradas, incluindo proteção contra
   compartilhamento de caminhos.
6. Enumeração da biblioteca Media e cálculo de aliases, inodes e blocos.

Em `ops/myct-archive`, `fingerprint()` lê integralmente cada arquivo em blocos de
1 MiB, calculando MD5 e SHA-256 na mesma passagem e comparando metadados antes e
depois da leitura. A prévia pode ler tanto a árvore original como uma restauração
independente quando ambas existem.

Hardlinks reconhecidos por `(dev, ino)` são associados sem nova leitura de
conteúdo. Entretanto, se não há uma origem correspondente e existe um destino
conhecido no manifesto/índice, o ramo de fallback da biblioteca também chama
`fingerprint()` para tentar comprovar o conteúdo. Nas duas amostras instrumentadas
não houve fase `library_read`: o gargalo estava na árvore do pacote.

O inventário referido como Jellyfin é a árvore local Media e seu índice; esse
fluxo não executa varredura pela API do Jellyfin.

### SSH e atualização da interface

Não existem tempos separados registrados para SSH e UI. O total do recibo começa
no Fedora e não mede o percurso completo desde o toque no Moto.

`SshConnection.cloud()` reutiliza a sessão SSH e abre um canal `exec` por chamada.
O comando `start` entrega um trabalho persistente ao Fedora; os minutos de
processamento não são uma única chamada SSH síncrona aguardando o hash inteiro.
O transporte bloqueante é executado em `Dispatchers.IO` e serializado por mutex.

`CloudScreen.kt` solicita polling a cada três segundos enquanto a tela está
visível. Quando a tarefa termina, o modelo ainda consulta snapshot/catálogo e o
resultado da tarefa. Isso acrescenta transporte e espera de atualização; não
explica os minutos já comprovados dentro do backend. A cadência é propriedade do
código, não tempo de atraso medido no aparelho.

Também há trabalho repetido em `myct-cloud.snapshot()`: `live_library()` importa
o módulo e carrega o estado Media para cada pacote. JSONs são processados no
transporte e novamente pelo modelo. Não há medição que permita atribuir duração
a essas etapas ou à renderização. São custos secundários observáveis no código,
sem evidência de predominância sobre o hashing.

## 2. Causa das consultas repetidas: fluxo exato

O fluxo inicial é:

```text
Consultar espaço / prévia
  → CloudWork(space, pacote, seleção)
  → CloudQuery.Start
  → myct-cloud / trabalho persistente
  → storage_inventory completo
  → prévia com token
```

Depois de uma alteração de seleção:

```text
marcar/desmarcar
  → CloudModel.toggleLink()
  → invalidatePreview()
  → space = null
  → authorized = null
  → previewRequest = null
```

A seleção visual permanece em `linkSelections`, mas a prévia e a possibilidade
de confirmar desaparecem. O próximo toque envia outro trabalho e repete Drive,
leitura dos vídeos, RPC e biblioteca. O texto da tela declara explicitamente que
alterar a seleção exige nova prévia.

Isso também decorre do contrato do backend: `preview_token()` calcula o token
sobre **todo o plano**, incluindo seleção, caminhos removidos e estimativa.
Portanto, o token atual deixa de corresponder quando a seleção muda. Conservar
somente `space` no Android não resolve.

Há ainda repetição durante `release()`:

- um `storage_inventory()` completo antes de alterar o Transmission;
- outro inventário completo após remover a entrada, antes do primeiro `unlink`;
- outro `fingerprint()` de cada caminho imediatamente antes da exclusão,
  inclusive de aliases do mesmo inode.

`ON_STOP`, saída da composição, navegação entre pacotes e certos refreshes também
revogam a prévia. O problema é que os mesmos campos representam inventário e
consentimento: revogar a confirmação acaba descartando dados que poderiam
continuar úteis para consulta e seleção.

## 3. Opções de solução

| Opção | Vantagens | Limitações e riscos reais |
| --- | --- | --- |
| Cachear o inventário atual, mantendo os hashes iniciais | Elimina releituras ao mudar seleção | Mantém a primeira espera de minutos; ainda exige separar o token da seleção. Resolve apenas parte do objetivo. |
| **Inventário leve por metadados, cache por pacote e validação forte na operação** | Elimina hashing e Drive da prévia; permite seleção e estimativa locais; mantém proteção antes da exclusão | A prévia deixa de afirmar integridade de conteúdo atual. A operação pode ser recusada se encontrar mudança ou backup inválido. |
| Índice persistente com monitoramento de alterações | Pode atender consultas frequentes sem nova enumeração | Exige sincronização, recuperação e tratamento de eventos perdidos. Complexidade desproporcional para este fluxo. |

**Recomendação: segunda opção.** Não é necessário novo daemon, banco, sistema de
autorização, monitoramento permanente ou mecanismo de auditoria.

## 4. Solução recomendada: contrato de implementação

### Inventário inicial independente da seleção

Uma consulta inicial produz um inventário imutável por pacote. Ele contém:

- árvores original/restaurada e diretórios;
- todos os caminhos conhecidos e seus papéis;
- associações Media e estado completo/incompleto da enumeração;
- grupos de inodes, aliases, `nlink`, blocos e tamanho;
- identidade e tempos dos arquivos;
- metadados relevantes do catálogo e Transmission;
- impedimentos encontrados.

A consulta enumera nomes e metadados, sem `fingerprint()` dos vídeos. Reutiliza
manifesto, metainfo e índice locais, obtém os metadados necessários do Transmission
e mantém uma enumeração completa da biblioteca para reconhecer aliases.

Não chama Drive para calcular a prévia de espaço. A verificação remota registrada
no catálogo é apresentada com sua data e identificada como histórica, sem ser
tratada como conferência atual. Verificação de pequenos arquivos locais de
manifesto/metainfo pode permanecer: não representa leitura integral de mídia.

Um vínculo cuja associação não possa ser comprovada por metadados deve aparecer
protegido. A prévia não deve iniciar hashing oculto para tentar reconhecê-lo.
Metadados permitem associação e estimativa; não provam integridade audiovisual.

### Cache, seleção e estimativa

O Android guarda o inventário por pacote, somente em memória, por até 600 segundos.
Não há cache persistente novo no Fedora ou réplica durável de catálogo no app.

Selecionar/desmarcar apenas deriva outro plano local, sem SSH, Drive, RPC ou
releitura de vídeos. O plano é reconstruído a partir do inventário completo e da
seleção corrente, sem alterar o inventário-base.

A estimativa soma os blocos de cada inode uma única vez. Só considera candidato à
liberação o inode cujos nomes seriam todos removidos e cuja contagem de aliases
seja coerente. Links desconhecidos permanecem impedimento conforme o contrato de
segurança do fluxo Cloud. Reflinks, snapshots e arquivos abertos podem reter
blocos: a estimativa continua sendo limite superior e a economia garantida é zero.

O escopo permanece o da liberação Cloud: árvores do pacote indicadas pelo
inventário mais os links opcionais explicitamente escolhidos. Não se converte
essa ação em exclusão individual de arquivos locais do fluxo Media.

### Token e confirmação dos caminhos exatos

O contrato é versionado. O token identifica a versão do inventário, independente
da seleção e dos totais derivados. Telemetria e tempos voláteis não compõem a
identidade do inventário.

A seleção continua enviada explicitamente por caminhos em `--remove-library`,
com as confirmações existentes. O servidor projeta novamente a seleção sobre o
inventário validado, verifica pertencimento e elegibilidade, e não confia na
estimativa calculada pelo Android.

O diálogo congela pacote, token, seleção, caminhos atingidos e consequências.
Qualquer alteração da seleção fecha/invalida esse diálogo, preservando o
inventário. Confirmar transmite exatamente a fotografia exibida, sem acrescentar
alvos a partir de uma seleção mutável consultada depois.

O usuário não precisa refazer a carga para confirmar outra seleção. A confirmação
é explícita em cada operação; a posse de um inventário válido não constitui
consentimento automático. Histórico de tarefa nunca restaura consentimento.

### Verificações na liberação Cloud

Usar os locks e o journal existentes, com a seguinte ordem lógica:

1. Validar confirmação e prazo de aceite, recoletar metadados do inventário e
   conferir token, caminhos, Media, aliases, mounts e RPC sob os locks.
2. Executar a conferência integral de conteúdo uma vez por inode distinto antes
   das alterações destrutivas. Origem e hardlink do mesmo inode não justificam
   duas leituras; árvores independentes continuam inodes diferentes.
3. Verificar o backup remoto com `remote_objects()` antes de prosseguir com a
   liberação. Falha remota ou divergência de conteúdo não autoriza excluir dados.
4. Preservar entrega/journal, pausa e remoção da entrada Transmission sem
   `delete_local_data`, com confirmação do resultado RPC.
5. Após a alteração no Transmission e antes dos `unlink`, revalidar metadados,
   caminhos e descritores sem repetir hashing integral. Durante os próprios
   `unlink`, contabilizar as alterações esperadas de `nlink` e `ctime` por inode,
   distinguindo-as de mudanças externas.
6. Preservar bloqueios, estado incerto, proteção contra repetição e o journal dos
   caminhos efetivamente removidos.

Hashes continuam necessários em arquivamento/restauração e na verificação forte
da liberação Cloud. Não são necessários para listar caminhos ou calcular blocos.
A liberação pode continuar demorada por exigir uma leitura forte real; isso não
deve ser repetido a cada checkbox nem disfarçado como consulta de espaço.

### Validade e revogação

- Alteração de seleção: recalcular plano e revogar somente confirmação aberta;
  não renovar os 600 segundos nem descartar inventário válido.
- Segundo plano/navegação: revogar confirmação; conservar o inventário visual
  válido em memória. Voltar à tela não restaura autorização anterior.
- Expiração, reconexão, mudança relevante de catálogo ou operação que altere o
  pacote: exigir atualização leve antes de executar outra ação dependente do
  inventário. A reconexão não conserva autorização destrutiva.
- Resultado tardio ou histórico: pode ser consultado, mas não reativa confirmação
  revogada nem substitui o inventário corrente de outro pacote/geração.
- Mudança externa não observada pelo app: detectar na revalidação do servidor,
  recusando a operação sem aplicar um subconjunto silencioso.

Restaurar, recuperar e arquivar mantêm as validações próprias. O cache reutiliza
informações úteis ao gerenciamento; não substitui as provas exigidas por cada
operação.

### Resultado esperado e limite da evidência

A redução inicial decorre de retirar as operações responsáveis pelos tempos
medidos: leitura integral e consulta remota da prévia. Não existe tempo medido
após a correção nem promessa numérica de desempenho. A implementação e o teste
físico ainda não ocorreram.

## 5. Implementação fechada para uma única rodada

Os itens abaixo compõem uma única entrega de implementação, não novas rodadas
de investigação, auditoria preliminar ou microetapas de planejamento.

| Arquivo | Alteração necessária |
| --- | --- |
| `ops/myct-archive-torrent` — `storage_inventory()` | Separar coleta de inventário de uma função pura de projeção da seleção. Inventariar por metadados; retornar todos os aliases e atributos; remover `digest_tree()` e o fallback de hashing da prévia; preservar enumeração completa e impedimentos. |
| Mesmo arquivo — `space()` e `preview_token()` | Emitir contrato versionado de inventário, token independente da seleção e validade de 600 s. Excluir telemetria e valores derivados da seleção do conteúdo identificado pelo token. Informar que integridade atual e Drive ainda não foram revalidados. |
| Mesmo arquivo — `release()` | Validar token contra inventário fresco; projetar seleção no servidor; exigir confirmação explícita dos links. Conferir conteúdo uma vez por inode e remoto antes da destruição. Substituir releituras seguintes por comparação de metadados/descritores, contabilizando `nlink`/`ctime` dos próprios `unlink`. Preservar locks, journal, replay seguro e estado incerto. |
| `android/app/src/main/java/dev/myct/Cloud.kt` | Criar tipos para inventário, aliases/inodes, plano derivado e confirmação imutável. Implementar cálculo local equivalente ao servidor. Exigir versão nova para esse fluxo; resultados antigos permanecem somente consultivos. Manter envio explícito de seleção e confirmações. |
| `android/app/src/main/java/dev/myct/CloudModel.kt` | Cache em memória por pacote; separar descarte de inventário de revogação de confirmação. `toggleLink()` recalcula sem SSH. Reutilizar consulta válida e impedir pedidos duplicados. Aplicar expiração/invalidação descritas acima e recusar respostas tardias incompatíveis. Aproveitar resultado de tarefa já recebido para evitar sua consulta redundante na conclusão. |
| `android/app/src/main/java/dev/myct/CloudScreen.kt` | Remover exigência textual/funcional de nova consulta por seleção. Exibir estimativa derivada e estado real das verificações. Mostrar caminhos exatos no diálogo congelado e invalidá-lo ao mudar seleção. Preservar dados visuais ao revogar confirmação. |
| `ops/myct-cloud` — `snapshot()`/`live_library()` | Carregar módulo e índice Media uma vez por snapshot, reutilizando-os entre pacotes. Manter bridge e trabalhos persistentes existentes. |
| `ops/myct_cloud_progress.py` e `Cloud.kt` | Identificar inventário por metadados sem simular bytes lidos ou integridade verificada. Instrumentar leitura forte quando ela realmente ocorrer em `release`. |

### Testes necessários no código existente

Atualizar os testes existentes:

- `ops/tests/test_cloud_space.py`;
- `ops/tests/test_archive_lifecycle.py`;
- `ops/tests/test_cloud.py`;
- `android/app/src/test/java/dev/myct/CloudTest.kt`;
- `android/app/src/test/java/dev/myct/CloudModelTest.kt`;
- `android/app/src/test/java/dev/myct/CloudR1Test.kt`;
- `android/app/src/androidTest/java/dev/myct/CloudUiInstrumentedTest.kt`.

Critérios fechados para a alteração:

- zero leitura de conteúdo de mídia e zero consulta Drive durante a prévia;
- nenhuma chamada SSH ao alternar a seleção com inventário válido;
- uma carga inicial por pacote enquanto o inventário permanecer válido;
- estimativas corretas com aliases, inodes distintos e links desconhecidos;
- confirmação dos caminhos exatos exibidos, sem ampliar seleção;
- uma leitura forte por inode na operação Cloud;
- recusa de mudanças concorrentes, caminhos inseguros e backup inválido;
- nenhum arquivo não escolhido acrescentado ao plano de exclusão;
- expiração, segundo plano, navegação, reconexão, histórico e respostas tardias
  tratados sem restaurar consentimento;
- inventário incompleto explícito, sem autorizar um subconjunto como se fosse
  resultado completo;
- arquivamento/restauração e exclusão local independente preservados.

O bundle SETUP já incorpora esses helpers e regenera seus hashes. Conferir o teste
existente `ops/setup/tests/test_cloud_setup.py`, a integridade das fontes e a
compatibilidade do payload; não editar manualmente hashes gerados nem alterar o
identificador de contrato SETUP apenas por essa mudança.

Registrar o contrato e rationale em `docs/05-DECISOES-E-QUESTOES.md` e atualizar o
relatório da issue #61 na entrega de implementação. Helpers instalados e APK
precisam de atualização própria, autorizada no escopo correspondente: Git não os
implanta. Este documento não autoriza instalação ou publicação automática.

### Teste físico posterior já delimitado

A rodada posterior de testes deve usar fixture pequena reproduzível e um aceite
funcional no Moto G06 com o botão em questão, sem exclusão real. Registrar tempo
de resposta e alternância de seleção sem novo processamento remoto. Os tempos
históricos deste relatório não devem ser apresentados como comparação controlada
com uma fixture ou pacote diferente. Uma comparação antes/depois só é válida
quando o cenário e o ponto inicial/final da medida forem equivalentes.

Build e testes simulados não substituem o aceite físico exigido pela issue #61.
Nenhum desses testes foi executado nesta investigação ou no registro documental.

## Retomada e encerramento da rodada

**Rodada 1 encerrada.** A próxima solicitação é a única de implementação, usando
este contrato como entrada. Depois haverá somente a rodada de testes e,
finalmente, consolidação e fechamento da R1, conforme determinado pelo
proprietário. Não iniciar implementação automaticamente nem abrir uma segunda
investigação.

A issue #61 permanece pendente até comprovar melhora no uso real e obter
confirmação do proprietário. O aceite funcional anterior da code 51 não constitui
aceite da latência de `space`.

Referências complementares:

- [Relatório anterior da Cloud R1](MYCT-CLOUD-R1-RELATORIO.md).
- [Diagnóstico anterior após teste físico](MYCT-CLOUD-R1-POS-TESTE-DIAGNOSTICO.md).
- [Contrato de identidade e exclusão local independente](MYCT-MEDIA-LOCAL-RELATORIO.md).
- [Issue #61 — latência ainda pendente](https://github.com/Samuel-Francisco-AS/My-CT/issues/61).

O registro não contém catálogos brutos, tokens de prévia, credenciais, nomes de
mídias pessoais ou caminhos absolutos dos arquivos do acervo.
