# Reconciliação Media — contrato do aplicador supervisionado R1

21/10/2026. Implementação para auditoria na PR #60. **Não implantada e nunca
executada sobre o catálogo pessoal nesta missão.** Não autoriza release, limpeza,
reimportação, alteração de torrents, mídia ou serviços. A release code 49 continua
imutável; o fonte Android permanece code 50, sem distribuição.

O [parecer da Luna sobre o plano anterior](https://github.com/Samuel-Francisco-AS/My-CT/pull/60#issuecomment-6323672244)
foi favorável ao diagnóstico/proteção, exigindo um escritor auditado separado.
Este documento descreve esse escritor, implementado em
`ops/myct_media_reconcile.py`, chamado explicitamente por `myct-media`.

## Comandos e consentimento

| Comando | Contrato |
| --- | --- |
| `reconcile-plan` | Diagnóstico anterior, somente metadados; não é aprovação aplicável |
| `reconcile-estimate --provenance ARQUIVO` | Diagnóstico somente de metadados: volume distinto, duas passagens; taxa/viabilidade desconhecidas sem medição |
| `reconcile-preview --provenance ARQUIVO` | Prévia pronta para confirmação, somente leitura, com SHA-256 dos candidatos, contagens, razões, caminhos propostos e ID |
| `reconcile-apply --plan ARQUIVO --provenance ARQUIVO --confirm ID --accept-legacy ID` | Aplicação explícita; `--accept-legacy` obrigatório para legado e deve repetir o ID inteiro |
| `reconcile-recover --operation ID [--plan ARQUIVO]` | Reconcilia recibos; plano exato obrigatório quando falta o primeiro intent; **nunca restaura/substitui o catálogo** |

Arquivos de plano/procedência devem ter caminhos absolutos sob o home, ser
regulares privados (0600, dono atual, nlink=1), sem symlinks ou ancestrais
graváveis por outras contas. A prévia não salva esses arquivos nem cria locks;
o proprietário poderá redirecionar seu resultado para arquivo privado. Importações
dos módulos locais não criam `__pycache__` junto dos helpers.

A prévia tem `application_supported=true`, **schema 2**, nonce aleatório, instante de
emissão, expiração, snapshot, resumo e caminhos propostos. O ID é o SHA-256 da
representação canônica de todo esse conteúdo, incluindo procedência e índice.
Não é senha nem prova criptográfica de autoria: o proprietário controla a sessão
SSH/CLI, lê a prévia e confirma exatamente seu ID. Não há `--yes` ou aceite
genérico. Opções duplicadas/desconhecidas são recusadas.

Após [MA01 da Luna](https://github.com/Samuel-Francisco-AS/My-CT/pull/60#issuecomment-6325881368),
a validade distingue **aceitação** de **execução de uma única aplicação**:

- Aceitação: **600 segundos após terminar a prévia**, conferidos antes e novamente
  sob todos os locks, antes do segundo hashing. `--confirm ID` e
  `--accept-legacy ID` continuam exatos. Uma prévia expirada não inicia aplicação.
- Execução: ao aceitar sob locks, fixa deadline por relógio monotônico, sem
  renovação: `max(600, ceil(3 * preview_seconds + 120))` segundos. O tempo da prévia
  inclui leitura, RPC, inventário e revalidações; o fator 3 e reserva de 120 s são
  margem para variação de carga/cache e fsync, **não garantia de throughput**.
- Teto explícito: **24 horas**, limite conservador de ocupação cooperativa. Se o
  orçamento calculado ultrapassar o teto, `TEMPORAL_PREFLIGHT` recusa antes do
  segundo hashing, diretório de operação ou backup. Não recorta o orçamento para
  fingir viabilidade. Esse teto não foi medido no servidor pessoal.
- Após o segundo hashing e antes de criar intent, confere o deadline; ao publicar
  o catálogo, confere novamente. Tempo excedido antes do intent →
  `EXECUTION_EXPIRED`, catálogo intacto. Depois do intent → `RECOVERY_NEEDED: ID`,
  backup/evidências preservados para classificação. Não mata IO/RPC bloqueado;
  o deadline limita autorização de publicar, não garante timeout de syscall.

Isso evita exigir que a própria prova integral caiba no prazo destinado à decisão
humana. Depois de aceita, a autorização serve exclusivamente àquela execução
contínua sob locks; não permite soltar locks, pausar/retomar ou iniciar novamente
com consentimento vencido. A coleta integral fresca, hashes exatos e última
revalidação continuam obrigatórios. Alterar o relógio civil não renova o deadline.
O novo orçamento e as métricas integram o ID aprovado. Planos schema 1 não são
aplicáveis: gerar uma prévia schema 2; journals anteriores continuam recuperáveis.

**O token de release/space e seus 600 segundos são independentes e inalterados.**
Concluir a migração nunca concede autorização de exclusão Android.

### Diagnóstico de volume e viabilidade

`reconcile-estimate` não lê conteúdos nem cria arquivos/locks: mostra caminhos
candidatos, `distinct_files` por `(dev, ino)`, `bytes_per_pass`, `full_passes=2`
(prévia + aplicação) e `total_read_bytes`. Aliases do mesmo inode não são contados
novamente. Essas são leituras lógicas, não bytes de rede ou IO físico do SSD.
Sem amostras, taxa, duração e viabilidade são `null`, não zero ou aprovação.

A prévia completa acrescenta `workload.preview_seconds`,
`observed_effective_bytes_per_second` (bytes distintos / duração total observada),
`estimated_apply_seconds`, `execution_limit_seconds`, teto e `temporal_viable`.
`preview_phases_seconds` separa `collect` (RPC/metadados/catálogos/procedência),
`hash` (leitura integral e seus checks) e `recheck` (reconsulta final). Esses tempos
não são IO de disco isolado; permitem localizar uma fase cara sem presumir o
gargalo. Uma projeção viável não promete o desempenho da segunda leitura.

Antes da migração pessoal, ainda é necessário coletar o volume com estimate e
medir a prévia não destrutiva, após autorização específica, comparar taxa/carga e
revisar o orçamento antes de confirmar. A amostra anterior de ~74,4 MiB/s de um
pacote não mede os 264 candidatos. Nenhum deles foi lido nesta correção.

## Procedência do filesystem legado

Um arquivo JSON privado fornecido pelo proprietário deve conter:

- `schema: 1`;
- `owner_verified_origin: true`;
- `historical_identity_missing: true`: reconhecimento informado da limitação;
- `filesystems`: lista das identidades **atuais exatas** usadas pelos candidatos,
  cada uma com `kind: "btrfs"`, `uuid` e `subvolume`;
- `evidence_reference`: referência descritiva não vazia, de 8–2048 caracteres,
  à evidência independente conferida pelo proprietário. Não incluir segredos.

O conteúdo inteiro desse arquivo participa do plano por hash. Divergência de
UUID/subvolume ou da declaração entre preview/apply bloqueia a aplicação.

Exemplos de evidência a conferir antes de assinar a declaração: inventário ou
backup anterior do mesmo volume/home, correspondência dos UUIDs de montagem e
subvolume com a instalação conhecida, ausência de troca/restauração de filesystem
desde o inventário e correlação com registros anteriores de importação/arquivo.
Se não houver base para essa procedência, **não declarar verificação nem aplicar**.
Sem o UUID histórico no índice v1, nenhum algoritmo consegue fabricar essa prova
retroativa; dev 50/51, inode igual ou o UUID atual isolado não a estabelecem.

O código verifica a coerência da declaração com o estado atual e exige o aceite
legado vinculado ao ID. **Não consegue comprovar a veracidade da declaração
humana nem continuidade histórica do filesystem.** A confiança na origem atual
reconhecida e nessa evidência deve ser revisada pela Luna/proprietário.

## Provas do snapshot

Preservam-se todas as condições do plano anterior: ID registrado, torrent gerenciado
completo/sem erro, origem única no caminho confiável, tamanho no metadado do
torrent, inode anterior, dev/inode atuais iguais entre origem e biblioteca,
ausência de symlink e de aliases desconhecidos, UUID/subvolume Btrfs atual e,
quando existentes, metadados/identidade persistidos anteriores.

Além disso, o escritor:

- valida dono e permissões, recusando mídia gravável por outras contas;
- lê SHA-256 por descritor `O_NOFOLLOW`, uma vez por inode candidato; compara
  `fstat`/caminho antes/depois, incluindo ctime, mtime, tamanho, modo e nlink;
- vincula o plano aos bytes e metadados do índice e à lista dos candidatos;
- registra membership, caminho, estado, conclusão, erro, metadados e arquivos de
  todos os torrents gerenciados. Rates/peers/contadores de upload não são identidade;
- verifica o diário privado diretamente, sem chamar `cloud.rows()` (que poderia
  escrever/reconciliar recibos); tarefas pendentes, ativas, cancelando, incertas,
  desconhecidas ou recibos inválidos impedem aplicação;
- inclui hashes dos catálogos de arquivo/torrent e dos recibos de tarefas; estado
  pendente/incerto de release/restore/recovery também bloqueia;
- repete a coleta sob locks e compara integralmente com o plano aprovado.

Após backup e temporário duráveis e publicação do intent `committing`, uma coleta
final de metadados/RPC/índice/catálogos/tarefas/procedência deve continuar idêntica.
Essa coleta não repete o hashing inteiro: alterações posteriores ao hash mudam
ctime/mtime e os demais metadados verificados. O deadline de execução e o diretório
ativo do catálogo também são reconferidos antes do rename.

Hashing aqui prova estabilidade entre as amostras e a origem atual; não é uma
verificação nova de peças contra o metainfo do torrent nem uma prova do conteúdo
histórico ausente do v1. A integridade remota/local exigida por release permanece
inteiramente no motor autoritativo da Nuvem.

## Locks, gravação e recibo

Ordem de aquisição, sempre exclusiva e não bloqueante:

1. `cloud-jobs/delivery.lock`: impede aceite/spawn de novos trabalhos do bridge.
2. `archive/catalog.lock` e `archive-torrents/catalog.lock`.
3. Locks de nomeação por infohash, em ordem lexicográfica estável.
4. `media/state.lock`.

É compatível com catálogo → nomeação → Media dos motores e nomeação → Media do
adaptador Transmission. Não há espera circular: contenção aborta com `BUSY` e
libera os descritores já adquiridos. O aplicador não cancela tarefas nem muda
serviços para conseguir esses locks. Alterações via CLI externa que não obedeça
aos locks são tratadas pelas reconsultas; ver também os limites abaixo.

Não é usado `save_state()` manualmente. O escritor percorre ancestrais com
descritores de diretório e `O_NOFOLLOW`, valida owner/permissões/nlink e cria:

`~/.local/state/myct/media-reconcile/operations/ID/`

Diretório exclusivo 0700, fora do repositório/helpers; nunca reutiliza destino de
backup preexistente. Arquivos privados 0600:

- `transaction.json`: intent/recibo sanitizado com ID, fase, contagem, hashes antes/
  depois e infohashes necessários à recuperação; sem paths de mídia, tokens,
  conteúdo da procedência ou respostas brutas de RPC;
- `state.before.json`: bytes exatos do índice anterior, criação exclusiva, fsync,
  conferência de leitura/hash e fsync do diretório.

Estados do intent: `preparing` → `prepared` → `committing` → `succeeded`. Não são
autorizações para repetir comandos. Depois do backup, o novo índice é escrito em
temporário privado/exclusivo no mesmo diretório do catálogo e recebe fsync. Após
as revalidações, `os.replace` publica o arquivo, seguido de fsync do diretório,
conferência dos bytes publicados e recibo final também atômico/durável.

Somente entradas `eligible` da prévia fresca são substituídas na cópia do índice.
Registros `CURRENT`, ausentes, excepcionais, `ignored` e outros bloqueados são
preservados semanticamente; nenhum arquivo/hardlink/ACL/torrent é modificado.
O JSON pode ter ordenação/escapes diferentes; o backup preserva os bytes originais.

Recibo público de sucesso: ID, estado, número atualizado, hashes do índice antes/
depois e `already_applied`. A saída de erro é sanitizada, com código e ID quando
disponível; não inclui a exceção bruta de RPC ou caminhos privados.
Na recuperação, `updated=0` em `not_applied` e `null` em `superseded` (resultado
histórico indeterminável); `planned_updates` mantém a contagem originalmente
proposta, sem apresentá-la como uma escrita comprovada.

## Interrupção, repetição e recuperação

Erro depois do intent exige `reconcile-recover --operation ID`, não repetição
automática. Uma transação pendente impede iniciar outra. Sob os mesmos locks,
com tarefas/catálogos novamente seguros, a recuperação compara o índice atual:

| Evidência | Resultado |
| --- | --- |
| Hash atual igual ao anterior | `not_applied`; nenhum catálogo é restaurado |
| Hash atual igual ao proposto | Verifica backup, refaz fsync do diretório e finaliza recibo `succeeded` |
| Hash atual diferente de ambos | `superseded`; preserva integralmente o catálogo diferente/mais recente |

Backup corrompido impede declarar sucesso recuperado. Intent ilegível,
permissões inseguras ou estado impossível de confirmar exigem diagnóstico manual;
não remover evidências para contornar o bloqueio.

Após MA02, falha ao sincronizar o diretório recém-criado ou publicar o primeiro
`transaction.json` retorna `RECOVERY_NEEDED: ID`, preservando o diretório. Sem
intent, `reconcile-recover --operation ID` responde
`MISSING_INTENT_REQUIRES_PLAN: ID`. A recuperação assistida requer adicionalmente
`--plan ARQUIVO`, plano schema 2 cujo conteúdo canônico e ID correspondam exatamente
à operação. O prazo pode estar vencido porque essa via nunca aplica catálogo.

Sob a mesma ordem de locks, com tarefas/catálogos seguros, só classifica
`not_applied` se o índice atual for byte a byte o baseline aprovado (SHA-256), e o
diretório estiver vazio ou contiver exclusivamente temporários privados
`.reconcile-<32 hex>` da publicação inicial. Faz hash desses temporários e registra
nomes/hashes em `preserved_evidence`, sem removê-los ou usá-los como autorização.
A rotina atômica limpa somente o temporário transitório que ela própria criou;
jamais usa essa limpeza para remover órfãos ou evidências preexistentes.
Um recibo novo, atômico e fsync, registra `recovered_missing_intent=true` e
`updated=0`. Não cria backup retroativo, não grava mídia/catálogo, não retoma apply.

Qualquer backup/arquivo inesperado, symlink, journal inválido, plano divergente ou
catálogo diferente impede classificação assistida (`UNCLASSIFIED_EVIDENCE` ou
`UNCLASSIFIED_CATALOG` quando pertinente). Preservar tudo para diagnóstico; nunca
remover o diretório para desbloquear. Uma falha da própria publicação da
recuperação mantém o bloqueio/evidências. Após classificação, outra aplicação
exige nova prévia/ID e confirmação. A recuperação normal de journals já publicados
permanece independente do plano e compatível com o formato anterior.

Falha após rename/fsync pode deixar o novo catálogo visível com recibo `committing`; nunca faz rollback
automático sobre esse estado. Backups parciais de falha são preservados.

Repetir um apply já concluído, com confirmação ainda válida e hash atual esperado,
retorna `already_applied=true`, sem reescrever catálogo/backup. Depois de
`not_applied` ou diante de mudança, gerar **outra prévia/ID** e novo consentimento.
Recuperação não depende de token antigo ainda válido porque não aplica catálogo.
Não existe comando de restauração do backup; essa ação exigiria nova missão,
verificação dos trabalhos e autorização específica, sem sobrescrever estado novo.

## Procedimento futuro, condicionado a auditoria e autorização

**Não executar agora.** Após a Luna aprovar e o proprietário autorizar:

1. Preparar instalação coerente dos helpers R1 (`myct-media`, cleanup,
   archive-torrent e dependências), incluindo o novo módulo privado
   `myct_media_reconcile.py`. Conferir hashes, paths, jobs, permissões e backups;
   recusar componentes legados divergentes sem substituição supervisionada.
2. Conferir a procedência do filesystem e preparar o JSON privado acima. Usar
   diretório de revisão 0700 fora do repositório e `umask 077` para os arquivos.
3. Primeiro gerar `reconcile-estimate --provenance CAMINHO_ABSOLUTO`, revisar o
   volume/passagens; depois gerar
   `reconcile-preview --provenance CAMINHO_ABSOLUTO` e salvar o JSON privado.
   Ler `summary`, `updates`, UUID/subvolume, `workload`, tempos por fase,
   emissão/expiração e o ID completo; confirmar viabilidade medida e margem.
   Não presumir que a contagem antiga de 264 continua válida.
4. Confirmar explicitamente os dois argumentos `--confirm ID --accept-legacy ID`
   junto dos arquivos exatos de plano/procedência. Não automatizar a confirmação
   copiando um ID recém-gerado sem revisão humana.
5. Conferir recibo, backup e contagens. Se houver erro/interrupção, usar somente a
   classificação de recuperação descrita acima e inspecionar o estado; não editar
   `state.json`, não copiar backup por cima e não repetir comandos por tentativa.
6. Com ambiente estável, gerar nova prévia da Nuvem no Moto. Verificar 13 links,
   inventário completo e consentimentos, abrindo/cancelando o diálogo. A migração
   não reutiliza prévia/token nem autoriza exclusão real como teste.

## Limites e revisão independente

Os locks são cooperativos. Não é possível garantir imutabilidade contra root,
processo da mesma conta que ignore os locks ou alteração externa no intervalo
final entre a amostragem e o rename. Não há freeze de filesystem, pausa de
serviços ou bloqueio global do Transmission; esses poderes não foram autorizados.
As rechecagens, hashes e ctimestamps reduzem essa janela sem declarar garantia
impossível. Operações normais do My-CT que respeitam os locks ficam serializadas.

A prévia/aplicação leem integralmente os candidatos, potencialmente centenas de
GiB; leituras podem vir de cache e não representam IO físico garantido no SSD.
Nenhum custo real do acervo foi medido nesta missão; cache/carga podem tornar o
orçamento insuficiente e impedir publicação, preservando catálogo/evidências. Não houve otimização
R2, leitura de mídia pessoal, implantação, migração real ou aceite físico.

Resultados e evidências desta missão estão no
[relatório pós-teste R1](MYCT-CLOUD-R1-POS-TESTE-DIAGNOSTICO.md).
