# Reconciliação Media — contrato atual do aplicador supervisionado R1

21/10/2026. PR #60 draft, em revisão. **Não implantado nem aplicado no catálogo
pessoal nesta missão.** Corrige identidade de hardlinks registrados; não verifica
integridade audiovisual, peças de torrent ou conteúdo histórico. Nenhuma leitura
sequencial, SHA-256 de vídeo, cópia, reconstrução ou remoção de mídia.

Este contrato substitui schemas 1/2 e o orçamento de execução MA01 do HEAD
`5e5f5889b238ac48d4d2a17ddb340ee132606c65`, por solicitação expressa do proprietário.
O histórico está preservado no Git e identificado como superado no
[relatório pós-teste](MYCT-CLOUD-R1-POS-TESTE-DIAGNOSTICO.md).
A integridade dos motores archive/restore/space/release permanece inalterada.
Fonte Android code 50, sem nova APK publicada; release code 49 imutável.

## A — Prévia de metadados, somente leitura

`myct-media reconcile-preview --provenance ARQUIVO`

Sem locks de escrita, arquivos novos, alteração do índice ou caches junto dos
helpers. Consulta RPC de leitura, stat/lstat e ioctls Btrfs; abrir um descritor para
ioctl não lê os bytes do vídeo. O helper `reconcile-plan` anterior continua como
diagnóstico somente de metadados, sem gerar consentimento aplicável.

A prévia aplicável, **schema 3**, apresenta:

- `counts`: examinados, candidatos, coerentes (`current`), ausentes e protegidos;
  protegidos incluem divergentes, inseguros e `ignored`, detalhados por motivo;
- `summary`: contagem por razão, sem converter bloqueios em candidatos;
- `filesystems`: UUID/subvolume Btrfs atuais comprovados para entradas com prova;
- `updates`: caminhos exatos propostos; registros protegidos não entram na lista;
- snapshot do índice, metadados, RPC, tarefas, catálogos e declaração de origem;
- emissão, expiração, nonce aleatório e `id` SHA-256 do JSON canônico completo.

Os hashes SHA-256 restantes são somente de arquivos de controle: plano, catálogo,
backup, declaração, recibos e temporários de journal. Não são hashes de mídia.
`content()`, passagens de hashing, `workload`, taxas, estimativas, deadline longo
e `reconcile-estimate` foram removidos. Planos antigos não autorizam apply;
gerar e revisar nova prévia. Journals já publicados mantêm o formato recuperável.

### Provas exigidas por candidato

1. Registro válido fora de `ignored`, caminho relativo seguro, biblioteca regular,
   dono atual e nenhum componente simbólico/redirecionado.
2. Origem atual **única**, com o ID exato armazenado, no diretório confiável de um
   torrent reconhecido pelo adaptador My-CT/Transmission.
3. Torrent concluído, metadados completos, sem erro, origem regular pertencente ao
   proprietário. Metadado `files[].name` exato e tamanho positivo igual ao arquivo.
4. Device **e** inode atuais iguais entre origem e biblioteca; inode igual ao
   persistido. Igualdade de conteúdo não é usada como prova de hardlink.
5. Uma origem reconhecida por inode; aliases registrados coerentes e `nlink`
   exatamente igual a uma origem mais os nomes conhecidos. Aliases desconhecidos,
   duplicidade de origem/torrent ou ambiguidade bloqueiam.
6. UUID/subvolume Btrfs atuais iguais nos dois caminhos. Identidade indisponível ou
   divergência com filesystem/metadados anteriormente persistidos bloqueia.
7. Ancestrais percorridos por dirfd/O_NOFOLLOW, dono atual, sem escrita por outras
   contas; candidatos regulares, sem escrita de grupo/outros. Stat compara
   dev/inode, tamanho, UID, nlink, mtime e ctime com as evidências do plano.
8. Nova coleta das evidências/RPC/índice/ambiente ao terminar a inspeção e novamente
   sob locks antes de publicar. Qualquer mudança relevante recusa aplicação.

