# MYCT-C1C — Nuvem no Android

Candidata de **20/10/2026**, branch única `myct-c1c-cloud-android`, vinculada à
[issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46). **Sem merge e
sem aceite físico Android declarado.** A C1B-2 continua aceita; seu motor não
foi alterado nem seus testes físicos repetidos.

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
for helper in myct-cloud myct-archive myct-archive-torrent myct-torrent myct-media myct-media-cleanup; do
    install -Dm0700 "ops/$helper" "$HOME/.local/bin/$helper"
done
python3 --version
systemd-run --version
rclone version
loginctl show-user "$(id -u)" -p Linger --value
"$HOME/.local/bin/myct-cloud" snapshot
```

Os seis helpers precisam ficar no **mesmo diretório**. `myct-media` também usa
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
