# MYCT-CLOUD-R1 — seleção e progresso da consulta de espaço

21/10/2026. Candidata para auditoria independente da Luna, **sem aceite físico
R1 no Moto G06**. Branch `fix/myct-cloud-r1`, baseada em
`54618d91182efbd81c5c1c3124ab8a418d89ebe7` (`origin/main` conferida após fetch).
Implementação e testes no commit `2fff22e`.
A PR #59 está MERGED e integra SETUP-1/2/3/4 e AUDIT-FIX-1. Checkout inicialmente
limpo, único worktree; a branch R1 não existia. Branches e artefatos anteriores
preservados. Sem merge, release, distribuição do APK ou implantação pessoal.

## Aceite e recorte

Critérios desta implementação: preservar os 13 links por pacote ao recompor a
tela; comparar pedido persistido, resposta e seleção atual; revogar autorização
obsoleta; medir leituras efetivas por fase, com ETA apenas sustentado por amostras;
manter integridade, locks, confirmação Jellyfin e prazo de 600 s; regenerar o
bundle SETUP e executar regressões. O aceite final exige teste do proprietário.
R2 (desempenho), cancelamento de `space` e nova política de exclusão ficam fora.

## Diagnóstico com limites da evidência

No baseline, `CloudDetail` em `CloudScreen.kt` guardava `selectedLinks` e
`choices` em `remember(key)`. Essa memória pertence à composição, não ao trabalho
persistente. `CloudScreen` é removida quando se troca Nuvem/Biblioteca local
(`MediaStorageScreen`), sai da seção ou recria a Activity. O estado não era
`rememberSaveable` e não estava no `CloudModel` mantido pela aplicação.

`CloudModel.start()` apagava `space`, mas enviava uma cópia dos caminhos no
`CloudWork`; `readResult()` depois repunha a prévia sem repor a seleção visual.
Após recriação: a resposta podia conter 13 links e `can_release:true`, enquanto
a nova composição começava com seleção vazia. `canRelease()` comparava exatamente
`plan.selected == paths.sorted()` e, corretamente, recusava a liberação.
Remover essa comparação ou habilitar o botão contornaria uma proteção.

**Causa estrutural identificada:** tempos de vida diferentes para seleção visual,
pedido e resultado. O simples apagamento de `space` numa composição que permanece
viva **não demonstra**, isoladamente, perda de `selectedLinks`. Não há trace de
lifecycle das duas consultas originais; não se afirma qual evento específico
recriou a tela no incidente. Os 216,3/231,4 s, pedido/resposta de 13 caminhos e
`blockers:[]` são evidências fornecidas pelo proprietário, não medições novas.
A candidata tem teste JVM de consulta demorada e teste instrumentado de remoção e
recriação real da composição; este último foi compilado, não executado em aparelho.

Havia também uma lacuna de autorização: abrir resultado histórico podia colocar
uma prévia ainda jovem em `space` após reconexão. Agora histórico é consultável,
mas não restaura autorização. A referência de objeto, identidade e prazo do token
continuam necessários.

## Solução Android e confirmações

- Seleção e opções ficam em mapas observáveis do `CloudModel`, por archive ID.
  Sobrevivem à recriação da tela/Activity e à navegação enquanto o processo e a
  conexão permanecem. A tela lê esses mapas e mostra `13/256 links selecionados`.
- Não há persistência em disco/SavedState de seleção ou autorização. `attach()`
  limpa os mapas e a autorização em toda reconexão/mudança SSH; processo novo
  também começa sem consentimento. O diário Fedora continua recuperável.
- Um pedido `space` só parte se os caminhos coincidirem exatamente com a seleção
  do pacote aberto. O ID da tarefa e o `CloudWork` submetido são retidos apenas
  em memória. A resposta de aceite e as consultas seguintes devem corresponder
  ao mesmo ID/work, incluindo os caminhos persistidos no recibo do bridge.
- No resultado, ID do pacote e seleção retornada precisam coincidir com o pedido.
  Divergência bloqueia a prévia e mostra mensagem própria. A seleção atual também
  precisa coincidir; a resposta nunca marca caixas automaticamente.
