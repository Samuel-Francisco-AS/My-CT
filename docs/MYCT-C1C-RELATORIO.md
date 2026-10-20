# MYCT-C1C — Nuvem no Android

**20/10/2026 — ACEITE FUNCIONAL REAL NO MOTO G06 PELO PROPRIETÁRIO.**
[PR #50](https://github.com/Samuel-Francisco-AS/My-CT/pull/50), branch
`myct-c1c-cloud-android`. A C1C cobre Nuvem no Android, trabalhos duráveis
no Fedora e progresso real no upload. **Fechamento da implementação autorizado**;
os resultados ainda não observados fisicamente são mantidos como limitações, não
promovidos ficticiamente a PASS. A situação de merge consta no GitHub e na seção
de fechamento remoto deste relatório.

A descrição de candidata RC1/RC2 nos blocos abaixo é **histórica, anterior ao
aceite físico e ao fechamento**.



## Aceite físico final C1C no Moto G06 — 20/10/2026

**Autorização expressa de fechamento concedida pelo proprietário.**
Evidências foram obtidas no app instalado no Moto G06 e no Fedora real, sem
reexecutar matrizes de teste antigas. O uso cotidiano do app e acesso ao servidor
prosseguiram durante a fase.

**Upload real C1B-2 / nuvem:** o pacote de mídia nomeado `S01` (~24,4 GiB)
foi arquivado no Drive via RC1. A operação entrou em `running` (job
`22894a4872e3483f80a8b269f980bad4`) e após conclusão o app exibiu
`Arquivar: concluído`, pacote `24.4 GiB · Arquivado`, Drive
`Arquivado e verificado`, Transmission pausado e 28 nomes/28 hardlinks Jellyfin
presentes. Capturas da UI do Google Drive mostraram episódios separados na tela
**Sugestões** (que não representa a estrutura real de pastas) além de
`manifest.json` e `original.torrent`. O motor remoto conserva a árvore
`My-CT-Archive/torrents/<archive-id>/data/...`. Esses metadados não devem ser
movidos/excluídos manualmente.

**RC2 / progresso real:** app RC2 code 42 e sete helpers instalados após concluir
upload grande. Captura física de envio de arquivo próprio descartável de 128 MiB
revelou **20,6%, 26,5 MiB de 128 MiB, 1,7 MiB/s, ETA ~1min02s, etapa 1/1**,
barra de progresso e comando `Cancelar transferência…` visíveis.
O proprietário saiu da aba e do app, retornou, e **o upload prosseguiu e o
progresso foi recuperado**; reportou **fim das piscadas** de `Atualizar` e
`Resultado`. A conclusão final com hash da fixture de 128 MiB não foi
documentada em saída separada; o aceite físico de progresso *em operação*
não equivale à validação da conclusão dessa fixture específica.

**Liberação real de espaço pela interface Android:** o proprietário optou por
remover a temporada S01 do SSD, mantendo o Drive. Após a operação, a tela
exibiu **`S01 · 24,4 GiB · Liberado do SSD`**, com
`Drive: Arquivado e verificado · pode restaurar`. A aba Saúde confirmou
`Core conectado`, CPU ~0%, RAM **1,7 GiB usada de 7,6 GiB**, disco raiz
**171,0 GiB ocupados / 221,6 GiB totais / 50,5 GiB livres** e uptime do Core
**0d 18h 11min 56s**, todos da última consulta exibida. A medição de 50,5 GiB
livres é um **snapshot final**, e não prova matemática isolada de exatamente
24,4 GiB liberados. O proprietário confirmou que a temporada poderá ficar
offline no Jellyfin até restaurar do Drive. Nenhum arquivo do acervo foi
removido por intervenção externa; a liberação foi acionada pelo usuário
na interface aprovada, com os consentimentos do motor.

**Verificações Android adicionais:** no mesmo aparelho, listagem real das
entradas, navegação para os detalhes, tarefa assíncrona `space`, resultado
consultável, bloqueio seguro da fixture antiga com `LOCAL_DIVERGENCE` /
`NO_LOCAL_DATA`, consulta de seeding e hardlinks, retorno ao app/SSH e
interface estável. No período de desenvolvimento, o upload grande de 24,4 GiB
não foi interrompido para instalar helpers; a troca para RC2 aconteceu
posteriormente.

### Evidências não obtidas fisicamente e condições de manutenção

- **Cancelar transferência:** o botão e o diálogo existem no APK; o fluxo
  servidor foi coberto pelos testes direcionados e simulação isolada, **mas o
  cancelamento de uma transferência real a partir do Moto ainda não foi
  demonstrado**. Não afirmar PASS físico.
- **Download / restauração com porcentagem e ETA:** implementação e mocks
  aprovados, porém **sem captura física de restauração em andamento** nessa RC2.
- **Fixture C1B-2 antiga:** diretório restaurado divergente (`LOCAL_DIVERGENCE`)
  e link Jellyfin ausente permaneceram bloqueados; o motor evitou exclusão e
  não houve limpeza cega. Tratar só se o proprietário voltar ao assunto.
- **Armazenamento Btrfs:** os cálculos `space` são estimativos; ganhos
  efetivos precisam ser medidos antes/depois para qualquer novo pacote.
- **Persistência:** queda do processo no Fedora/reboot não ganha retomada
  automática. Arquivamentos com resultado `uncertain` exigem inspeção
  manual, sem replay automático.
- **Drive:** este trabalho mantém cópia remota e catálogo; o encerramento
  não autoriza exclusão remota, nem reinstalar/desinstalar APK.

**Decisão:** C1C aceita funcionalmente e autorizada para merge/fechamento
padrão; os dois comportamentos físicos não exercitados permanecem descritos
como limitações documentadas, sem criar uma subfase ou exigir outro upload
de 24,4 GiB.

## Correção atual — 0.9.1-c1c-rc2 / code 42

Esta revisão responde aos dois comentários da Luna, na **mesma PR #50 draft**:
[progresso/estados](https://github.com/Samuel-Francisco-AS/My-CT/pull/50#issuecomment-6283497682)
e [polling/cancelamento](https://github.com/Samuel-Francisco-AS/My-CT/pull/50#issuecomment-6283572595).
Os critérios de aceite são os contratos desses comentários: números reais do
Rclone para upload/restore C1A e torrent multiarquivo; persistência após
reconexão; nenhuma conclusão antes da validação; controles estáveis; cancelamento
confirmado apenas de cópias; preservação de originais e dos bloqueios do motor.

**Proteção operacional:** o upload real de aproximadamente **24,4 GiB** foi
observado ativo durante o desenvolvimento. Não foi interrompido, reiniciado,
editado nem usado em testes. Nenhum helper em `~/.local/bin` foi substituído.
Não há progresso retroativo nem cancelamento habilitado para esse job antigo.
A instalação dos helpers e do APK fica para **depois da conclusão e conferência
remota desse upload**, com uma única sessão curta no Moto e fixture pequena.
Não repetir o upload de 24 GiB para testar. Publicar a candidata não instala nada.
Na RC1 o proprietário já comprovou consultas/tela reais e `space` de Severance;
isso não autoriza liberação desse conteúdo nem comprova conclusão do upload.

### Progresso e integridade

- `myct_cloud_progress.py` é um módulo compartilhado pelos helpers, sem serviço
  residente. Somente o worker `myct-cloud` e seu filho, vinculados a recibo
  `running` com `worker_pid`, habilitam a escrita. CLI avulsa e consultas não
  escrevem progresso. O catálogo autoritativo permanece separado.
- `copyto` opt-in usa `--use-json-log --stats 2s --stats-log-level NOTICE`.
  Consome somente `stats.bytes`, `totalBytes`, `speed`, `eta` numéricos válidos.
  Stderr é drenado simultaneamente ao stdout; linhas e buffer privado de erro
  são limitados. Nenhum log bruto Rclone é persistido ou devolvido ao Android.
  [Contrato oficial Rclone](https://rclone.org/docs/#use-json-log).
- Cada `ID.progress` fica em `~/.local/share/myct/cloud-jobs`, 0600/0700, por
  substituição atômica e `fsync`. Contém somente `phase`, `bytes_done`,
  `bytes_total`, `percent`, `speed_bps`, `eta_seconds`, `updated_at`,
  `current_file_index` e `total_files`; campos desconhecidos não são projetados.
- A soma inclui todos os `copyto` efetivos do pacote, inclusive `.torrent` e
  manifesto. Objetos já verificados e dispensados de nova cópia não são somados
  como bytes transmitidos. Hashing e validação não aumentam o numerador. Se um
  `copyto` não produzir stats válidas, não se deduzem bytes pelo tamanho do arquivo
  nem se inventa porcentagem. Uma tentativa nova tem seu próprio ID/contagem.
- ETA usa taxa real suavizada entre amostras/arquivos. Taxa zero, ETA Rclone
  ausente, total desconhecido, hashing ou validação mostram **Tempo estimado
  indisponível**. Stats com mais de 8 s também deixam de sustentar taxa/ETA.
- A barra é determinada quando a porcentagem é conhecida. Ao terminar o
  transporte, informa **Transporte finalizado; verificação pendente**, com barra
  indeterminada; só apresenta 100% após resultado de sucesso do motor, incluindo
  hashes/metainfo e gravação do catálogo. A porcentagem exibida é truncada a uma
  casa decimal para não arredondar 99,99% para 100 antes da verificação.
- Últimas operações e detalhes mostram fase, porcentagem, bytes exatos/formatados,
  taxa, ETA e etapas. `BUSY` é informativo: **Transferência em andamento; catálogo
  temporariamente ocupado**. Mantém entradas/progresso conhecidos, sem habilitar
  mutações com catálogo indisponível. Diretório presente não comprova integridade.
  `LOCAL_DIVERGENCE` passa a **Arquivos locais não correspondem ao pacote arquivado;
  liberação bloqueada**; `NO_LOCAL_DATA` redundante é ocultado apenas na apresentação.
  Nenhum bloqueio é contornado e nenhum resíduo é removido automaticamente.

### Polling e cancelamento

- Polling visível a cada **3 s** consulta apenas `task ID` enquanto há trabalho
  ativo. Não altera `busy`, seleção, rolagem ou diálogo. Um mutex serializa IO;
  consultas sobrepostas são agrupadas. Atualizar manual também consulta a tarefa
  ativa. Catálogos são lidos uma vez após término/falha/cancelamento ou reconexão.
  Não há polling Android em segundo plano; o worker Fedora continua independente.
- **Detalhes / progresso** fica disponível durante o job; **Resultado** aparece
  após término. O botão **Cancelar transferência…** aparece apenas para
  `archive`/`restore` de arquivo/torrent novos e compatíveis. Um diálogo identifica
  o conteúdo e explica preservação de originais/backup anterior, possíveis
  parciais e ausência de retry automático. Fechar tela/SSH não envia cancelamento.
- `myct-cloud cancel ID` aceita somente ID hexadecimal exato de recibo privado,
  versão/origem compatível e ação de cópia. Confere nome exato da unidade,
  `MainPID` do worker e argv em `/proc`, além de `KillMode=control-group`.
  Não recebe PID/nome de serviço fornecido pelo cliente. `release`, `recover`,
  `space`, `inspect`, `relink` e jobs antigos não são interrompidos.
- Grava `cancel_requested` antes do `systemctl --user stop --no-block` específico.
  O spawn do motor e o pedido de cancelamento são serializados para não iniciar
  transporte após a solicitação. O worker coleta o JSON sanitizado do motor;
  `INTERRUPTED` produz `cancelling`, ainda bloqueante. Somente unidade inativa
  com esse recibo vira `cancelled`. `stop` aceito isoladamente nunca equivale a
  cancelamento confirmado. Sucesso que vence a corrida permanece `succeeded`.
- Unidades novas têm `KillMode=control-group` e `TimeoutStopSec=15s`. O systemd
  encerra os filhos remanescentes se o desligamento gracioso não terminar.
  Resultado perdido, identidade não confirmada ou stop ambíguo gera `uncertain`,
  sem replay e sem nova mutação até avaliação. O protocolo é idempotente;
  repetir `cancel` não despacha outro stop. [Semântica oficial do systemd](https://www.freedesktop.org/software/systemd/man/latest/systemd.kill.html).
- Cancelar não executa comandos de exclusão adicionais. O motor mantém seu
  tratamento existente de temporários próprios, integridade, catálogos e objetos
  parciais. Download interrompido não é publicado como arquivo completo/verificado.
  Torrent pode conservar arquivos completos já restaurados em uma árvore parcial;
  nenhum pacote parcial recebe `verified`. Originais e backup verificado prévio
  são preservados. Reboot/queda não ganham retomada automática nesta correção.

### Verificações desta revisão

```bash
python3 -m unittest discover -s ops/tests -p 'test_*archive*.py' -v
python3 -m unittest discover -s ops/tests -p 'test_cloud*.py' -v
cd android
ANDROID_HOME=/home/sam/Android/Sdk ./gradlew \
  :app:testDebugUnitTest --tests 'dev.myct.Cloud*' \
  :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin \
  --offline --console=plain
```

- Moto: ADB não encontrou aparelho autorizado. O teste instrumentado
  `CloudUiInstrumentedTest` foi acrescentado para botão/diálogo e estabilidade
  dos controles com polling bloqueado e SSH falso. **Compilação desse teste não
  é execução nem aceite no aparelho.** Não se usou emulador nem se instalou APK.
- Prova local adicional: Rclone **1.74.3 real**, somente entre dois arquivos
  temporários, 147.456 bytes idênticos. Stats reais observadas: 98.304 bytes
  (66,66%), 147.456 bytes, taxa e ETA numéricos; fase final pré-recibo `verifying`,
  porcentagem nula. Não acessou Drive, OAuth ou acervo. Os testes de transferência
  multiarquivo e cancelamento usam motores/Rclone simulados e fixtures próprias.
- Logs de validação locais ignorados pelo Git:
  `artifacts/myct-c1c-rc2-validation/`.

### Resultado final e artefato RC2

| Verificação direcionada | Resultado |
| --- | --- |
| Regressões dos motores C1A/C1B-2 | 79 PASS, 24,610 s |
| Bridge, progresso e cancelamento | 33 PASS, 4,349 s |
| JVM `Cloud*` | 24 PASS, sem falhas/erros/skip |
| APK / lint | PASS; 0 erros, 0 warnings, 2 hints existentes |
| `compileDebugAndroidTestKotlin` (inclui UI) | PASS; não executado em aparelho |
| Certificado contra RC1/code 41 | Idêntico, conferido por apksigner |
| Upload grande / helpers instalados | Preservados; unidade observada ativa, hashes instalados idênticos |
| Aceite Moto RC2 | Pendente após término/conferência do upload grande |

Rodada final Android: **3m20,38s**, pico RSS **2.342.460 KiB**; regressões Python
medidas: pico **30.352 KiB**, 24,75 s externos. É custo de desenvolvimento, não
medição do runtime de uma transferência Drive de 24 GiB. Não se alteraram
configurações de memória/serviços. Warnings de Metaspace do Gradle e dois
warnings Kotlin já existentes em `IdeScreen` não impediram o build.

[Release RC2](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-c1c-v0.9.1-rc2) · [APK code 42](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-c1c-v0.9.1-rc2/myct-c1c-0.9.1-rc2-debug.apk) · [SHA-256](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-c1c-v0.9.1-rc2/myct-c1c-0.9.1-rc2-debug.apk.sha256).
APK **19.043.431 bytes**, `dev.myct`, **0.9.1-c1c-rc2 / code 42**.
SHA-256: `3a60d0c6fc692d3b5854e4e15f4f0d761dbe5e3fcefc882a261666b220b4f211`.
Certificado SHA-256: `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Atualizar sem desinstalar/limpar dados; nenhuma instalação foi feita pelo agente.
Os assets publicados são apenas APK e checksum; nenhum catálogo/OAuth/mídia.

### Sessão curta de aceite após o upload grande

1. Confirmar que o upload terminou e a cópia remota está verificada; conferir
   ausência de jobs ativos/incertos. Só então instalar manualmente os helpers
   deste checkout, **incluindo o novo módulo**, e atualizar o APK sem desinstalar.
2. Usar fixture pequena descartável (arquivo comum e pacote multiarquivo):
   observar upload/restore com barra, bytes, %, taxa e ETA; conferir fase de
   integridade e conclusão. Sair/desconectar/reconectar sem replay. Verificar
   Atualizar/Resultado estáveis e que abrir detalhes/diálogo não pisca nem fecha.
3. Cancelar uma cópia da fixture com confirmação e consultar após reconectar:
   `cancel_requested` → `cancelling` → `cancelled`, ou sucesso se já concluída;
   conferir original/backup preservados e ausência de publicação incompleta.
   Não cancelar/liberar conteúdo real nem refazer os testes físicos já aceitos.
4. Conferir a mensagem de divergência na fixture antiga sem excluir seus resíduos.
   Registrar separadamente o que foi efetivamente observado. PR continua draft,
   sem merge até o aceite do proprietário.

---

## Registro histórico da RC1 / code 41

Os resultados, limitações e instruções históricas abaixo referem-se à entrega
original; a revisão vigente acima acrescenta telemetria, polling e cancelamento.

## Entrega e critérios

Uma entrega integra **Downloads → Mídia → Armazenamento → Nuvem** ao aplicativo
`dev.myct`. APK **0.9.0-c1c-rc1 / versionCode 41**, sobre a `main` code 40,
base `cfb0cd33ea5946e77ee8b64f752ec411e1a54ea2` (contém PR #49 e merge
`86df44e0d610a807081c6b7995112c3d45ceced6`). Nenhuma dependência Android nova.

Critérios definidos antes da implementação: catálogo real do Fedora;
arquivamento/restore/recover independentes da tela e SSH; consulta após
reconexão sem replay; liberação vinculada à prévia vigente e seleção exata;
consentimento adicional somente para os hardlinks Jellyfin escolhidos;
recuperação pausada e retomada explicitamente solicitada. Build/testes não
substituem o teste da interação no Moto G06.

## Uso

1. Conecte ao Fedora pelo Terminal e abra **Downloads → Mídia → Armazenamento →
   Nuvem**. **Atualizar** lê catálogos e estado atual do Transmission.
2. Abra um cartão arquivado para gerenciar o pacote. Os cartões mostram nome,
   tamanho, presença local, estado da cópia e data do registro/verificação,
   disponibilidade de restauração e estado do Transmission. A presença dos
   nomes Jellyfin e o inode compartilhado com árvores existentes são consultados
   no Fedora; **Consultar espaço** faz a conferência completa. Um nome preservado
   após liberar todas as árvores pode continuar existindo sem estar ligado a
   uma árvore atual. Voltar por **‹ Nuvem** ou pelo Android preserva a posição da
   lista. Os estados anteriores à atualização/reconexão não autorizam ações.
3. **Arquivar um torrent local** mostra os torrents gerenciados concluídos.
   **Conferir pacote** usa `inspect`; confira tamanho, arquivos e impedimentos
   antes de **Arquivar no Drive**. O motor pausa o torrent, preserva originais e
   hardlinks e verifica a cópia. Um arquivo com falha pode ser conferido para
   nova tentativa explícita; não há repetição automática.
4. **Consultar espaço / prévia** roda em trabalho persistente, pois pode fazer
   hashing longo. Mostra árvores original/restaurada, arquivos atingidos,
   vínculos, bloqueios, blocos ocupados/retidos e estimativa de liberação.
   **Até X bytes** é limite estimado por inodes, sem garantia de aumento imediato
   de espaço livre em Btrfs. A garantia continua sendo **0 B**.
5. **Excluir links da biblioteca** é uma seleção separada, inicialmente vazia.
   Alterá-la invalida a prévia; consulte novamente. **Liberar espaço…** usa a
   mesma prévia e sua confirmação funcional. Somente havendo hardlinks
   selecionados aparece também a confirmação específica de remoção Jellyfin.
   Drive/metainfo são preservados; as árvores indicadas e a entrada Transmission
   são removidas e o seeding termina. O motor revalida os dados antes da remoção.
6. **Restaurar** recupera o pacote integral. **Recuperar torrent** confere todas
   as peças e mantém pausado. Depois de recuperação verificada, com o torrent
   ainda pausado e completo, **Retomar seeding** solicita `recover --resume`,
   que verifica novamente antes do início. **Seeding iniciado, sem upload
   observado** não equivale a tráfego comprovado para peers.
7. Consulte espaço após recuperar. Vínculos ausentes e registrados têm
   **Reconstruir vínculo**, com o torrent recuperado e pausado. Cada pedido
   identifica o destino exato; arquivo ocupado por outro inode não é sobrescrito.
8. Para **arquivos comuns**, use **Escolher em Arquivos…**, navegue no SFTP
   existente e toque no arquivo → **Arquivar no Drive…**. A confirmação informa
   preservação do original e continuidade no Fedora. A seção compacta na Nuvem
   lista o catálogo C1A, consulta acesso/cota e restaura. O resultado de restore
   permite **Ver em Arquivos**. Um restore já publicado não é sobrescrito.

A faixa de andamento não inventa percentuais. **Últimas operações → Resultado**
consulta o JSON privado pelo identificador e apresenta o resultado útil, sem
exibir um painel de JSON. Após reabrir/reconectar, **Atualizar** recupera o
histórico remoto; selecione Resultado se a tela perdeu o pedido em memória.
Fechar a tela, bloquear o celular ou perder SSH não cancela um trabalho aceito.

## Arquitetura e contratos

- `Cloud.kt`: pedidos tipados, validação de infohash/ID/token/caminhos, parsing
  dos contratos e rótulos. Payload JSON em Base64URL sem padding. Comandos SSH
  contêm somente o helper fixo, verbos fixos, IDs hexadecimais e Base64URL.
  Caminhos/títulos nunca viram shell script.
- `CloudModel.kt`: conecta à sessão autenticada existente, serializa consultas,
  bloqueia duplo toque, trata confirmação obsoleta, rede/helper ausente e
  resultados incertos. Não persiste outro catálogo no Android.
- `CloudScreen.kt`: cartões Material 3, detalhes, prévia/consentimento e ações.
  `MediaStorageScreen` contém a seção Nuvem. `FilesScreen` reaproveita a seleção
  SFTP. Terminal, Cofre, tmux e os demais fluxos mantêm suas implementações.
- `SshConnection.cloud`: usa o executor/canais existentes, com saída limitada a
  **4 MiB** para inventários. Outros consumidores continuam com **256 KiB**.
  O timeout de 25 s limita apenas entrega/consultas curtas, nunca o trabalho.
- `ops/myct-cloud`: adaptador específico das CLIs C1A/C1B-2, biblioteca padrão
  Python, sem daemon, banco ou API nova. `snapshot` chama `list` das duas CLIs e
  `myct-torrent list`. Projeta campos úteis sem copiar manifestos/diários de
  exclusão para outro catálogo. Usa também o resolvedor `library_targets` e a
  leitura de estado de mídia do próprio motor para incluir vínculos criados
  após o upload. Estas informações rápidas não autorizam exclusão.

Contrato do adaptador (uma linha JSON, `ok`, erro sanitizado e saída 0/2):

```text
myct-cloud snapshot
myct-cloud task REQUEST_ID_32_HEX
myct-cloud start REQUEST_ID_32_HEX PEDIDO_JSON_BASE64URL
```

O pedido é `{"kind":"torrent|file","args":[...]}`. Whitelist explícita:
`inspect`, `archive`, `space`, `release`, `restore`, `recover [--resume]`,
`relink` para torrents; `archive`, `restore`, `status` para arquivos. Não há
executor genérico. `release` requer `--preview TOKEN --confirm TOKEN`; havendo
`--remove-library`, também `--confirm-library TOKEN`. O motor continua sendo
a fonte de verdade e conserva seus bloqueios, validade de 600 s e revalidação
integral. O relógio Android também bloqueia prévias vencidas, sem renovar o
prazo ao reler resultado antigo; o relógio Fedora decide a validade final.

### Execução persistente e falhas

Antes de aceitar, o adaptador confere `Linger=yes` e ausência de outro trabalho
ativo/incerto. Não ativa linger nem modifica serviços. Grava recibo atômico
com `fsync`, ID, pedido e estado `starting` em
`~/.local/share/myct/cloud-jobs/ID.json` (diretório 0700, arquivo 0600).
Sob lock de entrega, inicia `systemd-run --user --collect` com unidade
`myct-cloud-ID.service`, `Type=exec`, `UMask=0077`, `Nice=10`, stdout/stderr
suprimidos. A unidade executa somente o worker interno fixo e o ID; nomes de
arquivos e tokens permanecem no recibo privado. Nenhum timer ou serviço
residente é instalado.

O worker passa uma vez de `starting` a `running`, chama a CLI sem shell,
preserva seu JSON em `result` e grava `succeeded`/`failed`. Repetir o mesmo ID
retorna o recibo; outro pedido com o mesmo ID é recusado. Nenhum trabalho em
andamento/incerto permite outro início. O Android não repete uma entrega após
perder a resposta. Consultas podem encontrar catálogos `BUSY`: mostram aviso e
mantêm as ações bloqueadas até nova leitura.

Timeout de lançamento, queda/reboot sem resultado ou desaparecimento da
unidade produzem `uncertain`, nunca sucesso nem relançamento. O motor também
pode retornar `RELEASE_UNCERTAIN`, `STALE_PREVIEW`, `BUSY`, `AUTH`, `NETWORK`,
`RCLONE_NOT_FOUND` etc.; a interface apresenta instrução em português. Não são
publicados stderr bruto, configurações Rclone, OAuth ou catálogos particulares.

**Intervenção em `uncertain`:** confira no próprio Fedora o recibo privado,
unidade e diário C1B-2, árvores, Transmission e Drive. Não reinicie o worker e
não reutilize prévia antiga. Depois de concluir a investigação e confirmar que
nenhum trabalho está ativo, o proprietário pode guardar o recibo resolvido
fora de `cloud-jobs` para retirar o bloqueio de entrega; isso não resolve nem
contorna um diário `RELEASE_UNCERTAIN` no motor. Não há botão Android para
apagar a evidência ou ignorar esse bloqueio.

## Instalação manual no espaço do usuário

Git não atualiza `~/.local/bin`. Nenhum helper operacional foi instalado ou
substituído por esta entrega. Execute **no checkout candidato conferido**, sem
trabalho de arquivamento ativo:

```bash
cd ~/Projetos/My-CT
# Checkout da branch/commit candidato recebido pela PR; não trocar para main antiga.
# PARE se o upload grande ainda estiver ativo ou houver resultado incerto.
python3 - <<'PY_CHECK'
import json
from pathlib import Path
root = Path.home() / '.local/share/myct/cloud-jobs'
assert not any(json.loads(p.read_text())['state'] in
               {'starting', 'running', 'cancelling', 'uncertain'} for p in root.glob('*.json')), 'Há trabalho ativo/incerto; não instalar.'
PY_CHECK
# Só prossiga após o check passar e conferir a cópia remota/ausência de unidades ativas.
install -Dm0600 ops/myct_cloud_progress.py "$HOME/.local/bin/myct_cloud_progress.py"
for helper in myct-cloud myct-archive myct-archive-torrent myct-torrent myct-media myct-media-cleanup; do
    install -Dm0700 "ops/$helper" "$HOME/.local/bin/$helper"
done
python3 --version
systemd-run --version
rclone version
loginctl show-user "$(id -u)" -p Linger --value
"$HOME/.local/bin/myct-cloud" snapshot
```

Os seis helpers e o módulo `myct_cloud_progress.py` precisam ficar no **mesmo diretório**. `myct-media` também usa
`~/.local/bin/myct-torrent`; por isso instalar somente o novo bridge não basta
se os complementos estiverem ausentes/desatualizados. Estes arquivos vêm da
mesma branch atual e preservam o mecanismo de nomeação/hook vigente. Não
instalar uma CLI antiga. Não executar `enable-naming`, reiniciar Transmission
nem alterar SSH/systemd global, timers, firewall, rede ou PostgreSQL.

Pré-requisitos já usados pelo projeto: Python 3, Rclone privado configurado para execução não interativa no usuário,
Transmission dedicado e systemd de usuário com linger. Se linger não for
`yes`, o adaptador recusa o início: sua ativação é uma operação administrativa
separada, a decidir pelo proprietário. Neste Fedora a consulta retornou `yes`.
Não copiar `rclone.conf`, credenciais ou catálogos para o repositório/release.
Overrides de ambiente exclusivos de um shell (remote/configuração Rclone) não
são provisionados no gerenciador de usuário pelo aplicativo. O ambiente de
execução persistente precisa acessar a configuração privada existente; nenhuma
senha de configuração criptografada é transmitida para propriedades da unidade.
Faça backup privado dos catálogos/bundles existentes; o recibo de tarefa não
substitui os metadados necessários à recuperação.

## Validação automatizada

Ferramentas observadas antes de qualquer instalação: Fedora, Python 3.14.7,
systemd 259.9, Java 21.0.10, Gradle wrapper 8.14.3, Android SDK/build-tools 36,
GitHub CLI 2.97.0. Nenhuma dependência instalada.

Uma rodada completa Android e complementação dirigida após correções:

```bash
python3 -m unittest discover -s ops/tests -p test_cloud.py -v
cd android
ANDROID_HOME=/home/sam/Android/Sdk ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
# Complementação: somente Cloud*, APK e lint, após os ajustes finais.
ANDROID_HOME=/home/sam/Android/Sdk ./gradlew \
  :app:testDebugUnitTest --tests 'dev.myct.Cloud*' \
  :app:assembleDebug :app:lintDebug --offline --console=plain
```

- Rodada completa: **285 testes JVM PASS**, incluindo 13 novos iniciais
  (272 regressões existentes). APK compilou. Lint inicial apontou
  `SuspiciousIndentation` enquanto havia ajuste final de fonte; a indentação
  foi corrigida, sem suprimir a regra. Fontes ficaram estáveis na complementação.
- Adaptador final: **17 testes PASS**, 2,493 s. Inclui consentimentos e seleção,
  IDs/caminhos/flags inválidos, injeção tratada como dado, helper/JSON/erro de
  rede, linger, recibo antes de lançamento, duplicação, timeout ambíguo,
  desaparecimento da unidade, corrida com conclusão, catálogo `BUSY` e presença
  de hardlink com árvore original ausente/restaurada presente.
- O teste persistente usa **systemd de usuário real**, processo cliente que
  termina após aceite e motores falsos em diretório temporário. O worker
  termina depois, grava resultado, e uma segunda entrega com o mesmo ID não
  executa o motor de novo. Não toca em Drive/RPC/acervo pessoal. Os demais testes
  do adaptador usam mocks/fixtures temporárias.
- Testes Android exercitam parsing, comandos seguros, rótulos principais,
  confirmação exata e consentimento de biblioteca, expiração/seleção/identidade
  obsoleta, reconexão/consulta, ausência de replay, cancelamento de consulta SSH,
  helper/rede/JSON, catálogo ocupado, resultado incerto e transição do infohash
  recém-arquivado para seu ID real. São testes JVM de modelo/estado, não provas
  de renderização ou toque físico.

A complementação dirigida cobre também a abertura do resultado em seus
detalhes após reconexão e a recusa de entrada inválida antes do SSH. A seleção
de biblioteca respeita o máximo de 256 caminhos do motor. Resultados finais,
medição e artefatos são registrados ao fim deste relatório.
Logs locais em `artifacts/myct-c1c-validation/` (ignorados pelo Git). A rodada
completa consumiu 2m57s, pico RSS de 2.389.020 KiB no build/teste/lint. Isso é
custo de desenvolvimento, não do runtime Fedora. Nenhuma matriz C1B-2 foi
reaberta.

### Consulta real de estado, sem mutações do acervo

O snapshot candidato consultou os catálogos existentes e RPC no Fedora real,
com resposta `ok:true`, ambos os catálogos disponíveis, cerca de **20 KiB** e
**0,4 s**. Não fez upload, restore, release, recover ou relink reais. Criou apenas
o diretório privado vazio de recibos, sem instalar helpers.

A fixture informada foi encontrada no catálogo:

- Infohash `590561b2aac74e4392da9a71b42a380bc8258e9f`.
- Archive ID `8b027a7acf219ef87b7c3aa75328f46112b5d68d975bcf89318c9a2a9469067e`.
- Arquivamento `verified`; diário de liberação `released/complete` em
  `2026-10-20T03:10:46.445745+00:00`; restauração `verified` em
  `2026-10-20T03:11:45.523852+00:00`; recuperação `verified`,
  `resume_requested:true` em `2026-10-20T03:11:50.456884+00:00`.
- **Estado consultado agora:** árvore local presente; fixture **ausente no RPC
  atual**, inclusive consulta direta pelo infohash; **zero nomes Jellyfin
  presentes/ligados** na projeção atual do resolvedor do motor. O histórico
  anterior de seeding RPC 6 não foi apresentado como estado atual. Não foi
  investigada nem alterada a causa desta diferença; a sessão no Moto deve
  começar consultando novamente o estado real e seu diário.

## Uma sessão de aceite no Moto G06

Pré-condições: instalar os helpers manualmente, instalar o APK candidato sobre
o existente **sem desinstalar/limpar dados**, preservar catálogos e manter
somente a fixture descartável como alvo de operações destrutivas.

1. Conectar por SSH e abrir **Downloads → Mídia → Armazenamento → Nuvem**.
   Atualizar, conferir nome/ID da fixture, situação local/nuvem/RPC/Jellyfin e
   navegar detalhes/voltar para observar rolagem. Abrir o diário/Resultado
   quando necessário. **Parar se houver `uncertain` ou estado incompatível.**
2. Como ela já está arquivada/verificada, não repetir upload somente por
   ritual. Se houver necessidade real de novo arquivamento, usar apenas um
   torrent descartável gerenciado e completo, conferindo pacote antes do envio.
   Um arquivo comum descartável pode ser selecionado no SFTP para validar C1A.
3. Consultar espaço, esperar **Em andamento**, fechar/bloquear a tela ou
   desconectar depois do aceite. Reconectar, Atualizar e abrir Resultado;
   conferir conclusão sem outro pedido. Não usar o timeout SSH como medição
   do tempo do hashing.
4. Conferir as árvores e **todos** os arquivos atingidos da fixture e bloqueios.
   Manter exclusão da biblioteca desmarcada. Se estado/diário forem coerentes,
   confirmar uma liberação guiada. Nunca selecionar outro pacote/acervo. Se
   escolher retirar um hardlink de teste, obter nova prévia e confirmar também
   sua remoção específica; uma prévia anterior deve ficar inutilizável.
5. Restaurar a fixture pelo cartão; aguardar resultado. **Recuperar torrent**,
   conferir verificação completa e estado pausado. Consultar espaço de novo;
   **Reconstruir vínculo** somente para o destino de teste registrado e ausente.
   Destino ocupado deve permanecer protegido.
6. **Retomar seeding** explicitamente. Conferir estado RPC atualizado; registrar
   upload observado somente se houver taxa real > 0. Verificar rapidamente
   Terminal/tmux, Arquivos e Cofre sem repetir seus aceites antigos. Registrar
   o resultado desta sessão na mesma PR; corrigir eventual falha nesta branch.

## Limites efetivos

- Aceite de renderização, legibilidade, rolagem, interação e atualização sobre
  o APK **realmente instalado** no Moto permanece com o proprietário; `adb
  devices` não encontrou aparelho. Não houve instalação Android automática.
- Sem porcentagem de progresso das CLIs; consulta a cada 4 s enquanto a tela
  está visível e há trabalho ativo/incerto. Fora da tela, o Fedora continua,
  mas não há notificação push nem polling móvel em segundo plano.
- Não há retomada automática após reboot/queda do processo worker. A existência
  de uma unidade ou pedido aceito isoladamente não comprova conclusão.
- Catálogos `BUSY`, previews vencidas e inconsistências bloqueiam ações. Relógios
  muito diferentes podem exigir acerto pelo proprietário; o motor é a decisão
  final. Resultados maiores que 4 MiB são recusados no Android, sem replay.
- Só torrents/caminhos aceitos pela C1B-2, metainfo v1 e arquivos C1A estáveis.
  Requisitos de espaço, cota, ACL e integridade continuam nas CLIs existentes.
- Snapshot rápido consulta catálogos e presença/inode; a revalidação de Drive e
  hashing completos ocorre nas operações do motor, não a cada cartão.
- Histórico de entrega privado não tem limpeza automática: até 40 recibos são
  exibidos, últimos cinco na seção compacta. O proprietário pode arquivar
  recibos antigos concluídos fora da pasta; nunca remover evidência incerta
  antes de investigar. Não é um catálogo de backup.

## Publicação e retomada

Uma única PR draft para `main`, sem merge; release candidata com APK e checksum.
O próximo trabalho é o aceite concentrado descrito acima e correções que ele
exigir, na mesma branch/PR. Sem novas subdivisões C1C nem reabertura das dívidas
#9/#4. Catálogo privado, OAuth, mídia e objetos Drive não fazem parte dos assets.


Arquivos principais alterados: `Cloud.kt`, `CloudModel.kt`, `CloudScreen.kt`,
`SshConnection.kt` e limite opcional de saída em `Downloads.kt`; integração em
`MediaModel.kt`, `MediaScreen.kt`, `MediaStorageScreen.kt`, `TorrentsScreen.kt`,
`FilesScreen.kt` e `MainActivity.kt`; versão em `android/app/build.gradle.kts`;
`ops/myct-cloud`, `ops/tests/test_cloud.py`, testes `CloudTest`/`CloudModelTest`,
este relatório, README e decisão em `docs/05-DECISOES-E-QUESTOES.md`. Os motores
`myct-archive` e `myct-archive-torrent` ficaram intactos.

## Resultado final e APK candidato

| Verificação | Resultado |
| --- | --- |
| Suíte Android completa (uma execução) | 285 PASS, 0 falhas/erros |
| Cloud final após ajustes | 15 PASS, 0 falhas/erros |
| Adaptador final | 17 PASS, incluindo systemd real isolado |
| `:app:assembleDebug` | PASS |
| `:app:lintDebug` final | PASS: 0 erros, 0 warnings, 2 hints existentes em SearchModel |
| Whitespace `git diff --check` | PASS |
| Certificado contra APK S1C/code 40 | Idêntico, verificado por apksigner |
| Aceite físico Moto G06 | Pendente com o proprietário |

A complementação final levou **3m09,73s**, pico RSS **2.520.844 KiB** no build.
A suíte Python final teve pico **23.684 KiB**, 2,62 s medidos externamente.
Não se repetiu a suíte Android completa; correções e revalidações dirigidas
permaneceram na mesma entrega. Warnings da ferramenta sobre XML do SDK e
Metaspace durante o build não causaram falha; configurações de memória do
projeto não foram alteradas.

- [Release candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-c1c-v0.9.0-rc1).
- [APK instalável](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-c1c-v0.9.0-rc1/myct-c1c-0.9.0-debug.apk).
- [Checksum](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-c1c-v0.9.0-rc1/myct-c1c-0.9.0-debug.apk.sha256).

APK: **19.024.376 bytes**, `dev.myct`, code **41**, `0.9.0-c1c-rc1`.
SHA-256:

```text
e037d8933a98374614dd98f7b3ec0f6c2ee12a37655f817e31935d0c56b96954
```

Certificado SHA-256 público, igual ao APK anterior code 40:
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
A keystore existente foi preservada; material privado não foi distribuído.
Atualizar sobre a instalação existente. A compatibilidade com o artefato code
40 está conferida; a atualização no aparelho será comprovada pelo proprietário.