Os registros legados só guardam ID/dev/inode: tamanho, UUID e metadados históricos
não podem ser inventados. `LEGACY_REANCHOR` identifica reinscrição explícita da
identidade atual provada, **não comprovação de continuidade histórica**. Um arquivo
clonado/substituído anteriormente não pode ser descartado apenas pelo índice v1.

### Declaração privada de procedência

JSON fornecido/revisado pelo proprietário: `schema: 1`, `owner_verified_origin:
true`, `historical_identity_missing: true`, `filesystems` com os UUIDs/subvolumes
atuais exatos dos candidatos, e `evidence_reference` não vazia (8–2048 caracteres).
Referenciar inventário/backup independente, UUID/subvolume conhecido e histórico
de montagem/restauração conferido; não incluir segredos. Sem base de procedência,
**não declarar verificação nem aplicar**. O programa verifica coerência e hash,
não a veracidade da declaração humana nem uma identidade histórica ausente.

Plano/procedência: caminhos absolutos sob o home, arquivos regulares 0600, dono
atual, nlink=1, sem symlinks ou ancestrais graváveis por outras contas. Usar diretório
privado 0700 e `umask 077`; a prévia não salva o plano automaticamente.

## B — Confirmação explícita, 600 segundos

`myct-media reconcile-apply --plan ARQUIVO --provenance ARQUIVO --confirm ID --accept-legacy ID`

Revisar contagens, razões, filesystem e caminhos antes de confirmar o ID inteiro.
Para legado, `--accept-legacy` repete exatamente esse ID; sem aceite genérico,
automático, `--yes` ou confirmação recém-gerada sem revisão humana.

Validade única de **600 s após terminar a prévia**, sem renovação nem orçamento
especial de execução. Rechecada antes de locks, sob locks, após a recoleta e
imediatamente antes do rename. Se a consulta de metadados/RPC inesperadamente
ultrapassar o prazo, recusar: antes do intent o catálogo permanece intacto sem
transação; depois do intent exige recuperação. Nova aplicação exige nova prévia.

O token e os 600 s próprios de space/release continuam independentes; reconciliação
não cria/reutiliza autorização Android, Jellyfin ou consentimento de exclusão.

## C — Aplicação, backup e publicação

Locks exclusivos, não bloqueantes, na ordem existente:

1. `cloud-jobs/delivery.lock`.
2. `archive/catalog.lock`, depois `archive-torrents/catalog.lock`.
3. Nomeação por infohash, em ordem lexicográfica.
4. `media/state.lock`.

Contenção retorna `BUSY`; não cancela tarefas nem pausa serviços para prosseguir.
Tarefas pendentes/ativas/cancelando/incertas, catálogos ambíguos ou RPC indisponível
bloqueiam. Diários são lidos diretamente, sem chamar rotinas que reparam estado.

Sob locks, reconsulta Transmission/índice/filesystem/ambiente/procedência, recompõe
o snapshot e compara exatamente com o plano confirmado. Gera cópia do índice
atualizando **somente** entradas elegíveis. Coerentes, ausentes, excepcionais,
`ignored` e demais protegidos ficam preservados. Nenhuma mídia/ACL/torrent muda.

Cria operação exclusiva 0700 fora do repositório/helpers:
`~/.local/state/myct/media-reconcile/operations/ID/`. Nunca reutiliza backup antigo.
Arquivos privados 0600:

- `transaction.json`: intent/recibo sanitizado, ID, fase, contagem e hashes antes/
  depois, infohashes para recuperação; sem paths de mídia ou conteúdo da declaração;
- `state.before.json`: bytes exatos do catálogo original, criação exclusiva,
  fsync de arquivo/diretório e leitura de conferência.

Estados existentes: `preparing` → `prepared` → `committing` → `succeeded`.
Temporário privado no filesystem do catálogo, fsync, última recoleta de metadados/
RPC/ambiente, autorização ainda válida, diretório ativo conferido, replace atômico,
fsync do diretório, leitura de conferência e recibo durável. Não usa edição manual
de `state.json`. O backup conserva bytes; a nova serialização pode reordenar JSON.

Recibo: ID, estado, hashes antes/depois, `updated`, `planned_updates`, repetição e
origem de recuperação. Não inclui logs brutos, tokens ou credenciais.