- Só o resultado `succeeded` da consulta iniciada e ainda válida nesta sessão pode
  autorizar. Abrir histórico, trocar pacote, alterar seleção, atualizar uma prévia
  já concluída, sair da tela/segundo plano ou reconectar revoga essa autorização.
  Após a correção A01 abaixo, Atualizar apenas acompanha o `space` corrente ativo
  sem apagar sua identidade; isso não recupera autorizações já revogadas. A seleção
  visual é preservada na mesma conexão; uma **nova consulta explícita** a revalida.
  Mesmo uma consulta que termine fora da tela não rearma consentimento ao voltar.
- `canRelease()` exige também `authorized === plan`, seleção atual exata e
  ausência de blockers, além das proteções anteriores. O diálogo mantém a
  confirmação específica Jellyfin. Alteração de seleção/identidade/prazo invalida
  até um diálogo já aberto. O teste envia `release` apenas a transporte simulado.

O motor de `release` continua refazendo o inventário autoritativo sob os locks
existentes, verificando Drive, arquivos, inodes e vínculos antes de excluir.
Telemetria e tempos **não entram no plano nem no token**, pois isso tornaria sua
comparação não determinística. Uma regressão compara o token com/sem telemetria.

## Contrato de telemetria

Mantém o arquivo privado `JOB.progress`, atômico, 0600 sob diretório 0700,
separado dos recibos e catálogos. Só worker/filho vinculado a recibo `running`
publica; CLI avulsa `space` continua sem escrita de telemetria. `myct-cloud`
continua executando unidades persistentes e publicando o resultado autoritativo;
seu código e regras de cancelamento não foram alterados.

Upload/download mantêm a semântica anterior de bytes de `copyto` observados por
Rclone. `space` acrescenta **`metric:"read", scope:"phase"`**, com:

| Campo/fase | Significado |
| --- | --- |
| `preparing` | Preparação e leitura inicial do catálogo |
| `remote_check` | Listagem/validação Rclone de dados, manifesto e metainfo; indeterminada |
| `local_inventory` | Bundle/metainfo, catálogo Media e enumeração local; indeterminada |
| `local_read` | Hashing de uma árvore local; denominador dos arquivos realmente enumerados |
| `transmission_check` | Consultas RPC e conflitos com outros torrents; indeterminada |
| `library_inventory` | Descoberta/conferência de nomes, hardlinks e inodes; indeterminada |
| `library_read` | Hashing de vínculo cujo inode não foi reconhecido nas árvores |
| `finalizing` | Consolidação de inodes/plano e preparação do token/resultado; indeterminada |
| `read_pass` | Número da leitura mensurável; distingue reinícios entre árvores/vínculos |
| `bytes_done` | Soma de `len(block)` devolvidos pelo leitor efetivo MD5/SHA-256 desta leitura |
| `bytes_total` | Soma dos tamanhos enumerados da árvore, ou tamanho do vínculo; `null` se deixa de ser confiável |
| `percent` | Fração de bytes desta leitura, nunca porcentagem global; indisponível ao esgotar a leitura |
| `speed_bps` | Taxa observada no último intervalo de publicação |
| `eta_seconds` | Restante desta leitura pela média de amostras estáveis; caso contrário `null` |
| `phase_seconds` | Tempos acumulados por classificação, em segundos monotônicos, sem caminhos |

São **bytes lidos pelo processo**, sujeitos a cache/releituras; não são bytes de
rede nem prova de IO físico do SSD. Duas árvores independentes produzem duas
leituras numeradas, com contagem monotônica dentro de cada leitura. Um hardlink
reconhecido por inode é inventariado sem adicionar bytes de leitura. Se o motor
precisa reler um vínculo, esses bytes reais aparecem numa leitura própria. Nada
muda na contagem de blocos/inodes para estimar espaço recuperável.

