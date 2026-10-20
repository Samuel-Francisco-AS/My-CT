# MYCT-C1B-2 — ciclo integral de armazenamento torrent

**20/10/2026 — ACEITE FUNCIONAL NO FEDORA:** C1B-2 integrada em uma única PR #49, com 121 testes executados no Fedora e ciclo físico completo de fixture descartável: arquivamento Drive, restauração/verificação, duas modalidades de liberação consentida, preservação Jellyfin, reconstrução e início explícito de seeding. A PR #49 é a unidade de fechamento e integrações na `main` devem ser verificadas no GitHub. **Nenhum upload efetivo para peers foi observado**; o teste demonstra prontidão e início do estado seeding, não throughput. Testes físicos não equivalem a prova de compatibilidade irrestrita com quaisquer torrents pessoais.

Uma entrega, branch `myct-c1b-storage-lifecycle`, baseada na `main` sincronizada
em `c5a2c82b378d51549e2da164862ae8047e7a66d6`.
Relacionado à [PR #49](https://github.com/Samuel-Francisco-AS/My-CT/pull/49)
e à [issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46).
A C1C Android permanece pendente.

## Resultado e arquitetura

Extensão de `ops/myct-archive-torrent`, Python 3 e biblioteca padrão. Mantém
`inspect`, `archive`, `restore`, `recover [--resume]`, `status` e `list` da C1B-1;
acrescenta `space`, `release` e `relink`. Não altera a interface nem o catálogo
v1 de arquivos comuns da C1A. Não cria serviço, banco, timer ou interface Android.

O ciclo implementado é: arquivar o pacote integral → conferir a prévia → liberar
nomes locais selecionados → restaurar todos os dados → recuperar/verificar pausado
→ reconstruir vínculos solicitados → verificar e iniciar explicitamente.
Inclui arquivos desmarcados já completos, legendas, extras, arquivos locais extras,
diretórios vazios, seleção/prioridades e `.torrent` original. Magnet não substitui
o metainfo na recuperação.

Reutiliza runner Rclone/fingerprints/escrita atômica da C1A, RPC e naming do
Transmission, lock de Media e `myct-media.add_one` com as ACLs Jellyfin existentes.
Ordem dos locks: catálogo → naming por infohash → Media. Não chama
`myct-media-cleanup.apply` nem `torrent_remove(delete_local_data=true)`.
Os helpers complementares atuais continuam necessários no mesmo diretório.

Catálogo privado existente: `~/.local/share/myct/archive-torrents/catalog.json`.
Bundle: `archive-torrents/bundles/ID/{manifest.json,original.torrent}`.
Drive: `myct-gdrive:My-CT-Archive/torrents/ID/`. Esses ativos permanecem após a
liberação. Preservar backup **privado** do catálogo/bundle; não anexá-los à PR.
Nenhuma credencial OAuth, configuração Rclone ou conteúdo de metainfo é emitido.

## Comandos

Executar do checkout auditado, **sem instalar helpers nesta entrega**:

```bash
cd /home/sam/Projetos/My-CT
# Substituir os marcadores pelos identificadores da fixture autorizada.
ID='ID_DO_ARQUIVAMENTO_COM_64_HEXADECIMAIS'
HASH='INFOHASH_V1_COMPLETO_COM_40_HEXADECIMAIS'
./ops/myct-archive-torrent list
./ops/myct-archive-torrent inspect "$HASH"
./ops/myct-archive-torrent space "$ID"
```

`list` consulta catálogo com o lock herdado da C1B-1; `inspect` consulta
torrent/local; `space` consulta local, RPC e Drive. **`inspect` e `space` não
criam locks, diretórios, registros nem pausam torrents.** Leituras podem atualizar
`atime` pelo filesystem; não alteram conteúdo, mtime/ctime ou catálogo. Rclone pode
cuidar internamente da validade OAuth conforme sua configuração existente.

Todas as ações mantêm JSON em uma linha, exit 0 em sucesso/2 em falha.
`space` pode retornar `ok:true` com `can_release:false`: a consulta funcionou,
mas os códigos em `blockers` impedem a ação. `--remote NOME:` antes da ação ou
`MYCT_ARCHIVE_REMOTE` mantêm o contrato existente. Não passar tokens OAuth.

### space — inventário e prévia somente leitura

```bash
./ops/myct-archive-torrent space "$ID"
# Prévia ALTERNATIVA: selecionar especificamente um vínculo da biblioteca.
REL='Filmes/Fixture (2026)/Fixture (2026).mkv'
./ops/myct-archive-torrent space "$ID" --remove-library "$REL"
```

Mostra cópia remota revalidada, catálogo/bundle/metainfo, nome/infohash, estado e
localização RPC, árvore original e restauração em `archive-ID-HASH8`, arquivos
integrais/extras, diretórios e vínculos. Compara conteúdo local tamanho/MD5/SHA-256
e verifica no Drive tamanho/MD5 de **cada objeto, manifesto e metainfo**.
Não consulta só a cota para concluir integridade. Hash ausente/objeto divergente,
duplicado ou inacessível bloqueia. Metainfo local precisa corresponder ao original.

Os registros por inode incluem dispositivo, inode, nlink, tamanho lógico,
`st_blocks * 512`, nomes conhecidos/removíveis e contagem de links desconhecidos.
Um inode é contado uma única vez; a restauração com inode independente é contada
separadamente. A biblioteca é inspecionada mesmo que o RPC aponte para a segunda
árvore ou que as árvores já tenham sido liberadas.

| Campo | Interpretação |
| --- | --- |
| `package_logical_bytes` | Soma dos bytes do pacote completo, incluindo extras |
| `physical_bytes` | Soma de blocos alocados reportados por inodes distintos conhecidos |
| `reclaim_estimate_bytes` | Blocos candidatos dos inodes cujos **todos** os nomes serão retirados |
| `retained_known_bytes` | Blocos reportados que continuam referenciados por nomes preservados |
| `reclaim_guaranteed_bytes` | **0**, sem garantia de economia física efetiva |
| `estimate_kind` | `inode_blocks_upper_bound_not_guaranteed` |

`st_blocks` não revela exclusividade de extents Btrfs/reflinks/snapshots nem
descritores ainda abertos. A estimativa é um **limite superior por inode**;
pode não corresponder à variação de `df`. Diretórios/metadados não entram nela.
Não somar nomes hardlinkados como cópias nem apresentar tamanho lógico como SSD
recuperado. Economia garantida zero é intencional; medir o resultado físico depois.

`preview` identifica a fotografia completa e a seleção; validade de 600 s.
Inclui conteúdo, inodes, timestamps, diretórios, estado Media, RPC e resultados
remotos. Não salva a prévia. Qualquer alteração relevante exige nova consulta.
Links desconhecidos (`nlink` maior/menor que a contagem identificada), árvores
divergentes/parciais, symlinks/especiais, montagens internas/bind mounts,
outro torrent referenciando a árvore e estado RPC inseguro bloqueiam.

### release — operação destrutiva, NÃO executada no acervo

Pré-requisitos: auditoria Luna; autorização específica do proprietário para a
fixture descartável; arquivo C1B `verified`; Drive e metainfo revalidados;
`can_release:true`; revisão de **todos** os caminhos/consequências na prévia;
nenhuma operação concorrente por outro cliente/terminal/automação.

Por padrão, a prévia seleciona **as duas árvores integrais existentes** (original
e restaurada), preservando todos os nomes Jellyfin. Não é uma seleção de apenas
vídeos. Conferir os caminhos reais em `trees` e `remove_paths` antes de confirmar.

```bash
# Somente após consentimento físico específico; NÃO executados pelo agente.
./ops/myct-archive-torrent space "$ID"
PREVIEW='COPIAR_O_CAMPO_preview_DA_PREVIA_REVISADA'
./ops/myct-archive-torrent release "$ID" --preview "$PREVIEW" --confirm "$PREVIEW"
```

Remover também biblioteca é uma **alternativa**, com outra prévia, seleção exata
e segunda confirmação. Repetir `--remove-library` para cada caminho desejado
(até 256). Somente vínculos gerenciados comprovados são removíveis; os demais
podem permanecer preservados, sem prometer liberação do inode.

```bash
./ops/myct-archive-torrent space "$ID" --remove-library "$REL"
PREVIEW='COPIAR_O_CAMPO_preview_DESSA_NOVA_PREVIA_REVISADA'
./ops/myct-archive-torrent release "$ID" --preview "$PREVIEW" --confirm "$PREVIEW" \
  --remove-library "$REL" --confirm-library "$PREVIEW"
```

Confirmações inválidas, expiradas, seleção ou fotografia alteradas impedem a
mutação. Sob locks, refaz inventário/hashes completos. Registra intenção no
catálogo **antes** do primeiro RPC. Pausa e observa status 0; retira apenas a
entrada Transmission com `delete_local_data:false` e confirma sua ausência.
Refaz conferência remota/local e de todos os vínculos após RPC, antes do primeiro
unlink. Confere novamente ausência da entrada e identidade/conteúdo/nlink de
cada arquivo antes de retirar seu nome, com dirfd e ancestrais `O_NOFOLLOW`.
Só retira diretórios inventariados e vazios do pacote. Não apaga pastas da
biblioteca, não substitui mídia e não remove objetos do Drive.

Sucesso: `state:released`, lista dos caminhos retirados e vínculos selecionados,
seeding inativo, catálogo/metainfo preservados. O torrent deixa de constar no
Transmission até `recover`. A biblioteca continua acessível se seus nomes foram
preservados. Retirar apenas os nomes originais não libera o inode do vídeo ainda
vinculado ao Jellyfin. Nenhuma afirmação de economia física efetivamente medida.

Diário `release` (`pending` → `released` ou `uncertain`) registra fase, fotografia,
caminhos retirados e código sanitizado. Estado pendente após crash também bloqueia.
Timeout RPC pode significar mutação já aceita: **nenhum retry destrutivo automático**.
`RELEASE_UNCERTAIN` bloqueia nova liberação, restore, recover e relink desse
infohash (inclusive outro arquivo do catálogo para o mesmo hash). `space` continua
permitido e mostra diagnóstico/estado/caminhos. O catálogo não é descartado.
Repetir o token de uma operação concluída retorna no-op, sem novo RPC/exclusão.

Em falha parcial não há rollback mágico: parte dos nomes pode já ter sido
retirada. Não editar o catálogo nem remover arquivos para contornar o bloqueio.
Luna/proprietário devem conferir `release.phase`, `removed`, caminhos remanescentes,
Drive e estado RPC, guardar cópia privada do catálogo e decidir a recuperação
manual. Não há comando de limpar/ignorar automaticamente um diário incerto.

### restore, recover e relink — recuperação completa

Depois da liberação concluída, com espaço SSD suficiente e autorização:

```bash
./ops/myct-archive-torrent restore "$ID"
./ops/myct-archive-torrent recover "$ID"
# Opcional e explícito: apenas vínculo registrado, ausente e não ignorado.
./ops/myct-archive-torrent relink "$ID" --library "$REL"
# Ação explícita separada, somente para conteúdo redistribuível autorizado:
./ops/myct-archive-torrent recover "$ID" --resume
```

`restore` usa o caminho isolado C1B-1, sem sobrescrever destino existente, valida
tamanho/MD5/SHA-256, metainfo/infohash e agora também **peças localmente**. Repetição
só reutiliza árvore integral idêntica. Parcial divergente é preservado/bloqueado.
Não chama Transmission nem faz redownload dos peers.

`recover` reconfere bytes/peças, reutiliza entrada compatível ou reimporta o
metainfo **pausado**, recupera seleção, usa `move:false`, observa a localização
restaurada e `torrent_verify` em fila/verificando (1/2) até pausado (0). Exige
100% de todos os arquivos, `have_valid == total_size`, zero unchecked, pendências
ou erros, e fotografia local inalterada. Sem observar a verificação, bloqueia.
Sem `--resume`, termina pausado com `seeding_ready:true`. Antes do start explícito,
reconfere localização/conclusão/pausa; depois consulta o estado real novamente.

`relink` exige recuperação previamente verificada e RPC pausado/completo na árvore
restaurada. Seleção explícita de caminhos registrados no manifesto/estado Media;
preflight de todos antes de criar o primeiro. Usa `myct-media.add_one`/ACL existente,
mantém o índice por link bem-sucedido e respeita `ignored`. Não limpa decisões
anteriores. Destino com inode diferente é `DEST_EXISTS`, mesmo com bytes iguais:
nunca substituir o link original preservado para fazê-lo apontar à restauração.
Se já possui o mesmo inode, retorna `existing` e conserva/repara o registro sem
duplicação. Falha de ACL remove só o link recém-criado pelo mecanismo Media.
Links retirados por `release` mantêm seu registro, sem adicionar `ignored`: são
ausências intencionais temporárias, disponíveis à reconstrução solicitada.

Campos de retomada: `seeding_ready` prova dados verificados; `seed_started` indica
status real 5 (fila de seeding) ou 6 (seeding); `rpc_status` distingue esses estados.
`peers_connected`, `upload_rate` e `upload_observed` mostram a fotografia RPC.
Sem peers/tráfego, prontidão e início não provam upload. Uma resposta RPC de sucesso
isolada não serve como prova. Falhas de recuperação tentam manter pausado; não
retomam automaticamente para reparar dados ausentes por download.

## Validação automatizada e custo

Testes em diretórios temporários, RPC falso e Rclone simulado, sem dados reais.
**121 testes PASS**: 25 C1B-2, 25 C1B-1, 29 C1A, 17 Torrent e 25 Media
(8 Media, 8 cleanup, 5 batch, 4 library). Execução agregada final: **25,431 s**;
parede **25,57 s**, CPU usuário/sistema **9,54/1,88 s**, pico RSS **31.508 KiB
(~30,8 MiB)**, medidos por `/usr/bin/time`. Logs locais ignorados pelo Git:
`artifacts/myct-c1b2-regression.log` e `artifacts/myct-c1b2-regression-time.txt`.
`py_compile` e `git diff --check`: PASS. Sem build/testes Android nesta etapa.
Ambiente existente: Fedora 44, Python 3.14.7, Git 2.55.0 e Rclone 1.74.3;
nenhuma dependência instalada. Medições descrevem fixtures pequenas/fakes,
não custo/tempo de transferências e leitura de hashes em pacotes reais grandes.

Na elaboração da suíte, a comparação de `atime` produziu uma falha: leituras
legítimas podem atualizá-lo. O teste passou a conferir conteúdo/estado relevante
e metadados de identidade/permissão/mtime/ctime, sem exigir `atime` constante.
Não resta falha automatizada conhecida; os limites operacionais abaixo persistem.

```bash
python3 -m unittest discover -s ops/tests -p 'test_archive*.py' -v
python3 -m unittest discover -s ops/tests -p 'test_torrent.py' -v
python3 -m unittest discover -s ops/tests -p 'test_media*.py' -v
python3 -m py_compile ops/myct-archive-torrent ops/tests/test_archive_lifecycle.py
git diff --check
```

Cobertura C1B-2: multifile/extras; original+Jellyfin e segunda cópia independente;
contagem de blocos/nlink; prévia sem escrita/locks/RPC mutante; consentimento inválido,
expiração, mudança de inode/estrutura/status/seleção; Drive divergente/sem MD5,
manifesto/metainfo alterados, rede/OAuth; links externos; seeder ativo/pausado/ausente;
pausa não observada; timeout com remoção RPC aceita; interrupção após unlink;
reimportação concorrente interrompendo a exclusão; diário corrompido; montagens e
symlinks; liberação consentida, restauração com extras, reimportação pausada,
relink idempotente/ignored/ACL/divergência; pouco SSD e corrupção; localização
incorreta após start. A suíte C1B-1 cobre verificação incompleta/não observada e
ausência de start nesses casos; as suítes C1A/Torrent/Media verificam regressões.

## Limitações e gate físico

- C1B-1 continua aceita como documentado; a C1B-2 **não foi instalada nem executada
  sobre torrents reais**. Não há prova física de liberação, ACL/relink ou seeding
  desta candidata. Essa validação, após auditoria, é o gate restante do aceite.
- Mantém limites C1B-1: v1 original, sem híbrido/v2/padding/renomeações; 1000
  arquivos/1000 diretórios por pacote, metainfo até 4 MiB e resposta RPC 256 KiB.
  Inspeção de biblioteca até 2000 arquivos; links fora das raízes conhecidas não
  são localizados globalmente: nlink desconhecido bloqueia, sem override.
- Inventaria árvore original e restauração no caminho gerenciado conhecido.
  Cópias manuais independentes fora dessas raízes não são descobertas, nem são
  selecionadas para exclusão. Nada promete recuperar o espaço delas.
- Não é snapshot nem transação distribuída com Transmission/Drive/filesystem.
  Locks coordenam helpers que os respeitam, não todos os clientes RPC, escritores,
  mounts ou administradores. Não executar operações concorrentes. Verificações
  imediatas/dirfds reduzem corridas; não eliminam interferência externa.
- Mesmo prévia válida pode terminar parcial por erro de disco, mudança externa
  ou sinal. O diário bloqueia replay; recuperação de `uncertain` é manual.
- Verificação Transmission muito rápida, sem transição 1/2 observada, continua
  conservadoramente bloqueada (`VERIFY_UNOBSERVED`, sem override; limite 5 s para
  observar início, 600 s para concluir). Não iniciar para tentar corrigir isso.
- Não há recuperação automática de catálogo perdido ou segunda cópia independente
  do Drive. Preservar ativos privados e conferir assinatura/cota antes de depender
  do armazenamento remoto. Nenhuma mudança OAuth/Google Pages nesta entrega.

Roteiro físico original (histórico: **executado e validado em 20/10/2026**, conforme fechamento acima):

1. Preservar o teste C1B-1 atual e usar outra fixture pequena, descartável e
   livremente redistribuível, completa no Transmission, com vídeo e extras.
2. Conferir `inspect`, arquivar, restaurar e recuperar pausado. Criar o vínculo
   de teste pelo mecanismo Media já existente, com consentimento.
3. Consultar `space`; conferir RPC apontando à restauração, original e hardlink
   compartilhando inode, restauração independente, extras e bytes retidos.
   Nenhuma mutação nesta consulta. Guardar evidência sanitizada de `stat`/`df`.
4. Autorizar separadamente a liberação padrão; executar **somente** o token
   revisado. Conferir retirada das duas árvores, ausência RPC, catálogo/Drive
   intactos e vídeo Jellyfin preservado. Medir `df` sem prometer delta exato.
5. Se autorizado especificamente a retirar o link descartável restante, gerar
   **nova prévia** com `--remove-library` e dupla confirmação. Conferir o impacto.
6. Restaurar, recuperar pausado, reconstruir apenas o link de teste ausente e
   repetir relink para comprovar `existing`. Conferir leitura Jellyfin e extras.
7. Autorizar `recover --resume`; observar verificação integral, estado 5/6,
   zero bytes pendentes e ausência de redownload. Registrar peers/upload somente
   se observados; ausência de peers não invalida prontidão.
8. Em divergência, timeout ou `uncertain`, parar e auditar. Não limpar dados ou
   Drive, não alterar serviços/configuração nem instalar helpers como parte do teste.

Referências técnicas: [RPC Transmission 4.1.3](https://github.com/transmission/transmission/blob/4.1.3/docs/rpc-spec.md),
[Rclone lsjson/Hashes](https://rclone.org/commands/rclone_lsjson/),
[C1B-1 aceita](MYCT-C1B-1-RELATORIO.md),
[mecanismos Media](MYCT-MEDIA-STORAGE-CLEANUP.md),
[infraestrutura OAuth preservada](MYCT-INFRA-GOOGLE-CLOUD-DRIVE-OAUTH-PAGES.md).


## Fechamento físico e aceite do proprietário — 20/10/2026

**Escopo aprovado:** uma entrega C1B-2, sem 2A/2B/2C. Auditoria estática da Luna favorável e testes reais executados pelo proprietário no Fedora 44. A autorização incluiu **somente a fixture própria**; os conteúdos e links pessoais permaneceram fora das operações destrutivas. Branch/PR de implementação: [#49](https://github.com/Samuel-Francisco-AS/My-CT/pull/49), head funcional auditado `d6058fb4e61fd4d2a1841ebafb7593a748353695`. O fechamento remoto foi executado após estas evidências; a situação vigente deve ser consultada na PR.

### Regressões reais e inventário de acervo preexistente

Com o SHA auditado, execução independente no Fedora: **79 testes archive + 17 torrent + 25 media = 121/121 PASS**, sem falhas. Sem CI externa declarada (nenhum status publicado para esse head).

Consulta `space` **somente leitura** sobre a entrada da C1B-1 já arquivada: `ok:true`, nuvem verificada (size/MD5/manifest/metainfo), original e cópia restaurada presentes e íntegras, vínculo Jellyfin reconhecido, `blockers:[]`, `can_release:true`, `unknown_links:0`. `physical_bytes:1229570048`, `reclaim_estimate_bytes:640176128`, `retained_known_bytes:589393920` e `reclaim_guaranteed_bytes:0`. Observou-se Transmission em status RPC 6 (seeding), embora o aceite anterior C1B-1 tivesse terminado em RPC 0; a causa da retomada anterior **não foi estabelecida**. Esse acervo não foi liberado, restaurado novamente nem usado como cobaia destrutiva.

### Fixture 100% própria e redistribuível

Gerada com `transmission-create` sem trackers, nome `MyCT-C1B2-Fixture`, infohash `590561b2aac74e4392da9a71b42a380bc8258e9f` e três arquivos: vídeo Matroska produzido localmente (`32280` bytes), texto (`55` bytes) e payload descartável (`134217728` bytes); total `134250063` bytes. Registrada no Transmission pausada, peça/estrutura verificada: `inspect` retornou `complete:true`, `can_archive:true`, `blockers:[]` e metainfo original válido.

Vínculo de teste criado por `python3 ops/myct-media approve HASH:CAMINHO movie "MyCT C1B2 Fixture" 2026`: destino `Filmes/MyCT C1B2 Fixture (2026)/MyCT C1B2 Fixture (2026).mkv`, dev/inode compartilhados `50:4098075` com `nlink=2`. A tentativa inicial de executar `./ops/myct-media` sem bit executável recebeu "Permissão negada"; a execução por Python funcionou, sem alterar permissões no repositório.

`archive HASH`: `ok:true`, `state:verified`, ID `8b027a7acf219ef87b7c3aa75328f46112b5d68d975bcf89318c9a2a9469067e`, `storage_bytes:134272484`, `remote_verified:["size","md5"]`, `sha256_recorded:true`, originais e hardlinks preservados, Transmission pausado.

`restore ID`: `state:verified`, `reused:false`, size/MD5/SHA-256/metainfo verificados. `recover ID` pausado: `seeding_ready:true`, `rpc_status:0`, `seed_started:false`; sem peers/upload. `space ID` após restauração: original e cópia restaurada íntegras, link da biblioteca preservado, `blockers:[]`, `can_release:true`, `physical_bytes:268509184`, `reclaim_estimate_bytes:268476416`, `retained_known_bytes:32768` e `reclaim_guaranteed_bytes:0`.

### Duas liberações consentidas e recuperação física

**Teste 1 — liberação padrão:** operador conferiu programa em branch/SHA auditados, **duas árvores exatas**, seis arquivos, diretórios, objetos Drive e metainfo, RPC pausado na cópia restaurada, único link de teste e ausência de links desconhecidos. `release ID --preview TOKEN --confirm TOKEN` retornou `state:released`, removeu a entrada do Transmission e ambas as árvores, preservando o link Jellyfin com **mesmo inode e SHA-256** e mantendo nuvem/catálogo/metainfo. Restauração subsequente: `restore` verified; `recover` pausado/validado; `recover --resume`: `seeding_ready:true`, `seed_started:true`, `rpc_status:6`, `peers_connected:0`, `upload_observed:false`. Sem redownload de dados faltantes observado no fluxo.

Observações do espaço livre de `/home` em bytes: antes `27925688320`; **imediatamente depois da liberação `27925688320` (delta 0)**; depois da restauração `28059926528` (**diferença líquida final +134238208 bytes**). É **compatível** com contabilização atrasada do Btrfs e com armazenamento de uma única árvore restaurada, mas não comprova sozinho causa nem bytes liberados isoladamente no instante de `release`. Não apresentar `268476416` como economia medida/garantida.

**Teste 2 — liberação com biblioteca e reconstrução:** com original ausente, cópia restaurada e biblioteca de teste presentes, revisão da prévia `space ID --remove-library REL` mostrou **quatro arquivos exatos** da fixture (três da árvore restaurada + um link de biblioteca); sem links externos/bloqueios. `release` com duas confirmações (`--confirm` e `--confirm-library`) retornou `released`, removeu os caminhos explicitamente selecionados. `restore ID` verified e `recover ID` pausado concluídos. `relink ID --library REL` retornou `added`; repetição `existing` (idempotente); comparação de `dev/ino` e SHA-256 confirmou vínculo correto. `recover ID --resume` terminou em `seeding_ready:true`, `seed_started:true`, `rpc_status:6`, `upload_observed:false`. **Ciclo ponta a ponta funcional aprovado.**

### Condição residual e continuidade

- Torrent da fixture foi deixado em **seeding RPC 6**, sem peers/tráfego observado. O vídeo de teste na biblioteca está restaurado por hardlink ao arquivo da árvore recuperada. Nenhuma exclusão adicional de fixture, mídia pessoal, Drive ou metadados foi realizada.
- O programa é uma ferramenta CLI executada do checkout auditado; nenhuma instalação de helper, mudança do Android, serviços, SSH, banco, OAuth ou infraestrutura `gh-pages` foi realizada.
- `space` é custoso para torrents grandes por hashing completo. Suporte restrito às árvores e metainfo v1 gerenciadas, e a estimativa Btrfs não é garantia. Diários `uncertain` continuam exigindo inspeção manual; sem bypass automático.
- **C1C Android é a próxima fase**: expor de modo adequado na interface móvel os comandos e confirmações agora validados na CLI, na mesma linha de engenharia enxuta. A issue #46 deve permanecer aberta para acompanhar C1C.

**Estado:** C1B-2 **funcionalmente aceita** após testes reais; PR #49 é o único veículo de integração.


## Fechamento remoto — 20/10/2026

**PR [#49](https://github.com/Samuel-Francisco-AS/My-CT/pull/49) MERGED na `main`**, commit de merge `86df44e0d610a807081c6b7995112c3d45ceced6`, depois do aceite físico documentado acima. Relatórios, implementação e testes C1B-2 foram incorporados; não foi criada release/APK nem instalados helpers nesta operação remota. O Fedora permanece sujeito à sincronização `git pull --ff-only` pelo proprietário, com preservação do serviço e dados existentes. A issue #46 fica aberta para a C1C Android. A remoção de branches é uma ação Git separada e só será feita após verificar a sincronização local.