## Falhas, repetição e recuperação preservadas

`myct-media reconcile-recover --operation ID [--plan ARQUIVO]`

Sob os mesmos locks e checagem de tarefas, **somente classifica/finaliza recibo**:

| Catálogo atual | Resultado |
| --- | --- |
| Hash anterior exato | `not_applied`, `updated=0` |
| Hash proposto exato | Confere backup/fsync; `succeeded`, contagem comprovada |
| Diferente de ambos | `superseded`, `updated=null`; preserva catálogo posterior |

Nunca restaura backup ou sobrescreve catálogo. Backup corrompido, journal inválido
ou estado inseguro mantém bloqueio. Transação incompleta impede outra aplicação.
Repetição concluída, com confirmação ainda válida e catálogo esperado, retorna
`already_applied=true` sem reescrever índice/backup. Após falha classificada,
gerar outra prévia/ID, sem retry automático. Recover não exige confirmação vigente
porque nunca aplica catálogo; restauração de backup exigiria autorização separada.

MA02 permanece: falha ao sincronizar a criação da operação ou publicar primeiro
intent preserva a pasta e retorna `RECOVERY_NEEDED: ID`. Sem journal, recover pede
`MISSING_INTENT_REQUIRES_PLAN: ID`. A via `--plan ARQUIVO` exige plano schema 3
canônico correspondente ao ID; pode estar expirado para **classificar**, não aplicar.
Só publica `not_applied` se o catálogo for o baseline anterior e a pasta estiver
vazia ou contiver apenas temporários privados esperados `.reconcile-<32 hex>`.
Órfãos são preservados e registrados por nome/hash, `recovered_missing_intent=true`;
sincroniza também o diretório pai. Plano errado, catálogo posterior ou arquivo
inesperado exigem diagnóstico, sem remoção de evidências. A rotina atômica limpa
somente o temporário transitório que ela própria criou. Journals antigos válidos
continuam recuperáveis; plano antigo sem journal requer diagnóstico específico.

## Limites e implantação futura

Metadados provam a identidade observável do vínculo; não provam cada byte,
integridade audiovisual ou conteúdo histórico. Alterações comuns aparecem em
size/mtime/ctime/inode/nlink/RPC. Não há proteção absoluta contra root/escritores
que ignorem locks ou manipulem evidências, nem freeze no intervalo final.

UUID/subvolume persistidos ajudam a distinguir uma mudança legítima de device de
filesystem diferente; `record_matches()` continua exigindo device atual exato.
Outra mudança futura pode bloquear gerenciamento até nova reconciliação explícita
`DEVICE_REANCHOR`, agora barata e baseada em metadados. Não há migração automática.

**Aguardar Luna e autorização antes de executar no servidor pessoal.** Depois:

1. Conferir instalação coerente dos helpers/módulos, hashes, paths, permissões,
   backups de helpers e tarefas; SETUP recusa legados divergentes automaticamente.
2. Conferir procedência e preparar declaração privada; não usar somente inode.
3. Gerar/revisar prévia fresca, contagens/filesystems/caminhos/razões e ID.
4. Confirmar especificamente plano e legado dentro dos 600 s; sem automatizar.
5. Conferir recibo/backup; diante de erro, classificar conservadoramente por recover.
6. Novo teste Nuvem/Moto com nova prévia/consentimentos, abrindo e cancelando o
   diálogo; nenhuma exclusão real como condição. R1 segue pendente de aceite.

## Atualização posterior: reconhecimento entre boots

O reconhecimento corrente agora compara origem/destino atuais e a identidade
Btrfs persistente disponível. Uma mudança isolada de `st_dev` não exige aplicar
novamente este procedimento para gerenciar os hardlinks comprovados. As categorias
LEGACY_REANCHOR/DEVICE_REANCHOR deste aplicador descrevem possível enriquecimento
do registro histórico, não uma exigência para excluir arquivos locais. O recibo e
os backups da aplicação real de 264 registros permanecem preservados.
[Contrato atual de armazenamento local](MYCT-MEDIA-LOCAL-RELATORIO.md).