O callback ocorre após `read()` efetivo e atualização dos hashes. Publicações de
blocos são limitadas a **uma a cada 2 s**; há publicação nas fronteiras de fase e
flush final de cada leitura. O teste de 30 callbacks produz três publicações
periódicas, não 30 `fsync`. ETA exige pelo menos três intervalos de 2 s, todas as
taxas positivas e desvio máximo de 50% da média nas últimas até cinco amostras.
Leitura curta, taxa instável/zero, total ausente e fases não mensuráveis não têm
ETA. Amostra com mais de 8 s deixa de sustentar taxa/ETA no Android.

Ao esgotar bytes, não se mostra 100% global. Fases seguintes continuam
indeterminadas. `Progress.finish()` só recebe sucesso depois de o bridge salvar
resultado `ok` autoritativo; para `metric:read`, até o estado `done` permanece sem
porcentagem global. Erro/interrupção não publica sucesso. `can_release:false` pode
ser uma consulta concluída com sucesso e, ainda assim, manter liberação bloqueada.

O leitor de progresso agora usa `open(O_NOFOLLOW|O_NONBLOCK)` + `fstat` e leitura
limitada no mesmo descritor. Aceita snapshot privado já aberto e substituído
atomicamente (`nlink=0`); recusa symlinks, múltiplos hardlinks, modos/proprietário
inválidos e dados excessivos. Isso resolve a corrida lstat/open encontrada na
regressão. Falha de escrita é tolerada pelo publicador, sem tocar em catálogo.
Não são publicados paths privados, credenciais, URLs OAuth ou logs Rclone.

## Compatibilidade SETUP e arquivos

Inspecionados `manifest.json`, `build_bundle.py`, `cloud.py`, Gradle e
`ServerSetup.kt`. O manifesto já lista os três helpers modificados e o Gradle já
os declara como inputs. `buildSetupBundle` regenera `MANIFEST.sha256` no instalador,
`diagnose.py`, `payload.pyz` e `integrity.json`; não há hashes estáticos de helpers
para editar manualmente no manifesto fonte. A prova adicional reconstrói dois
bundles idênticos, confere todas as fontes/hashes e a lista exata de entradas.

O identificador **`myct-setup-4-fix1-rc1`** foi mantido: ele identifica o contrato
SETUP aceito, inclusive a allowlist de recuperação. Alterá-lo indiscriminadamente
quebraria backups; os hashes distinguem o payload novo. O diagnóstico continua
passivo. Instalação nova recebe os helpers atuais; helper existente divergente
vira `previous` e bloqueia aplicação, sem adoção/atualização automática.
O APK só embute fontes da allowlist; não inclui catálogo, mídia ou credencial.

APK local de validação: **`dev.myct`, 0.13.2-cloud-r1-rc1 / code 49**, acima do
code 48 integrado. Assinatura anterior preservada; nenhum APK foi distribuído.

Arquivos de produção: `CloudModel.kt`, `CloudScreen.kt`, `Cloud.kt`,
`android/app/build.gradle.kts`, `ops/myct-archive`, `ops/myct-archive-torrent`,
`ops/myct_cloud_progress.py`. Testes: `CloudModelTest.kt`, `CloudProgressTest.kt`,
`CloudR1Test.kt`, `CloudUiInstrumentedTest.kt`, `ops/tests/test_cloud_space.py`,
`ops/setup/tests/test_cloud_setup.py`. Documentação: este relatório, README e
`docs/05-DECISOES-E-QUESTOES.md`.

## Validação e custos da entrega inicial (head `02c9678`)

Ferramentas observadas: Fedora 44/Linux 7.2.8, Python 3.14.7, Git 2.55.0,
Java 21.0.10, Gradle wrapper 8.14.3, SDK/build-tools 36, Podman 5.8.7.
Nenhuma dependência instalada. Logs locais em
`artifacts/myct-cloud-r1-validation/`, ignorados pelo Git.

| Verificação | Resultado final |
| --- | --- |
| JVM completo | **322 PASS, 0 FAIL, 2 SKIP** (324 casos) |
| JVM Cloud dirigido após ajuste final de rótulo | **30 PASS, 0 FAIL, 0 SKIP**; build/lint também PASS |
| Operacional `ops/tests`, Fedora 44 descartável offline | **209 PASS, 0 FAIL, 2 SKIP** (211 casos), 39,719 s |
| Novos testes `test_cloud_space.py` (incluídos acima) | **7 PASS, 0 FAIL, 0 SKIP**; 1,144 s na rodada dirigida |
| SETUP no host, fixtures | **58 PASS, 0 FAIL, 0 SKIP**, 3,423 s |
| SETUP Fedora 44 descartável, usuário tester | **58 PASS, 0 FAIL, 0 SKIP**, 3,628 s |
| `assembleDebug` e `assembleDebugAndroidTest` | PASS; teste instrumentado compilado, não executado |
| `lintDebug` | PASS: 0 erros, 1 warning UseKtx e 2 hints anteriores |
| Bundle fonte e embutido no APK | PASS: 16 fontes/hashes conferidos, reprodução determinística |
| Certificado contra code 48 | PASS: SHA-256 idêntico |
| `git diff --check` | PASS |
| Moto G06 / Drive e acervo reais | **NÃO EXECUTADOS / SEM ACEITE R1** |

Os 2 SKIP JVM são os testes opcionais `FedoraHandshakeTest` e
`IdeFedoraSftpTest`, não habilitados contra serviços pessoais. Os 2 SKIP Python
são as provas legadas com unidades reais systemd (`test_cloud` e
`test_cloud_cancel`), pois o contêiner desta rodada não tem user manager/linger.
Não foram contados como PASS. O teste novo de persistência usa processo worker
independente real e transporte/engine simulados, sem depender desse manager.

Comandos principais reproduzíveis (ops completa somente no ambiente descartável):

```bash
python3 -m unittest discover -s ops/tests -p 'test_*.py' -v
python3 -m unittest discover -s ops/setup/tests -p 'test_*.py' -v
# Na raiz do checkout, com SDK/assinatura previamente disponíveis:
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --offline --console=plain
# Complementação após rótulo final da UI:
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:testDebugUnitTest --tests 'dev.myct.Cloud*' \
  :app:assembleDebug :app:lintDebug --offline --console=plain
```

Contêineres rootless com imagem local `localhost/myct-setup4-test:latest`,
`--rm --network=none --memory=1536m --cpus=1 --pids-limit=1024`, fontes copiadas
por tar, sem bind mounts pessoais. SETUP roda como `tester`. Logs finais:
`android-verified.log`, `android-ui-final.log`, `setup-final.log`,
`setup-container-final.log` e trecho operacional/JSON de `python-verified.log`
(a tentativa SETUP anexada a este último foi substituída pelo log específico).

Pico RSS por processo no ensaio operacional: **44.752 KiB**, incluindo o maior
filho, não soma do contêiner. Build/test/lint Android completo: **3m29,79s**,
pico **2.518.292 KiB**. São custos de desenvolvimento; não benchmark de `space`
no acervo. Nenhum limite de recursos pessoal foi alterado.

Bundle do APK: **103.127 bytes**; SHA-256
`1d92c5291e504d2ca1c0f7e266170bb07ca85373be63d0074c4b3fe7fb21233f`.
Diagnóstico SHA-256:
`745819c977f21498bd5a057151fbe179836d66753b4d01bf8cda822197256a63`.
Certificado SHA-256 público, igual ao code 48:
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Hash do APK local da entrega inicial: `e78d4c32f0dac869f1f341bf0fe0c9bc90716b50d57b8e0df839d0ef14f80a1b`. Não é artefato de distribuição.

A suíte JVM simula 231,4 s com relógio controlável, cobre 13 caminhos no pedido e
resposta, navegação, retorno de segundo plano, reconexão, processo novo, seleção
alterada, expiração, troca de pacote/resposta tardia, divergência, blockers,
`can_release:false` e confirmação Jellyfin. Não é prova de toque/renderização.
O teste instrumentado acrescentado remove e remonta `CloudScreen` durante a
consulta, confere a contagem e a autorização revogada; sem aparelho, só compilado.
`adb devices` não encontrou dispositivo; nenhum teste físico R1 executado.

O worker testado em processo separado usa apenas helpers falsos e bytes de
fixture temporária: mantém pedido de 13 links, progresso parcial e resultado
após o cliente de consulta sair, sem criar unidades pessoais. Os testes de disco
usam árvores descartáveis; Google Drive e Transmission são simulados. A suíte
legada de `release` só opera fixtures dentro do contêiner. Nenhum `release`,
consulta Drive autenticada ou alteração de acervo real foi executado.

Falhas intermediárias registradas nos logs: import de teste sem path, modo de
falha incorreto na fixture remota, dois testes JVM antigos sem pacote selecionado,
import do novo módulo fora do tratamento de dependências, corrida do leitor de
telemetria; corrigidos e revalidados. Uma tentativa de renomear a versão SETUP foi
revertida após as regressões de recuperação, preservando o contrato existente.
O harness inicial do contêiner não tinha `/usr/bin/time` e uma cópia para testar
SETUP omitiu fontes C++ do bundle; outra execução SETUP como root foi
corretamente bloqueada pelo contrato de conta normal. Harness refeito como
`tester`, sem relaxar esses guards, instalar ferramentas ou alterar serviços pessoais. Esses erros não são contados como PASS.

## Procedimento futuro, somente após auditoria e autorização

1. Luna deve revisar o diff contra a base integrada, autorização da prévia, token,
   leituras reais, proteção de catálogo e hashes no APK. Não marcar a PR pronta
   nem fazer merge enquanto a auditoria/aceite estiverem pendentes.
2. Após liberação explícita da candidata, reconstruir deste commit com o mesmo
   certificado, conferir `dev.myct`, versionCode 49 e hashes. Instalar sobre o
   code 48 no Moto **sem desinstalar/limpar dados**. Não usar esta documentação
   como autorização para publicar release ou distribuir APK agora.
3. Para R1A, helpers antigos continuam consultáveis, com progresso indeterminado.
   Para R1B, é necessária atualização manual autorizada dos três helpers acima,
   do mesmo checkout revisado. Antes, conferir que não há job/unidade ativa ou
   incerta e guardar backup privado dos executáveis/catálogos; não encerrar jobs
   para instalar. Preservar Rclone/OAuth/seleção remota e todos os demais helpers.
   Não usar SETUP para sobrescrever instalação preexistente divergente.
4. Com autorização específica e ausência de trabalhos, os arquivos a instalar
   juntos são `myct-archive`, `myct-archive-torrent` (0700) e
   `myct_cloud_progress.py` (0600), no diretório privado de helpers já usado.
   Conferir SHA-256 fonte/destino. Git/pull/build não atualizam executáveis
   instalados. Não alterar serviços, linger, SSH, timers ou configuração do Drive.
5. No Moto: abrir o pacote/fixture previamente verificado; obter opções se preciso;
   selecionar 13 links e consultar. Conferir contagem durante/depois, leitura
   numerada, fases e ETA quando houver amostras. Sair/voltar: seleção deve continuar,
   mas exigir nova consulta para liberar. Repetir com reconexão: sem consentimento
   restaurado. Mudança de seleção ou prazo invalida a prévia.
6. Conferir o diálogo e a confirmação específica Jellyfin; cancelar o diálogo.
   **Não executar liberação de acervo real para provar esta R1.** Uma eventual
   operação destrutiva de fixture exigirá autorização separada do proprietário.
   Registrar observações do Moto na PR antes de declarar aceite funcional.

## Riscos residuais e preparação de R2

- O gatilho exato do incidente original não foi rastreado no aparelho. A correção
  remove a dependência estrutural de `remember`, mas aceite físico continua aberto.
- Seleção por pacote dura só no processo/conexão; reconexão deliberadamente limpa
  opções/seleção. Histórico não recria autorização. Tela/segundo plano exigem nova
  consulta, mesmo que isso custe outra leitura completa: proteção existente mantida.
- Não há progresso retroativo para jobs iniciados com helpers antigos. Polling
  visível continua a cada 3 s, sem polling em segundo plano/push. Worker Fedora
  continua; reboot/crash não ganha retomada automática nesta R1.
- `phase_seconds` separa Rclone, leituras locais, inventário e RPC; é evidência
  sanitizada da tarefa, não log bruto nem parte do catálogo. Tempos de finalização
  cobrem consolidação/token no motor; a última serialização/fsync do recibo no
  bridge não é integralmente cronometrada por ele. A duração `finished-created`
  do recibo permanece a referência total e inclui inicialização/entrega.
- Não se mediu novamente o pacote de ~18 GiB. Os testes curtos provam contagem e
  isolamento, não apontam gargalo de produção. Possíveis custos de Rclone,
  hashing de múltiplas árvores, walk de biblioteca e RPC são **hipóteses para R2**.
  Usar tempos de uma consulta autorizada futura para escolher o gargalo real.
  Nenhum cache, hashing paralelo, supressão de verificação ou otimização profunda.
- Publicações em fronteiras/leituras muito curtas ainda geram IO proporcional ao
  número de fases; somente o callback de blocos tem teto periódico de 2 s.
  Isso fica mensurável para R2, sem introduzir um serviço de telemetria.

Após publicar a PR draft, parar e aguardar a auditoria independente da Luna.


## Correção pós-auditoria R1-A01 — Atualizar durante `space`

21/10/2026. [Auditoria independente da Luna, comentário 6309324947](https://github.com/Samuel-Francisco-AS/My-CT/pull/60#issuecomment-6309324947),
no head `02c9678b4b379a0df92d09e5cf40943d39879ebd`: **CHANGES REQUESTED**.
Esta correção é proposta na mesma branch/PR draft #60, com os dois commits
anteriores preservados. Não equivale a aceite da reauditoria ou do Moto.

**Causa confirmada:** o botão Atualizar fazia `invalidatePreview(); refresh()`.
`busy` só cobre a entrega/consulta SSH, não os minutos de execução do trabalho
Fedora. Portanto, depois do aceite, o botão habilitado apagava `previewRequest`;
`readResult()` depois não podia associar a prévia à consulta da sessão, mesmo
com 13 caminhos idênticos, sucesso autoritativo e ausência de bloqueios.

**Solução mínima:** `CloudScreen` passa a usar `CloudModel.refreshFromUser()`.
O método recusa chamada desconectada/ocupada. Preserva o pedido somente se há
`previewRequest` e uma tarefa **ativa com o mesmo ID e o mesmo CloudWork**, cuja
ação é `space`. Nessa situação chama a consulta de acompanhamento existente.
Sem essa correspondência, invalida a prévia antes de consultar, como antes.
Atualização manual após conclusão continua revogando a autorização; atualizar
um histórico ou trabalho após invalidação não reconstrói `previewRequest`.

O polling automático e a execução Fedora não mudam. Se o Fedora já tiver
concluído, mas o Android ainda souber da tarefa como ativa, a atualização pode
receber o resultado autoritativo e autorizar a prévia na mesma consulta. Se o
Android já tiver publicado a prévia concluída, Atualizar a revoga. Navegação,
ON_STOP/disposal, troca de pacote, reconexão e mudança efetiva da seleção mantêm
as invalidações existentes. Comparações exatas, token, prazo máximo de 600 s,
`can_release`, blockers e confirmação específica Jellyfin não foram alterados.

Arquivos modificados nesta correção: `CloudModel.kt`, `CloudScreen.kt`,
`CloudR1Test.kt`, `CloudUiInstrumentedTest.kt` e este relatório. Sem alterações
em helpers Fedora, telemetria, manifesto/builder SETUP ou configuração Gradle.
`dev.myct`, versionCode **49** e certificado anterior permanecem preservados.
O bundle SETUP permanece byte a byte igual ao da entrega inicial; os testes
Python/integração não foram repetidos por não haver alteração dos seus inputs.

### Regressões A01 e evidência

O teste positivo usa a mesma função do botão: seleciona 13 episódios, recebe
aceite, confirma `busy=false`/tarefa ativa, toca Atualizar duas vezes durante
231,4 s simulados e conclui com os mesmos 13, `can_release=true`/`blockers=[]`.
Exige seleção 13, `canRelease=true`, somente um início `space` e consultas de
tarefa, sem novo snapshot durante o acompanhamento nem execução de `release`.

Os negativos atualizam durante navegação/segundo plano, reconexão, expiração,
troca de pacote e divergência de resultado; continuam bloqueantes. Edição de
seleção durante a tarefa permanece recusada pelo modelo/UI (13 inalterados);
a edição efetiva após conclusão revoga a prévia, e abrir histórico não a rearma.
Outro teste comprova que Atualizar após prévia concluída a revoga e histórico
não recupera o consentimento. O teste instrumentado usa o botão acessível
Atualizar e compara os casos com/sem descarte da composição, sem executar
liberação; apenas compilado nesta missão.

| Verificação A01 | Resultado final |
| --- | --- |
| Cloud JVM dirigido (`Cloud*`) | **34 PASS, 0 FAIL, 0 SKIP** |
| JVM completo (328 casos) | **326 PASS, 0 FAIL, 2 SKIP** |
| `assembleDebug` | PASS |
| `assembleDebugAndroidTest` | PASS; três cenários Cloud compilados, não executados em dispositivo |
| `lintDebug` | PASS: 0 erros, 1 warning UseKtx e 2 hints anteriores |
| Certificado / applicationId / versionCode | PASS: certificado anterior, `dev.myct`, 49 |
| Bundle SETUP / diagnóstico no APK | PASS: hashes idênticos aos da entrega inicial |
| `git diff --check` | PASS |
| Helpers/Python/integração Fedora | Não alterados; testes não repetidos nesta correção |
| Moto G06 / teste físico | NÃO EXECUTADO; distribuição aguarda reauditoria |

Os dois SKIP são os testes opcionais já existentes de SSH/SFTP reais
(`FedoraHandshakeTest` e `IdeFedoraSftpTest`). Seus flags foram explicitamente
removidos do ambiente desta rodada para preservar os serviços pessoais.
Logs: `cloud-build-lint.log` + `cloud-results.json` para os 34 testes dirigidos;
`android-final.log` + `jvm-results.json` para a rodada final completa, que também
compilou os testes instrumentados e executou build/lint. A primeira rodada
agregada terminou com falha no helper de teste nullable mencionado abaixo;
os resultados PASS de build/lint referem-se à rodada final corrigida.

```bash
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:testDebugUnitTest --tests 'dev.myct.Cloud*' \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  --offline --console=plain
# Rodada completa após correção do helper do teste de clique:
env -u MYCT_TEST_LOCAL_SSH -u MYCT_TEST_LOCAL_SFTP \
  ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug --offline --console=plain
```

APK local A01: **19.399.434 bytes**, SHA-256
`133d40bfb6ee9eaec7c2a608f302c31a23731cb1f629b1ae57a65763237700dc`.
Continua com a assinatura pública SHA-256
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Payload SETUP e diagnóstico no APK foram verificados contra os dois hashes
registrados na entrega inicial; igualdade comprovada sem alterar o bundle.
Rodada final Android: **1m13,56s**, pico RSS **1.623.760 KiB**, custo de
desenvolvimento, não medição de runtime Fedora. Nenhuma dependência, parâmetro
de Gradle ou limite pessoal foi modificado.

Logs locais privados em `artifacts/myct-cloud-r1-a01-validation/`.
A reprodução válida antes da correção terminou em **1 FAIL**, na asserção
`canRelease` (log `reproduction-before-fix-corrected.log` e XML
`reproduction-confirmed.xml`); esse resultado é evidência do defeito, não PASS.
A primeira versão da fixture usava `single()` ignorando a tarefa histórica já
presente; foi corrigida para identificar a tarefa ativa. A primeira compilação
do novo helper de clique instrumentado apontou nullable indevido; tipo explícito
corrigido antes da validação final. Nenhum guard de produção foi relaxado para
satisfazer testes.
Nenhuma distribuição de APK, instalação no Moto, consulta Drive, operação no
acervo, deploy ou alteração de serviços pessoais. R2 não foi iniciada.
A PR permanece draft, aguardando reauditoria da Luna antes de distribuição e
teste físico autorizado; ainda sem aceite funcional R1.
